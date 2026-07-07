#include "NamChain.h"

#include <algorithm>

NamChain::~NamChain() {
    for (Slot& slot : mSlots) {
        nam::DSP* incoming = slot.incoming.exchange(nullptr, std::memory_order_acq_rel);
        if (incoming != nullptr && incoming != clearSentinel()) {
            delete incoming;
        }
        delete slot.retired.exchange(nullptr, std::memory_order_acq_rel);
        delete slot.active;
        slot.active = nullptr;
    }
}

void NamChain::publish(int slot, std::unique_ptr<nam::DSP> dsp) {
    if (slot < 0 || slot >= kNumSlots) {
        return;
    }
    nam::DSP* next = dsp ? dsp.release() : clearSentinel();
    nam::DSP* previous = mSlots[slot].incoming.exchange(next, std::memory_order_acq_rel);
    // A pending model the audio thread never adopted is ours to free.
    if (previous != nullptr && previous != clearSentinel()) {
        delete previous;
    }
}

void NamChain::collectGarbage() {
    for (Slot& slot : mSlots) {
        delete slot.retired.exchange(nullptr, std::memory_order_acq_rel);
    }
}

void NamChain::adoptIncoming(Slot& slot) {
    // Only adopt when the retirement hand-off is free, so no model is ever
    // dropped on the audio thread.
    if (slot.incoming.load(std::memory_order_relaxed) == nullptr ||
        slot.retired.load(std::memory_order_relaxed) != nullptr) {
        return;
    }
    nam::DSP* next = slot.incoming.exchange(nullptr, std::memory_order_acq_rel);
    if (next == nullptr) {
        return;
    }
    slot.retired.store(slot.active, std::memory_order_release);
    slot.active = (next == clearSentinel()) ? nullptr : next;
}

void NamChain::prepare(double sampleRate, int maxBufferSize) {
    for (Slot& slot : mSlots) {
        collectGarbage();
        adoptIncoming(slot);
        collectGarbage();
        if (slot.active != nullptr) {
            slot.active->Reset(sampleRate, maxBufferSize);
        }
    }
}

void NamChain::process(const float* input, float* output, int32_t numFrames) {
    for (Slot& slot : mSlots) {
        adoptIncoming(slot);
    }
    nam::DSP* dsp = mSlots[activeSlot()].active;
    if (dsp == nullptr) {
        std::copy(input, input + numFrames, output);
        return;
    }
    float* inChannels[1] = {const_cast<float*>(input)};
    float* outChannels[1] = {output};
    dsp->process(inChannels, outChannels, numFrames);
}
