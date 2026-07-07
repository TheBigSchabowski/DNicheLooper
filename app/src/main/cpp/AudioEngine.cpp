#include "AudioEngine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <exception>
#include <filesystem>

#include "NAM/get_dsp.h"

#define LOG_TAG "NAMLooper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr int32_t kDrainCallbackCount = 16;
constexpr int32_t kMinBlockFrames = 8192;
}  // namespace

bool AudioEngine::openStreams(int32_t inputDeviceId, int32_t outputDeviceId) {
    // Output first: it defines the sample rate the input must follow.
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setUsage(oboe::Usage::Media)
            ->setDeviceId(outputDeviceId)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = outBuilder.openStream(mOutputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream on device %d: %s", outputDeviceId,
             oboe::convertToText(result));
        return false;
    }

    mSampleRate = mOutputStream->getSampleRate();
    mOutputChannels = mOutputStream->getChannelCount();
    mFramesPerBurst = mOutputStream->getFramesPerBurst();
    // Four bursts of buffering: NAM keeps the callback near its deadline,
    // and two bursts left no headroom for scheduler jitter (audible as
    // periodic ticks). At 96-frame bursts this is 8 ms — fine for live.
    mOutputStream->setBufferSizeInFrames(mFramesPerBurst * 4);

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setSampleRate(mSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            // Unprocessed = raw signal for music, bypassing the speech
            // preprocessing the default preset may apply.
            ->setInputPreset(oboe::InputPreset::Unprocessed)
            ->setDeviceId(inputDeviceId);
    // No callback on the input stream: it is read non-blocking from the
    // output callback.

    result = inBuilder.openStream(mInputStream);
    if (result != oboe::Result::OK) {
        LOGI("Unprocessed input preset rejected (%s), retrying with VoiceRecognition",
             oboe::convertToText(result));
        inBuilder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        result = inBuilder.openStream(mInputStream);
    }
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream on device %d: %s", inputDeviceId,
             oboe::convertToText(result));
        return false;
    }

    mInputChannels = mInputStream->getChannelCount();

    if (mInputStream->getSampleRate() != mSampleRate) {
        // Should be impossible on a single USB interface; Oboe's resampler
        // was requested above, so this indicates a broken stream setup.
        LOGE("Sample rate mismatch: in=%d out=%d", mInputStream->getSampleRate(), mSampleRate);
        return false;
    }

    LOGI("Streams open: rate=%d burst=%d inCh=%d outCh=%d inDev=%d outDev=%d "
         "outSharing=%s inSharing=%s inPreset=%d",
         mSampleRate, mFramesPerBurst, mInputChannels, mOutputChannels,
         inputDeviceId, outputDeviceId,
         oboe::convertToText(mOutputStream->getSharingMode()),
         oboe::convertToText(mInputStream->getSharingMode()),
         static_cast<int>(mInputStream->getInputPreset()));
    return true;
}

bool AudioEngine::start(int32_t inputDeviceId, int32_t outputDeviceId) {
    std::lock_guard<std::mutex> lock(mLock);
    closeStreamsLocked();
    mDisconnected.store(false, std::memory_order_relaxed);

    if (inputDeviceId <= 0 || outputDeviceId <= 0) {
        LOGE("Refusing to start without explicit USB device ids (in=%d out=%d)",
             inputDeviceId, outputDeviceId);
        return false;
    }

    if (!openStreams(inputDeviceId, outputDeviceId)) {
        closeStreamsLocked();
        return false;
    }

    mLooper.prepare(mSampleRate, kMaxLoopSeconds);

    mMaxBlockFrames = std::max(mOutputStream->getBufferCapacityInFrames(), kMinBlockFrames);
    mInputInterleaved.assign(static_cast<size_t>(mMaxBlockFrames) * mInputChannels, 0.0f);
    mMonoIn.assign(static_cast<size_t>(mMaxBlockFrames), 0.0f);
    mMonoFx.assign(static_cast<size_t>(mMaxBlockFrames), 0.0f);
    mMonoOut.assign(static_cast<size_t>(mMaxBlockFrames), 0.0f);

    // No callback runs yet: adopt pending NAM models and size them for the
    // new stream (Reset prewarns, which is why this happens before start).
    mNamChain.prepare(mSampleRate, mMaxBlockFrames);

    mDrainCallbacks.store(kDrainCallbackCount, std::memory_order_relaxed);

    // ADPF: report callback timing to the scheduler so CPU clocks don't dip
    // mid-performance (NAM keeps the callback near its deadline; without
    // this, governor dips cause periodic ticks). Must be set before start.
    mOutputStream->setPerformanceHintEnabled(true);

    // Input first so it is already producing when the output callback fires.
    oboe::Result result = mInputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        closeStreamsLocked();
        return false;
    }
    result = mOutputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        closeStreamsLocked();
        return false;
    }

    mRunning.store(true, std::memory_order_relaxed);
    LOGI("Engine started");
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    closeStreamsLocked();
}

void AudioEngine::closeStreamsLocked() {
    mRunning.store(false, std::memory_order_relaxed);
    // Close the callback-owning stream first so no callback touches the
    // input stream while it is being torn down.
    if (mOutputStream) {
        mOutputStream->stop();
        mOutputStream->close();
        mOutputStream.reset();
    }
    if (mInputStream) {
        mInputStream->stop();
        mInputStream->close();
        mInputStream.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/,
                                                   void* audioData,
                                                   int32_t numFrames) {
    const auto callbackStart = std::chrono::steady_clock::now();
    float* out = static_cast<float*>(audioData);
    const int32_t outChannels = mOutputChannels;

    if (numFrames > mMaxBlockFrames || !mInputStream) {
        std::fill(out, out + static_cast<size_t>(numFrames) * outChannels, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    // Startup phase: throw away whatever queued up in the input stream so
    // steady state begins with an empty (= lowest-latency) input FIFO.
    if (mDrainCallbacks.load(std::memory_order_relaxed) > 0) {
        mDrainCallbacks.fetch_sub(1, std::memory_order_relaxed);
        while (true) {
            auto r = mInputStream->read(mInputInterleaved.data(), numFrames, 0);
            if (r.error() != oboe::Result::OK || r.value() < numFrames) {
                break;
            }
        }
        std::fill(out, out + static_cast<size_t>(numFrames) * outChannels, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    auto readResult = mInputStream->read(mInputInterleaved.data(), numFrames, 0);
    int32_t framesRead = 0;
    if (readResult.error() == oboe::Result::OK) {
        framesRead = readResult.value();
    } else if (readResult.error() == oboe::Result::ErrorDisconnected ||
               readResult.error() == oboe::Result::ErrorClosed) {
        mDisconnected.store(true, std::memory_order_relaxed);
    }
    if (framesRead < numFrames) {
        // Input momentarily behind: pad the tail with silence.
        std::fill(mInputInterleaved.begin() + static_cast<size_t>(framesRead) * mInputChannels,
                  mInputInterleaved.begin() + static_cast<size_t>(numFrames) * mInputChannels,
                  0.0f);
    }

    // Downmix input to mono by SUMMING channels (not averaging): a guitar
    // sits on one channel of a stereo interface, and averaging would cost
    // 6 dB against a silent second channel. (Channel counts were fixed at
    // stream-open time; nothing here depends on runtime negotiation.)
    const float inGain = mInputGain.load(std::memory_order_relaxed);
    float inPeak = 0.0f;
    for (int32_t i = 0; i < numFrames; ++i) {
        const float* frame = mInputInterleaved.data() + static_cast<size_t>(i) * mInputChannels;
        float sum = 0.0f;
        for (int32_t c = 0; c < mInputChannels; ++c) {
            sum += frame[c];
        }
        const float mono = sum * inGain;
        mMonoIn[i] = mono;
        const float magnitude = mono < 0.0f ? -mono : mono;
        if (magnitude > inPeak) {
            inPeak = magnitude;
        }
    }
    if (inPeak > mInputPeak.load(std::memory_order_relaxed)) {
        mInputPeak.store(inPeak, std::memory_order_relaxed);
    }

    // Amp sim in front of the looper: the loop records the amp sound, and
    // the live monitor plays it too (empty slot = dry pass-through).
    mNamChain.process(mMonoIn.data(), mMonoFx.data(), numFrames);
    float fxPeak = 0.0f;
    for (int32_t i = 0; i < numFrames; ++i) {
        const float magnitude = mMonoFx[i] < 0.0f ? -mMonoFx[i] : mMonoFx[i];
        if (magnitude > fxPeak) {
            fxPeak = magnitude;
        }
    }
    if (fxPeak > mFxPeak.load(std::memory_order_relaxed)) {
        mFxPeak.store(fxPeak, std::memory_order_relaxed);
    }

    mLooper.process(mMonoFx.data(), mMonoOut.data(), numFrames);

    // Mix loop + (optional) live monitor, apply output gain, expand to all
    // output channels, and hard-limit as a final safety.
    const bool monitor = mMonitorEnabled.load(std::memory_order_relaxed);
    const float outGain = mOutputGain.load(std::memory_order_relaxed);
    float outPeak = 0.0f;
    for (int32_t i = 0; i < numFrames; ++i) {
        float sample = mMonoOut[i];
        if (monitor) {
            sample += mMonoFx[i];
        }
        if (!(sample == sample)) {  // NaN from a broken model must not reach the DAC
            sample = 0.0f;
        }
        sample = std::clamp(sample * outGain, -1.0f, 1.0f);
        const float magnitude = sample < 0.0f ? -sample : sample;
        if (magnitude > outPeak) {
            outPeak = magnitude;
        }
        float* frame = out + static_cast<size_t>(i) * outChannels;
        for (int32_t c = 0; c < outChannels; ++c) {
            frame[c] = sample;
        }
    }
    if (outPeak > mOutputPeak.load(std::memory_order_relaxed)) {
        mOutputPeak.store(outPeak, std::memory_order_relaxed);
    }

    // DSP load = callback time vs. the real-time budget of this block.
    const double elapsedSec = std::chrono::duration<double>(
        std::chrono::steady_clock::now() - callbackStart).count();
    const float load = static_cast<float>(elapsedSec * mSampleRate / numFrames);
    if (load > mDspLoad.load(std::memory_order_relaxed)) {
        mDspLoad.store(load, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

std::string AudioEngine::namLoadModel(int slot, const std::string& path) {
    if (slot < 0 || slot >= NamChain::kNumSlots) {
        return "Invalid slot";
    }

    // Heavy part outside the lock so stop()/start() stay responsive.
    std::unique_ptr<nam::DSP> dsp;
    try {
        dsp = nam::get_dsp(std::filesystem::path(path));
    } catch (const std::exception& e) {
        LOGE("NAM model load failed (%s): %s", path.c_str(), e.what());
        const char* what = e.what();
        return (what != nullptr && what[0] != '\0') ? what : "Could not load model";
    }
    if (!dsp) {
        return "Could not load model";
    }
    if (dsp->NumInputChannels() != 1 || dsp->NumOutputChannels() != 1) {
        return "Only mono models are supported";
    }

    const double expectedRate = dsp->GetExpectedSampleRate();

    std::lock_guard<std::mutex> lock(mLock);
    if (isRunning()) {
        // Size + prewarm for the live stream BEFORE the audio thread can see
        // the model. Audio keeps running; only start/stop wait on the lock.
        dsp->Reset(mSampleRate, mMaxBlockFrames);
    }
    mNamChain.publish(slot, std::move(dsp));
    mNamExpectedRate[slot].store(expectedRate, std::memory_order_relaxed);
    LOGI("NAM model loaded into slot %d (expected rate %.0f Hz): %s",
         slot, expectedRate, path.c_str());
    return "";
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("Stream error after close: %s", oboe::convertToText(error));
    if (error == oboe::Result::ErrorDisconnected) {
        // USB interface gone. Per design there is NO fallback to built-in
        // audio: flag it and let the app layer stop the engine and surface
        // the error in the UI.
        mDisconnected.store(true, std::memory_order_relaxed);
    }
    mRunning.store(false, std::memory_order_relaxed);
}
