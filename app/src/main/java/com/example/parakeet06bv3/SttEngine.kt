package com.example.parakeet06bv3

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlin.math.abs

class SttEngine(private val context: Context) {
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentRecognitionJob: Job? = null

    var isReady = false
        private set

    private val sampleRate = 16000
    private val threshold = 0.1f
    private val chunkSize = (sampleRate * 0.14).toInt()
    private val ignoredWords = listOf("Yeah","hmm", "mhm", "um", "uh", "ah", "oh", "hmm.", "mhm.")

    // 모델 초기화
    suspend fun initModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "parakeet-tdt-0.6b-int8/encoder.int8.onnx",
                        decoder = "parakeet-tdt-0.6b-int8/decoder.int8.onnx",
                        joiner = "parakeet-tdt-0.6b-int8/joiner.int8.onnx"
                    ),
                    tokens = "parakeet-tdt-0.6b-int8/tokens.txt",
                    modelType = "nemo_transducer",
                    provider = "cpu",
                    numThreads = 2
                ),
                decodingMethod = "greedy_search"
            )
            recognizer = OfflineRecognizer(context.assets, config)
            isReady = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        scope: CoroutineScope,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit
    ) {
        if (!isReady || recognizer == null) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        )
        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(chunkSize)
            val recordedFrames = mutableListOf<FloatArray>()
            var consecutiveLoudFrames = 0
            var lastLoudTime = System.currentTimeMillis()
            var currentPartialEng = ""

            while (isActive) {
                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readResult <= 0) continue

                val floatArray = FloatArray(readResult) { buffer[it] / 32768.0f }
                val volume = floatArray.maxOfOrNull { abs(it) } ?: 0f
                val currentTime = System.currentTimeMillis()

                if (volume > threshold && volume < 0.90f) {
                    consecutiveLoudFrames++
                    lastLoudTime = currentTime
                    recordedFrames.add(floatArray)

                    if (consecutiveLoudFrames >= 2 && currentRecognitionJob?.isActive != true) {
                        val currentAudioChunk = recordedFrames.flatMap { it.asIterable() }.toFloatArray()
                        currentRecognitionJob = launch(Dispatchers.Default) {
                            val partialText = recognize(currentAudioChunk).trim()
                            if (isActive && partialText.isNotBlank() && partialText.lowercase() !in ignoredWords && partialText.length > 1) {
                                if (partialText != currentPartialEng) {
                                    currentPartialEng = partialText
                                    onPartialResult(partialText)
                                }
                            }
                        }
                    }
                } else {
                    consecutiveLoudFrames = 0
                    if (recordedFrames.isNotEmpty()) {
                        recordedFrames.add(floatArray)
                        val silencePassed = currentTime - lastLoudTime

                        if (silencePassed >= 220) {
                            val finalAudioChunk = recordedFrames.flatMap { it.asIterable() }.toFloatArray()
                            recordedFrames.clear()
                            lastLoudTime = System.currentTimeMillis()
                            currentRecognitionJob?.cancel()

                            currentRecognitionJob = launch(Dispatchers.Default) {
                                val finalText = recognize(finalAudioChunk).trim()
                                if (isActive && finalText.isNotBlank() && finalText.lowercase() !in ignoredWords && finalText.length > 1) {
                                    currentPartialEng = "" // 초기화
                                    onFinalResult(finalText)
                                }
                            }
                        }
                    } else {
                        lastLoudTime = currentTime
                    }
                }
            }
        }
    }

    private fun recognize(audio: FloatArray): String {
        val stream = recognizer!!.createStream()
        stream.acceptWaveform(audio, sampleRate)
        recognizer!!.decode(stream)
        val text = recognizer!!.getResult(stream).text
        stream.release()
        return text
    }

    fun stopListening() {
        recordingJob?.cancel()
        currentRecognitionJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun release() {
        stopListening()
        recognizer?.release()
        recognizer = null
        isReady = false
    }
}