package com.callsync.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.callsync.app.IRecorderService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The AIDL service that Shizuku launches in a process running as the shell UID
 * (2000). Everything here executes with shell privileges, which is what lets us
 * open MediaRecorder.AudioSource.VOICE_CALL.
 *
 * Shizuku instantiates this class either with a no-arg constructor or with a
 * (Context) constructor — we provide both.
 */
@Suppress("unused")
class RecorderUserService : IRecorderService.Stub {

    private val context: Context?

    constructor() : super() {
        this.context = null
        exemptHiddenApis()
    }

    // Shizuku passes a system context here when available; prefer it.
    constructor(context: Context) : super() {
        this.context = context
        exemptHiddenApis()
    }

    private val recording = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null

    override fun isRecording(): Boolean = recording.get()

    override fun setPaused(paused: Boolean) {
        this.paused.set(paused)
        Log.i(TAG, "paused=$paused")
    }

    @SuppressLint("MissingPermission") // we run as shell UID, which holds the permission
    override fun startRecording(pfd: ParcelFileDescriptor, pcmPfd: ParcelFileDescriptor?, sampleRate: Int) {
        if (!recording.compareAndSet(false, true)) {
            Log.w(TAG, "startRecording ignored: already running")
            return
        }
        paused.set(false)
        captureThread = thread(name = "voicecall-capture") {
            try {
                captureLoop(pfd, pcmPfd, sampleRate)
            } catch (t: Throwable) {
                Log.e(TAG, "capture failed", t)
            } finally {
                recording.set(false)
                runCatching { pfd.close() }
                runCatching { pcmPfd?.close() }
            }
        }
    }

    override fun stopRecording() {
        recording.set(false)
        captureThread?.let { runCatching { it.join(2_000) } }
        captureThread = null
    }

    override fun destroy() {
        stopRecording()
    }

    // --- capture + encode -----------------------------------------------------

    private fun captureLoop(pfd: ParcelFileDescriptor, pcmPfd: ParcelFileDescriptor?, sampleRate: Int) {
        val pcmOut = pcmPfd?.let { java.io.FileOutputStream(it.fileDescriptor) }
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        val bufferSize = (minBuf * 2).coerceAtLeast(sampleRate) // ~0.5s headroom

        val record = buildVoiceCallRecord(sampleRate, channelConfig, audioEncoding, bufferSize)
        audioRecord = record

        // --- AAC encoder ---
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        record.startRecording()
        val pcm = ByteArray(bufferSize)
        var presentationUs = 0L

        try {
            while (recording.get()) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                // While paused, keep draining the mic buffer but don't encode it —
                // the paused stretch is simply omitted from the recording.
                if (paused.get()) continue
                // Live stream: raw PCM to the pipe (best-effort; drop on error).
                if (pcmOut != null) runCatching { pcmOut.write(pcm, 0, read) }
                drainInput(codec, pcm, read, presentationUs, endOfStream = false)
                presentationUs += read.toLong() * 1_000_000L / (sampleRate * 2) // 2 bytes/sample mono

                trackIndex = drainOutput(codec, muxer, bufferInfo, trackIndex) { started -> muxerStarted = started }
                if (trackIndex >= 0 && !muxerStarted) {
                    muxer.start(); muxerStarted = true
                }
            }
            // flush encoder
            drainInput(codec, ByteArray(0), 0, presentationUs, endOfStream = true)
            drainOutput(codec, muxer, bufferInfo, trackIndex) { /* final */ }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { pcmOut?.close() }
            audioRecord = null
        }
    }

    /**
     * Build an AudioRecord bound to VOICE_CALL, attributed to the shell package.
     *
     * setContext(...) on AudioRecord.Builder is a hidden method; we reach it via
     * HiddenApiBypass. Passing a [ShellContext] is what carries the shell
     * attribution into the native AudioRecord — running as UID 2000 alone is not
     * enough, the package attribution is also checked.
     */
    @SuppressLint("MissingPermission")
    private fun buildVoiceCallRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioEncoding: Int,
        bufferSize: Int,
    ): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(audioEncoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_CALL) // = 4; needs shell privilege
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)

        val base = context
        if (base != null) {
            // builder.setContext(ShellContext(base)) — hidden, invoked reflectively.
            runCatching {
                HiddenApiBypass.invoke(
                    AudioRecord.Builder::class.java,
                    builder,
                    "setContext",
                    ShellContext(base),
                )
            }.onFailure { Log.w(TAG, "setContext(ShellContext) unavailable; relying on UID only", it) }
        } else {
            Log.w(TAG, "no base context: VOICE_CALL may be refused without shell attribution")
            // TODO: if your device refuses capture here, feed a system Context into the
            // (Context) constructor from Shizuku, or fall back to the hidden 6-arg
            // AudioRecord(AudioAttributes, AudioFormat, int, int, Context, int) constructor.
        }
        return builder.build()
    }

    private fun drainInput(
        codec: MediaCodec,
        data: ByteArray,
        length: Int,
        presentationUs: Long,
        endOfStream: Boolean,
    ) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (length > 0) input.put(data, 0, length)
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        codec.queueInputBuffer(index, 0, length, presentationUs, flags)
    }

    private inline fun drainOutput(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        trackIndexIn: Int,
        onTrack: (Boolean) -> Unit,
    ): Int {
        var trackIndex = trackIndexIn
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> return trackIndex
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    onTrack(false)
                }
                out >= 0 -> {
                    val encoded = codec.getOutputBuffer(out)
                    if (encoded != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        trackIndex >= 0
                    ) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return trackIndex
                }
            }
        }
    }

    private fun exemptHiddenApis() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    companion object {
        private const val TAG = "RecorderUserService"
    }
}
