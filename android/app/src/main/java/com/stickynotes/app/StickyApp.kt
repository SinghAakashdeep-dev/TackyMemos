package com.stickynotes.app

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

class StickyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FIREBASE_API_KEY.isNotEmpty()) {
            try {
                if (FirebaseApp.getApps(this).isEmpty()) {
                    FirebaseApp.initializeApp(
                        this,
                        FirebaseOptions.Builder()
                            .setApiKey(BuildConfig.FIREBASE_API_KEY)
                            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                            .build()
                    )
                }
                firebaseReady = true
            } catch (e: Exception) {
                Log.e("StickyApp", "Firebase init failed", e)
            }
        }
        val repo = NoteRepo.get(this)
        if (firebaseReady) {
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                if (auth.currentUser != null) repo.startListening() else repo.stopListening()
            }
        }
        SyncWorker.schedulePeriodic(this)
    }

    companion object {
        @Volatile
        var firebaseReady = false
            private set
    }
}
