/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.verto.receive.FcmRegistrationResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded

import retrofit2.http.POST

interface RetrofitAPIInterface {


    //Server key needs to be included as well.
    // Remember id and token are different. Confirm if we need to send the token or ID
    @POST("register")
    @FormUrlEncoded
    fun registrationPost(@Field("action") action: String,
                         @Field("type") type: String,
                         @Field("device_id") device_id: String): retrofit2.Call<FcmRegistrationResponse>}


object ApiUtils {
    val BASE_URL = "http://push-notification.query.consul:2023/v2/"
    val apiService: RetrofitAPIInterface
        get() = RetrofitAPIClient.getClient(BASE_URL)!!.create(RetrofitAPIInterface::class.java)
}