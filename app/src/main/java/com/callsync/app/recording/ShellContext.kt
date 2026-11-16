package com.callsync.app.recording

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process

/**
 * A Context that reports the shell package/UID as its attribution source.
 *
 * Running inside the Shizuku user service means we are already UID 2000, which
 * holds CAPTURE_AUDIO_OUTPUT. But AudioRecord also checks the *attribution
 * source* of the Context it is built with — if that still points at our own
 * app package, the capture is refused. Wrapping the base context so it reports
 * `com.android.shell` (the package that owns the shell UID) is what makes
 * AudioRecord accept the VOICE_CALL source.
 */
class ShellContext(base: Context) : ContextWrapper(base) {

    override fun getOpPackageName(): String = SHELL_PACKAGE

    override fun getAttributionSource(): AttributionSource {
        // AttributionSource.Builder is public API from API 31.
        return AttributionSource.Builder(Process.myUid())
            .setPackageName(SHELL_PACKAGE)
            .build()
    }

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"

        init {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "ShellContext attribution requires API 31+"
            }
        }
    }
}
