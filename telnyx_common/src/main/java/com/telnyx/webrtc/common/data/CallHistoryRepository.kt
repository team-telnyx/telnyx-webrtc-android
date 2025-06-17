package com.telnyx.webrtc.common.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

private const val MAX_HISTORY_CALLS_LIMIT = 20

class CallHistoryRepository(context: Context) {

    private val callHistoryDao = CallHistoryDatabase.getDatabase(context).callHistoryDao()
    
    fun getCallHistoryForProfile(profileName: String): Flow<List<CallHistoryEntity>> {
        return callHistoryDao.getCallHistoryForProfile(profileName)
    }
    
    suspend fun addCall(profileName: String, callType: String, destinationNumber: String) {
        val call = CallHistoryEntity(
            userProfileName = profileName,
            callType = callType,
            destinationNumber = destinationNumber,
            date = System.currentTimeMillis()
        )
        
        callHistoryDao.insertCall(call)
        
        // Check if we need to delete old calls (keep only 20 per profile)
        val callCount = callHistoryDao.getCallCountForProfile(profileName)
        if (callCount > MAX_HISTORY_CALLS_LIMIT) {
            callHistoryDao.deleteOldCallsForProfile(profileName)
        }
    }

    suspend fun deleteCallHistoryForProfile(profileName: String) {
        callHistoryDao.deleteCallHistoryForProfile(profileName)
    }
}
