package com.example.dnichelooper.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The input and output endpoints of one physical USB audio interface.
 * Android exposes them as two separate [AudioDeviceInfo] entries with
 * distinct ids, so both ids are needed to route a duplex engine.
 */
data class UsbAudioInterface(
    val name: String,
    val inputDeviceId: Int,
    val outputDeviceId: Int,
)

/**
 * Watches for USB audio interfaces via [AudioDeviceCallback].
 *
 * Only devices with both a USB input (source) and a USB output (sink) are
 * reported — the looper needs duplex. Built-in mics/speakers are never
 * considered, by design.
 */
class UsbDeviceDetector(private val audioManager: AudioManager) {

    private val _device = MutableStateFlow<UsbAudioInterface?>(null)
    val device: StateFlow<UsbAudioInterface?> = _device.asStateFlow()

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
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isUsb() }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isUsb() }

        // Pair input and output of the same physical interface; if several
        // are connected, prefer a matching product name.
        val input = inputs.firstOrNull()
        val output = outputs.firstOrNull {
            it.productName?.toString() == input?.productName?.toString()
        } ?: outputs.firstOrNull()

        _device.value = if (input != null && output != null) {
            UsbAudioInterface(
                name = output.productName?.toString()?.ifBlank { null } ?: "USB audio interface",
                inputDeviceId = input.id,
                outputDeviceId = output.id,
            )
        } else {
            null
        }
    }

    private fun AudioDeviceInfo.isUsb(): Boolean = type in USB_TYPES

    private companion object {
        val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
