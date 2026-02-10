/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Service class responsible for uploading call statistics to the Telnyx call report endpoint.
 * This class handles HTTP POST requests with the required headers and JSON body.
 *
 * @param host The host address from TxServerConfiguration (e.g., "rtc.telnyx.com" or "rtcdev.telnyx.com")
 */
internal class CallStatsUploader(private val host: String) : CoroutineScope {

    companion object {
        private const val TAG = "CallStatsUploader"
        private const val CALL_REPORT_PATH = "/call_report"
        private const val HEADER_CALL_REPORT_ID = "x-call-report-id"
        private const val HEADER_CALL_ID = "x-call-id"
        private const val HEADER_VOICE_SDK_ID = "x-voice-sdk-id"
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 30L
    }

    private val callReportEndpoint: String = "https://$host$CALL_REPORT_PATH"

    private var job: Job = SupervisorJob()
    override val coroutineContext = Dispatchers.IO + job

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads call statistics to the call report endpoint.
     *
     * @param callReportId The call report ID token received from the REGED response
     * @param callId The UUID of the call
     * @param voiceSdkId The WebSocket session ID
     * @param jsonContent The JSON content of the call statistics
     */
    fun uploadCallStats(
        callReportId: String,
        callId: UUID,
        voiceSdkId: String,
        jsonContent: String
    ) = launch {
        try {
            val requestBody = jsonContent.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(callReportEndpoint)
                .addHeader(HEADER_CALL_REPORT_ID, callReportId)
                .addHeader(HEADER_CALL_ID, callId.toString())
                .addHeader(HEADER_VOICE_SDK_ID, voiceSdkId)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Timber.tag(TAG).d("Call stats uploaded successfully for call: $callId")
                } else {
                    Timber.tag(TAG).w(
                        "Failed to upload call stats for call $callId: HTTP ${response.code}"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error uploading call stats for call: $callId")
        }
    }

    /**
     * Cleans up resources and cancels any pending upload operations.
     */
    fun destroy() {
        job.cancel()
    }
}
