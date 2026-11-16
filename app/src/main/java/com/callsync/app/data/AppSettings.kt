package com.callsync.app.data

import android.content.Context
import com.callsync.app.BuildConfig

/**
 * User-entered configuration from the onboarding screen: the user's name, their
 * Notion integration token + database, and their Deepgram key.
 *
 * Values entered in the app win; the build-time values from local.properties
 * (BuildConfig) are only a fallback so a developer build keeps working without
 * going through onboarding.
 */
object AppSettings {
    private const val PREFS = "callsync_settings"
    private const val K_NAME = "user_name"
    private const val K_NOTION_TOKEN = "notion_token"
    private const val K_NOTION_DB = "notion_database_id"
    private const val K_DEEPGRAM = "deepgram_key"
    private const val K_ONBOARDED = "onboarded"
    private const val K_ASK = "ask_before_recording"

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun prefs(context: Context? = null) =
        (context?.applicationContext ?: appContext)
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun str(key: String, fallback: String = "", context: Context? = null): String =
        prefs(context).getString(key, null)?.takeIf { it.isNotBlank() } ?: fallback

    fun userName(context: Context? = null): String = str(K_NAME, context = context)
    fun notionToken(context: Context? = null): String =
        str(K_NOTION_TOKEN, BuildConfig.NOTION_TOKEN, context)
    fun notionDatabaseId(context: Context? = null): String =
        str(K_NOTION_DB, BuildConfig.NOTION_DATABASE_ID, context)
    fun deepgramKey(context: Context? = null): String =
        str(K_DEEPGRAM, BuildConfig.TRANSCRIBE_API_KEY, context)

    /** When true (default), a call shows a Record / Don't record prompt instead of auto-recording. */
    fun askBeforeRecording(context: Context? = null): Boolean =
        prefs(context).getBoolean(K_ASK, true)

    fun setAskBeforeRecording(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(K_ASK, value).apply()

    fun isOnboarded(context: Context): Boolean = prefs(context).getBoolean(K_ONBOARDED, false)

    fun save(
        context: Context,
        name: String,
        notionToken: String,
        notionDatabase: String,
        deepgramKey: String,
    ) {
        prefs(context).edit()
            .putString(K_NAME, name.trim())
            .putString(K_NOTION_TOKEN, notionToken.trim())
            .putString(K_NOTION_DB, parseDatabaseId(notionDatabase))
            .putString(K_DEEPGRAM, deepgramKey.trim())
            .putBoolean(K_ONBOARDED, true)
            .apply()
    }

    /**
     * Accepts either a raw database ID or a full Notion URL
     * (https://www.notion.so/workspace/Calls-0123456789abcdef0123456789abcdef?v=…)
     * and returns the 32-hex-char ID.
     */
    fun parseDatabaseId(input: String): String {
        val trimmed = input.trim()
        // The ID is always the tail of the last path segment, with or without
        // UUID dashes: "My-DB-<32hex>", "<8-4-4-4-12>", or just "<32hex>".
        val segment = trimmed.substringBefore('?').substringBefore('#')
            .trimEnd('/').substringAfterLast('/')
        val compact = segment.replace("-", "")
        val tail = compact.takeLast(32)
        return if (tail.length == 32 && tail.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            tail.lowercase()
        } else trimmed
    }
}
