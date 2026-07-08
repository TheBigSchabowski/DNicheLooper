#include "IrProcessor.h"

#include <algorithm>
#include <cmath>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define DN_HAS_NEON 1
#else
#define DN_HAS_NEON 0
#endif

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
    if (static_cast<int32_t>(mLine.size()) < 2 * kMaxTaps) {
        mLine.assign(static_cast<size_t>(2 * kMaxTaps), 0.0f);
    } else {
        std::fill(mLine.begin(), mLine.end(), 0.0f);
    }
    mWritePos = 0;
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

namespace {
// Returns the dot product of two length-`n` float arrays, NEON-vectorized
// on ARM. Four independent accumulators hide the FMLA latency; the loop is
// associative-free (the reduction order is fixed by us, so no -ffast-math is
// needed — matching the project's no-fast-math rule).
inline float firDot(const float* h, const float* x, int32_t n) {
#if DN_HAS_NEON
    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);
    float32x4_t acc2 = vdupq_n_f32(0.0f);
    float32x4_t acc3 = vdupq_n_f32(0.0f);
    int32_t k = 0;
    for (; k + 16 <= n; k += 16) {
        acc0 = vmlaq_f32(acc0, vld1q_f32(h + k),       vld1q_f32(x + k));
        acc1 = vmlaq_f32(acc1, vld1q_f32(h + k + 4),   vld1q_f32(x + k + 4));
        acc2 = vmlaq_f32(acc2, vld1q_f32(h + k + 8),   vld1q_f32(x + k + 8));
        acc3 = vmlaq_f32(acc3, vld1q_f32(h + k + 12),  vld1q_f32(x + k + 12));
    }
    for (; k + 4 <= n; k += 4) {
        acc0 = vmlaq_f32(acc0, vld1q_f32(h + k), vld1q_f32(x + k));
    }
    float32x4_t acc = vaddq_f32(vaddq_f32(acc0, acc1), vaddq_f32(acc2, acc3));
    float y = (vgetq_lane_f32(acc, 0) + vgetq_lane_f32(acc, 1)) +
              (vgetq_lane_f32(acc, 2) + vgetq_lane_f32(acc, 3));
    for (; k < n; ++k) {
        y += h[k] * x[k];
    }
    return y;
#else
    // Scalar fallback (x86 emulator builds only — not a performance path).
    float y = 0.0f;
    for (int32_t k = 0; k < n; ++k) {
        y += h[k] * x[k];
    }
    return y;
#endif
}
}  // namespace

void IrProcessor::process(float* samples, int32_t numFrames) {
    adoptIncoming();
    if (mBypass.load(std::memory_order_relaxed)) {
        return;  // compare bypass: leave the block untouched, keep IR loaded
    }
    const IrData* ir = mActive;
    if (ir == nullptr || ir->tapCount <= 0) {
        return;  // no IR loaded: bypass
    }
    const int32_t taps = ir->tapCount;
    // The delay line is pre-allocated to 2*kMaxTaps in resetDelayLine/prepare.
    if (static_cast<int32_t>(mLine.size()) < 2 * taps) {
        return;  // defensive: should not happen
    }

    const float* h = ir->hrev.data();
    float* line = mLine.data();
    int32_t w = mWritePos;

    for (int32_t i = 0; i < numFrames; ++i) {
        const float x = samples[i];
        // Mirrored write so the window [w+1 .. w+taps] is contiguous.
        line[w] = x;
        line[w + taps] = x;
        // Window oldest..newest = line[w+1 .. w+taps] (length taps).
        samples[i] = firDot(h, line + w + 1, taps);
        if (++w >= taps) {
            w = 0;
        }
    }
    mWritePos = w;
}
