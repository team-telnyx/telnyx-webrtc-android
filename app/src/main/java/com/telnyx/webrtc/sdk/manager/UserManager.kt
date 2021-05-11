/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.manager

import android.annotation.SuppressLint
import android.content.SharedPreferences
import javax.inject.Inject

@SuppressLint("ApplySharedPref")
class UserManager @Inject constructor(private val sharedPreferences: SharedPreferences) {

    companion object{
        const val KEY_USER_LOGIN_STATUS = "logged-in"
        const val KEY_USER_SIP_USERNAME = "sip-username"
        const val KEY_USER_SIP_PASSWORD = "sip-password"
        const val KEY_USER_FCM_TOKEN = "fcm-token"
        const val KEY_USER_FCM_DEVICE_ID = "fcm-device-id"
        const val KEY_USER_CALLER_ID_NAME = "caller-id-name"
        const val KEY_USER_CALLER_ID_NUMBER = "caller-id-number"
        const val KEY_USER_SESSION_ID = "session_id"
    }

    var isUserLogin: Boolean
        get() = sharedPreferences.getBoolean(KEY_USER_LOGIN_STATUS, false)
        set(isUserLogin) {
            sharedPreferences.edit().putBoolean(KEY_USER_LOGIN_STATUS, isUserLogin).commit()
        }

    var sipUsername: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_SIP_USERNAME, "")
            return temp ?: ""
        }
        set(bool) = sharedPreferences.edit().putString(KEY_USER_SIP_USERNAME, bool).apply()

    var sipPass: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_SIP_PASSWORD, "")
            return temp ?: ""
        }
        set(bool) = sharedPreferences.edit().putString(KEY_USER_SIP_PASSWORD, bool).apply()

    var fcmToken: String?
        get() {
            val temp = sharedPreferences.getString(KEY_USER_FCM_TOKEN, "")
            return temp ?: ""
        }
        set(bool) = sharedPreferences.edit().putString(KEY_USER_FCM_TOKEN, bool).apply()

    var fcmDeviceId: String?
        get() {
            val temp = sharedPreferences.getString(KEY_USER_FCM_DEVICE_ID, "")
            return temp ?: ""
        }
        set(bool) = sharedPreferences.edit().putString(KEY_USER_FCM_DEVICE_ID, bool).apply()

    var calledIdName: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_CALLER_ID_NAME, "")
            return temp ?: ""
        }
        set(bool) = sharedPreferences.edit().putString(KEY_USER_CALLER_ID_NAME, bool).apply()


    var callerIdNumber: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_CALLER_ID_NUMBER, "")
            return temp ?: ""
        }
        set(bool) = sharedPreferences.edit().putString(KEY_USER_CALLER_ID_NUMBER, bool).apply()

    var sessionId: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_SESSION_ID, "")
            return temp ?: ""
        }
        set(bool) = sharedPreferences.edit().putString(KEY_USER_SESSION_ID, bool).apply()
}