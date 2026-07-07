#pragma once

#include <atomic>
#include <memory>
#include <string>

#include "NAM/dsp.h"

/**
 * Three switchable NAM amp slots (A/S/D) sitting in FRONT of the looper, so
 * a loop records the amp sound (same design as the desktop NicheLooper).
 * An empty slot passes the dry signal through.
 *
 * Threading model:
 *  - Model loading (nam::get_dsp) is heavy and runs on app threads only.
 *  - New models are handed to the audio thread through one atomic pointer
 *    per slot; the audio thread adopts them at a block boundary and hands
 *    the previous model back through a second atomic for the app thread to
 *    delete (collectGarbage). The audio thread never allocates or frees.
 *  - While the streams are stopped (no callback running) prepare() adopts
 *    pending models directly and Resets every model for the new stream
 *    parameters.
 */
class NamChain {
public:
    static constexpr int kNumSlots = 3;

    ~NamChain();

    // App thread. Takes ownership of dsp; nullptr clears the slot. The model
    // must already be Reset for the current stream parameters if the engine
    // is running (AudioEngine::namLoadModel takes care of that).
    void publish(int slot, std::unique_ptr<nam::DSP> dsp);

    // App thread, called periodically (UI poll): frees models the audio
    // thread retired after a swap.
    void collectGarbage();

    // App thread with the streams stopped: adopt pending models, drop
    // retired ones and Reset all live models (Reset prewarns by default).
    void prepare(double sampleRate, int maxBufferSize);

    void setActiveSlot(int slot) {
        if (slot >= 0 && slot < kNumSlots) {
            mActiveSlot.store(slot, std::memory_order_relaxed);
        }
    }
    int activeSlot() const { return mActiveSlot.load(std::memory_order_relaxed); }

    // Audio thread only. Adopts pending models, then runs the active slot
    // (or copies input to output when the slot is empty).
    void process(const float* input, float* output, int32_t numFrames);

private:
    // Sentinel published for an explicit "clear slot" (distinguishes it from
    // "no news" = nullptr in the incoming atomic).
    static nam::DSP* clearSentinel() { return reinterpret_cast<nam::DSP*>(1); }

    struct Slot {
        std::atomic<nam::DSP*> incoming{nullptr};  // app -> audio
        std::atomic<nam::DSP*> retired{nullptr};   // audio -> app
        nam::DSP* active = nullptr;  // audio thread (app thread only when stopped)
    };

    void adoptIncoming(Slot& slot);

    Slot mSlots[kNumSlots];
    std::atomic<int> mActiveSlot{0};
};
