package com.telnyx.webrtc.common.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallHistoryDao {
    
    @Query("SELECT * FROM calls_history WHERE userProfileName = :profileName ORDER BY date DESC LIMIT 20")
    fun getCallHistoryForProfile(profileName: String): Flow<List<CallHistoryEntity>>
    
    @Insert
    suspend fun insertCall(call: CallHistoryEntity)
    
    @Query("DELETE FROM calls_history WHERE userProfileName = :profileName AND id NOT IN (SELECT id FROM calls_history WHERE userProfileName = :profileName ORDER BY date DESC LIMIT 20)")
    suspend fun deleteOldCallsForProfile(profileName: String)
    
    @Query("SELECT COUNT(*) FROM calls_history WHERE userProfileName = :profileName")
    suspend fun getCallCountForProfile(profileName: String): Int

    @Query("DELETE FROM calls_history WHERE userProfileName = :profileName")
    suspend fun deleteCallHistoryForProfile(profileName: String)

}
