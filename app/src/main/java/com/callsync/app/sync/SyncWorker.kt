package com.callsync.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.callsync.app.data.RecordingMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sync pipeline: transcribe → create Notion page.
 *
 * Durability model:
 *  - Each recording gets a sidecar JSON ("<audio>.m4a.json") holding its full
 *    [RecordingMeta]. It is written *before* the work is enqueued.
 *  - WorkManager persists the job and retries with exponential backoff on
 *    transient failure (no network, API down) — this survives app death and
 *    reboot.
 *  - Only on confirmed success is the audio moved into recordings/synced/ and
 *    the sidecar deleted. Anything still carrying a sidecar is "pending".
 *  - [enqueuePending] rescans on app/service start and re-queues every pending
 *    recording, so nothing is lost even if a job was dropped or never scheduled.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    // TODO: swap these two lines to change providers.
    private val transcriber: TranscriptionProvider = HttpTranscriptionProvider()
    private val syncTarget: SyncTarget = NotionClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val audioPath = inputData.getString(KEY_PATH) ?: return@withContext Result.failure()
        val audio = File(audioPath)
        val sidecar = sidecarFor(audio)

        if (!audio.exists()) {
            Log.e(TAG, "audio missing, dropping: $audioPath")
            sidecar.delete()
            return@withContext Result.failure()
        }
        val meta = readMeta(sidecar) ?: run {
            Log.e(TAG, "sidecar unreadable: ${sidecar.path}")
            return@withContext Result.failure()
        }

        val transcript = runCatching { transcriber.transcribe(audio) }.getOrNull()
        if (transcript == null) {
            // null = the transcription call itself failed (no network, API error).
            // Transient — keep the file and retry later.
            Log.w(TAG, "transcription failed; will retry: ${audio.name}")
            return@withContext Result.retry()
        }

        // Empty-transcript guard: a non-null but blank transcript means the call
        // connected but nobody spoke (ring-out, voicemail, pocket dial). We still
        // log it as a "no answer" attempt — the outreach record and last-called
        // tracking matter — but with a one-line note instead of a transcript, and
        // we don't keep the silent audio. Notion failure is still retried so the
        // entry is never lost.
        // Personal filter: keep personal calls (family, friends) out of the work
        // Notion log. Biased to push — only a clear personal signal holds a call
        // back; it's still recorded in the app's local history.
        if (CallClassifier.isPersonal(meta.contactName, meta.number, transcript)) {
            Log.i(TAG, "personal call, kept out of Notion: ${audio.name}")
            runCatching {
                com.callsync.app.data.CallHistoryStore.add(
                    applicationContext,
                    com.callsync.app.data.CallRecord(
                        startedAtEpochMs = meta.startedAtEpochMs,
                        displayName = meta.displayName(),
                        number = meta.number,
                        direction = meta.direction,
                        durationSeconds = meta.durationSeconds,
                        status = com.callsync.app.data.CallStatus.PERSONAL,
                        notionUrl = null,
                    ),
                )
            }
            com.callsync.app.service.RecorderState.lastEvent.value =
                "Personal call kept private (${meta.displayName()})"
            runCatching { audio.delete() }
            runCatching { sidecar.delete() }
            return@withContext Result.success()
        }

        val noAnswer = transcript.isBlank()
        if (noAnswer) Log.i(TAG, "empty transcript -> logging as 'no answer': ${audio.name}")

        val result = runCatching { syncTarget.createPage(meta, transcript, noAnswer) }.getOrNull()
        if (result == null) {
            Log.w(TAG, "notion failed; will retry: ${audio.name}")
            return@withContext Result.retry()
        }

        // Record in the local history of tracked calls so the app can list it.
        runCatching {
            com.callsync.app.data.CallHistoryStore.add(
                applicationContext,
                com.callsync.app.data.CallRecord(
                    startedAtEpochMs = meta.startedAtEpochMs,
                    displayName = meta.displayName(),
                    number = meta.number,
                    direction = meta.direction,
                    durationSeconds = meta.durationSeconds,
                    status = if (noAnswer) com.callsync.app.data.CallStatus.NO_ANSWER
                    else com.callsync.app.data.CallStatus.SYNCED,
                    notionUrl = result.pageUrl,
                ),
            )
        }

        if (noAnswer) {
            // Nothing worth keeping in the silent recording; just clear it.
            com.callsync.app.service.RecorderState.lastEvent.value =
                "Logged no-answer call (${meta.displayName()})"
            runCatching { audio.delete() }
            runCatching { sidecar.delete() }
        } else {
            com.callsync.app.service.RecorderState.lastEvent.value =
                "Synced call (${meta.displayName()})"
            markSynced(audio, sidecar)
        }
        Log.i(TAG, "synced ${audio.name}${if (noAnswer) " (no answer)" else ""}")
        Result.success()
    }

    private fun readMeta(sidecar: File): RecordingMeta? =
        runCatching { JSON.decodeFromString(RecordingMeta.serializer(), sidecar.readText()) }.getOrNull()

    private fun markSynced(audio: File, sidecar: File) {
        runCatching {
            val syncedDir = File(audio.parentFile, "synced").apply { mkdirs() }
            audio.renameTo(File(syncedDir, audio.name))
            sidecar.delete()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val KEY_PATH = "path"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun sidecarFor(audio: File): File = File(audio.parentFile, "${audio.name}.json")

        /** Persist the sidecar, then schedule a sync for this recording. */
        fun enqueue(context: Context, meta: RecordingMeta) {
            val audio = File(meta.filePath)
            runCatching {
                sidecarFor(audio).writeText(JSON.encodeToString(RecordingMeta.serializer(), meta))
            }.onFailure { Log.e(TAG, "could not write sidecar for ${audio.name}", it) }
            schedule(context, audio)
        }

        /** Rescan the recordings folder and re-queue anything still pending. */
        fun enqueuePending(context: Context) {
            val dir = File(context.getExternalFilesDir(null), "recordings")
            val sidecars = dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a.json") } ?: return
            var count = 0
            for (sc in sidecars) {
                val audio = File(dir, sc.name.removeSuffix(".json"))
                if (audio.exists()) { schedule(context, audio); count++ } else sc.delete()
            }
            if (count > 0) Log.i(TAG, "re-queued $count pending recording(s)")
        }

        private fun schedule(context: Context, audio: File) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString(KEY_PATH, audio.absolutePath).build())
                .build()
            // KEEP: if a job for this file is already queued/running, don't stack another.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync_${audio.name}", ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
