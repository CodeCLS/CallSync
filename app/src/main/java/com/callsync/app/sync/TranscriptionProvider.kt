package com.callsync.app.sync

import com.callsync.app.BuildConfig
import com.callsync.app.data.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Swappable transcription backend. Implement this for Deepgram, a local
 * Whisper server, or anything else, and return the plain-text transcript.
 */
interface TranscriptionProvider {
    /** @return transcript text, or null on failure. */
    fun transcribe(audio: File): String?
}

/**
 * Default implementation: POSTs the raw audio to a single endpoint and reads a
 * text transcript out of the response. Wired for Deepgram's prerecorded API by
 * default; point TRANSCRIBE_URL at your local Whisper server to swap.
 */
class HttpTranscriptionProvider(
    private val url: String = BuildConfig.TRANSCRIBE_URL,
    private val apiKey: String = AppSettings.deepgramKey(),
    private val client: OkHttpClient = defaultClient,
) : TranscriptionProvider {

    override fun transcribe(audio: File): String? {
        val body = audio.asRequestBody("audio/mp4".toMediaType())
        val builder = Request.Builder().url(url).post(body)
        if (apiKey.isNotBlank()) {
            // Deepgram expects "Token <key>". A bare bearer / local server may differ.
            builder.header("Authorization", "Token $apiKey")
        }
        builder.header("Content-Type", "audio/mp4")

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = resp.body?.string().orEmpty()
            return extractTranscript(json)
        }
    }

    /**
     * Pulls the transcript out of a Deepgram response shape:
     * results.channels[0].alternatives[0].transcript
     *
     * Parses the JSON properly (via kotlinx.serialization) rather than scanning
     * for a quote — the old string scan truncated any transcript containing a
     * quotation mark. Falls back to raw text for a local Whisper server that
     * returns plain text instead of JSON.
     */
    private fun extractTranscript(json: String): String? {
        if (json.isBlank()) return null
        val parsed = runCatching {
            Json.parseToJsonElement(json)
                .jsonObject["results"]?.jsonObject
                ?.get("channels")?.jsonArray?.get(0)?.jsonObject
                ?.get("alternatives")?.jsonArray?.get(0)?.jsonObject
                ?.get("transcript")?.jsonPrimitive?.content
        }.getOrNull()
        // Deepgram returned a transcript (possibly ""); otherwise treat the body
        // as plain text (local Whisper) when it isn't a JSON object.
        return parsed ?: json.takeUnless { it.trimStart().startsWith("{") }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
