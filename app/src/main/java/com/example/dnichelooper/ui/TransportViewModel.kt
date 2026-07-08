package com.example.dnichelooper.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.AndroidViewModel
import java.io.File
import androidx.lifecycle.viewModelScope
import com.example.dnichelooper.audio.AudioEngine
import com.example.dnichelooper.audio.DrumKitLoader
import com.example.dnichelooper.audio.EngineService
import com.example.dnichelooper.audio.LoopLibrary
import com.example.dnichelooper.audio.LoopSaver
import com.example.dnichelooper.audio.LooperCommand
import com.example.dnichelooper.audio.IrStore
import com.example.dnichelooper.audio.NamStore
import com.example.dnichelooper.audio.WavWriter
import com.example.dnichelooper.audio.SavedLoop
import com.example.dnichelooper.audio.LooperState
import com.example.dnichelooper.audio.UsbAudioInterface
import com.example.dnichelooper.audio.UsbDeviceDetector
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TransportUiState(
    val hasPermission: Boolean = false,
    val usbDevice: UsbAudioInterface? = null,
    val engineRunning: Boolean = false,
    val looperState: LooperState = LooperState.EMPTY,
    val positionFrames: Int = 0,
    val loopLengthFrames: Int = 0,
    val maxLoopFrames: Int = 0,
    val sampleRate: Int = 0,
    val framesPerBurst: Int = 0,
    val monitorEnabled: Boolean = true,
    val inputGain: Float = 1f,
    val outputGain: Float = 1f,
    val bpm: Int = 120,
    val metronomeEnabled: Boolean = false,
    val drumsEnabled: Boolean = false,
    val timeSignature: Int = 0,          // 0 = 4/4, 1 = 3/4, 2 = 6/8
    val countInEnabled: Boolean = false,
    val rhythmVolume: Float = 0.15f,  // drum+click default 15% (install); user can raise
    val loopVolume: Float = 1f,
    val autoLoopEnabled: Boolean = false,
    val autoLoopBars: Int = 4,           // 4, 6 or 8
    val inputPeak: Float = 0f,
    val fxPeak: Float = 0f,
    val outputPeak: Float = 0f,
    val xRuns: Int = 0,
    val dspLoad: Float = 0f,
    val appCpu: Float = 0f,              // whole app, 1.0 = one full core
    val saving: Boolean = false,
    val saveMessage: String? = null,
    val savedLoops: List<SavedLoop> = emptyList(),
    val loadingLoop: Boolean = false,
    val errorMessage: String? = null,
    // NAM amp slots: model file name per slot (null = dry pass-through).
    val namSlots: List<String?> = List(NamStore.NUM_SLOTS) { null },
    val namActiveSlot: Int = 0,
    val namBusy: Boolean = false,
    val namMessage: String? = null,
    val namPresets: List<String> = emptyList(),
    // Slot D: fixed cab IR (global, not part of A/B/C switching).
    val irFileName: String? = null,  // null = bypass
    val irBusy: Boolean = false,
    val irMessage: String? = null,
    // Compare mode: A/B bypass states (Clean / Amp / Amp+IR).
    val compareMode: Int = 2,  // 0=Clean, 1=Amp, 2=Amp+IR
    val capturing: Boolean = false,
    val captureSeconds: Float = 0f,
    val captureMessage: String? = null,
)

class TransportViewModel(application: Application) : AndroidViewModel(application) {

    private val audioManager =
        application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val detector = UsbDeviceDetector(audioManager)

    private val _uiState = MutableStateFlow(TransportUiState())
    val uiState: StateFlow<TransportUiState> = _uiState.asStateFlow()

    private val startInProgress = AtomicBoolean(false)

    init {
        detector.startWatching()

        viewModelScope.launch {
            detector.device.collect { device ->
                _uiState.update { it.copy(usbDevice = device) }
                if (device != null) {
                    maybeStartEngine()
                } else if (_uiState.value.engineRunning) {
                    stopEngine("USB audio interface disconnected. Playback stopped — built-in audio is not used.")
                }
            }
        }

        // Poll the engine for playhead/state; also catches disconnects
        // reported from the native error callback.
        viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (!_uiState.value.engineRunning) continue
                if (AudioEngine.isDisconnected) {
                    stopEngine("USB audio interface disconnected. Playback stopped — built-in audio is not used.")
                } else if (!AudioEngine.isRunning) {
                    // Non-disconnect stream error: the native engine already
                    // shut itself down (onErrorAfterClose).
                    stopEngine("Audio streams stopped unexpectedly. Press Retry to restart.")
                } else {
                    // Free NAM models the audio thread retired after a swap.
                    AudioEngine.namMaintenance()
                    val inPeak = AudioEngine.readInputPeak()
                    val fxPeak = AudioEngine.readFxPeak()
                    val outPeak = AudioEngine.readOutputPeak()
                    _uiState.update {
                        it.copy(
                            looperState = AudioEngine.looperState,
                            positionFrames = AudioEngine.positionFrames,
                            loopLengthFrames = AudioEngine.loopLengthFrames,
                            // simple meter ballistics: instant attack, ~0.25s release
                            inputPeak = maxOf(inPeak, it.inputPeak * 0.8f),
                            fxPeak = maxOf(fxPeak, it.fxPeak * 0.8f),
                            outputPeak = maxOf(outPeak, it.outputPeak * 0.8f),
                            xRuns = AudioEngine.xRunCount.coerceAtLeast(0),
                            // slow release so the worst block stays readable
                            dspLoad = maxOf(AudioEngine.readDspLoad(), it.dspLoad * 0.9f),
                            appCpu = sampleAppCpu() ?: it.appCpu,
                        )
                    }
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasPermission = granted,
                errorMessage = if (granted) it.errorMessage
                else "Audio recording permission denied — the looper cannot open the USB input.",
            )
        }
        if (granted) maybeStartEngine()
    }

    fun retryStart() = maybeStartEngine()

    private fun maybeStartEngine() {
        val state = _uiState.value
        val device = state.usbDevice ?: return
        if (!state.hasPermission || state.engineRunning) return
        if (!startInProgress.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Real drum samples must be staged before the streams open.
                DrumKitLoader.ensureLoaded(getApplication())
                val ok = AudioEngine.start(device.inputDeviceId, device.outputDeviceId)
                if (ok) {
                    maxOutMediaVolume()
                    EngineService.start(getApplication())
                    // Re-apply UI-side settings to the fresh engine.
                    val s = _uiState.value
                    AudioEngine.setMonitor(s.monitorEnabled)
                    AudioEngine.setInputGain(s.inputGain)
                    AudioEngine.setOutputGain(s.outputGain)
                    AudioEngine.setBpm(s.bpm)
                    AudioEngine.setMetronome(s.metronomeEnabled)
                    AudioEngine.setDrums(s.drumsEnabled)
                    AudioEngine.setTimeSignature(s.timeSignature)
                    AudioEngine.setCountIn(s.countInEnabled)
                    AudioEngine.setRhythmVolume(s.rhythmVolume)
                    AudioEngine.setLoopVolume(s.loopVolume)
                    AudioEngine.setAutoLoopBars(if (s.autoLoopEnabled) s.autoLoopBars else 0)
                    // Re-decode the cab IR at the now-valid engine sample rate
                    // (the original file is kept; only a rendered array would go stale).
                    val irName = _uiState.value.irFileName
                    if (irName != null) {
                        applyIrFromFile(irName, AudioEngine.sampleRate)
                    } else {
                        AudioEngine.irClear()
                    }
                    setCompareMode(s.compareMode)
                    _uiState.update {
                        it.copy(
                            engineRunning = true,
                            looperState = LooperState.EMPTY,
                            positionFrames = 0,
                            loopLengthFrames = 0,
                            maxLoopFrames = AudioEngine.maxLoopFrames,
                            sampleRate = AudioEngine.sampleRate,
                            framesPerBurst = AudioEngine.framesPerBurst,
                            errorMessage = null,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            engineRunning = false,
                            errorMessage = "Could not open duplex audio streams on \"${device.name}\".",
                        )
                    }
                }
            } finally {
                startInProgress.set(false)
            }
        }
    }

    /**
     * Android throttles the USB interface's DAC to the media volume set at
     * connect time (8/25 by default cost us ~10 dB). Digital full scale is
     * the correct setting for an audio interface — loudness is controlled
     * analog at the interface/monitors.
     */
    private fun maxOutMediaVolume() {
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        }
    }

    /** Refreshes the saved-loop list (call before showing the picker). */
    fun refreshSavedLoops() {
        viewModelScope.launch(Dispatchers.IO) {
            val loops = runCatching { LoopLibrary.list(getApplication()) }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(savedLoops = loops) }
        }
    }

    /** Decodes the file and swaps it into the engine — playback starts immediately. */
    fun loadSavedLoop(loop: SavedLoop) {
        val state = _uiState.value
        if (state.loadingLoop || !state.engineRunning || state.sampleRate <= 0) return
        _uiState.update { it.copy(loadingLoop = true, saveMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val samples = LoopLibrary.decode(
                    getApplication(), loop, state.sampleRate, AudioEngine.maxLoopFrames,
                )
                check(AudioEngine.loadLoop(samples)) { "Engine rejected the loop" }
            }
            _uiState.update {
                it.copy(
                    loadingLoop = false,
                    saveMessage = result.fold(
                        onSuccess = { "Loaded: ${loop.name}" },
                        onFailure = { e -> "Load failed: ${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    private fun stopEngine(error: String?) {
        EngineService.stop(getApplication())
        viewModelScope.launch(Dispatchers.Default) {
            AudioEngine.stop()
            _uiState.update {
                it.copy(
                    engineRunning = false,
                    looperState = LooperState.EMPTY,
                    positionFrames = 0,
                    loopLengthFrames = 0,
                    errorMessage = error,
                )
            }
        }
    }

    fun record() = AudioEngine.sendCommand(LooperCommand.RECORD)
    fun play() = AudioEngine.sendCommand(LooperCommand.PLAY)
    fun toggleOverdub() = AudioEngine.sendCommand(LooperCommand.OVERDUB)
    fun stopLoop() = AudioEngine.sendCommand(LooperCommand.STOP)
    fun clear() {
        AudioEngine.sendCommand(LooperCommand.CLEAR)
        _uiState.update { it.copy(saveMessage = null) }
    }

    /** Snapshots the loop and encodes it to Music/DNicheLooper — audio keeps running. */
    fun saveLoop(name: String? = null) {
        val state = _uiState.value
        if (state.saving || state.loopLengthFrames <= 0 || state.sampleRate <= 0) return
        val samples = AudioEngine.copyLoop() ?: return
        _uiState.update { it.copy(saving = true, saveMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = LoopSaver.save(getApplication(), samples, state.sampleRate, name)
            _uiState.update {
                it.copy(
                    saving = false,
                    saveMessage = result.fold(
                        onSuccess = { path -> "Saved: $path" },
                        onFailure = { e -> "Save failed: ${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    /** Renames a saved loop and refreshes the picker list. */
    fun renameLoop(loop: SavedLoop, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = LoopLibrary.rename(getApplication(), loop, newName)
            val loops = runCatching { LoopLibrary.list(getApplication()) }
                .getOrDefault(_uiState.value.savedLoops)
            _uiState.update {
                it.copy(
                    savedLoops = loops,
                    saveMessage = result.fold(
                        onSuccess = { name -> "Renamed to: $name" },
                        onFailure = { e -> "Rename failed: ${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    fun setMonitor(enabled: Boolean) {
        AudioEngine.setMonitor(enabled)
        _uiState.update { it.copy(monitorEnabled = enabled) }
    }

    fun setInputGain(gain: Float) {
        AudioEngine.setInputGain(gain)
        _uiState.update { it.copy(inputGain = gain) }
    }

    fun setOutputGain(gain: Float) {
        AudioEngine.setOutputGain(gain)
        _uiState.update { it.copy(outputGain = gain) }
    }

    fun setBpm(bpm: Int) {
        val clamped = bpm.coerceIn(MIN_BPM, MAX_BPM)
        AudioEngine.setBpm(clamped)
        _uiState.update { it.copy(bpm = clamped) }
    }

    fun setMetronome(enabled: Boolean) {
        AudioEngine.setMetronome(enabled)
        _uiState.update { it.copy(metronomeEnabled = enabled) }
    }

    fun setDrums(enabled: Boolean) {
        AudioEngine.setDrums(enabled)
        _uiState.update { it.copy(drumsEnabled = enabled) }
    }

    fun setTimeSignature(index: Int) {
        AudioEngine.setTimeSignature(index)
        _uiState.update { it.copy(timeSignature = index) }
    }

    fun setCountIn(enabled: Boolean) {
        AudioEngine.setCountIn(enabled)
        _uiState.update { it.copy(countInEnabled = enabled) }
    }

    fun setRhythmVolume(volume: Float) {
        AudioEngine.setRhythmVolume(volume)
        _uiState.update { it.copy(rhythmVolume = volume) }
    }

    fun setLoopVolume(volume: Float) {
        AudioEngine.setLoopVolume(volume)
        _uiState.update { it.copy(loopVolume = volume) }
    }

    fun setAutoLoop(enabled: Boolean) {
        val bars = _uiState.value.autoLoopBars
        AudioEngine.setAutoLoopBars(if (enabled) bars else 0)
        _uiState.update { it.copy(autoLoopEnabled = enabled) }
    }

    fun setAutoLoopBars(bars: Int) {
        if (_uiState.value.autoLoopEnabled) {
            AudioEngine.setAutoLoopBars(bars)
        }
        _uiState.update { it.copy(autoLoopBars = bars) }
    }

    // --- NAM amp slots ---------------------------------------------------

    fun setNamActiveSlot(slot: Int) {
        AudioEngine.namSetActiveSlot(slot)
        _uiState.update { it.copy(namActiveSlot = slot) }
    }

    /** Copies the picked .nam file into app storage and loads it into [slot]. */
    fun loadNamModel(slot: Int, uri: Uri) {
        if (_uiState.value.namBusy) return
        _uiState.update { it.copy(namBusy = true, namMessage = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                val file = NamStore.importModel(getApplication(), uri)
                AudioEngine.namLoadModel(slot, file.absolutePath)?.let { err ->
                    throw IllegalStateException(err)
                }
                file.name
            }
            _uiState.update { st ->
                result.fold(
                    onSuccess = { fileName ->
                        st.copy(
                            namBusy = false,
                            namSlots = st.namSlots.replaceAt(slot, fileName),
                            namMessage = loadedModelMessage(slot, fileName, st.sampleRate),
                        )
                    },
                    onFailure = { e ->
                        st.copy(
                            namBusy = false,
                            namMessage = "Model load failed: ${e.message ?: e.javaClass.simpleName}",
                        )
                    },
                )
            }
        }
    }

    private fun loadedModelMessage(slot: Int, fileName: String, engineRate: Int): String {
        val expected = AudioEngine.namExpectedSampleRate(slot)
        return if (expected > 0 && engineRate > 0 && expected.toInt() != engineRate) {
            "Loaded: $fileName — model expects ${expected.toInt()} Hz, engine runs at $engineRate Hz"
        } else {
            "Loaded: $fileName"
        }
    }

    fun clearNamSlot(slot: Int) {
        AudioEngine.namClearSlot(slot)
        _uiState.update {
            it.copy(namSlots = it.namSlots.replaceAt(slot, null), namMessage = null)
        }
    }

    // --- Slot D: fixed cab IR (global, post-amp) -------------------------

    /** Copies the picked audio file into app storage and loads it as Slot D. */
    fun loadIr(uri: Uri) {
        if (_uiState.value.irBusy) return
        _uiState.update { it.copy(irBusy = true, irMessage = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                val file = IrStore.importIr(getApplication(), uri)
                file.name
            }
            _uiState.update { st ->
                result.fold(
                    onSuccess = { fileName ->
                        st.copy(
                            irBusy = false,
                            irFileName = fileName,
                            irMessage = "IR staged: $fileName",
                        )
                    },
                    onFailure = { e ->
                        st.copy(
                            irBusy = false,
                            irMessage = "IR import failed: ${e.message ?: e.javaClass.simpleName}",
                        )
                    },
                )
            }
            // Decode + push to the engine at the current rate (only when the
            // engine is actually running; otherwise the IR is staged and gets
            // decoded fresh on the next engine start).
            val name = _uiState.value.irFileName ?: return@launch
            val rate = _uiState.value.sampleRate
            if (_uiState.value.engineRunning && rate > 0) {
                applyIrFromFile(name, rate)
            }
        }
    }

    fun clearIr() {
        AudioEngine.irClear()
        _uiState.update { it.copy(irFileName = null, irMessage = null) }
    }

    // --- Compare mode: Clean (0) / Amp (1) / Amp+IR (2) -----------------
    // Bypasses keep the loaded model/IR so switching is instant (no reload).
    fun setCompareMode(mode: Int) {
        val m = mode.coerceIn(0, 2)
        AudioEngine.setNamBypass(m == 0)          // Clean = bypass amp
        AudioEngine.setIrBypass(m != 2)           // only Amp+IR keeps the IR
        _uiState.update { it.copy(compareMode = m) }
    }

    fun startCapture() {
        if (_uiState.value.capturing || !_uiState.value.engineRunning) return
        AudioEngine.captureStart()
        _uiState.update { it.copy(capturing = true, captureSeconds = 0f, captureMessage = null) }
        viewModelScope.launch {
            while (_uiState.value.capturing) {
                delay(200)
                // approximate: the native buffer fills at the engine rate
                val rate = _uiState.value.sampleRate
                if (rate > 0) {
                    _uiState.update { it.copy(captureSeconds = it.captureSeconds + 0.2f) }
                }
                if (_uiState.value.captureSeconds >= 85f) break // native cap ~90s
            }
        }
    }

    fun stopCapture() {
        if (!_uiState.value.capturing) return
        _uiState.update { it.copy(capturing = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val rate = _uiState.value.sampleRate
            val samples = AudioEngine.copyCapture()
            if (samples == null || samples.isEmpty() || rate <= 0) {
                _uiState.update { it.copy(captureMessage = "Capture empty") }
                return@launch
            }
            val result = runCatching {
                val ctx: android.content.Context = getApplication()
                val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "DNicheLooper")
                check(dir.isDirectory || dir.mkdirs()) { "Cannot create ${dir.absolutePath}" }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val name = "DNicheLooper_$stamp.wav"
                val file = java.io.File(dir, name)
                WavWriter.writeMono(file, samples, rate)
                name
            }
            _uiState.update {
                it.copy(captureMessage = result.fold(
                    onSuccess = { n -> "Saved: $n (${samples.size / rate}s)" },
                    onFailure = { e -> "Capture failed: ${e.message ?: e.javaClass.simpleName}" },
                ))
            }
        }
    }

    /**
     * Decodes [fileName] to mono float at the current engine sample rate and
     * pushes it into Slot D. No-op (clears) when the file is missing or the
     * engine is not running. Runs on a worker scope; safe to call on restart.
     */
    private fun applyIrFromFile(fileName: String, rate: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (rate <= 0) return@launch
            val file = IrStore.irFile(getApplication(), fileName)
            if (!file.isFile) {
                _uiState.update { it.copy(irMessage = "IR file missing: $fileName") }
                return@launch
            }
            val result = runCatching {
                val coeffs = IrStore.decode(getApplication(), file, rate)
                check(coeffs.isNotEmpty()) { "Decoded no audio from $fileName" }
                AudioEngine.irLoad(coeffs)
            }
            _uiState.update { st ->
                result.fold(
                    onSuccess = { st.copy(irMessage = "Cab IR active: $fileName") },
                    onFailure = { e ->
                        AudioEngine.irClear()
                        st.copy(irMessage = "IR load failed: ${e.message ?: e.javaClass.simpleName}")
                    },
                )
            }
        }
    }

    fun refreshNamPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            val presets = runCatching { NamStore.listPresets(getApplication()) }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(namPresets = presets) }
        }
    }

    fun saveNamPreset(name: String) {
        val st = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                NamStore.savePreset(getApplication(), name, st.namSlots, st.namActiveSlot, st.irFileName)
            }
            val presets = runCatching { NamStore.listPresets(getApplication()) }
                .getOrDefault(_uiState.value.namPresets)
            _uiState.update {
                it.copy(
                    namPresets = presets,
                    namMessage = result.fold(
                        onSuccess = { "Preset saved: $name" },
                        onFailure = { e -> "Preset save failed: ${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    /** Restores all three slots + active slot from a preset. */
    fun loadNamPreset(name: String) {
        if (_uiState.value.namBusy) return
        _uiState.update { it.copy(namBusy = true, namMessage = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                val preset = NamStore.loadPreset(getApplication(), name)
                preset.slotFileNames.forEachIndexed { slot, fileName ->
                    if (fileName == null) {
                        AudioEngine.namClearSlot(slot)
                    } else {
                        val file = NamStore.modelFile(getApplication(), fileName)
                        check(file.isFile) { "Model $fileName is missing" }
                        AudioEngine.namLoadModel(slot, file.absolutePath)?.let { err ->
                            throw IllegalStateException("$fileName: $err")
                        }
                    }
                }
                AudioEngine.namSetActiveSlot(preset.activeSlot)
                preset
            }
            _uiState.update { st ->
                result.fold(
                    onSuccess = { preset ->
                        st.copy(
                            namBusy = false,
                            namSlots = preset.slotFileNames,
                            namActiveSlot = preset.activeSlot,
                            irFileName = preset.ir,
                            namMessage = "Preset loaded: $name",
                        )
                    },
                    onFailure = { e ->
                        st.copy(
                            namBusy = false,
                            namMessage = "Preset load failed: ${e.message ?: e.javaClass.simpleName}",
                        )
                    },
                )
            }
            // Apply Slot D from the preset (decode at the current rate, or
            // clear it). No effect until the engine runs.
            val irName = _uiState.value.irFileName
            if (irName != null && _uiState.value.engineRunning) {
                applyIrFromFile(irName, _uiState.value.sampleRate)
            } else if (irName == null) {
                AudioEngine.irClear()
            }
        }
    }

    fun deleteNamPreset(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = runCatching { NamStore.deletePreset(getApplication(), name) }
                .getOrDefault(false)
            val presets = runCatching { NamStore.listPresets(getApplication()) }
                .getOrDefault(_uiState.value.namPresets)
            _uiState.update {
                it.copy(
                    namPresets = presets,
                    namMessage = if (deleted) "Preset deleted: $name" else "Preset delete failed: $name",
                )
            }
        }
    }

    private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
        mapIndexed { i, old -> if (i == index) value else old }

    // --- App CPU sampling (whole process, like `top`; 1.0 = one core) ----

    private var cpuLastTicks = -1L
    private var cpuLastWallMs = 0L

    /**
     * CPU time of this process from /proc/self/stat, sampled over ≥500 ms
     * windows (ticks are only 10 ms, shorter windows would just jitter).
     * Returns null between samples.
     */
    private fun sampleAppCpu(): Float? {
        val nowMs = SystemClock.elapsedRealtime()
        if (cpuLastTicks >= 0 && nowMs - cpuLastWallMs < 500) return null
        val ticks = runCatching {
            val stat = File("/proc/self/stat").readText()
            val fields = stat.substringAfterLast(") ").split(" ")
            fields[11].toLong() + fields[12].toLong()  // utime + stime
        }.getOrNull() ?: return null

        var load: Float? = null
        if (cpuLastTicks >= 0) {
            val wallSec = (nowMs - cpuLastWallMs) / 1000f
            val clkTck = Os.sysconf(OsConstants._SC_CLK_TCK).toFloat()
            if (wallSec > 0 && clkTck > 0) {
                load = (ticks - cpuLastTicks) / clkTck / wallSec
            }
        }
        cpuLastTicks = ticks
        cpuLastWallMs = nowMs
        return load
    }

    override fun onCleared() {
        detector.stopWatching()
        EngineService.stop(getApplication())
        AudioEngine.stop()
    }

    companion object {
        private const val POLL_MS = 50L
        const val MIN_BPM = 40
        const val MAX_BPM = 240
    }
}
