package com.telnyx.webrtc.sdk.observability

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/** Only an Android session-local ID and a generic category; never a product name or address. */
internal data class V2AudioDevice(val id: String, val label: String, val input: Boolean)

/** Observes device facts without changing audio focus, routing or communication mode. */
internal class V2AudioDeviceObserver(context: Context, private val sink: CallReportV2Ingress) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val lock = Any()
    private var generation = 0L
    private var callback: AudioDeviceCallback? = null
    private var communicationObserver: CommunicationObserver? = null
    private var inventory: List<V2AudioDevice>? = null
    private var output: V2AudioDevice? = null

    fun start() = synchronized(lock) {
        if (callback != null) return@synchronized
        val manager = audioManager ?: return@synchronized
        val epoch = ++generation
        val handler = Handler(Looper.getMainLooper())
        val listener = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh(epoch)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh(epoch)
        }
        callback = listener
        try {
            manager.registerAudioDeviceCallback(listener, handler)
        } catch (_: RuntimeException) {
            callback = null
            try {
                manager.unregisterAudioDeviceCallback(listener)
            } catch (_: RuntimeException) {
                // A binder may attach before failing; invalidate delivery even if detach also fails.
            }
            return@synchronized
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val observer = CommunicationObserver(manager, Executor { handler.post(it) }) { device ->
                synchronized(lock) callbackLock@{
                    if (generation == epoch && callback != null) {
                        val next = try {
                            device?.toV2Device(input = false)
                        } catch (_: RuntimeException) {
                            return@callbackLock
                        }
                        if (next == null) output = null
                        else if (output != next) {
                            output = next
                            try {
                                sink.outputDevice(next)
                            } catch (_: RuntimeException) {
                                // Diagnostics must not escape a framework callback.
                            }
                        }
                    }
                }
            }
            communicationObserver = observer
            observer.start()
        }
        refresh(epoch)
    }

    fun stop() = synchronized(lock) {
        ++generation
        val listener = callback
        callback = null
        communicationObserver?.stop()
        communicationObserver = null
        if (listener != null) {
            try {
                audioManager?.unregisterAudioDeviceCallback(listener)
            } catch (_: RuntimeException) {
                // Callbacks from an already queued delivery are rejected by generation.
            }
        }
        inventory = null
        output = null
    }

    private fun refresh(epoch: Long) = synchronized(lock) {
        if (epoch != generation || callback == null) return@synchronized
        val next = try {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_ALL) ?: return@synchronized
            devices.flatMap { device ->
                buildList {
                    if (device.isSource) add(device.toV2Device(input = true))
                    if (device.isSink) add(device.toV2Device(input = false))
                }
            }.sortedBy { it.id }
        } catch (_: RuntimeException) {
            return@synchronized
        }
        if (inventory != next) {
            inventory = next
            try {
                sink.devices(next)
            } catch (_: RuntimeException) {
                // Diagnostics must not escape a framework callback.
            }
        }
    }

    /** Isolated so older Android releases never instantiate API 31 listener types. */
    @RequiresApi(Build.VERSION_CODES.S)
    private class CommunicationObserver(
        private val manager: AudioManager,
        private val executor: Executor,
        private val observed: (AudioDeviceInfo?) -> Unit
    ) {
        private val listener = AudioManager.OnCommunicationDeviceChangedListener { observed(it) }
        private var registered = false

        fun start() {
            try {
                registered = true
                manager.addOnCommunicationDeviceChangedListener(executor, listener)
                observed(manager.communicationDevice)
            } catch (_: RuntimeException) {
                // A binder may attach before failing. No inferred output event is emitted.
                stop()
            }
        }

        fun stop() {
            if (!registered) return
            registered = false
            try {
                manager.removeOnCommunicationDeviceChangedListener(listener)
            } catch (_: RuntimeException) {
                // The enclosing observer has already invalidated queued callbacks.
            }
        }
    }
}

private fun AudioDeviceInfo.toV2Device(input: Boolean) = V2AudioDevice(
    id = "android:$id:${if (input) "input" else "output"}",
    label = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        else -> "other"
    },
    input = input
)
