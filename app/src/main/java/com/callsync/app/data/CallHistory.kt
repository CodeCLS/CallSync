package com.callsync.app.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Outcome of a tracked call once the sync pipeline finished with it. */
@Serializable
enum class CallStatus {
    /** Business call pushed to Notion. */
    SYNCED,
    /** Connected but no speech recorded; logged to Notion as a no-answer attempt. */
    NO_ANSWER,
    /** Classified personal (e.g. Mum); kept out of Notion, local history only. */
    PERSONAL,
}

/**
 * One entry in the local history of calls CallSync handled. This is written by
 * the sync worker after a call is logged to Notion, so the app can show past
 * calls without a network round-trip and even after the audio file is gone.
 */
@Serializable
data class CallRecord(
    val startedAtEpochMs: Long,
    val displayName: String,
    val number: String,
    val direction: CallDirection,
    val durationSeconds: Long,
    val status: CallStatus,
    /** Notion page URL, if the create call returned one — makes the row tappable. */
    val notionUrl: String? = null,
)

/**
 * Append-only JSON log of tracked calls at filesDir/call_history.json, newest
 * first, capped at [MAX]. Small and synchronous — history is tiny.
 */
object CallHistoryStore {

    private const val TAG = "CallHistoryStore"
    private const val MAX = 300
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(context: Context): File =
        File(context.filesDir, "call_history.json")

    @Synchronized
    fun all(context: Context): List<CallRecord> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        JSON.decodeFromString(ListSerializer, f.readText())
    }.getOrElse {
        Log.w(TAG, "could not read history", it)
        emptyList()
    }

    @Synchronized
    fun add(context: Context, record: CallRecord) {
        runCatching {
            val current = all(context).toMutableList()
            current.add(0, record)
            while (current.size > MAX) current.removeAt(current.size - 1)
            file(context).writeText(JSON.encodeToString(ListSerializer, current))
        }.onFailure { Log.e(TAG, "could not append history", it) }
    }

    private val ListSerializer =
        kotlinx.serialization.builtins.ListSerializer(CallRecord.serializer())
}
