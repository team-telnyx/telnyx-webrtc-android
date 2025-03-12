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
        const val KEY_USER_CALLER_ID_NAME = "caller-id-name"
        const val KEY_USER_CALLER_ID_NUMBER = "caller-id-number"
        const val KEY_USER_SESSION_ID = "session_id"
        const val KEY_USER_ENVIRONMENT = "environment"
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
        set(username) = sharedPreferences.edit().putString(KEY_USER_SIP_USERNAME, username).apply()

    var sipPass: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_SIP_PASSWORD, "")
            return temp ?: ""
        }
        set(password) = sharedPreferences.edit().putString(KEY_USER_SIP_PASSWORD, password).apply()

    var fcmToken: String?
        get() {
            val temp = sharedPreferences.getString(KEY_USER_FCM_TOKEN, "")
            return temp ?: ""
        }
        set(fcmToken) = sharedPreferences.edit().putString(KEY_USER_FCM_TOKEN, fcmToken).apply()

    var callerIdName: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_CALLER_ID_NAME, "")
            return temp ?: ""
        }
        set(callerId) = sharedPreferences.edit().putString(KEY_USER_CALLER_ID_NAME, callerId).apply()


    var callerIdNumber: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_CALLER_ID_NUMBER, "")
            return temp ?: ""
        }
        set(callerNumber) = sharedPreferences.edit().putString(KEY_USER_CALLER_ID_NUMBER, callerNumber).apply()

    var sessid: String
        get() {
            val temp = sharedPreferences.getString(KEY_USER_SESSION_ID, "")
            return temp ?: ""
        }
        set(sessId) = sharedPreferences.edit().putString(KEY_USER_SESSION_ID, sessId).apply()

    var isDev: Boolean
        get() = sharedPreferences.getBoolean(KEY_USER_ENVIRONMENT, false)
        set(isDev) {
            sharedPreferences.edit().putBoolean(KEY_USER_ENVIRONMENT, isDev).commit()
        }
}