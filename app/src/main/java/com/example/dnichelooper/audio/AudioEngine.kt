package com.example.dnichelooper.audio

/**
 * Kotlin facade over the native (C++/Oboe) engine in libnamlooper.so.
 *
 * All calls are cheap: they either flip atomics read by the audio thread or
 * read atomics written by it. start()/stop() are the only heavy calls and
 * must not be invoked from the main thread in a tight loop.
 */
object AudioEngine {

    init {
        System.loadLibrary("namlooper")
    }

    /**
     * Opens and starts input + output streams on the given device ids — both
     * must belong to the USB interface. Returns false if either stream could
     * not be opened/started.
     */
    fun start(inputDeviceId: Int, outputDeviceId: Int): Boolean =
        nativeStart(inputDeviceId, outputDeviceId)

    fun stop() = nativeStop()

    val isRunning: Boolean get() = nativeIsRunning()

    /** True after the USB interface disappeared; the engine must be stopped. */
    val isDisconnected: Boolean get() = nativeIsDisconnected()

    fun sendCommand(command: LooperCommand) = nativeSendCommand(command.nativeValue)

    val looperState: LooperState get() = LooperState.fromNative(nativeGetState())
    val positionFrames: Int get() = nativeGetPositionFrames()
    val loopLengthFrames: Int get() = nativeGetLoopLengthFrames()
    val maxLoopFrames: Int get() = nativeGetMaxLoopFrames()
    val sampleRate: Int get() = nativeGetSampleRate()
    val framesPerBurst: Int get() = nativeGetFramesPerBurst()

    /**
     * Snapshot of the current loop as mono float samples, or null if there
     * is no loop. Safe to call while the engine keeps running.
     */
    fun copyLoop(): FloatArray? {
        val length = loopLengthFrames
        if (length <= 0) return null
        val samples = FloatArray(length)
        val copied = nativeCopyLoop(samples)
        if (copied <= 0) return null
        return if (copied == length) samples else samples.copyOf(copied)
    }

    /**
     * Replaces the current loop with the given mono samples (at the engine
     * sample rate) and starts playback. Requires a running engine.
     */
    fun loadLoop(samples: FloatArray): Boolean = nativeLoadLoop(samples)

    /** Peak |sample| since the last call (reading resets the meter). */
    fun readInputPeak(): Float = nativeReadInputPeak()
    fun readFxPeak(): Float = nativeReadFxPeak()
    fun readOutputPeak(): Float = nativeReadOutputPeak()

    /**
     * Underruns/overruns of both streams since start (-1 while stopped).
     * Growing while playing = the audio callback misses its deadline.
     */
    val xRunCount: Int get() = nativeGetXRunCount()

    /**
     * Worst audio-callback load since the last call (callback time divided
     * by its real-time budget, 0..1+; reading resets). Like a DAW DSP meter.
     */
    fun readDspLoad(): Float = nativeReadDspLoad()

    // --- NAM amp slots (A/S/D) — the active slot processes the live input
    // in front of the looper; empty slots pass the dry signal through.

    /**
     * Loads a .nam model file into a slot. Heavy (JSON parse + prewarm) —
     * call from a background thread. Returns null on success, otherwise a
     * readable error message.
     */
    fun namLoadModel(slot: Int, path: String): String? = nativeNamLoadModel(slot, path)

    fun namClearSlot(slot: Int) = nativeNamClearSlot(slot)
    fun namSetActiveSlot(slot: Int) = nativeNamSetActiveSlot(slot)

    /** Sample rate the slot's model was trained at (-1 = unknown/empty). */
    fun namExpectedSampleRate(slot: Int): Double = nativeNamExpectedSampleRate(slot)

    /** Frees models the audio thread retired after a swap; call from the UI poll. */
    fun namMaintenance() = nativeNamMaintenance()

    // --- Slot D: fixed cabinet IR (global, post-amp). An empty slot = bypass.
    /** Loads a mono float IR at the engine sample rate into Slot D. */
    fun irLoad(coeffs: FloatArray) = nativeIrLoad(coeffs)
    fun irClear() = nativeIrClear()

    // --- Compare mode: instant bypass (keep model/IR loaded) ---
    fun setNamBypass(bypass: Boolean) = nativeNamBypass(bypass)
    fun setIrBypass(bypass: Boolean) = nativeIrBypass(bypass)

    // --- Live capture of the post-amp+IR signal (pre-looper) ---
    fun captureStart() = nativeCaptureStart()
    fun captureStop(): Int = nativeCaptureStop()
    /** Returns the captured mono samples (at the engine sample rate), or null. */
    fun copyCapture(): FloatArray? {
        val n = captureStop()
        if (n <= 0) return null
        val a = FloatArray(n)
        val copied = nativeCopyCapture(a)
        return if (copied > 0) a.copyOf(copied) else null
    }

    fun setMonitor(enabled: Boolean) = nativeSetMonitor(enabled)
    fun setInputGain(gain: Float) = nativeSetInputGain(gain)
    fun setOutputGain(gain: Float) = nativeSetOutputGain(gain)

    // Rhythm section (metronome click + drum machine; 4/4, 3/4 or 6/8).
    fun setBpm(bpm: Int) = nativeSetBpm(bpm)
    fun setMetronome(enabled: Boolean) = nativeSetMetronome(enabled)
    fun setDrums(enabled: Boolean) = nativeSetDrums(enabled)

    /** 0 = 4/4, 1 = 3/4, 2 = 6/8. */
    fun setTimeSignature(index: Int) = nativeSetTimeSignature(index)
    fun setRhythmVolume(volume: Float) = nativeSetRhythmVolume(volume)
    fun setCountIn(enabled: Boolean) = nativeSetCountIn(enabled)

    /** Playback volume of the loop track (recording stays full-scale). */
    fun setLoopVolume(volume: Float) = nativeSetLoopVolume(volume)

    /** Recording auto-closes after this many bars; 0 disables auto-loop. */
    fun setAutoLoopBars(bars: Int) = nativeSetAutoLoopBars(bars)

    /**
     * Stages real drum one-shots (mono float at [sourceRate]) for the drum
     * machine. Call before start(); the engine converts them to its own
     * sample rate when the streams open. The metronome click stays
     * synthesized (deliberate — real stick samples were rejected).
     */
    fun setDrumSamples(kick: FloatArray, snare: FloatArray, hat: FloatArray, sourceRate: Int) =
        nativeSetDrumSamples(kick, snare, hat, sourceRate)

    private external fun nativeStart(inputDeviceId: Int, outputDeviceId: Int): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeIsDisconnected(): Boolean
    private external fun nativeSendCommand(command: Int)
    private external fun nativeGetState(): Int
    private external fun nativeGetPositionFrames(): Int
    private external fun nativeGetLoopLengthFrames(): Int
    private external fun nativeGetMaxLoopFrames(): Int
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetFramesPerBurst(): Int
    private external fun nativeCopyLoop(dest: FloatArray): Int
    private external fun nativeLoadLoop(samples: FloatArray): Boolean
    private external fun nativeReadInputPeak(): Float
    private external fun nativeReadFxPeak(): Float
    private external fun nativeReadOutputPeak(): Float
    private external fun nativeGetXRunCount(): Int
    private external fun nativeReadDspLoad(): Float
    private external fun nativeNamLoadModel(slot: Int, path: String): String?
    private external fun nativeNamClearSlot(slot: Int)
    private external fun nativeNamSetActiveSlot(slot: Int)
    private external fun nativeNamExpectedSampleRate(slot: Int): Double
    private external fun nativeNamMaintenance()
    private external fun nativeIrLoad(coeffs: FloatArray)
    private external fun nativeIrClear()
    private external fun nativeNamBypass(bypass: Boolean)
    private external fun nativeIrBypass(bypass: Boolean)
    private external fun nativeCaptureStart()
    private external fun nativeCaptureStop(): Int
    private external fun nativeCopyCapture(dest: FloatArray): Int
    private external fun nativeSetMonitor(enabled: Boolean)
    private external fun nativeSetInputGain(gain: Float)
    private external fun nativeSetOutputGain(gain: Float)
    private external fun nativeSetBpm(bpm: Int)
    private external fun nativeSetMetronome(enabled: Boolean)
    private external fun nativeSetDrums(enabled: Boolean)
    private external fun nativeSetTimeSignature(index: Int)
    private external fun nativeSetRhythmVolume(volume: Float)
    private external fun nativeSetCountIn(enabled: Boolean)
    private external fun nativeSetLoopVolume(volume: Float)
    private external fun nativeSetAutoLoopBars(bars: Int)
    private external fun nativeSetDrumSamples(
        kick: FloatArray,
        snare: FloatArray,
        hat: FloatArray,
        sourceRate: Int,
    )
}
