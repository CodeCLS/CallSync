package com.callsync.app.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live, process-wide status the UI (status panel, recording screen, widget) can
 * observe so the user can tell at a glance what is happening.
 */
object RecorderState {
    val serviceRunning = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val lastEvent = MutableStateFlow("—")
    val listeningForCalls = MutableStateFlow(false)

    /**
     * Whether the Shizuku recorder is bound and pingable right now. Maintained by
     * [com.callsync.app.shizuku.ShizukuManager] on connect/disconnect and
     * re-verified by the service watchdog, so the widget and app screens show one
     * consistent "connected" signal instead of each polling the binder on its own
     * (which lagged ~1s behind the real state).
     */
    val recorderReady = MutableStateFlow(false)

    /** Epoch ms the current recording began (for the on-screen timer). */
    val startedAtMs = MutableStateFlow(0L)

    /** Running live transcript (finalised text + current interim tail). */
    val liveTranscript = MutableStateFlow("")

    fun resetLive() {
        liveTranscript.value = ""
    }
}
