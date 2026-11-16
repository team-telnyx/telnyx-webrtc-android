@file:Suppress("FunctionNaming", "MagicNumber")
package com.telnyx.webrtc.sdk.observability

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import io.mockk.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class V2MicrophoneObserverTest {
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<AudioManager>(relaxed = true)
    private val sink = mockk<CallReportV2Ingress>(relaxed = true)
    private val callbacks = mutableListOf<BroadcastReceiver>()
    private fun observer(): V2MicrophoneObserver {
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns manager
        every { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) } returns PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            every { context.registerReceiver(capture(callbacks), any(), any<Int>()) } returns null
        }
        every { context.registerReceiver(capture(callbacks), any()) } returns null
        every { manager.isMicrophoneMute } returns false
        return V2MicrophoneObserver(context, sink)
    }
    @Test fun `snapshots deduplicate and permission denial stays separate from system mute`() {
        val observer = observer()
        observer.start(); observer.start(); observer.refreshPermission()
        verify(exactly = 1) { sink.nativeFact(V2NativeFact.MicrophonePermission(true)) }
        verify(exactly = 1) { sink.nativeFact(V2NativeFact.MicrophoneSystemMute(false)) }
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
        observer.refreshPermission()
        verify(exactly = 1) { sink.nativeFact(V2NativeFact.MicrophonePermission(false)) }
        verify(exactly = 0) { sink.nativeFact(V2NativeFact.MicrophoneSystemMute(true)) }
        observer.stop(); observer.stop()
        observer.refreshPermission()
        verify(exactly = 1) { context.unregisterReceiver(any()) }
    }
    @Test fun `broadcast refreshes actual state and old callback is inert after restart`() {
        val observer = observer()
        observer.start()
        every { manager.isMicrophoneMute } returns true
        callbacks.first().onReceive(context, Intent("unrelated"))
        verify(exactly = 0) { sink.nativeFact(V2NativeFact.MicrophoneSystemMute(true)) }
        callbacks.first().onReceive(context, Intent(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED))
        verify(exactly = 1) { sink.nativeFact(V2NativeFact.MicrophoneSystemMute(true)) }
        observer.stop(); observer.start()
        every { manager.isMicrophoneMute } returns false
        callbacks.first().onReceive(context, Intent(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED))
        verify(exactly = 1) { sink.nativeFact(V2NativeFact.MicrophoneSystemMute(false)) }
        callbacks.last().onReceive(context, Intent(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED))
        verify(exactly = 2) { sink.nativeFact(V2NativeFact.MicrophoneSystemMute(false)) }
        observer.stop()
    }
    @Test @Config(sdk = [23]) fun `old Android still uses permission and system mute snapshots`() {
        val observer = observer()
        observer.start()
        verify { sink.nativeFact(V2NativeFact.MicrophonePermission(true)) }
        verify { sink.nativeFact(V2NativeFact.MicrophoneSystemMute(false)) }
        observer.stop()
    }
    @Test fun `unavailable readings never become false claims`() {
        val observer = observer()
        every { context.checkSelfPermission(any()) } throws SecurityException()
        every { manager.isMicrophoneMute } throws IllegalStateException()
        observer.start()
        verify(exactly = 0) { sink.nativeFact(any()) }
        observer.stop()
    }
}
