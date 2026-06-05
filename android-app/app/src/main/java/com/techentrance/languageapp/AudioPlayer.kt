package com.techentrance.languageapp

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class AudioPlayer {
    private val TAG = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null

    fun playMp3(audioBytes: ByteArray, cacheDir: File) {
        try {
            // Write bytes to a temp file — MediaPlayer requires a file path
            val tempFile = File(cacheDir, "translated_audio_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    tempFile.delete()
                    it.release()
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}")
        }
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
