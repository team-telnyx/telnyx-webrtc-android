package com.telnyx.webrtc.common

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.telnyx.webrtc.common.model.Profile

object ProfileManager {

    private const val LIST_OF_PROFILES = "list_of_profiles"

    fun getProfilesList(context: Context): List<Profile> {
        val sharedPreferences = TelnyxCommon.getSharedPreferences(context)
        val gson = Gson()
        return sharedPreferences.getString(LIST_OF_PROFILES, null)?.let { json ->
            val type = object : TypeToken<List<Profile>>() {}.type
            gson.fromJson(json, type)
        } ?: run {
            emptyList<Profile>()
        }
    }

    fun saveProfile(context: Context, profile: Profile) {
        val sharedPreferences = TelnyxCommon.getSharedPreferences(context)

        val listOfProfiles = getProfilesList(context).toMutableList()

        listOfProfiles.firstOrNull { it.sipUsername == profile.sipUsername }?.let { existingProfile ->
            listOfProfiles.remove(existingProfile)
        }

        listOfProfiles.add(profile)

        val gson = Gson()

        val json = gson.toJson(listOfProfiles)
        sharedPreferences.edit().putString(LIST_OF_PROFILES, json).apply()
    }

    fun getLoggedProfile(context: Context): Profile? {
        return getProfilesList(context).firstOrNull { it.isUserLogin }
    }

    fun deleteProfileBySipUsername(context: Context, sipUsername: String): Boolean {
        val sharedPreferences = TelnyxCommon.getSharedPreferences(context)

        val listOfProfiles = getProfilesList(context).toMutableList()

        return listOfProfiles.firstOrNull { it.sipUsername == sipUsername }?.let { existingProfile ->
            listOfProfiles.remove(existingProfile)
            val gson = Gson()

            val json = gson.toJson(listOfProfiles)
            sharedPreferences.edit().putString(LIST_OF_PROFILES, json).apply()
            true
        } ?: false
    }
}