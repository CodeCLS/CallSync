package com.callsync.app.sync

import com.callsync.app.data.AppSettings
import com.callsync.app.data.RecordingMeta
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Behind the same swap seam as transcription: a single call that creates one
 * Notion page per recording. Properties carry the metadata; the transcript goes
 * in the page body as paragraph blocks.
 */
/** Result of a successful page creation. */
data class SyncResult(val pageUrl: String?)

interface SyncTarget {
    /**
     * @param noAnswer true for a call that connected but had no speech (ring-out,
     *   voicemail). The page is logged as a "no answer" attempt with a one-line
     *   body instead of a transcript, so the outreach record is still kept.
     * @return a [SyncResult] on success (page URL if known), or null on failure.
     */
    fun createPage(meta: RecordingMeta, transcript: String, noAnswer: Boolean = false): SyncResult?
}

class NotionClient(
    private val token: String = AppSettings.notionToken(),
    private val databaseId: String = AppSettings.notionDatabaseId(),
    private val recordedBy: String = AppSettings.userName(),
    private val client: OkHttpClient = OkHttpClient(),
) : SyncTarget {

    override fun createPage(meta: RecordingMeta, transcript: String, noAnswer: Boolean): SyncResult? {
        if (token.isBlank() || databaseId.isBlank()) return null

        val isoStart = iso8601(meta.startedAtEpochMs)
        val title = if (noAnswer) "Call · ${meta.displayName()} · no answer"
        else "Call · ${meta.displayName()}"
        val payload = buildJsonObject {
            putJsonObject("parent") { put("database_id", databaseId) }

            // ---- Properties ----
            // Mapped to the real "Meetings" database schema, which has only
            // Name (title) and Date among the fields we can fill. Type/Contact/
            // Attendees are left for the Call Intake agent to enrich.
            putJsonObject("properties") {
                putJsonObject("Name") {
                    putJsonArray("title") {
                        // Prefer the contact name; fall back to the number.
                        addJsonObject { putJsonObject("text") { put("content", title) } }
                    }
                }
                putJsonObject("Date") {
                    putJsonObject("date") { put("start", isoStart) }
                }
            }

            // ---- Page body ----
            putJsonArray("children") {
                // Context line: who was called (name + number), direction, duration.
                // The number alone is often not spoken in the call, so the name is
                // important standalone context for whoever reads the transcript.
                val who = if (meta.contactName.isNullOrBlank()) meta.number
                else "${meta.contactName} (${meta.number})"
                val lastCalled = meta.lastCalledEpochMs?.let { " · last called ${iso8601(it)}" } ?: ""
                val by = if (recordedBy.isBlank()) "" else " · recorded by $recordedBy"
                val metaLine = "📞 $who · ${meta.direction.name.lowercase()} · " +
                    "${meta.durationSeconds}s · $isoStart$lastCalled$by"
                addJsonObject {
                    put("object", "block")
                    put("type", "paragraph")
                    putJsonObject("paragraph") {
                        putJsonArray("rich_text") {
                            addJsonObject {
                                put("type", "text")
                                putJsonObject("text") { put("content", metaLine) }
                            }
                        }
                    }
                }
                // Body text: a clear "no answer" note, or the transcript split
                // into Notion-sized paragraph blocks.
                val bodyChunks = if (noAnswer) listOf(
                    "☎️ No answer — the call connected but no speech was recorded " +
                        "(${meta.durationSeconds}s, ${meta.direction.name.lowercase()})."
                ) else transcript.chunkedForNotion()
                bodyChunks.forEach { chunk ->
                    addJsonObject {
                        put("object", "block")
                        put("type", "paragraph")
                        putJsonObject("paragraph") {
                            putJsonArray("rich_text") {
                                addJsonObject {
                                    put("type", "text")
                                    putJsonObject("text") { put("content", chunk) }
                                }
                            }
                        }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("https://api.notion.com/v1/pages")
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", "2022-06-28")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val url = resp.body?.string()?.let { extractPageUrl(it) }
            return SyncResult(url)
        }
    }

    /**
     * Check the token can see the database. Returns null on success, or a short
     * human-readable error for the onboarding screen.
     */
    fun verify(): String? {
        if (token.isBlank()) return "Notion token is empty"
        if (databaseId.isBlank()) return "Database ID is empty"
        val request = Request.Builder()
            .url("https://api.notion.com/v1/databases/$databaseId")
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", "2022-06-28")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> null
                    401 -> "Token rejected — check you copied the whole secret"
                    404 -> "Database not found — open it in Notion, ••• → Connections → add your integration"
                    else -> "Notion returned HTTP ${resp.code}"
                }
            }
        }.getOrElse { "Network error: ${it.message}" }
    }

    /** Pull the "url" field out of the created-page response, if present. */
    private fun extractPageUrl(json: String): String? {
        val marker = "\"url\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = json.indexOf('"', from)
        if (end < 0) return null
        return json.substring(from, end).replace("\\/", "/")
    }

    /** Notion caps a rich_text content string at 2000 chars. */
    private fun String.chunkedForNotion(): List<String> =
        ifBlank { "(empty transcript)" }.chunked(1900)

    private fun iso8601(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
