package com.techentrance.languageapp.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val roomId: String,
    val otherPersonName: String,
    val originalText: String,
    val translatedText: String,
    val speakerSlot: String,      // "me" or "them"
    val timestampMs: Long = System.currentTimeMillis(),
)
