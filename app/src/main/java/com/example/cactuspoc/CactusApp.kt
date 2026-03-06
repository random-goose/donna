package com.example.cactuspoc

import android.app.Application
import android.util.Log
import com.cactus.CactusContextInitializer

class CactusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Required by Cactus SDK (Agent 2)
        CactusContextInitializer.initialize(this)
        Log.d("Donna", "App started")
    }
}
