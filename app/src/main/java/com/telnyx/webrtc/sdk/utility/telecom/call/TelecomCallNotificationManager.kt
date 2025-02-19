package com.telnyx.webrtc.sdk.utility.telecom.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.PermissionChecker
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCall
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallAction

/**
 * Handles call status changes and updates the notification accordingly. For more guidance around
 * notifications check https://developer.android.com/develop/ui/views/notifications
 *
 * @see updateCallNotification
 */
class TelecomCallNotificationManager(private val context: Context) {

    internal companion object {
        const val TELECOM_NOTIFICATION_ID = 200
        const val TELECOM_NOTIFICATION_ACTION = "telecom_action"
        const val TELECOM_NOTIFICATION_INCOMING_CHANNEL_ID = "telecom_incoming_channel"
        const val TELECOM_NOTIFICATION_ONGOING_CHANNEL_ID = "telecom_ongoing_channel"

        private val ringToneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    /**
     * Updates, creates or dismisses a CallStyle notification based on the given [TelecomCall]
     */
    fun updateCallNotification(call: TelecomCall) {
        // If notifications are not granted, skip it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            return
        }

        // Ensure that the channel is created
        createNotificationChannels()

        // Update or dismiss notification
        when (call) {
            TelecomCall.None, is TelecomCall.Unregistered -> {
                notificationManager.cancel(TELECOM_NOTIFICATION_ID)
            }

            is TelecomCall.Registered -> {
                val notification = createNotification(call)
                notificationManager.notify(TELECOM_NOTIFICATION_ID, notification)
            }

            TelecomCall.Idle -> {
                print("Idle state")
            }
        }
    }

    private fun createNotification(call: TelecomCall.Registered): Notification {
        // To display the caller information
        val caller = Person.Builder()
            .setName(call.callAttributes.displayName)
            .setUri(call.callAttributes.address.toString())
            .setImportant(true)
            .build()

        // Defines the full screen notification activity or the activity to launch once the user taps
        // on the notification
        val contentIntent = PendingIntent.getActivity(
            /* context = */ context,
            /* requestCode = */ 0,
            /* intent = */ Intent(context, TelecomCallActivity::class.java),
            /* flags = */ PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Define the call style based on the call state and set the right actions
        val isIncoming = call.isIncoming() && !call.isActive
        val callStyle = if (isIncoming) {
            NotificationCompat.CallStyle.forIncomingCall(
                caller,
                getPendingIntent(
                    TelecomCallAction.Disconnect(
                        DisconnectCause(DisconnectCause.REJECTED),
                    ),
                ),
                getPendingIntent(TelecomCallAction.Answer),
            )
        } else {
            NotificationCompat.CallStyle.forOngoingCall(
                caller,
                getPendingIntent(
                    TelecomCallAction.Disconnect(
                        DisconnectCause(DisconnectCause.LOCAL),
                    ),
                ),
            )
        }
        val channelId = if (isIncoming) {
            TELECOM_NOTIFICATION_INCOMING_CHANNEL_ID
        } else {
            TELECOM_NOTIFICATION_ONGOING_CHANNEL_ID
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .setSmallIcon(R.drawable.ic_round_call_24)
            .setOngoing(true)
            .setStyle(callStyle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (call.isOnHold) {
            builder.addAction(
                R.drawable.ic_phone_paused_24, "Resume",
                getPendingIntent(
                    TelecomCallAction.Activate,
                ),
            )
        }
        return builder.build()
    }

    /**
     * Creates a PendingIntent for the given [TelecomCallAction]. Since the actions are parcelable
     * we can directly pass them as extra parameters in the bundle.
     */
    private fun getPendingIntent(action: TelecomCallAction): PendingIntent {
        val callIntent = Intent(context, TelecomCallBroadcast::class.java)
        callIntent.putExtra(
            TELECOM_NOTIFICATION_ACTION,
            action,
        )

        return PendingIntent.getBroadcast(
            context,
            callIntent.hashCode(),
            callIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
        val incomingChannel = NotificationChannelCompat.Builder(
            TELECOM_NOTIFICATION_INCOMING_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        ).setName("Incoming calls")
            .setDescription("Handles the notifications when receiving a call")
            .setVibrationEnabled(true).setSound(
                ringToneUri,
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_RING)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
            ).build()

        val ongoingChannel = NotificationChannelCompat.Builder(
            TELECOM_NOTIFICATION_ONGOING_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH
        )
            .setName("Ongoing calls")
            .setDescription("Displays the ongoing call notifications")
            .build()

        notificationManager.createNotificationChannelsCompat(
            listOf(incomingChannel, ongoingChannel)
        )
    }
}