package com.techentrance.languageapp.db

import androidx.room.*

@Dao
interface TranscriptDao {

    @Insert
    suspend fun insert(entry: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE roomId = :roomId ORDER BY timestampMs ASC")
    suspend fun getByRoom(roomId: String): List<TranscriptEntity>

    @Query("SELECT DISTINCT roomId, otherPersonName, MAX(timestampMs) as lastMs FROM transcripts GROUP BY roomId ORDER BY lastMs DESC")
    suspend fun getRoomSummaries(): List<RoomSummary>

    @Query("DELETE FROM transcripts WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: String)
}

data class RoomSummary(
    val roomId: String,
    val otherPersonName: String,
    val lastMs: Long,
)
