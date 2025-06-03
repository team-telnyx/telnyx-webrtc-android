package com.telnyx.webrtc.common

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.telnyx.webrtc.common.model.Profile

/**
 * Object responsible for managing user profiles.
 */
object ProfileManager {

    private const val LIST_OF_PROFILES = "list_of_profiles"

    /**
     * Retrieves the list of profiles from shared preferences.
     *
     * @param context The application context.
     * @return List of profiles.
     */
    fun getProfilesList(context: Context): List<Profile> {
        val sharedPreferences = TelnyxCommon.getInstance().getSharedPreferences(context)

        val gson = Gson()
        return sharedPreferences.getString(LIST_OF_PROFILES, null)?.let { json ->
            val type = object : TypeToken<List<Profile>>() {}.type
            gson.fromJson(json, type)
        } ?: run {
            emptyList<Profile>()
        }
    }


    /**
     * Saves a profile to shared preferences.
     *
     * @param context The application context.
     * @param profile The profile to save.
     */
    fun saveProfile(context: Context, profile: Profile) {
        val sharedPreferences = TelnyxCommon.getInstance().getSharedPreferences(context)

        val listOfProfiles = getProfilesList(context).toMutableList()

        listOfProfiles.firstOrNull { it.sipUsername?.isEmpty() == false && it.sipUsername == profile.sipUsername }?.let { existingProfile ->
            listOfProfiles.remove(existingProfile)
        }

        listOfProfiles.firstOrNull { it.sipToken?.isEmpty() == false && it.sipToken == profile.sipToken }?.let { existingProfile ->
            listOfProfiles.remove(existingProfile)
        }

        if (profile.isUserLoggedIn)
            listOfProfiles.forEach { it.isUserLoggedIn = false }

        listOfProfiles.add(profile)

        val gson = Gson()

        val json = gson.toJson(listOfProfiles)
        sharedPreferences.edit().putString(LIST_OF_PROFILES, json).apply()
    }

    /**
     * Retrieves the logged-in profile.
     *
     * @param context The application context.
     * @return The logged-in profile, or null if no profile is logged in.
     */
    fun getLoggedProfile(context: Context): Profile? {
        return getProfilesList(context).firstOrNull { it.isUserLoggedIn }
    }

    /**
     * Deletes a profile by SIP username.
     *
     * @param context The application context.
     * @param sipUsername The SIP username of the profile to delete.
     * @return True if the profile was deleted, false otherwise.
     */
    fun deleteProfileBySipUsername(context: Context, sipUsername: String): Boolean {
        val sharedPreferences = TelnyxCommon.getInstance().getSharedPreferences(context)

        val listOfProfiles = getProfilesList(context).toMutableList()

        return listOfProfiles.firstOrNull { it.sipUsername == sipUsername }?.let { existingProfile ->
            listOfProfiles.remove(existingProfile)
            val gson = Gson()

            val json = gson.toJson(listOfProfiles)
            sharedPreferences.edit().putString(LIST_OF_PROFILES, json).apply()
            true
        } ?: false
    }

    /**
     * Deletes a profile by SIP token.
     *
     * @param context The application context.
     * @param sipToken The SIP token of the profile to delete.
     * @return True if the profile was deleted, false otherwise.
     */
    fun deleteProfileBySipToken(context: Context, sipToken: String): Boolean {
        val sharedPreferences = TelnyxCommon.getInstance().getSharedPreferences(context)

        val listOfProfiles = getProfilesList(context).toMutableList()

        return listOfProfiles.firstOrNull { it.sipToken == sipToken }?.let { existingProfile ->
            listOfProfiles.remove(existingProfile)
            val gson = Gson()

            val json = gson.toJson(listOfProfiles)
            sharedPreferences.edit().putString(LIST_OF_PROFILES, json).apply()
            true
        } ?: false
    }
}
