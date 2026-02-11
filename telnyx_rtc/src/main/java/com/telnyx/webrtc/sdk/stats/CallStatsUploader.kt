/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_INTERNAL_SERVER_ERROR = 500
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_PAYLOAD_SIZE_BYTES = 2 * 1024 * 1024 // 2MB
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
     * Uploads call statistics to the call report endpoint with retry logic.
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
        // Check payload size before upload
        val payloadSize = jsonContent.toByteArray().size
        if (payloadSize > MAX_PAYLOAD_SIZE_BYTES) {
            Timber.tag(TAG).w(
                "Call stats for call $callId exceed 2MB limit ($payloadSize bytes), skipping upload"
            )
            return@launch
        }

        var lastException: Exception? = null
        var attempt = 0

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                val success = executeUpload(callReportId, callId, voiceSdkId, jsonContent)
                if (success) {
                    return@launch
                }
                // Non-retryable HTTP error (4xx), don't retry
                break
            } catch (e: Exception) {
                lastException = e
                attempt++
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl (attempt - 1)) // Exponential backoff
                    Timber.tag(TAG).w(
                        "Upload attempt $attempt failed for call $callId, retrying in ${delayMs}ms"
                    )
                    delay(delayMs)
                }
            }
        }

        lastException?.let {
            Timber.tag(TAG).e(it, "Failed to upload call stats for call $callId after $MAX_RETRY_ATTEMPTS attempts")
        }
    }

    /**
     * Executes the HTTP upload request.
     *
     * @return true if upload was successful, false if a non-retryable error occurred
     * @throws Exception if a retryable error occurred (network issues)
     */
    private fun executeUpload(
        callReportId: String,
        callId: UUID,
        voiceSdkId: String,
        jsonContent: String
    ): Boolean {
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
                return true
            }

            val errorBody = response.body?.string()
            val errorMessage = when (response.code) {
                HTTP_BAD_REQUEST -> when {
                    errorBody?.contains("missing call_id") == true ->
                        "Missing call_id - no call_id in header or body"
                    errorBody?.contains("missing voice_sdk_id") == true ->
                        "Missing voice_sdk_id - no voice_sdk_id in header or body"
                    else -> "Bad request: $errorBody"
                }
                HTTP_UNAUTHORIZED -> when {
                    errorBody?.contains("missing call_report_id") == true ->
                        "Missing call_report_id - no call_report_id in header or body"
                    errorBody?.contains("invalid call_report_id") == true ->
                        "Invalid call_report_id - token failed to decode/validate"
                    else -> "Unauthorized: $errorBody"
                }
                HTTP_PAYLOAD_TOO_LARGE -> "Body too large - request body exceeds 2MB limit"
                else -> "HTTP error: $errorBody"
            }
            Timber.tag(TAG).w(
                "Failed to upload call stats for call $callId: HTTP ${response.code} - $errorMessage"
            )

            // 4xx errors are not retryable
            return response.code >= HTTP_INTERNAL_SERVER_ERROR
        }
    }

    /**
     * Cleans up resources and cancels any pending upload operations.
     */
    fun destroy() {
        job.cancel()
    }
}
