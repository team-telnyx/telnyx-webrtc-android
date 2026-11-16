package com.telnyx.webrtc.sdk.observability

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build

/** Passive snapshots, not permission requests or recording/selected-input claims. */
internal class V2MicrophoneObserver(context: Context, private val sink: CallReportV2Ingress) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val lock = Any()
    private var generation = 0L
    private var receiver: BroadcastReceiver? = null
    private var lastPermission: Boolean? = null
    private var lastMute: Boolean? = null

    fun start() = synchronized(lock) {
        if (receiver != null) return@synchronized
        val epoch = ++generation
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = synchronized(lock) {
                if (epoch == generation && receiver != null &&
                    intent?.action == AudioManager.ACTION_MICROPHONE_MUTE_CHANGED) refreshPermission()
            }
        }
        receiver = listener
        try {
            val filter = IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(listener, filter)
            }
        } catch (_: RuntimeException) { /* Initial/foreground snapshots remain available. */ }
        refreshPermission()
    }

    fun refreshPermission() = synchronized(lock) {
        if (receiver == null) return@synchronized
        try {
            val granted = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted != lastPermission) {
                lastPermission = granted
                sink.nativeFact(V2NativeFact.MicrophonePermission(granted))
            }
        } catch (_: RuntimeException) { /* Unknown is not denied; reporting cannot affect calls. */ }
        try {
            val muted = manager?.isMicrophoneMute ?: return@synchronized
            if (muted != lastMute) {
                lastMute = muted
                sink.nativeFact(V2NativeFact.MicrophoneSystemMute(muted))
            }
        } catch (_: RuntimeException) { /* Unknown is not unmuted. */ }
    }

    fun stop() = synchronized(lock) {
        ++generation
        val listener = receiver
        receiver = null
        if (listener != null) try { app.unregisterReceiver(listener) }
        catch (_: RuntimeException) { /* Invalidate queued callbacks even if binder detach fails. */ }
        lastPermission = null
        lastMute = null
    }
}
