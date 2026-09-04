package com.sd.lib.compose.facepreview

import android.graphics.Bitmap

/**
 * 达到稳定条件的人脸帧。
 *
 * [image] 是完整相机分析帧，[faceImage] 包含人脸框及 [FacePreviewView] 的 `faceImageExpansionRatio` 参数指定的扩张区域。
 * 两张图片均应用与预览一致的旋转，是否应用预览镜像由 [FacePreviewView] 的 `isStableFrameMirrored` 参数决定。
 * 使用原始预览帧时不应用 `ContentScale` 裁剪；使用预览采样帧时已经裁到预览区域。
 * 图片均不包含 overlay 或外部 Modifier 内容。
 * 两个 Bitmap 的所有权均属于接收方，使用完毕后必须调用 [recycle] 或分别回收。
 */
class FacePreviewFrame(
  val image: Bitmap,
  val faceImage: Bitmap,
) {
  /** 回收此稳定帧持有的全部 Bitmap */
  fun recycle() {
    recycleFacePreviewBitmaps(image, faceImage)
  }
}

/** 始终尝试回收两张图片，并保留第一次回收失败。 */
internal fun recycleFacePreviewBitmaps(
  image: Bitmap,
  faceImage: Bitmap,
) {
  var failure: Throwable? = null
  try {
    image.recycle()
  } catch (error: Throwable) {
    failure = error
  }

  if (faceImage !== image) {
    try {
      faceImage.recycle()
    } catch (error: Throwable) {
      val currentFailure = failure
      if (currentFailure == null) {
        failure = error
      } else if (currentFailure !== error) {
        currentFailure.addSuppressed(error)
      }
    }
  }

  val recycleFailure = failure
  if (recycleFailure != null) throw recycleFailure
}
