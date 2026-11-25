package com.telephoto.editor

import android.app.Application
import org.telegram.messenger.ApplicationLoader

class TelePhotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogManager.install(this)
        AppTheme.init(this)
        AppLocale.init(this)
        ApplicationLoader.init(this)
        UpdateChecker.schedulePeriodicCheck(this)
    }
}
