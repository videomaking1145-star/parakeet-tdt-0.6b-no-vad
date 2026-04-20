package com.example.parakeet06bv3

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.parakeet06bv3.databinding.ActivityMainBinding
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private lateinit var permissionManager: PermissionManager

    private val TAG = "MLKIT_DEBUG"

    // --- 통신 설정 (SSE) ---
    // 타임아웃을 0으로 설정하여 스트리밍 연결이 끊기지 않도록 유지
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // 모든 통신 내용을 Logcat에 찍음
        })
        .build()

    // Ngrok URL (수시로 바뀔 수 있으니 상단에 배치)
    private val BACKEND_URL = "https://amends-divinely-cauterize.ngrok-free.dev/t_stream"

    // STT (Parakeet-TDT) 관련 변수
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentRecognitionJob: Job? = null

    private val sampleRate = 16000
    private val threshold = 0.1f
    private val chunkSize = (sampleRate * 0.14).toInt()

    // ML Kit 번역 관련 변수
    private var translator: Translator? = null
    private var isTranslatorReady = false
    private var isSttReady = false

    // RecyclerView 및 상태 관리
    private val translationList = mutableListOf<TranslationItem>()
    private lateinit var adapter: TranslationAdapter
    private var currentPartialEng = ""
    private val textMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        permissionManager = PermissionManager(this)

        setupRecyclerView()
        setupButtons()

        binding.progressBar.visibility = View.VISIBLE
        updateStatusUI()

        checkAndInit()
    }

    private fun setupRecyclerView() {
        adapter = TranslationAdapter(translationList)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            if (permissionManager.hasAudioPermission()) {
                startListening()
            } else {
                permissionManager.requestAudioPermission()
            }
        }

        binding.btnPause.setOnClickListener {
            stopListening()
            binding.tvStatus.text = "일시 정지됨"
            binding.btnStart.text = "다시 시작"
        }

        binding.btnStop.setOnClickListener {
            stopListening()
            binding.tvStatus.text = "정지됨 (대기 중)"
            binding.btnStart.text = "시작"
            currentPartialEng = ""
            adapter.clearItems()
        }

        binding.move.setOnClickListener {
            finish()
        }
    }

    private fun updateStatusUI(errorMsg: String? = null) {
        runOnUiThread {
            if (errorMsg != null) {
                binding.tvStatus.text = "❌ 에러 발생: $errorMsg"
                return@runOnUiThread
            }
            val sttStatus = if (isSttReady) "✅ 완료" else "⏳ 대기/로드 중..."
            val transStatus = if (isTranslatorReady) "✅ 완료" else "⏳ 다운로드/대기 중..."
            binding.tvStatus.text = "시스템 초기화 중...\nSTT: $sttStatus\nML Kit: $transStatus"
        }
    }

    private fun checkAndInit() {
        Log.d(TAG, "초기화 시퀀스 시작")
        initMlKitTranslator()

        if (permissionManager.hasAudioPermission()) {
            initSherpaOnnx()
        } else {
            permissionManager.requestAudioPermission()
        }
    }

    private fun initMlKitTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()

        translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        translator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                isTranslatorReady = true
                checkDependenciesReady()
                updateStatusUI()
            }
            ?.addOnFailureListener { e -> updateStatusUI("ML Kit 에러: ${e.message}") }
    }

    private fun initSherpaOnnx() {
        lifecycleScope.launch(Dispatchers.IO) {
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
                        numThreads = 1
                    ),
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(assets, config)
                withContext(Dispatchers.Main) {
                    isSttReady = true
                    checkDependenciesReady()
                    updateStatusUI()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { updateStatusUI("STT 로드 에러: ${e.message}") }
            }
        }
    }

    private fun checkDependenciesReady() {
        if (isSttReady && isTranslatorReady) {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "🎙️ 모델 로드 완료! [시작] 버튼을 눌러주세요."
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (recognizer == null || translator == null) return

        binding.btnStart.text = "듣는 중..."
        binding.tvStatus.text = "마이크 작동 중 (음성 입력 대기)"

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        )
        audioRecord?.startRecording()

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(chunkSize)
            val recordedFrames = mutableListOf<FloatArray>()
            var consecutiveLoudFrames = 0
            var lastLoudTime = System.currentTimeMillis()
            val ignoredWords = listOf("hmm", "mhm", "um", "uh", "ah", "oh", "hmm.", "mhm.")

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
                            val partialText = recognizeAudio(currentAudioChunk, "PARTIAL")
                            if (!isActive) return@launch
                            val cleanPartial = partialText.trim()

                            if (cleanPartial.isNotBlank() && cleanPartial.lowercase() !in ignoredWords && cleanPartial.length > 1) {
                                if (cleanPartial != currentPartialEng) {
                                    currentPartialEng = cleanPartial
                                    translator?.translate(cleanPartial)?.addOnSuccessListener { translatedText ->
                                        runOnUiThread {
                                            adapter.updatePartial(cleanPartial, translatedText)
                                            binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    consecutiveLoudFrames = 0
                    if (recordedFrames.isNotEmpty()) {
                        recordedFrames.add(floatArray)
                        val silencePassed = currentTime - lastLoudTime

                        // VAD 종료 조건 (문장 끝 감지)
                        if (silencePassed >= 220) {
                            val finalAudioChunk = recordedFrames.flatMap { it.asIterable() }.toFloatArray()
                            recordedFrames.clear()
                            lastLoudTime = System.currentTimeMillis()
                            currentRecognitionJob?.cancel()

                            currentRecognitionJob = launch(Dispatchers.Default) {
                                val finalText = recognizeAudio(finalAudioChunk, "FINAL", silencePassed)
                                if (!isActive) return@launch
                                val cleanFinal = finalText.trim()

                                if (cleanFinal.isNotBlank() && cleanFinal.lowercase() !in ignoredWords && cleanFinal.length > 1) {
                                    Log.d(TAG, "[FINAL 감지] 백엔드 요청 준비: $cleanFinal")

                                    // 💡 핵심 수정: 인덱스 확보와 서버 통신을 한 세트로 묶어 UI 스레드 안으로 이동
                                    runOnUiThread {
                                        // 1. 리스트에 아이템 추가 (이 시점에 itemCount가 늘어남)
                                        adapter.finalizeLastItem(cleanFinal, "번역 중...")

                                        // 2. 정확한 인덱스 계산 (절대 -1이 나올 수 없음)
                                        val itemIndex = adapter.itemCount - 1

                                        binding.recyclerView.scrollToPosition(itemIndex)
                                        currentPartialEng = ""

                                        Log.d(TAG, "📡 백엔드 스트리밍 시작 (Index: $itemIndex)")

                                        // 3. 인덱스가 확정된 상태에서 서버 요청 발사
                                        streamTranslation(cleanFinal, itemIndex)
                                    }
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
    // --- 핵심: SSE 스트리밍 통신 로직 ---
    private fun streamTranslation(query: String, itemIndex: Int) {
        Log.d(TAG, "📡 백엔드 POST 요청 발사: $query (Index: $itemIndex)")

        val json = JSONObject().apply { put("q", query) }.toString()
        val requestBody = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(BACKEND_URL)
            .addHeader("ngrok-skip-browser-warning", "69420") // 게이트웨이 통과
            .addHeader("Accept", "text/event-stream")
            .post(requestBody) // 👈 피터가 말한대로 확실하게 POST 방식!
            .build()
        val eventSourceListener = object : EventSourceListener() {
            var streamResult = ""

            // 1. 단어가 떨어질 때마다 호출 (0.1초 간격)
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val token = JSONObject(data).getString("t")
                    streamResult += token

                    runOnUiThread {
                        // 리스트 직접 조작 X, 어댑터의 스트리밍 전용 함수 호출 O
                        adapter.updateStreamingText(itemIndex, streamResult)
                        binding.recyclerView.scrollToPosition(itemIndex)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SSE 파싱 에러", e)
                }
            }

            // 2. 백엔드에서 번역을 완전히 마치고 연결을 끊었을 때 호출
            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "SSE 스트리밍 완료")
                runOnUiThread {
                    adapter.finishStreaming(itemIndex)
                }
            }

            // 3. 네트워크 등 에러 발생 시
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                Log.e(TAG, "SSE 통신 실패", t)
                runOnUiThread {
                    if (streamResult.isEmpty()) {
                        adapter.updateStreamingText(itemIndex, "번역 실패 (네트워크 오류)")
                    }
                    adapter.finishStreaming(itemIndex)
                }
            }
        }

        // SSE 이벤트 구독 시작
        EventSources.createFactory(client).newEventSource(request, eventSourceListener)
    }

    private fun recognizeAudio(audio: FloatArray, type: String, silenceDuration: Long = 0L): String {
        val stream = recognizer!!.createStream()
        stream.acceptWaveform(audio, sampleRate)
        recognizer!!.decode(stream)
        val resultText = recognizer!!.getResult(stream).text
        stream.release()
        return resultText
    }

    private fun stopListening() {
        recordingJob?.cancel()
        currentRecognitionJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        recognizer?.release()
        translator?.close()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionManager.REQUEST_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initSherpaOnnx()
        }
    }
}