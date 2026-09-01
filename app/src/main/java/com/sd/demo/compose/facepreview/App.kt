package com.sd.demo.compose.facepreview

import android.app.Application

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    AppFaceDetector.init(this)
  }
}