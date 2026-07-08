#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "IrProcessor.h"
#include "LooperEngine.h"
#include "NamChain.h"

/**
 * Full-duplex Oboe engine.
 *
 * Two streams are opened on the SAME USB interface (explicit device ids for
 * both directions — never the phone's built-in audio). The output stream owns
 * the real-time callback; the input stream is read non-blocking from inside
 * that callback (the canonical Oboe full-duplex pattern, as in the LiveEffect
 * sample). Because both directions sit on one USB clock there is no drift and
 * no resampling.
 *
 * The audio callback is allocation-free, lock-free and log-free. All buffers
 * are sized in start(). Control values cross threads via atomics only.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    static constexpr int32_t kMaxLoopSeconds = 120;
    static constexpr int32_t kMaxCaptureSeconds = 90;

    // App thread. Opens + starts both streams on the given device ids.
    // Returns false (and leaves everything closed) on any failure.
    bool start(int32_t inputDeviceId, int32_t outputDeviceId);

    // App thread. Stops and closes both streams. Safe to call repeatedly.
    void stop();

    bool isRunning() const { return mRunning.load(std::memory_order_relaxed); }
    bool isDisconnected() const { return mDisconnected.load(std::memory_order_relaxed); }

    LooperEngine& looper() { return mLooper; }

    // App thread: snapshot the current loop (mono samples). Serialized with
    // start()/stop() so the buffer cannot be reallocated mid-copy; while
    // recording/overdubbing the result is a best-effort snapshot (see
    // LooperEngine::copyLoop).
    int32_t copyLoop(float* dest, int32_t maxSamples) {
        std::lock_guard<std::mutex> lock(mLock);
        return mLooper.copyLoop(dest, maxSamples);
    }

    // App thread: replace the loop with mono samples and start playback.
    // Requires a running stream; serialized with start()/stop().
    bool loadLoop(const float* data, int32_t numSamples) {
        std::lock_guard<std::mutex> lock(mLock);
        if (!isRunning()) {
            return false;
        }
        return mLooper.loadLoop(data, numSamples);
    }

    // App thread: stage real drum one-shots. Call before start(); while the
    // engine is running they only take effect on the next (re)start.
    void setDrumSamples(std::vector<float> kick, std::vector<float> snare,
                        std::vector<float> hat, int32_t sourceRate) {
        std::lock_guard<std::mutex> lock(mLock);
        mLooper.rhythm().setDrumSamples(std::move(kick), std::move(snare),
                                        std::move(hat), sourceRate);
    }

    // App thread: loads a .nam model into a slot. Heavy (JSON parse, weight
    // allocation, prewarm) — run it on a worker thread. Returns an empty
    // string on success, otherwise a readable error message.
    std::string namLoadModel(int slot, const std::string& path);

    void namClearSlot(int slot) {
        mNamChain.publish(slot, nullptr);
        if (slot >= 0 && slot < NamChain::kNumSlots) {
            mNamExpectedRate[slot].store(-1.0, std::memory_order_relaxed);
        }
    }

    void namSetActiveSlot(int slot) { mNamChain.setActiveSlot(slot); }

    // Expected sample rate of the model in a slot (-1 = unknown/empty).
    double namExpectedSampleRate(int slot) const {
        if (slot < 0 || slot >= NamChain::kNumSlots) {
            return -1.0;
        }
        return mNamExpectedRate[slot].load(std::memory_order_relaxed);
    }

    // App thread, called from the UI poll: frees models the audio thread
    // retired after a slot swap.
    void namMaintenance() {
        mNamChain.collectGarbage();
        mIrProcessor.collectGarbage();
    }

    // App thread: loads an engine-rate mono IR (Slot D, global/fixed).
    // [coeffs, coeffs+numTaps) is copied, truncated to IrProcessor::kMaxTaps
    // and energy-normalized. numTaps<=0 clears the slot (bypass).
    void irLoad(const float* coeffs, int32_t numTaps) { mIrProcessor.publish(coeffs, numTaps); }

    void irClear() { mIrProcessor.publish(nullptr, 0); }

    // --- Compare mode: instant bypass (keep model/IR loaded) + live capture ---
    void setNamBypass(bool bypass) { mNamChain.setBypass(bypass); }
    void setIrBypass(bool bypass) { mIrProcessor.setBypass(bypass); }

    // Capture the live post-amp+IR signal (pre-looper) to a pre-allocated
    // buffer; the audio thread only appends, allocation happens in start().
    void startCapture() {
        mCaptureFrames.store(0, std::memory_order_relaxed);
        mCapturing.store(true, std::memory_order_relaxed);
    }
    int32_t stopCapture() {
        mCapturing.store(false, std::memory_order_relaxed);
        return mCaptureFrames.load(std::memory_order_relaxed);
    }
    int32_t copyCapture(float* dest, int32_t maxSamples) {
        std::lock_guard<std::mutex> lock(mLock);
        const int32_t n = std::min(mCaptureFrames.load(std::memory_order_relaxed), maxSamples);
        if (n > 0) std::copy_n(mCaptureBuf.data(), static_cast<size_t>(n), dest);
        return n;
    }

    void setMonitorEnabled(bool enabled) { mMonitorEnabled.store(enabled, std::memory_order_relaxed); }
    void setInputGain(float gain) { mInputGain.store(gain, std::memory_order_relaxed); }
    void setOutputGain(float gain) { mOutputGain.store(gain, std::memory_order_relaxed); }

    int32_t sampleRate() const { return mSampleRate; }
    int32_t framesPerBurst() const { return mFramesPerBurst; }
    int32_t inputChannelCount() const { return mInputChannels; }
    int32_t outputChannelCount() const { return mOutputChannels; }

    // Peak meters: max |sample| since the last read (read resets to 0).
    // Input is measured post-input-gain, FX post-NAM, output post-limiter.
    float readInputPeak() { return mInputPeak.exchange(0.0f, std::memory_order_relaxed); }
    float readFxPeak() { return mFxPeak.exchange(0.0f, std::memory_order_relaxed); }
    float readOutputPeak() { return mOutputPeak.exchange(0.0f, std::memory_order_relaxed); }

    // DSP load: worst callback (execution time / real-time budget) since the
    // last read, 0..1+ (read resets to 0). >1 means a missed deadline.
    float readDspLoad() { return mDspLoad.exchange(0.0f, std::memory_order_relaxed); }

    // App thread: combined underrun/overrun count of both streams since
    // start (-1 while stopped). Nonzero growth while playing = the audio
    // thread is missing its deadline (audible glitches).
    int32_t xRunCount() {
        std::lock_guard<std::mutex> lock(mLock);
        if (!isRunning() || !mOutputStream || !mInputStream) {
            return -1;
        }
        int32_t total = 0;
        if (auto r = mOutputStream->getXRunCount()) {
            total += r.value();
        }
        if (auto r = mInputStream->getXRunCount()) {
            total += r.value();
        }
        return total;
    }

    // Real-time callback (output stream).
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;

    // Called on a non-RT thread after a stream error (e.g. USB unplugged).
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStreams(int32_t inputDeviceId, int32_t outputDeviceId);
    void closeStreamsLocked();

    std::mutex mLock;  // guards start/stop from app threads (never the callback)
    std::shared_ptr<oboe::AudioStream> mInputStream;
    std::shared_ptr<oboe::AudioStream> mOutputStream;

    LooperEngine mLooper;
    NamChain mNamChain;
    IrProcessor mIrProcessor;  // fixed Slot D (cab IR), post-amp
    std::array<std::atomic<double>, NamChain::kNumSlots> mNamExpectedRate{-1.0, -1.0, -1.0};

    // Pre-allocated conversion buffers (sized in start()).
    std::vector<float> mInputInterleaved;
    std::vector<float> mMonoIn;
    std::vector<float> mMonoFx;
    std::vector<float> mMonoOut;
    int32_t mMaxBlockFrames = 0;

    int32_t mSampleRate = 0;
    int32_t mFramesPerBurst = 0;
    int32_t mInputChannels = 0;
    int32_t mOutputChannels = 0;

    // Callbacks during which any input backlog is discarded after start, so
    // steady state runs at minimal input latency.
    std::atomic<int32_t> mDrainCallbacks{0};

    std::atomic<bool> mRunning{false};
    std::atomic<bool> mDisconnected{false};
    std::atomic<bool> mMonitorEnabled{true};
    std::atomic<float> mInputGain{1.0f};
    std::atomic<float> mOutputGain{1.0f};
    std::atomic<float> mInputPeak{0.0f};
    std::atomic<float> mFxPeak{0.0f};
    std::atomic<float> mOutputPeak{0.0f};
    std::atomic<float> mDspLoad{0.0f};

    // Live compare capture (post-amp+IR, pre-looper).
    std::vector<float> mCaptureBuf;
    std::atomic<bool> mCapturing{false};
    std::atomic<int32_t> mCaptureFrames{0};
};
