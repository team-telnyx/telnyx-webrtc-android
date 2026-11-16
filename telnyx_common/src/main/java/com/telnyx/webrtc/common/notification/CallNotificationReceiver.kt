/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import com.google.gson.Gson
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber
import android.content.Intent


/**
 * BroadcastReceiver for handling call notification actions - answer, reject, and cancel
 *
 * Note: handleAnswerCall and handleRejectCall are not actually used as in our implementation we do not show a notification for incoming calls while connected
 * The code is provided for reference in case you want to implement a similar feature
 *
 * The handleCancelCall method is used to end the call when the app is minimized and the notification 'End Call' button is clicked
 */
class CallNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        intent?.let {
            val notificationStateValue = it.getIntExtra(
                CallNotificationService.NOTIFICATION_ACTION,
                CallNotificationService.Companion.NotificationState.CANCEL.ordinal
            )

            val txPushMetadataJson = it.getStringExtra(MyFirebaseMessagingService.TX_PUSH_METADATA)
            val txPushMetadata = if (txPushMetadataJson != null) {
                Gson().fromJson(txPushMetadataJson, PushMetaData::class.java)
            } else {
                null
            }

            val observedCallId = txPushMetadata?.callId?.let { value -> runCatching { java.util.UUID.fromString(value) }.getOrNull() }
            val observedAction = actionFor(notificationStateValue, observedCallId)
            if (observedAction != null && observedCallId != null) TelnyxCommon.getInstance().recordPushTelemetry(
                com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent(
                    com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.NOTIFICATION_ACTION,
                    observedCallId, action = observedAction))

            when (notificationStateValue) {
                CallNotificationService.Companion.NotificationState.ANSWER.ordinal -> {
                    Timber.d("Call answered from notification")
                    handleAnswerCall(context, txPushMetadata)
                }
                CallNotificationService.Companion.NotificationState.REJECT.ordinal -> {
                    Timber.d("Call rejected from notification")
                    handleRejectCall(context, txPushMetadata)
                }
                else -> {
                    Timber.d("Call cancelled from notification")
                    handleCancelCall()
                }
            }

            // Cancel the notification
            CallNotificationService.cancelNotification(context)
        }
    }

    private fun handleAnswerCall(context: Context, txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)

            // Create an intent for the main activity to handle the call
            val targetActivityClass = getTargetActivityClass(context)
            targetActivityClass?.let { activityClass ->
                val intent = Intent(context, activityClass).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    action = Intent.ACTION_VIEW
                    putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_ANSWER_CALL)
                    putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pendingIntent.send()
            }
        }
    }

    private fun handleRejectCall(context: Context, txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Use background service to decline the call without launching the app
            BackgroundCallDeclineService.startService(context, txPushMetadata)
            
            Timber.d("Started background call decline service for call rejection")
        }
    }

    private fun handleCancelCall() {
        // Handle call cancellation
        val currentCall = TelnyxCommon.getInstance().currentCall
        currentCall?.endCall(currentCall.callId)
    }

    private fun getTargetActivityClass(context: Context): Class<*>? {
        return try {
            val serviceInfo = context.packageManager.getServiceInfo(
                ComponentName(context, LegacyCallNotificationService::class.java),
                android.content.pm.PackageManager.GET_META_DATA
            )
            val activityClassName = serviceInfo.metaData?.getString("activity_class_name") ?: return null
            Class.forName(activityClassName)
        } catch (e: ClassNotFoundException) {
            Timber.e(e, "Failed to get target activity class")
            null
        }
    }

    companion object {
        /**
         * Maps a notification state to the observed user action for telemetry.
         *
         * The ongoing-call notification's End intent carries the call metadata,
         * so a user End action with a call id is observable; without it there is
         * no call identity to record.
         */
        internal fun actionFor(notificationStateValue: Int,
            callId: java.util.UUID?): com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Action? =
            when (notificationStateValue) {
                CallNotificationService.Companion.NotificationState.ANSWER.ordinal ->
                    com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Action.ANSWER
                CallNotificationService.Companion.NotificationState.REJECT.ordinal ->
                    com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Action.REJECT
                CallNotificationService.Companion.NotificationState.CANCEL.ordinal ->
                    com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Action.END.takeIf { callId != null }
                else -> null
            }
    }
}
