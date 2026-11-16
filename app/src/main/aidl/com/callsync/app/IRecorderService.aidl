package com.callsync.app;

import android.os.ParcelFileDescriptor;

/**
 * Implemented by the Shizuku UserService that runs under the shell UID (2000).
 * The app binds to this across the process boundary and drives it from the
 * call-state service.
 */
interface IRecorderService {
    /**
     * Begin capturing VOICE_CALL audio and muxing AAC into the given fd.
     * @param pfd        output file, opened write-only by the app and handed over
     * @param sampleRate capture sample rate in Hz (e.g. 44100)
     */
    /**
     * @param pfd      output .m4a file (write end)
     * @param pcmPfd   optional pipe write end for live raw PCM (linear16 mono);
     *                 null disables live streaming
     * @param sampleRate capture sample rate in Hz
     */
    void startRecording(in ParcelFileDescriptor pfd, in ParcelFileDescriptor pcmPfd, int sampleRate) = 1;

    /** Stop capture and finalise the .m4a container. Safe to call when idle. */
    void stopRecording() = 2;

    boolean isRecording() = 3;

    /** Pause/resume capture in place (paused audio is dropped, not stored). */
    void setPaused(boolean paused) = 4;

    /**
     * Shizuku calls this when it tears the user service down. The transaction
     * code is fixed by the Shizuku protocol — do not change it.
     */
    void destroy() = 16777114;
}
