package com.telephoto.editor

import android.app.Application
import org.telegram.messenger.ApplicationLoader

class TelePhotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApplicationLoader.init(this)
    }
}
