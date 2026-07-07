#pragma once

#include <atomic>
#include <vector>

#include <Eigen/Core>

/**
 * Cabinet impulse-response loader — the fixed "Slot D" that sits AFTER the
 * switchable NAM amp (A/B/C) and BEFORE the looper. Amp changes, the cab
 * stays. An empty Slot D is a bypass (samples pass through untouched).
 *
 * Implementation: a zero-latency time-domain FIR (no FFT/block convolution).
 * The IR is truncated to at most [kMaxTaps] samples at the engine rate and
 * energy-normalized on load so swapping IRs does not jump in loudness.
 *
 * Threading model mirrors NamChain: the app thread builds an IrData and
 * publishes it through one atomic; the audio thread adopts it at a block
 * boundary and hands the previous one back through a second atomic for the
 * app thread to delete via collectGarbage(). The audio thread never
 * allocates or frees — the delay line is pre-allocated to the max tap count.
 */
class IrProcessor {
public:
    static constexpr int32_t kMaxTaps = 4096;

    ~IrProcessor();

    /**
     * App thread. Publishes an engine-rate IR taken from [coeffs, coeffs+count).
     * The data is copied, truncated to kMaxTaps and energy-normalized (unity
     * power gain). Pass count<=0 (or nullptr) to clear the slot (bypass).
     */
    void publish(const float* coeffs, int32_t count);

    /** App thread, periodic (UI poll): frees IRs the audio thread retired. */
    void collectGarbage();

    /**
     * App thread with the streams stopped: adopt any pending IR, drop retired
     * ones and reset the delay line. Safe because no callback is running.
     */
    void prepare();

    /**
     * Audio thread only. Adopts a pending IR (if any), then convolves the
     * [samples, samples+numFrames) block IN PLACE through the active IR.
     * With no IR loaded the block is left untouched (bypass).
     */
    void process(float* samples, int32_t numFrames);

private:
    struct IrData {
        // Reversed coefficients so the contiguous delay-line window
        // (oldest..newest) dots directly with hrev (newest-weight first).
        Eigen::VectorXf hrev;
        int32_t tapCount = 0;
    };

    // Distinguishes an explicit "clear" from "no pending update" (nullptr).
    static IrData* clearSentinel() { return reinterpret_cast<IrData*>(1); }

    void adoptIncoming();
    void resetDelayLine();

    std::atomic<IrData*> mIncoming{nullptr};  // app -> audio
    std::atomic<IrData*> mRetired{nullptr};   // audio -> app
    IrData* mActive = nullptr;                // audio thread (app thread when stopped)

    // Doubled circular delay line (mirrored writes) so the last [tapCount]
    // input samples always form a contiguous span for a vectorized dot.
    // Capacity is fixed at 2*kMaxTaps; only the first 2*tapCount are used.
    std::vector<float> mLine;
    int32_t mWritePos = 0;  // next write index, in [0, tapCount)
};
