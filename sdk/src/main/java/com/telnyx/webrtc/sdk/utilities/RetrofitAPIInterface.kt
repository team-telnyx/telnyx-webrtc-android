package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.verto.receive.FcmRegistrationResponse
import com.telnyx.webrtc.sdk.verto.receive.TelnyxNotificationServiceResponse
import org.json.JSONObject
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded

import retrofit2.http.POST

interface RetrofitAPIInterface {

    @POST("push_notifications/registration")
    @FormUrlEncoded
    fun registrationPost(@Field("action") action: String,
                         @Field("type") type: String,
                         @Field("device_id") device_id: String): retrofit2.Call<FcmRegistrationResponse>


    @POST("credentials")
    @FormUrlEncoded
    fun createCredentials(@Field("type") type: String,
                          @Field("data") data: JSONObject
    ): retrofit2.Call<TelnyxNotificationServiceResponse>

}

object ApiUtils {
    val BASE_URL = "https://push-notification.query.consul:2023/v2"
    val apiService: RetrofitAPIInterface
        get() = RetrofitAPIClient.getClient(BASE_URL)!!.create(RetrofitAPIInterface::class.java)
}