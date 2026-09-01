package com.sd.demo.compose.facepreview

import android.content.Context
import android.graphics.Bitmap
import com.sd.lib.facedetector.FaceDetection
import com.sd.lib.facedetector.FaceDetector
import kotlin.time.measureTimedValue

object AppFaceDetector : FaceDetector {
  private val _detector = FaceDetector.create()

  override fun init(context: Context) {
    val result = measureTimedValue { _detector.init(context) }
    logMsg { "init time:${result.duration.inWholeMilliseconds}" }
  }

  override fun detect(image: Bitmap, rotationDegrees: Int): List<FaceDetection> {
    return _detector.detect(image, rotationDegrees)
  }

  override fun detect(
    nv21: ByteArray,
    width: Int,
    height: Int,
    rotationDegrees: Int,
  ): List<FaceDetection> {
    return _detector.detect(nv21, width, height, rotationDegrees)
  }

  override fun close() {
    logMsg { "close ignored" }
  }
}
