package com.callsync.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.callsync.app.R
import com.callsync.app.RecordingActivity
import com.callsync.app.service.CallRecordingService
import com.callsync.app.service.RecorderState

/**
 * Home-screen widget: shows recording status + the latest transcript lines and
 * offers Pause/Resume, Stop, and Open. Android limits widgets to this simple
 * remote layout — the rich live view is [RecordingActivity], reached via Open.
 */
class CallSyncWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        /** Push current [RecorderState] into every placed widget instance. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CallSyncWidget::class.java))
            ids.forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_callsync)

            val recording = RecorderState.recording.value
            val paused = RecorderState.paused.value
            val active = recording || paused
            val ready = RecorderState.serviceRunning.value &&
                RecorderState.listeningForCalls.value &&
                RecorderState.recorderReady.value
            val status = when {
                paused -> "● Paused"
                recording -> "● Recording"
                ready -> "CallSync — armed ✓"
                RecorderState.serviceRunning.value -> "CallSync — connecting…"
                else -> "CallSync — off"
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setTextViewText(
                R.id.widget_transcript,
                RecorderState.liveTranscript.value.ifBlank {
                    if (active) "Listening…" else "Open to see the live transcript."
                },
            )
            views.setTextViewText(R.id.widget_btn_pause, if (paused) "Resume" else "Pause")

            views.setOnClickPendingIntent(R.id.widget_btn_pause,
                servicePending(context, CallRecordingService.ACTION_PAUSE_RESUME, 11))
            views.setOnClickPendingIntent(R.id.widget_btn_stop,
                servicePending(context, CallRecordingService.ACTION_STOP_SAVE, 12))
            views.setOnClickPendingIntent(R.id.widget_btn_open, openPending(context))

            mgr.updateAppWidget(id, views)
        }

        private fun servicePending(context: Context, action: String, rc: Int): PendingIntent =
            PendingIntent.getService(
                context, rc,
                Intent(context, CallRecordingService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun openPending(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 10,
                Intent(context, RecordingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
