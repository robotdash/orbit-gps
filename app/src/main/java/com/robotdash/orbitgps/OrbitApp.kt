package com.robotdash.orbitgps

import android.app.Application

class OrbitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        NavCore.init(this)
    }
}
