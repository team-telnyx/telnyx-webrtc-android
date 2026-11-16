@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class V2AudioDeviceObserverTest {
    private val context = mockk<Context>()
    private val manager = mockk<AudioManager>(relaxed = true)
    private val sink = mockk<CallReportV2Ingress>(relaxed = true)

    private fun observer(): V2AudioDeviceObserver {
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns manager
        every { manager.getDevices(AudioManager.GET_DEVICES_ALL) } returns emptyArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            every { manager.communicationDevice } returns null
        }
        return V2AudioDeviceObserver(context, sink)
    }

    private fun device(id: Int, type: Int, input: Boolean = false): AudioDeviceInfo {
        val device = mockk<AudioDeviceInfo>()
        every { device.id } returns id
        every { device.type } returns type
        every { device.isSource } returns input
        every { device.isSink } returns !input
        return device
    }

    @Test fun `inventory contains generic labels and refresh ignores removed observer callbacks`() {
        val observer = observer()
        val callback = slot<AudioDeviceCallback>()
        every { manager.registerAudioDeviceCallback(capture(callback), any()) } just Runs
        val mic = device(7, AudioDeviceInfo.TYPE_BUILTIN_MIC, input = true)
        every { manager.getDevices(AudioManager.GET_DEVICES_ALL) } returns arrayOf(mic)
        observer.start()
        verify(exactly = 1) { sink.devices(listOf(V2AudioDevice("android:7:input", "microphone", true))) }
        callback.captured.onAudioDevicesAdded(arrayOf(mic))
        verify(exactly = 1) { sink.devices(any()) }
        observer.stop()
        every { manager.getDevices(AudioManager.GET_DEVICES_ALL) } returns emptyArray()
        callback.captured.onAudioDevicesRemoved(arrayOf(mic))
        verify(exactly = 1) { sink.devices(any()) }
        verify(exactly = 1) { manager.unregisterAudioDeviceCallback(callback.captured) }
        verify(exactly = 0) { mic.productName }
        verify(exactly = 0) { mic.address }
    }

    @Test fun `communication callback is confirmed output and detaches exactly once`() {
        val observer = observer()
        val callback = slot<AudioManager.OnCommunicationDeviceChangedListener>()
        every { manager.addOnCommunicationDeviceChangedListener(any(), capture(callback)) } just Runs
        val speaker = device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        every { manager.communicationDevice } returns speaker
        observer.start()
        observer.start()
        callback.captured.onCommunicationDeviceChanged(speaker)
        verify(exactly = 1) { sink.outputDevice(V2AudioDevice("android:2:output", "speaker", false)) }
        callback.captured.onCommunicationDeviceChanged(null)
        callback.captured.onCommunicationDeviceChanged(speaker)
        verify(exactly = 2) { sink.outputDevice(any()) }
        observer.stop()
        observer.stop()
        callback.captured.onCommunicationDeviceChanged(device(8, AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        verify(exactly = 2) { sink.outputDevice(any()) }
        verify(exactly = 1) { manager.removeOnCommunicationDeviceChangedListener(any()) }
    }

    @Test @Config(sdk = [28]) fun `older Android reports inventory but never infers communication routing`() {
        val observer = observer()
        every { manager.getDevices(AudioManager.GET_DEVICES_ALL) } returns arrayOf(
            device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        observer.start()
        verify(exactly = 1) { sink.devices(any()) }
        verify(exactly = 0) { sink.outputDevice(any()) }
        observer.stop()
    }

    @Test fun `permission denial does not synthesize devices or prevent retry`() {
        val observer = observer()
        every { manager.registerAudioDeviceCallback(any(), any()) } throws SecurityException("denied")
        observer.start()
        verify(exactly = 0) { sink.devices(any()) }
        every { manager.registerAudioDeviceCallback(any(), any()) } just Runs
        observer.start()
        verify(exactly = 1) { sink.devices(emptyList()) }
        observer.stop()
    }

    @Test fun `binder registration failure detaches partial listener and permits restart`() {
        val observer = observer()
        val callbacks = mutableListOf<AudioDeviceCallback>()
        every { manager.registerAudioDeviceCallback(capture(callbacks), any()) } throws IllegalStateException("binder failed")
        observer.start()
        verify(exactly = 1) { manager.unregisterAudioDeviceCallback(any()) }
        verify(exactly = 0) { sink.devices(any()) }
        every { manager.registerAudioDeviceCallback(capture(callbacks), any()) } just Runs
        observer.start()
        every { manager.getDevices(AudioManager.GET_DEVICES_ALL) } throws IllegalStateException("binder failed")
        callbacks.last().onAudioDevicesAdded(emptyArray())
        every { manager.getDevices(AudioManager.GET_DEVICES_ALL) } returns arrayOf(
            device(3, AudioDeviceInfo.TYPE_BUILTIN_MIC, input = true))
        callbacks.first().onAudioDevicesAdded(emptyArray())
        verify(exactly = 1) { sink.devices(any()) }
        callbacks.last().onAudioDevicesAdded(emptyArray())
        verify(exactly = 2) { sink.devices(any()) }
        observer.stop()
        verify(exactly = 2) { manager.unregisterAudioDeviceCallback(any()) }
    }

    @Test fun `communication registration failure removes partial listener without breaking inventory`() {
        val observer = observer()
        every { manager.addOnCommunicationDeviceChangedListener(any(), any()) } throws IllegalStateException("binder failed")
        observer.start()
        verify(exactly = 1) { manager.removeOnCommunicationDeviceChangedListener(any()) }
        verify(exactly = 1) { sink.devices(emptyList()) }
        observer.stop()
        verify(exactly = 1) { manager.removeOnCommunicationDeviceChangedListener(any()) }
        every { manager.addOnCommunicationDeviceChangedListener(any(), any()) } just Runs
        observer.start()
        observer.stop()
        verify(exactly = 2) { manager.removeOnCommunicationDeviceChangedListener(any()) }
    }
}
