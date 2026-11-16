package com.callsync.app

import android.app.Application
import com.callsync.app.data.AppSettings

class CallSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
