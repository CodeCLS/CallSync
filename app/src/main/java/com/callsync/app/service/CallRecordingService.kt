package com.callsync.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.CallLog
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.callsync.app.data.AppSettings
import com.callsync.app.data.CallDirection
import com.callsync.app.data.RecordingMeta
import com.callsync.app.shizuku.ShizukuManager
import com.callsync.app.sync.SyncWorker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that watches call state. On OFFHOOK it asks the bound
 * Shizuku recorder to start; on IDLE it stops and hands the file to the sync
 * worker. Direction and number come from the call log.
 */
class CallRecordingService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private var callback: TelephonyCallback? = null

    private val sampleRate = 44_100
    private var currentFile: File? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var startedAtMs = 0L
    @Volatile private var active = false
    @Volatile private var paused = false
    /** True between OFFHOOK and IDLE — a call is in progress. */
    @Volatile private var inCall = false
    /** The user tapped "Don't record" for the current call. */
    @Volatile private var declinedThisCall = false
    private var liveTranscriber: com.callsync.app.recording.LiveTranscriber? = null
    private val widgetHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val widgetTick = object : Runnable {
        override fun run() {
            com.callsync.app.widget.CallSyncWidget.refresh(applicationContext)
            if (active) widgetHandler.postDelayed(this, 3000)
        }
    }

    // Watchdog: every couple of minutes, verify the whole chain is healthy and
    // self-heal — re-bind the Shizuku recorder if it dropped, re-register the
    // call-state listener if it was lost, and refresh the shared "connected"
    // signal. This is what makes the app trustworthy: it stays connected and
    // fixes itself in the background instead of only when the app is opened.
    private val healthHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healthTick = object : Runnable {
        override fun run() {
            runHealthCheck()
            healthHandler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Idle watcher runs as specialUse (not microphone): a mic-type FGS can't
        // be started from a boot broadcast on Android 12+. We upgrade to the
        // microphone type in onOffHook, only while a call is actually recording.
        startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, "Idle — waiting for a call")
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val listening = registerCallStateListener()
        RecorderState.serviceRunning.value = true
        RecorderState.listeningForCalls.value = listening
        RecorderState.lastEvent.value = if (listening) "Idle — waiting for a call" else "No phone permission"
        if (!listening) {
            updateNotification("Needs phone permission — open the app to grant it")
        }
        ShizukuManager.bind()
        // Catch up on any recording whose sync never completed.
        SyncWorker.enqueuePending(applicationContext)
        // Start the self-healing watchdog (runs immediately, then every 2 min).
        healthHandler.post(healthTick)
    }

    /** Re-verify and repair the recorder binding + call listener. */
    private fun runHealthCheck() {
        // 1. Make sure the Shizuku recorder is bound (no-op if already ready).
        ShizukuManager.ensureBound()
        val recorderReady = ShizukuManager.isRecorderReady()
        RecorderState.recorderReady.value = recorderReady

        // 2. Make sure we're still listening for call state; re-register if not
        //    (e.g. phone permission was granted after the service started).
        if (!RecorderState.listeningForCalls.value && callback == null) {
            val listening = registerCallStateListener()
            RecorderState.listeningForCalls.value = listening
        }
        RecorderState.serviceRunning.value = true

        // 3. Keep the widget's shown status honest.
        com.callsync.app.widget.CallSyncWidget.refresh(applicationContext)
        Log.i(TAG, "health check: recorderReady=$recorderReady listening=${RecorderState.listeningForCalls.value}")
    }

    /** @return true if we are now listening for call state. */
    @SuppressLint("MissingPermission")
    private fun registerCallStateListener(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE not granted; cannot watch call state yet")
            return false
        }
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handleCallState(state)
        }
        callback = cb
        telephonyManager.registerTelephonyCallback(mainExecutor, cb)
        return true
    }

    private fun handleCallState(state: Int) {
        Log.i(TAG, "call state -> $state (0=idle,1=ringing,2=offhook)")
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> onOffHook()
            TelephonyManager.CALL_STATE_IDLE -> onIdle()
            // RINGING: nothing yet — we start once the call is connected (OFFHOOK)
        }
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun onOffHook() {
        inCall = true
        if (active || declinedThisCall) return
        if (AppSettings.askBeforeRecording(this)) {
            showRecordPrompt()
            RecorderState.lastEvent.value = "Call detected ${now()} — waiting for Record / Don't record"
        } else {
            startRecording()
        }
    }

    /** User tapped "Record" on the call prompt. */
    private fun onRecordChosen() {
        cancelRecordPrompt()
        if (!inCall) {
            RecorderState.lastEvent.value = "Call already ended — nothing to record"
            return
        }
        startRecording()
    }

    /** User tapped "Don't record" on the call prompt. */
    private fun onDeclineChosen() {
        cancelRecordPrompt()
        declinedThisCall = true
        RecorderState.lastEvent.value = "Call at ${now()} not recorded (you chose Don't record)"
    }

    private fun startRecording() {
        if (active) return
        val recorder = ShizukuManager.recorder
        if (recorder == null) {
            Log.w(TAG, "OFFHOOK but no Shizuku recorder bound; cannot record")
            // Kick off a rebind so the next call is covered even if this one isn't.
            ShizukuManager.ensureBound()
            RecorderState.lastEvent.value = "Call at ${now()} NOT recorded — Shizuku not connected"
            updateNotification("Call active — recorder not connected")
            return
        }
        val file = newOutputFile()
        val pfd = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
        }.getOrNull()
        if (pfd == null) {
            Log.e(TAG, "could not open output file ${file.absolutePath}")
            return
        }
        currentFile = file
        currentPfd = pfd
        startedAtMs = System.currentTimeMillis()

        // Live transcription: a pipe carries raw PCM from the recorder (shell
        // process) to the LiveTranscriber here, which streams it to Deepgram.
        var pcmWrite: ParcelFileDescriptor? = null
        runCatching {
            val pipe = ParcelFileDescriptor.createPipe() // [read, write]
            pcmWrite = pipe[1]
            liveTranscriber = com.callsync.app.recording.LiveTranscriber(sampleRate).apply { start(pipe[0]) }
        }.onFailure { Log.w(TAG, "live transcription unavailable", it) }

        runCatching { recorder.startRecording(pfd, pcmWrite, sampleRate) }
            .onSuccess {
                active = true
                paused = false
                RecorderState.recording.value = true
                RecorderState.paused.value = false
                RecorderState.startedAtMs.value = startedAtMs
                RecorderState.lastEvent.value = "Recording started ${now()}"
                // Upgrade to microphone type now that a call is actively recording.
                startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "Recording…")
                widgetHandler.post(widgetTick)
            }
            .onFailure {
                Log.e(TAG, "startRecording failed", it)
                RecorderState.lastEvent.value = "Start failed ${now()}: ${it.message}"
                runCatching { pfd.close() }
                liveTranscriber?.stop(); liveTranscriber = null
            }
    }

    private fun onIdle() {
        inCall = false
        declinedThisCall = false
        cancelRecordPrompt()
        if (!active) return
        stopCurrent(save = true, reason = "Recording saved ${now()} — queued for sync")
    }

    /**
     * Stop the in-progress recording. If [save] is true the file is finalised and
     * queued for sync; if false (Cancel) it is deleted and never synced.
     */
    private fun stopCurrent(save: Boolean, reason: String) {
        if (!active) return
        active = false
        paused = false
        RecorderState.paused.value = false
        runCatching { ShizukuManager.recorder?.stopRecording() }
        liveTranscriber?.stop(); liveTranscriber = null
        runCatching { currentPfd?.close() }
        val file = currentFile
        currentPfd = null
        currentFile = null
        RecorderState.recording.value = false
        RecorderState.lastEvent.value = reason
        // Drop the microphone type again — back to the idle specialUse watcher.
        startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, "Idle — waiting for a call")
        widgetHandler.removeCallbacks(widgetTick)
        com.callsync.app.widget.CallSyncWidget.refresh(applicationContext)
        if (file == null) return
        if (save) {
            finishAndSync(file)
        } else {
            // Discard: remove the audio and any sidecar so it is never synced.
            runCatching { file.delete() }
            runCatching { File(file.parentFile, "${file.name}.json").delete() }
            Log.i(TAG, "recording cancelled and discarded: ${file.name}")
        }
    }

    private fun togglePause() {
        if (!active) return
        paused = !paused
        runCatching { ShizukuManager.recorder?.setPaused(paused) }
        RecorderState.recording.value = !paused
        RecorderState.paused.value = paused
        RecorderState.lastEvent.value = if (paused) "Paused ${now()}" else "Resumed ${now()}"
        updateNotification(if (paused) "Paused" else "Recording…")
        com.callsync.app.widget.CallSyncWidget.refresh(applicationContext)
    }

    @SuppressLint("MissingPermission")
    private fun finishAndSync(file: File) {
        val durationSec = ((System.currentTimeMillis() - startedAtMs) / 1000).coerceAtLeast(0)
        val entry = runCatching { lastCallLogEntry() }.getOrDefault(CallLogEntry())
        val lastCalled = runCatching { previousCallTo(entry.number) }.getOrNull()
        val renamed = renameWithMeta(file, entry.number, entry.direction)
        val meta = RecordingMeta(
            filePath = renamed.absolutePath,
            number = entry.number,
            direction = entry.direction,
            startedAtEpochMs = startedAtMs,
            durationSeconds = durationSec,
            contactName = entry.cachedName,
            lastCalledEpochMs = lastCalled,
        )
        SyncWorker.enqueue(applicationContext, meta)
    }

    private data class CallLogEntry(
        val number: String = "unknown",
        val direction: CallDirection = CallDirection.UNKNOWN,
        val cachedName: String? = null,
    )

    /** Most recent call-log row — number, direction, and cached contact name. */
    @SuppressLint("MissingPermission")
    private fun lastCallLogEntry(): CallLogEntry {
        if (!hasCallLog()) return CallLogEntry()
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME)
        // NB: do NOT put "LIMIT 1" in the sort-order string — some Android builds
        // reject it ("Invalid token LIMIT"). Sort newest-first and read row 0.
        contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            if (c.moveToFirst()) {
                val number = c.getString(0)?.ifBlank { "unknown" } ?: "unknown"
                val direction = when (c.getInt(1)) {
                    CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                    CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                    else -> CallDirection.UNKNOWN
                }
                val name = c.getString(2)?.takeIf { it.isNotBlank() }
                return CallLogEntry(number, direction, name)
            }
        }
        return CallLogEntry()
    }

    /** Date (epoch ms) of the most recent *earlier* call to [number], if any. */
    @SuppressLint("MissingPermission")
    private fun previousCallTo(number: String): Long? {
        if (!hasCallLog() || number == "unknown") return null
        val projection = arrayOf(CallLog.Calls.DATE)
        val selection = "${CallLog.Calls.NUMBER} = ? AND ${CallLog.Calls.DATE} < ?"
        val args = arrayOf(number, startedAtMs.toString())
        contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, selection, args,
            "${CallLog.Calls.DATE} DESC",
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    private fun hasCallLog(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun newOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${ts}_pending.m4a")
    }

    private fun renameWithMeta(file: File, number: String, direction: CallDirection): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtMs))
        val safeNumber = number.replace(Regex("[^0-9+]"), "").ifBlank { "unknown" }
        val target = File(file.parentFile, "${ts}_${direction.shortTag()}_${safeNumber}.m4a")
        return if (file.renameTo(target)) target else file
    }

    // --- notification ---------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openLive = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, com.callsync.app.RecordingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openLive)
            .setOngoing(true)
        // Show controls only while a recording is active.
        if (active) {
            builder.addAction(
                0,
                if (paused) "Resume" else "Pause",
                actionPendingIntent(ACTION_PAUSE_RESUME, 1),
            )
            builder.addAction(0, "Stop & save", actionPendingIntent(ACTION_STOP_SAVE, 2))
            builder.addAction(0, "Cancel", actionPendingIntent(ACTION_CANCEL, 3))
        }
        return builder.build()
    }

    /**
     * Big, high-priority heads-up notification shown when a call connects, so
     * the user decides per call whether it goes to Notion.
     */
    private fun showRecordPrompt() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(PROMPT_CHANNEL_ID, "Record this call?", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks whether to record a call when it starts"
                enableVibration(true)
            }
        )
        val who = runCatching { lastCallLogEntry() }.getOrNull()
            ?.let { it.cachedName ?: it.number.takeIf { n -> n != "unknown" } }
        val title = if (who != null) "Record call with $who?" else "Record this call?"
        val notification = NotificationCompat.Builder(this, PROMPT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText("Tap Record to transcribe it into Notion.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "A call just started. Tap RECORD to record and transcribe it into Notion, " +
                        "or DON'T RECORD to leave this call out."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setColor(0xFFC62828.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(0, "● Record", actionPendingIntent(ACTION_RECORD, 4))
            .addAction(0, "Don't record", actionPendingIntent(ACTION_DECLINE, 5))
            .build()
        mgr.notify(PROMPT_NOTIF_ID, notification)
    }

    private fun cancelRecordPrompt() {
        getSystemService(NotificationManager::class.java).cancel(PROMPT_NOTIF_ID)
    }

    private fun actionPendingIntent(action: String, requestCode: Int): android.app.PendingIntent {
        val intent = Intent(this, CallRecordingService::class.java).setAction(action)
        return android.app.PendingIntent.getService(
            this, requestCode, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    /**
     * (Re)enter the foreground with an explicit service type. Used to keep the
     * idle watcher on the boot-safe specialUse type and only claim the
     * microphone type while a call is actively being recorded. Falls back to a
     * plain notification update if the typed start is rejected.
     */
    private fun startForegroundTyped(type: Int, text: String) {
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        }.onFailure {
            Log.w(TAG, "startForeground(type=$type) rejected; updating notification only", it)
            updateNotification(text)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECORD -> onRecordChosen()
            ACTION_DECLINE -> onDeclineChosen()
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_STOP_SAVE -> stopCurrent(save = true, reason = "Stopped & saved ${now()}")
            ACTION_CANCEL -> stopCurrent(save = false, reason = "Cancelled ${now()} — discarded")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        healthHandler.removeCallbacks(healthTick)
        cancelRecordPrompt()
        callback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        ShizukuManager.unbind()
        RecorderState.serviceRunning.value = false
        RecorderState.listeningForCalls.value = false
        RecorderState.recording.value = false
        RecorderState.lastEvent.value = "Watcher stopped"
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallRecordingService"
        private const val CHANNEL_ID = "callsync_recording"
        private const val NOTIF_ID = 42
        private const val PROMPT_CHANNEL_ID = "callsync_record_prompt"
        private const val PROMPT_NOTIF_ID = 43
        /** Watchdog interval — verify/repair the recorder + listener this often. */
        private const val HEALTH_INTERVAL_MS = 120_000L
        const val ACTION_RECORD = "com.callsync.app.RECORD"
        const val ACTION_DECLINE = "com.callsync.app.DECLINE"
        const val ACTION_PAUSE_RESUME = "com.callsync.app.PAUSE_RESUME"
        const val ACTION_STOP_SAVE = "com.callsync.app.STOP_SAVE"
        const val ACTION_CANCEL = "com.callsync.app.CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallRecordingService::class.java))
        }
    }
}
