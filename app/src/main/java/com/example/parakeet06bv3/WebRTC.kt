package com.example.parakeet06bv3

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.parakeet06bv3.databinding.ActivityMainBinding
import com.example.parakeet06bv3.databinding.ActivityWebRtcBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack

class WebRTC : AppCompatActivity() {

    private val binding by lazy { ActivityWebRtcBinding.inflate(layoutInflater) }
    private val WS_URL = "ws://172.30.1.67:8080"
    private val TAG = "WebRTC_TEST"

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private lateinit var permissionManager: PermissionManager
    // 비디오 렌더링
    private lateinit var rootEglBase: EglBase
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
// 매니저 객체 먼저 생성
        permissionManager = PermissionManager(this)

        // 💡 수정된 부분: 무조건 실행하던 코드를 지우고, 오직 권한 체크 로직 안에서만 실행!
        if (permissionManager.hasAllPermissions()) {
            Log.d(TAG, "✅ 권한 이미 있음. WebRTC 초기화 시작.")
            initWebRtc()
            connectWebSocket()
        } else {
            Log.d(TAG, "⚠️ 권한 부족. 팝업 요청.")
            permissionManager.requestPermissions()
        }
        // 2. 통화 걸기 버튼 (발신)
        binding.btnCall.setOnClickListener {
            Log.d(TAG, "전화 걸기 시작 (Offer 전송)")
            updateUI(isCalling = true) // UI 업데이트

            peerConnection?.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                    sendSignal("offer", JSONObject().apply {
                        put("type", "offer")
                        put("sdp", desc?.description)
                    })
                }
            }, MediaConstraints())
        }

        // 3. 통화 끊기 버튼
        binding.btnHangup.setOnClickListener {
            Log.d(TAG, "통화 종료 버튼 클릭")
            disconnectWebRtc()
            finish() // 액티비티 닫기 (원하면 초기 상태로 돌려도 됨)
        }
    }

    private fun initWebRtc() {
        rootEglBase = EglBase.create()

        binding.localView.init(rootEglBase.eglBaseContext, null)
        binding.localView.setZOrderMediaOverlay(true)
        binding.remoteView.init(rootEglBase.eglBaseContext, null)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        val defaultVideoEncoderFactory =
            DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()

        createLocalMediaStream()
        createPeerConnection()
    }

    private fun createLocalMediaStream() {
        val videoCapturer = createCameraCapturer(Camera2Enumerator(this))
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
        videoCapturer.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource)
        localVideoTrack?.addSink(binding.localView)

        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) return enumerator.createCapturer(deviceName, null)
        }
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) return enumerator.createCapturer(deviceName, null)
        }
        return null
    }

    private fun createPeerConnection() {
        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal("candidate", JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.e(TAG, "🔥 P2P 상태: $newState")
            }

            // 상대방 트랙 들어올 때
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "📺 상대방 비디오 렌더링 시작")
                    runOnUiThread { track.addSink(binding.remoteView) }
                }
            }

            override fun onDataChannel(dc: DataChannel) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
        })

        peerConnection?.addTrack(localVideoTrack)
        peerConnection?.addTrack(localAudioTrack)
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "🟢 웹소켓 연결 성공 (대기 중)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.getString("type")) {
                    "offer" -> {
                        // 💡 내가 통화를 건 게 아닌데 Offer가 날아왔다? = 상대가 전화를 걸었다 (자동 수신 모드)
                        Log.d(TAG, "📥 상대방 전화 옴! 자동으로 Answer 쏨")
                        runOnUiThread { updateUI(isCalling = true) }

                        val sdp = SessionDescription(SessionDescription.Type.OFFER, json.getJSONObject("offer").getString("sdp"))
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)

                        peerConnection?.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                                sendSignal("answer", JSONObject().apply {
                                    put("type", "answer")
                                    put("sdp", desc?.description)
                                })
                            }
                        }, MediaConstraints())
                    }
                    "answer" -> {
                        Log.d(TAG, "📥 내 통화 요청에 상대가 응답함 (Answer 수신)")
                        val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.getJSONObject("answer").getString("sdp"))
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                    }
                    "candidate" -> {
                        val c = json.getJSONObject("candidate")
                        peerConnection?.addIceCandidate(IceCandidate(c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate")))
                    }
                }
            }
        })
    }

    private fun sendSignal(type: String, data: JSONObject) {
        webSocket?.send(JSONObject().apply {
            put("type", type)
            put(type, data)
        }.toString())
    }

    // UI 상태 변경은 무조건 Main Thread에서
    private fun updateUI(isCalling: Boolean) {
        binding.btnCall.isEnabled = !isCalling
        binding.btnHangup.isEnabled = isCalling
        if (isCalling) {
            binding.btnCall.text = "연결 중..."
        }
    }

    private fun disconnectWebRtc() {
        webSocket?.close(1000, "User Hangup")
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        peerConnection?.dispose()
        binding.localView.release()
        binding.remoteView.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectWebRtc()
        rootEglBase.release()
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    // 💡 3. 사용자가 팝업에서 '허용'이나 '거부'를 눌렀을 때 결과를 받는 콜백 함수
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PermissionManager.REQUEST_CODE) {
            if (permissionManager.hasAllPermissions()) {
                // 사용자가 방금 팝업에서 다 허용해줌!
                Log.d(TAG, "✅ 권한 획득 성공! WebRTC 초기화 시작.")
                initWebRtc()
                connectWebSocket()
            } else {
                // 사용자가 거부함
                Log.e(TAG, "❌ 권한이 거부되어 통화를 시작할 수 없어.")
                // 버튼들을 비활성화하거나 앱을 종료시키는 로직을 넣어도 됨
            }
        }
    }
}