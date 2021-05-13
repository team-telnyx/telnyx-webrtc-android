/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities.fcm

import android.content.Context
import com.bugsnag.android.Bugsnag
import com.google.firebase.messaging.RemoteMessage
import com.telnyx.webrtc.sdk.utilities.ApiUtils
import com.telnyx.webrtc.sdk.utilities.RetrofitAPIInterface
import com.telnyx.webrtc.sdk.verto.receive.FcmRegistrationResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

object TelnyxFcm {
    fun processPushMessage(context: Context, remoteMessage: RemoteMessage) {
        Timber.d("Message received from FCM to be processed by Telnyx: $remoteMessage")
    }

    fun sendRegistrationToServer(context: Context, token: String) {
        val apiService: RetrofitAPIInterface?
        apiService = ApiUtils.apiService

        apiService.registrationPost("register", "android", token).enqueue(object :
            Callback<FcmRegistrationResponse> {

            override fun onResponse(call: Call<FcmRegistrationResponse>, response: Response<FcmRegistrationResponse>) {
                if (response.isSuccessful) {

                    Timber.i("post registration to API %s", response.body().toString())
                    Timber.i("post status to API %s", response.code().toString())
                    Timber.i( "post msg to API %s", response.body()!!.message)

                }
            }
            override fun onFailure(call: Call<FcmRegistrationResponse>, t: Throwable) {
                    Timber.d("FCM Registration to Telnyx Notification Service failed.")
                    Bugsnag.notify(t)
            }
        })

    }
}