package com.callsync.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.callsync.app.BuildConfig
import com.callsync.app.IRecorderService
import com.callsync.app.recording.RecorderUserService
import rikka.shizuku.Shizuku

/**
 * Owns the Shizuku permission handshake and the bound [IRecorderService].
 *
 * Shizuku itself must already be running on the phone — started once over
 * Wireless Debugging / ADB. That is a manual step and cannot be automated from
 * inside the app.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 4211

    @Volatile var recorder: IRecorderService? = null
        private set

    // daemon(true): keep the recorder user service alive persistently so it is
    // ALWAYS ready the instant a call starts, instead of being torn down between
    // app opens (which caused "not connected" until a fresh ~1s bind completed).
    // One stable daemon is reused across rebinds; it holds no microphone until
    // startRecording() is actually called.
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, RecorderUserService::class.java.name)
    ).daemon(true)
        .processNameSuffix("recorder")
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recorder = if (binder != null && binder.pingBinder()) {
                IRecorderService.Stub.asInterface(binder)
            } else null
            com.callsync.app.service.RecorderState.recorderReady.value = recorder != null
            Log.i(TAG, "user service connected: ${recorder != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            com.callsync.app.service.RecorderState.recorderReady.value = false
            Log.i(TAG, "user service disconnected")
        }
    }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Register once (on app start) so a Shizuku started *after* us — or one that
     * restarts, or comes back after an app reinstall — gets the recorder re-bound
     * automatically. Previously this only logged, so after a reinstall/Shizuku
     * restart the recorder stayed null and calls were silently not recorded.
     */
    fun registerListeners() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                Log.i(TAG, "shizuku binder received -> (re)binding recorder")
                if (hasPermission()) bind()
            }
            Shizuku.addBinderDeadListener {
                recorder = null
                com.callsync.app.service.RecorderState.recorderReady.value = false
                Log.w(TAG, "shizuku binder dead")
            }
        }
    }

    /** True once the recorder user service is connected and pingable. */
    fun isRecorderReady(): Boolean =
        runCatching { recorder?.asBinder()?.pingBinder() == true }.getOrDefault(false)

    /**
     * Best-effort: make sure the recorder is bound. Safe to call repeatedly — if
     * already bound it is a no-op; otherwise it kicks off a (re)bind. Binding is
     * async, so the very next call may still miss, but it self-heals for the one
     * after.
     */
    fun ensureBound() {
        if (isRecorderReady()) return
        if (isShizukuReady() && hasPermission()) bind()
    }

    private fun isShizukuInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }
            .getOrDefault(false)

    /** Human-readable state for the UI, so we stop conflating distinct failures. */
    fun diagnose(context: Context): String = when {
        !isShizukuInstalled(context) -> "Shizuku app is not installed on this phone"
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
            "Shizuku installed but not running — start it (Wireless Debugging)"
        runCatching { Shizuku.getVersion() }.getOrDefault(0) < 11 ->
            "Shizuku version too old (need v11+)"
        hasPermission() -> "Shizuku ready and permission granted"
        else -> "Shizuku running — permission not yet granted"
    }

    fun isShizukuReady(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.getVersion() >= 11 }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** Returns true if already granted; otherwise fires the Shizuku prompt. */
    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (!isShizukuReady()) { onResult(false); return }
        if (hasPermission()) { onResult(true); return }
        Shizuku.addRequestPermissionResultListener(
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == PERMISSION_REQUEST_CODE) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }
        )
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    fun bind() {
        if (!isShizukuReady() || !hasPermission()) {
            Log.w(TAG, "cannot bind: shizuku not ready or no permission")
            return
        }
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { Log.e(TAG, "bindUserService failed", it) }
    }

    fun unbind() {
        // remove=false: drop our connection but KEEP the daemon alive, so when the
        // foreground service restarts (START_STICKY, reboot) the recorder is
        // already running and reconnects instantly instead of cold-spawning.
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, false) }
        recorder = null
        com.callsync.app.service.RecorderState.recorderReady.value = false
    }

    /** Fully tear down the recorder daemon — only for an explicit "stop/off". */
    fun unbindAndRemove() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        recorder = null
        com.callsync.app.service.RecorderState.recorderReady.value = false
    }

    fun warmUp(context: Context) {
        registerListeners()
    }
}
