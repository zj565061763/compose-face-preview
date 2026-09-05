package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.sd.lib.compose.camera.CameraFrame

/**
 * 传给 [FacePreviewStability.onFrame] 的分析帧。
 *
 * [cameraFrame] 是当前选择的相机帧，只能在 `onFrame` 回调执行期间读取；调用方不得保存、关闭或回收它。
 * 需要异步处理时，应在回调内复制所需数据。
 * [rotationDegrees] 表示将相机帧顺时针旋转多少度后可以正立显示；预览采样帧已经正立，因此该值为 `0`。
 * [imageFaceRect] 使用相机帧的像素坐标，[faceRect] 使用 Compose 预览布局坐标；
 * 没有完全位于预览区域内的人脸时两个矩形均为 [Rect.Zero]。
 */
interface FacePreviewAnalysisFrame {
  val cameraFrame: CameraFrame
  val rotationDegrees: Int
  val imageFaceRect: Rect
  val faceRect: Rect

  /** 当前分析帧对应的预览布局尺寸，单位为像素。 */
  val previewSize: Size
}

internal class CameraFacePreviewAnalysisFrame(
  override val cameraFrame: CameraFrame,
  override val rotationDegrees: Int,
  override val imageFaceRect: Rect,
  override val faceRect: Rect,
  override val previewSize: Size,
) : FacePreviewAnalysisFrame
