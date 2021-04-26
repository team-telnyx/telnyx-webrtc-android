package com.telnyx.webrtc.sdk.utilities.fcm

import android.content.Context
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

        val fcmDeviceId = token.split(":").first()

        apiService.registrationPost("register", "android", fcmDeviceId).enqueue(object :
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
            }
        })
    }

    fun unregisterDevice(context: Context, token: String) {
        val apiService: RetrofitAPIInterface?
        apiService = ApiUtils.apiService

        val fcmDeviceId = token.split(":").first()

        apiService.registrationPost("unregister", "android", fcmDeviceId).enqueue(object :
            Callback<FcmRegistrationResponse> {
            override fun onResponse(call: Call<FcmRegistrationResponse>, response: Response<FcmRegistrationResponse>) {
                if (response.isSuccessful) {

                    Timber.i("post unregister to API %s", response.body().toString())
                    Timber.i("post status to API %s", response.code().toString())
                    Timber.i( "post msg to API %s", response.body()!!.message)

                }
            }
            override fun onFailure(call: Call<FcmRegistrationResponse>, t: Throwable) {
                Timber.d("FCM unregister request to Telnyx Notification Service failed.")
            }
        })
    }
}