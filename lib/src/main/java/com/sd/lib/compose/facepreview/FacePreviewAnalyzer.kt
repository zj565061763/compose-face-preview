package com.sd.lib.compose.facepreview

import android.graphics.RectF
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.sd.lib.compose.camera.CameraFrame
import com.sd.lib.compose.camera.CameraFrameTransformToken
import com.sd.lib.compose.camera.CameraPreviewState
import com.sd.lib.facedetector.FaceDetector

internal fun analyzeFrame(
  frame: CameraFrame,
  previewState: CameraPreviewState,
  faceDetector: FaceDetector,
  state: FacePreviewState,
  analysisSnapshot: FacePreviewAnalysisSnapshot,
  frameCoordinator: DetectedFrameCoordinator,
  frameHandle: DetectedFrameHandle,
  isStableFrameMirrored: Boolean,
  faceImageExpansionRatio: Float,
): AnalyzedFaceFrame? {
  if (previewState.createTransformToPreview(frame) == null) return null
  val rotationDegrees = frame.rotationDegrees
  val detections = when (frame) {
    is CameraFrame.Preview -> faceDetector.detect(
      nv21 = frame.data,
      width = frame.width,
      height = frame.height,
      rotationDegrees = rotationDegrees,
    )
    is CameraFrame.PreviewSampled -> faceDetector.detect(frame.data, rotationDegrees)
  }

  // 推理期间预览可能已经重绑，重新取矩阵以丢弃过期请求。
  val previewMatrix = previewState.createTransformToPreview(frame) ?: return null

  val faceCandidates = detections.map { detection ->
    val rawFaceRect = RectF(detection.faceBox)
    val previewFaceRect = RectF(rawFaceRect).apply { previewMatrix.mapRect(this) }
    DetectedFaceCandidate(
      rawFaceRect = rawFaceRect,
      previewFaceRect = previewFaceRect,
    )
  }

  val detectedFace = faceCandidates.firstFullyVisibleFace(analysisSnapshot.previewSize)
  val transformChanged = frameCoordinator.updateTransform(
    frameHandle = frameHandle,
    token = frame.transformToken,
    previewMatrix = previewMatrix,
  ) ?: return null

  val analysisFrame = CameraFacePreviewAnalysisFrame(
    cameraFrame = frame,
    rotationDegrees = rotationDegrees,
    imageFaceRect = detectedFace?.rawFaceRect?.toComposeRect() ?: Rect.Zero,
    faceRect = detectedFace?.previewFaceRect?.toComposeRect() ?: Rect.Zero,
    previewSize = analysisSnapshot.previewSize,
  )
  val stateResult = state.analyzeFrame(
    snapshot = analysisSnapshot,
    frame = analysisFrame,
    resetForTransformChange = transformChanged,
  ) ?: return null

  val stableFrame = if (stateResult.isStable) {
    val face = checkNotNull(detectedFace) {
      "Stable analysis result does not contain a detected face."
    }
    requireStableFacePreviewFrame(
      frame.createFacePreviewFrame(
        faceRect = face.rawFaceRect,
        previewMatrix = previewMatrix,
        isMirrored = isStableFrameMirrored,
        faceImageExpansionRatio = faceImageExpansionRatio,
      )
    )
  } else {
    null
  }

  return createWithFailureCleanup(
    cleanup = { stableFrame?.recycle() },
  ) {
    AnalyzedFaceFrame(
      stateResult = stateResult,
      transformToken = frame.transformToken,
      stableFrame = stableFrame,
    )
  }
}

internal fun Throwable.isRecoverableFaceAnalysisFailure(): Boolean {
  return this is Exception || this is OutOfMemoryError
}

internal data class DetectedFaceCandidate(
  val rawFaceRect: RectF,
  val previewFaceRect: RectF,
)

internal fun List<DetectedFaceCandidate>.firstFullyVisibleFace(
  previewSize: Size,
): DetectedFaceCandidate? {
  return firstOrNull { candidate -> candidate.previewFaceRect.isFullyVisibleIn(previewSize) }
}

internal class AnalyzedFaceFrame(
  val stateResult: FacePreviewAnalysisResult,
  val transformToken: CameraFrameTransformToken,
  stableFrame: FacePreviewFrame?,
) {
  init {
    check(!stateResult.isStable || stableFrame != null) {
      "Stable analysis result must contain a FacePreviewFrame."
    }
  }

  private var _stableFrame = stableFrame

  fun takeStableFrame(): FacePreviewFrame {
    val stableFrame = checkNotNull(_stableFrame) {
      "Stable analysis result does not contain a FacePreviewFrame."
    }
    _stableFrame = null
    return stableFrame
  }

  fun recycle() {
    _stableFrame?.recycle()
    _stableFrame = null
  }
}

private fun RectF.toComposeRect(): Rect {
  return Rect(left = left, top = top, right = right, bottom = bottom)
}
