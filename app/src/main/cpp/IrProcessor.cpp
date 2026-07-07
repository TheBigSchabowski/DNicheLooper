#include "IrProcessor.h"

#include <algorithm>
#include <cmath>

IrProcessor::~IrProcessor() {
    IrData* incoming = mIncoming.exchange(nullptr, std::memory_order_acq_rel);
    if (incoming != nullptr && incoming != clearSentinel()) {
        delete incoming;
    }
    delete mRetired.exchange(nullptr, std::memory_order_acq_rel);
    delete mActive;
    mActive = nullptr;
}

void IrProcessor::publish(const float* coeffs, int32_t count) {
    IrData* next = nullptr;
    if (coeffs != nullptr && count > 0) {
        // Truncate to the engine-rate tap budget (typical cab IRs: 2048).
        const int32_t taps = std::min(count, kMaxTaps);

        // Energy normalization (unity power gain): scale so the sum of
        // squared coefficients is 1. This keeps loudness constant across IR
        // changes — a peak-normalized IR would jump when a quieter cab is
        // loaded.
        double energy = 0.0;
        for (int32_t i = 0; i < taps; ++i) {
            energy += static_cast<double>(coeffs[i]) * coeffs[i];
        }
        const float scale =
            (energy > 1e-12) ? static_cast<float>(1.0 / std::sqrt(energy)) : 1.0f;

        next = new IrData();
        next->hrev.resize(taps);
        // Store reversed: hrev[j] = h[taps-1-j], so the delay-line window
        // (oldest at index 0, newest at index taps-1) dots as
        // y = hrev.dot(window) = sum_j h[taps-1-j]*window[j].
        for (int32_t j = 0; j < taps; ++j) {
            next->hrev[j] = coeffs[taps - 1 - j] * scale;
        }
        next->tapCount = taps;
    } else {
        next = clearSentinel();
    }

    IrData* previous = mIncoming.exchange(next, std::memory_order_acq_rel);
    // A pending IR the audio thread never adopted is ours to free.
    if (previous != nullptr && previous != clearSentinel()) {
        delete previous;
    }
}

void IrProcessor::collectGarbage() {
    delete mRetired.exchange(nullptr, std::memory_order_acq_rel);
}

void IrProcessor::adoptIncoming() {
    // Only adopt when the retirement hand-off is free, so no IR is ever
    // dropped on the audio thread.
    if (mIncoming.load(std::memory_order_relaxed) == nullptr ||
        mRetired.load(std::memory_order_relaxed) != nullptr) {
        return;
    }
    IrData* next = mIncoming.exchange(nullptr, std::memory_order_acq_rel);
    if (next == nullptr) {
        return;
    }
    mRetired.store(mActive, std::memory_order_release);
    mActive = (next == clearSentinel()) ? nullptr : next;
    resetDelayLine();
}

void IrProcessor::resetDelayLine() {
    const int32_t taps = (mActive != nullptr) ? mActive->tapCount : 0;
    if (static_cast<int32_t>(mLine.size()) < 2 * kMaxTaps) {
        mLine.assign(static_cast<size_t>(2 * kMaxTaps), 0.0f);
    } else {
        std::fill(mLine.begin(), mLine.end(), 0.0f);
    }
    mWritePos = 0;
    (void)taps;
}

void IrProcessor::prepare() {
    collectGarbage();
    adoptIncoming();
    collectGarbage();
    // ensureCapacity + clear regardless (a fresh stream starts dry).
    if (static_cast<int32_t>(mLine.size()) < 2 * kMaxTaps) {
        mLine.assign(static_cast<size_t>(2 * kMaxTaps), 0.0f);
    } else {
        std::fill(mLine.begin(), mLine.end(), 0.0f);
    }
    mWritePos = 0;
}

void IrProcessor::process(float* samples, int32_t numFrames) {
    adoptIncoming();
    const IrData* ir = mActive;
    if (ir == nullptr || ir->tapCount <= 0) {
        return;  // bypass: leave the block untouched
    }
    const int32_t taps = ir->tapCount;
    // The delay line must be large enough; prepare() sized it. If an IR was
    // published while running, resetDelayLine() ran on adopt and resized/cleared.
    if (static_cast<int32_t>(mLine.size()) < 2 * taps) {
        // Defensive: should not happen (mLine is 2*kMaxTaps >= 2*taps).
        return;
    }

    const Eigen::Map<const Eigen::VectorXf> hrev(ir->hrev.data(), taps);
    float* line = mLine.data();
    int32_t w = mWritePos;

    for (int32_t i = 0; i < numFrames; ++i) {
        const float x = samples[i];
        // Mirrored write so the window [w+1 .. w+taps] is contiguous.
        line[w] = x;
        line[w + taps] = x;
        // Window oldest..newest = line[w+1 .. w+taps] (length taps).
        const Eigen::Map<const Eigen::VectorXf> window(line + w + 1, taps);
        samples[i] = static_cast<float>(hrev.dot(window));
        if (++w >= taps) {
            w = 0;
        }
    }
    mWritePos = w;
}
