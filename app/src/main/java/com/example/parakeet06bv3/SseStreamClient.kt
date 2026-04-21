import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

class SseStreamClient(private val client: OkHttpClient, private val backendUrl: String) {

    fun streamTranslation(
        query: String,
        onTokenReceived: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val json = JSONObject().apply { put("q", query) }.toString()
        val requestBody = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(backendUrl)
            .addHeader("ngrok-skip-browser-warning", "69420")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val token = JSONObject(data).getString("t")
                    onTokenReceived(token)
                } catch (e: Exception) {
                    Log.e("SSE", "파싱 에러", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                onComplete()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("SSE", "통신 실패", t)
                onError("네트워크 오류")
            }
        }

        EventSources.createFactory(client).newEventSource(request, listener)
    }
}