package com.techentrance.languageapp

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

class AudioStreamer(
    private val onChunkReady: (ByteArray) -> Unit
) {
    private val TAG = "AudioStreamer"

    // 16kHz mono PCM — matches Google STT config on backend
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // Collect ~1 second of audio before sending (tune for latency vs accuracy)
    private val CHUNK_DURATION_MS = 1000
    private val bufferSize = SAMPLE_RATE * (CHUNK_DURATION_MS / 1000) * 2 // 2 bytes per PCM16 sample

    private var audioRecord: AudioRecord? = null
    private var streamingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startStreaming() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            maxOf(minBuffer, bufferSize),
        )

        audioRecord?.startRecording()
        Log.d(TAG, "Recording started")

        streamingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    onChunkReady(buffer.copyOf(bytesRead))
                }
            }
        }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Recording stopped")
    }
}
