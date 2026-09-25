package com.example.filelink

import android.app.Application
import android.util.Log
import org.webrtc.PeerConnectionFactory

class FilelinkApp : Application() {

    companion object {
        private const val TAG = "FilelinkApp"
        lateinit var instance: FilelinkApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Prevent unexpected app crashes with an uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Safely initialize WebRTC native library on startup
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            Log.d(TAG, "WebRTC PeerConnectionFactory successfully initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize WebRTC PeerConnectionFactory", e)
        }
    }
}
