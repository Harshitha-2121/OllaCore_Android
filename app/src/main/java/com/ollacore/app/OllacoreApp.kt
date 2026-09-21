package com.ollacore.app

import android.app.Application
import com.ollacore.app.data.di.AppContainer

class OllacoreApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
