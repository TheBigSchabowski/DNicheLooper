package com.example.dnichelooper.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the engine gets its audio from and sends it to. */
enum class AudioRouteKind {
    /** One USB audio interface for both directions — the original design. */
    USB,

    /** The phone itself: built-in mic in, whatever Android routes out. */
    BUILT_IN,
}

/**
 * A duplex audio route the engine can be started on.
 *
 * For [AudioRouteKind.USB] the ids are the real Android device ids of the
 * interface's two endpoints. For [AudioRouteKind.BUILT_IN] both ids are 0,
 * which Oboe reads as "unspecified" — Android then picks the default input
 * and, crucially, follows the *current* output route on its own. That is why
 * built-in mode needs no headphone handling of its own: plug headphones in
 * and the sound follows them, unplug them and it returns to the speaker.
 */
data class AudioRoute(
    val name: String,
    val inputDeviceId: Int,
    val outputDeviceId: Int,
    val kind: AudioRouteKind,
) {
    val isUsb: Boolean get() = kind == AudioRouteKind.USB

    companion object {
        fun builtIn(outputName: String): AudioRoute = AudioRoute(
            name = outputName,
            inputDeviceId = UNSPECIFIED_DEVICE_ID,
            outputDeviceId = UNSPECIFIED_DEVICE_ID,
            kind = AudioRouteKind.BUILT_IN,
        )

        /** oboe::kUnspecified — "let Android choose". */
        const val UNSPECIFIED_DEVICE_ID = 0
    }
}

/**
 * Watches the available audio endpoints via [AudioDeviceCallback].
 *
 * Reports two independent things: the USB interface (if one is connected with
 * both a source and a sink, since the looper needs duplex), and a label for
 * where built-in audio would currently go. The choice between them is the
 * view model's, not this class's.
 */
class AudioRouteDetector(private val audioManager: AudioManager) {

    private val _usb = MutableStateFlow<AudioRoute?>(null)
    val usb: StateFlow<AudioRoute?> = _usb.asStateFlow()

    /** Human-readable current built-in output, e.g. "Headphones" or "Speaker". */
    private val _builtInOutput = MutableStateFlow(SPEAKER_LABEL)
    val builtInOutput: StateFlow<String> = _builtInOutput.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun startWatching() {
        // null handler => callbacks on the main thread
        audioManager.registerAudioDeviceCallback(callback, null)
        refresh()
    }

    fun stopWatching() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun refresh() {
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val inputs = allInputs.filter { it.isUsb() }
        val outputs = allOutputs.filter { it.isUsb() }

        // Pair input and output of the same physical interface; if several
        // are connected, prefer a matching product name.
        val input = inputs.firstOrNull()
        val output = outputs.firstOrNull {
            it.productName?.toString() == input?.productName?.toString()
        } ?: outputs.firstOrNull()

        _usb.value = if (input != null && output != null) {
            AudioRoute(
                name = output.productName?.toString()?.ifBlank { null } ?: "USB audio interface",
                inputDeviceId = input.id,
                outputDeviceId = output.id,
                kind = AudioRouteKind.USB,
            )
        } else {
            null
        }

        // Only a label — Android does the actual routing. Wired beats
        // Bluetooth here because that is also the order Android itself picks.
        _builtInOutput.value = when {
            allOutputs.any { it.type in WIRED_OUT_TYPES } -> HEADPHONE_LABEL
            allOutputs.any { it.type in BLUETOOTH_OUT_TYPES } -> BLUETOOTH_LABEL
            else -> SPEAKER_LABEL
        }
    }

    private fun AudioDeviceInfo.isUsb(): Boolean = type in USB_TYPES

    private companion object {
        const val SPEAKER_LABEL = "Phone speaker"
        const val HEADPHONE_LABEL = "Headphones"
        const val BLUETOOTH_LABEL = "Bluetooth"

        val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )

        val WIRED_OUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )

        val BLUETOOTH_OUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
    }
}
