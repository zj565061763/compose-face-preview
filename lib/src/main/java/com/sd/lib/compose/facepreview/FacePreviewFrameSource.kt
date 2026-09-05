package com.sd.lib.compose.facepreview

/** 控制 [FacePreviewView] 使用原始预览帧还是预览区域截图 */
sealed interface FacePreviewFrameSource {
  /** 使用原始 NV21 预览帧 */
  data object Preview : FacePreviewFrameSource

  /** 按指定间隔使用预览区域截图，间隔必须大于零。 */
  data class PreviewSampled(
    val intervalMillis: Long,
  ) : FacePreviewFrameSource {
    init {
      require(intervalMillis > 0) { "intervalMillis must be greater than zero." }
    }

    companion object {
      /** 采样间隔为 200 毫秒的默认配置 */
      val Default = PreviewSampled(200)
    }
  }
}
