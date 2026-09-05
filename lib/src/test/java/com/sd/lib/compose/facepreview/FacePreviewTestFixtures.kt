package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.sd.lib.compose.camera.CameraFrame

internal fun stateWithResult(result: Boolean): FacePreviewState {
  return FacePreviewState(
    stability = statelessStability { result },
  )
}

internal fun statelessStability(
  onFrame: (FacePreviewAnalysisFrame) -> Boolean,
): FacePreviewStability {
  return object : FacePreviewStability {
    override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = onFrame(frame)

    override fun reset() = Unit
  }
}

internal fun syntheticFrame(
  faceRect: Rect,
  previewSize: Size,
  imageFaceRect: Rect = faceRect,
): FacePreviewAnalysisFrame {
  return object : FacePreviewAnalysisFrame {
    override val cameraFrame: CameraFrame
      get() = error("Synthetic frame does not contain a CameraFrame.")
    override val rotationDegrees = 0
    override val imageFaceRect = imageFaceRect
    override val faceRect = faceRect
    override val previewSize = previewSize
  }
}

internal class TestMonotonicClock {
  private var _currentTimeMillis = 0L

  fun now(): Long = _currentTimeMillis

  fun advanceBy(durationMillis: Long) {
    _currentTimeMillis += durationMillis
  }
}
