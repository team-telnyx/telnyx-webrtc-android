package com.telnyx.webrtc.sdk.utility.telecom.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCall
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallAction
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallRepository
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent

/**
 * A simple BroadcastReceiver that routes the call notification actions to the TelecomCallRepository
 */
class TelecomCallBroadcast : BroadcastReceiver() {


    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TelecomCallBroadcastEntryPoint {
        fun telecomCallRepository(): TelecomCallRepository
    }


    override fun onReceive(context: Context, intent: Intent) {
        // Get the action or skip if none
        val action = intent.getTelecomCallAction() ?: return
        val entryPoint = EntryPoints
            .get(context.applicationContext, TelecomCallBroadcastEntryPoint::class.java)

        val repo = entryPoint.telecomCallRepository()
        val call = repo.currentCall.value

        if (call is TelecomCall.Registered) {
            // If the call is still registered perform action
            call.processAction(action)
        } else {
            // Otherwise probably something went wrong and the notification is wrong.
            TelecomCallNotificationManager(context).updateCallNotification(call)
        }
    }

    /**
     * Get the [TelecomCallAction] parcelable object from the intent bundlex.
     */
    private fun Intent.getTelecomCallAction() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(
                TelecomCallNotificationManager.TELECOM_NOTIFICATION_ACTION,
                TelecomCallAction::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(TelecomCallNotificationManager.TELECOM_NOTIFICATION_ACTION)
        }
}