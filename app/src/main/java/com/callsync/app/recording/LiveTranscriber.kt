package com.callsync.app.recording

import android.os.ParcelFileDescriptor
import android.util.Log
import com.callsync.app.BuildConfig
import com.callsync.app.data.AppSettings
import com.callsync.app.service.RecorderState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Streams live call audio to Deepgram's realtime WebSocket and publishes an
 * incremental transcript to [RecorderState.liveTranscript].
 *
 * This is only the on-screen *preview*. The authoritative transcript saved to
 * Notion is still produced after the call by the batch [HttpTranscriptionProvider]
 * with automatic language detection — so the live language choice here is not
 * critical.
 */
class LiveTranscriber(
    private val sampleRate: Int,
    private val apiKey: String = AppSettings.deepgramKey(),
) {
    private var ws: WebSocket? = null
    @Volatile private var running = false
    private var pumpThread: Thread? = null
    private val finals = StringBuilder()

    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun start(pcmRead: ParcelFileDescriptor) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "no transcription key; live transcript disabled")
            runCatching { pcmRead.close() }
            return
        }
        running = true
        finals.setLength(0)
        RecorderState.resetLive()

        // Deepgram realtime: linear16 PCM, mono, our capture sample rate.
        // nova-3 + language=multi matches the saved Notion transcript and handles
        // mixed German/English in the live preview (nova-2 forced a single
        // language and was noticeably weaker).
        val url = "wss://api.deepgram.com/v1/listen" +
            "?model=nova-3&language=multi&punctuate=true&interim_results=true" +
            "&encoding=linear16&sample_rate=$sampleRate&channels=1"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "deepgram ws open")
                pumpThread = thread(name = "pcm-pump") { pump(pcmRead, webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "deepgram ws failure: ${t.message}")
                runCatching { pcmRead.close() }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { pcmRead.close() }
            }
        })
    }

    private fun pump(pcmRead: ParcelFileDescriptor, webSocket: WebSocket) {
        ParcelFileDescriptor.AutoCloseInputStream(pcmRead).use { input ->
            val buf = ByteArray(sampleRate) // ~0.5s of 16-bit mono
            while (running) {
                val n = try { input.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n > 0) webSocket.send(buf.toByteString(0, n))
            }
            // Tell Deepgram no more audio is coming, flushing the final result.
            runCatching { webSocket.send(ByteString.EMPTY) }
            runCatching { webSocket.send("{\"type\":\"CloseStream\"}") }
        }
    }

    private fun handleMessage(text: String) {
        runCatching {
            val obj = JSONObject(text)
            val alts = obj.optJSONObject("channel")?.optJSONArray("alternatives") ?: return
            if (alts.length() == 0) return
            val transcript = alts.getJSONObject(0).optString("transcript", "")
            if (transcript.isBlank()) return
            val isFinal = obj.optBoolean("is_final", false)
            if (isFinal) {
                if (finals.isNotEmpty()) finals.append(' ')
                finals.append(transcript)
                RecorderState.liveTranscript.value = finals.toString()
            } else {
                // Show interim tail appended to the finalised text.
                val sep = if (finals.isEmpty()) "" else " "
                RecorderState.liveTranscript.value = "$finals$sep$transcript"
            }
        }
    }

    fun stop() {
        running = false
        runCatching { ws?.send("{\"type\":\"CloseStream\"}") }
        runCatching { ws?.close(1000, "done") }
        ws = null
        pumpThread = null
    }

    companion object {
        private const val TAG = "LiveTranscriber"
    }
}
