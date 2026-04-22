package com.example.parakeet06bv3

import LocalTranslator
import SseStreamClient
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
    private lateinit var adapter: TranslationAdapter

    // 캡슐화된 모듈들 선언
    private lateinit var sttEngine: SttEngine
    private lateinit var localTranslator: LocalTranslator
    private lateinit var sseClient: SseStreamClient

    private val BACKEND_URL = "https://amends-divinely-cauterize.ngrok-free.dev/t_stream"

    private val httpClient = OkHttpClient.Builder().build() // 로깅 인터셉터 등 추가 가능

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)


        sttEngine = SttEngine(this)
        localTranslator = LocalTranslator()
        sseClient = SseStreamClient(httpClient, BACKEND_URL)

        setupRecyclerView()
        setupButtons()

        binding.progressBar.visibility = View.VISIBLE
        checkAndInit()
    }

    private fun setupRecyclerView() {
        adapter = TranslationAdapter(mutableListOf())
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            if (permissionManager.hasAllPermissions()) startListening()
            else permissionManager.requestPermissions()
        }
        binding.btnStop.setOnClickListener {
            sttEngine.stopListening()
            updateUIState("정지됨 (대기 중)", "시작")
            adapter.clearItems()
        }
        binding.btnPause.setOnClickListener {
            sttEngine.stopListening()
            updateUIState("일시 정지됨", "다시 시작")
        }
    }

    private fun checkAndInit() {
        // 1. ML Kit 초기화
        localTranslator.init(
            onSuccess = { checkDependenciesReady() },
            onFailure = { e -> binding.tvStatus.text = "ML Kit 에러: ${e.message}" }
        )

        // 2. STT 초기화
        if (permissionManager.hasAllPermissions()) {
            lifecycleScope.launch {
                val success = sttEngine.initModel()
                if (success) checkDependenciesReady()
                else binding.tvStatus.text = "STT 로드 에러"
            }
        } else {
            permissionManager.requestPermissions()
        }
    }

    private fun checkDependenciesReady() {
        if (sttEngine.isReady && localTranslator.isReady) {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "🎙️ 모델 로드 완료! [시작] 버튼을 눌러주세요."
        }
    }

    private fun startListening() {
        updateUIState("마이크 작동 중 (음성 입력 대기)", "듣는 중...")

        // 캡슐화된 STT 엔진 호출
        sttEngine.startListening(
            scope = lifecycleScope,
            onPartialResult = { partialEng ->
                localTranslator.translate(partialEng) { translatedText ->
                    runOnUiThread {
                        adapter.updatePartial(partialEng, translatedText)
                        binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
                    }
                }
            },
            onFinalResult = { finalEng ->
                runOnUiThread {
                    adapter.finalizeLastItem(finalEng, "번역 중...")
                    val itemIndex = adapter.itemCount - 1
                    binding.recyclerView.scrollToPosition(itemIndex)

                    // 캡슐화된 SSE 클라이언트 호출
                    triggerBackendStream(finalEng, itemIndex)
                }
            }
        )
    }

    private fun triggerBackendStream(query: String, itemIndex: Int) {
        var streamResult = ""
        sseClient.streamTranslation(
            query = query,
            onTokenReceived = { token ->
                streamResult += token
                runOnUiThread {
                    adapter.updateStreamingText(itemIndex, streamResult)
                    binding.recyclerView.scrollToPosition(itemIndex)
                }
            },
            onComplete = {
                runOnUiThread { adapter.finishStreaming(itemIndex) }
            },
            onError = { errorMsg ->
                runOnUiThread {
                    if (streamResult.isEmpty()) adapter.updateStreamingText(itemIndex, "번역 실패: $errorMsg")
                    adapter.finishStreaming(itemIndex)
                }
            }
        )
    }

    private fun updateUIState(status: String, btnText: String) {
        binding.tvStatus.text = status
        binding.btnStart.text = btnText
    }

    override fun onDestroy() {
        super.onDestroy()
        sttEngine.release()
        localTranslator.close()
    }
}