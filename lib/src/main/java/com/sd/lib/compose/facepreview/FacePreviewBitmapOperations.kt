package com.sd.lib.compose.facepreview

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.sd.lib.compose.camera.CameraFrame
import kotlin.math.ceil
import kotlin.math.floor
import android.graphics.Rect as AndroidRect

internal fun CameraFrame.createFacePreviewFrame(
  faceRect: RectF,
  previewMatrix: Matrix,
  isMirrored: Boolean,
  faceImageExpansionRatio: Float,
): FacePreviewFrame? {
  return when (this) {
    is CameraFrame.Preview -> {
      val bitmap = toBitmap() ?: return null
      try {
        bitmap.createFacePreviewFrame(
          faceRect = faceRect,
          previewMatrix = previewMatrix,
          isMirrored = isMirrored,
          faceImageExpansionRatio = faceImageExpansionRatio,
        )
      } finally {
        bitmap.recycle()
      }
    }
    is CameraFrame.PreviewSampled -> data.createFacePreviewFrame(
      faceRect = faceRect,
      previewMatrix = previewMatrix,
      isMirrored = isMirrored,
      faceImageExpansionRatio = faceImageExpansionRatio,
    )
  }
}

internal fun requireStableFacePreviewFrame(frame: FacePreviewFrame?): FacePreviewFrame {
  return checkNotNull(frame) { "Failed to convert the stable camera frame to FacePreviewFrame." }
}

/** 回调未能接收稳定帧时收回 Bitmap 所有权，再保留原始异常语义。 */
internal fun deliverStableFrame(
  frame: FacePreviewFrame,
  callback: (FacePreviewFrame) -> Unit,
) {
  try {
    callback(frame)
  } catch (error: Throwable) {
    try {
      frame.recycle()
    } catch (recycleError: Throwable) {
      if (error !== recycleError) error.addSuppressed(recycleError)
    }
    throw error
  }
}

/** 仅在创建成功后转移资源所有权；创建失败时先清理并保留原始异常。 */
internal inline fun <T> createWithFailureCleanup(
  cleanup: () -> Unit,
  create: () -> T,
): T {
  try {
    return create()
  } catch (error: Throwable) {
    try {
      cleanup()
    } catch (cleanupError: Throwable) {
      if (error !== cleanupError) error.addSuppressed(cleanupError)
    }
    throw error
  }
}

internal fun Bitmap.createFacePreviewFrame(
  faceRect: RectF,
  previewMatrix: Matrix,
  isMirrored: Boolean,
  faceImageExpansionRatio: Float,
): FacePreviewFrame? {
  requireValidFaceImageExpansionRatio(faceImageExpansionRatio)

  val image = createFacePreviewBitmap(
    faceRect = RectF(0f, 0f, width.toFloat(), height.toFloat()),
    previewMatrix = previewMatrix,
    isMirrored = isMirrored,
  ) ?: return null
  val faceImage = try {
    createFacePreviewBitmap(
      faceRect = faceRect.expandByRatio(faceImageExpansionRatio),
      previewMatrix = previewMatrix,
      isMirrored = isMirrored,
    )
  } catch (error: Throwable) {
    image.recycle()
    throw error
  }
  if (faceImage == null) {
    image.recycle()
    return null
  }
  return createWithFailureCleanup(
    cleanup = { recycleFacePreviewBitmaps(image, faceImage) },
  ) {
    FacePreviewFrame(
      image = image,
      faceImage = faceImage,
    )
  }
}

internal fun requireValidFaceImageExpansionRatio(ratio: Float) {
  require(ratio.isFinite() && ratio >= 0f) { "faceImageExpansionRatio must be finite and non-negative." }
}

/** 以中心点为基准扩张，使宽和高分别增加指定比例。 */
private fun RectF.expandByRatio(ratio: Float): RectF {
  val horizontalExpansion = width() * ratio / 2f
  val verticalExpansion = height() * ratio / 2f
  return RectF(
    left - horizontalExpansion,
    top - verticalExpansion,
    right + horizontalExpansion,
    bottom + verticalExpansion,
  )
}

internal fun Bitmap.createFacePreviewBitmap(
  faceRect: RectF,
  previewMatrix: Matrix,
  isMirrored: Boolean,
): Bitmap? {
  val cropRect = faceRect.toBitmapCrop(
    bitmapWidth = width,
    bitmapHeight = height,
  ) ?: return null
  val orientationMatrix = createPreviewOrientationMatrix(
    matrix = previewMatrix,
    isMirrored = isMirrored,
  ) ?: return null
  val transformedBitmap = Bitmap.createBitmap(
    this,
    cropRect.left,
    cropRect.top,
    cropRect.width(),
    cropRect.height(),
    orientationMatrix,
    true,
  )
  return if (transformedBitmap === this) {
    checkNotNull(copy(config ?: Bitmap.Config.ARGB_8888, false))
  } else {
    transformedBitmap
  }
}

/** 从预览矩阵中保留旋转和可选镜像，去掉预览容器引入的缩放和位移。 */
private fun createPreviewOrientationMatrix(
  matrix: Matrix,
  isMirrored: Boolean,
): Matrix? {
  val values = FloatArray(9).also(matrix::getValues)
  val scaleX = kotlin.math.sqrt(
    values[Matrix.MSCALE_X] * values[Matrix.MSCALE_X] +
      values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y]
  )
  val scaleY = kotlin.math.sqrt(
    values[Matrix.MSKEW_X] * values[Matrix.MSKEW_X] +
      values[Matrix.MSCALE_Y] * values[Matrix.MSCALE_Y]
  )
  if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) return null

  val normalizedScaleX = values[Matrix.MSCALE_X] / scaleX
  val normalizedSkewX = values[Matrix.MSKEW_X] / scaleY
  val normalizedSkewY = values[Matrix.MSKEW_Y] / scaleX
  val normalizedScaleY = values[Matrix.MSCALE_Y] / scaleY
  val determinant = normalizedScaleX * normalizedScaleY - normalizedSkewX * normalizedSkewY
  val outputScaleX = if (!isMirrored && determinant < 0f) -1f else 1f

  return Matrix().apply {
    setValues(
      floatArrayOf(
        normalizedScaleX * outputScaleX,
        normalizedSkewX * outputScaleX,
        0f,
        normalizedSkewY,
        normalizedScaleY,
        0f,
        0f,
        0f,
        1f,
      )
    )
  }
}

private fun RectF.toBitmapCrop(
  bitmapWidth: Int,
  bitmapHeight: Int,
): AndroidRect? {
  if (bitmapWidth <= 0 || bitmapHeight <= 0) return null

  val left = floor(left.toDouble()).toInt().coerceIn(0, bitmapWidth)
  val top = floor(top.toDouble()).toInt().coerceIn(0, bitmapHeight)
  val right = ceil(right.toDouble()).toInt().coerceIn(0, bitmapWidth)
  val bottom = ceil(bottom.toDouble()).toInt().coerceIn(0, bitmapHeight)
  if (right <= left || bottom <= top) return null

  return AndroidRect(left, top, right, bottom)
}
