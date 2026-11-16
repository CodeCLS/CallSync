package com.callsync.app.data

import kotlinx.serialization.Serializable

/** Direction of the call, derived from the call log. */
@Serializable
enum class CallDirection { INCOMING, OUTGOING, UNKNOWN;
    fun shortTag(): String = when (this) {
        INCOMING -> "in"
        OUTGOING -> "out"
        UNKNOWN -> "unk"
    }
}

/** Metadata about one finished recording, carried into the sync worker. */
@Serializable
data class RecordingMeta(
    val filePath: String,
    val number: String,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
    /** Contact display name from the call log, if the number is a known contact. */
    val contactName: String? = null,
    /** Epoch ms of the previous call to this same number, if any. */
    val lastCalledEpochMs: Long? = null,
) {
    /** Best human label: contact name if known, else the number. */
    fun displayName(): String = contactName?.takeIf { it.isNotBlank() } ?: number
}
