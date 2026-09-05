package com.sd.lib.compose.facepreview

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.sd.lib.compose.camera.CameraDevicesState
import com.sd.lib.compose.camera.CameraFrame
import com.sd.lib.compose.camera.CameraMirrorMode
import com.sd.lib.compose.camera.CameraPreview
import com.sd.lib.compose.camera.CameraPreviewState
import com.sd.lib.compose.camera.FrameProcessor
import com.sd.lib.compose.camera.rememberCameraDevicesState
import com.sd.lib.compose.camera.rememberCameraPreviewState
import com.sd.lib.facedetector.FaceDetector
import java.util.concurrent.Executor

/**
 * 基于 [FaceDetector] 的 Compose 人脸预览。
 *
 * 人脸分析：
 *
 * - [faceDetector] 和 [FacePreviewStability.onFrame] 在相机分析线程同步调用，[FacePreviewState] 状态和外部回调在主线程发布。
 * - 按检测结果顺序使用第一个完全位于预览区域内的人脸，没有符合条件的人脸时清空人脸框。
 * - 达到稳定条件后暂停分析和预览采样；调用 [FacePreviewState.resetStability] 可重置稳定状态并继续识别。
 * - 暂停采样时保持当前相机会话，已经排队的采样仍可能完成。
 *
 * 稳定帧回调：
 *
 * - 稳定状态首次从 `false` 变为 `true` 时，[onStableFrame] 会在主线程收到 [FacePreviewFrame]。
 * - 完整帧和人脸区域图片均应用与预览一致的旋转；[isStableFrameMirrored] 为 `true` 时还会应用与预览一致的镜像。
 * - [faceImageExpansionRatio] 控制人脸区域图片相对检测框的扩张比例，默认为 `0.5` 且必须是有限非负数；
 *   例如 `0.1` 表示宽和高分别增加 10%，扩张区域超出原图时会裁到图片边界。
 * - 使用 [FacePreviewFrameSource.Preview] 时，图片不应用预览容器缩放、`ContentScale` 裁剪、[overlay] 或外部 [modifier] 裁剪。
 * - 使用 [FacePreviewFrameSource.PreviewSampled] 时，图片已应用 `ContentScale` 并裁到预览区域，但不包含镜像、[overlay] 或外部 [modifier] 内容。
 *
 * 摄像头选择与镜像：
 *
 * - [cameraId] 非空时按不透明标识精确选择；为 `null` 时使用当前枚举到的第一个摄像头。
 * - [mirrorMode] 控制预览显示和人脸框坐标的镜像，[isStableFrameMirrored] 单独控制稳定帧图片是否采用相同镜像。
 * - [devicesState] 可与同一界面的设备选择 UI 或其他预览共享，用于复用摄像头枚举、快照和手动刷新入口。
 *
 * 错误与恢复：
 *
 * - 人脸分析抛出 [Exception] 或发生 [OutOfMemoryError] 后暂停分析和预览采样，将故障发布到 [FacePreviewState.failure] 并调用 [onError]。
 * - 稳定帧无法转换为 [FacePreviewFrame] 时按可恢复的人脸分析故障处理。
 * - 其他 [Error] 会在恢复内部状态后继续抛出，不会转换为普通检测错误。
 * - 更换 [faceDetector]、调用 [FacePreviewState.retry] 或 [FacePreviewState.resetStability] 后恢复人脸分析。
 * - 相机发生不可恢复错误时，可调用 [cameraState] 的 [CameraPreviewState.retry] 重新绑定会话。
 *
 * 布局与状态：
 *
 * - [modifier] 决定预览布局区域；尺寸为零时暂停分析，恢复为非零尺寸后继续识别。
 * - [contentScale] 控制相机画面在预览区域中的缩放方式。
 * - [frameSource] 控制人脸检测使用原始预览帧还是按间隔截取的预览区域。
 * - [state] 暴露预览尺寸、人脸框和稳定状态，每个正在组合的预览必须使用独立实例。
 * - [overlay] 绘制在预览之上，默认显示 [state] 当前的人脸框。
 *
 * 权限：调用方必须在组合本组件前取得 `android.permission.CAMERA` 权限。
 */
@Composable
fun FacePreviewView(
  modifier: Modifier = Modifier,
  faceDetector: FaceDetector,
  state: FacePreviewState = rememberFacePreviewState(),
  cameraState: CameraPreviewState = rememberCameraPreviewState(),
  devicesState: CameraDevicesState = rememberCameraDevicesState(),
  cameraId: String? = null,
  mirrorMode: CameraMirrorMode = CameraMirrorMode.AUTO,
  contentScale: ContentScale = ContentScale.Crop,
  frameSource: FacePreviewFrameSource = FacePreviewFrameSource.Preview,
  isStableFrameMirrored: Boolean = false,
  faceImageExpansionRatio: Float = DefaultFaceImageExpansionRatio,
  onError: (Throwable) -> Unit = {},
  onStableFrame: (FacePreviewFrame) -> Unit,
  overlay: @Composable () -> Unit = { FacePreviewRectView(rect = state.faceRect.value) },
) {
  requireValidFaceImageExpansionRatio(faceImageExpansionRatio)

  val mainExecutor = remember {
    val mainHandler = Handler(Looper.getMainLooper())
    Executor { command -> mainHandler.post(command) }
  }
  val usesSampledFrames = frameSource is FacePreviewFrameSource.PreviewSampled
  val errorCallback by rememberUpdatedState(onError)
  val stableFrameCallback by rememberUpdatedState(onStableFrame)
  // 坐标或稳定帧配置变化时重建协调器，由 DisposableEffect 清空旧跟踪结果
  val frameCoordinator = remember(
    state,
    cameraState,
    devicesState,
    cameraId,
    mirrorMode,
    contentScale,
    usesSampledFrames,
    isStableFrameMirrored,
    faceImageExpansionRatio,
    mainExecutor,
  ) { DetectedFrameCoordinator(mainExecutor) }
  val previewResolution = cameraState.previewResolution.value
  val previewSize = state.previewSize.value
  val previewSizeChangedCallback = remember(state, frameCoordinator) {
    { size: IntSize -> handlePreviewSizeChanged(state, frameCoordinator, size) }
  }

  SideEffect {
    updateFaceDetectorIdentity(
      state = state,
      detector = faceDetector,
      frameCoordinator = frameCoordinator,
    )
  }

  LaunchedEffect(state, frameCoordinator, previewResolution) {
    if (previewResolution == IntSize.Zero) {
      handlePreviewUnavailable(state, frameCoordinator)
    }
  }

  DisposableEffect(frameCoordinator) {
    onDispose {
      frameCoordinator.close()
      state.resetTracking()
    }
  }

  Box(
    modifier = modifier
      .clipToBounds()
      .onSizeChanged(previewSizeChangedCallback),
    propagateMinConstraints = true,
  ) {
    // 会话输入变化时隔离内部 rememberUpdatedState，避免旧会话改用新协调器处理帧。
    key(cameraState, devicesState, cameraId) {
      val frameCallback: (CameraFrame) -> Unit = frameCallback@{ frame ->
        val frameTransformToken = frame.transformToken
        if (!cameraState.isFrameTransformCurrent(frameTransformToken)) return@frameCallback

        val initialAnalysisGeneration = state.currentAnalysisGeneration
        if (
          !frameCoordinator.prepareFrame(
            transformToken = frameTransformToken,
            analysisGeneration = initialAnalysisGeneration,
            isFrameCurrent = {
              state.isAnalysisGenerationCurrent(initialAnalysisGeneration) &&
                cameraState.isFrameTransformCurrent(frameTransformToken)
            },
          )
        ) {
          return@frameCallback
        }

        var coordinatorGeneration = frameCoordinator.currentGeneration
        state.runAnalysisFrameWithLease(
          initialAnalysisGeneration = initialAnalysisGeneration,
          onFailure = { analysisGeneration, error ->
            if (!error.isRecoverableFaceAnalysisFailure()) throw error
            frameCoordinator.submitError(
              generation = coordinatorGeneration,
              analysisGeneration = analysisGeneration,
              transformToken = frameTransformToken,
              error = error,
              isErrorCurrent = {
                state.isAnalysisGenerationCurrent(analysisGeneration) &&
                  cameraState.isFrameTransformCurrent(frameTransformToken)
              },
            ) { currentError ->
              state.stopDetectionAfterError(currentError)
              frameCoordinator.invalidate()
              errorCallback(currentError)
            }
          },
        ) { snapshot ->
          val frameHandle = frameCoordinator.beginFrame(faceDetector) ?: return@runAnalysisFrameWithLease
          coordinatorGeneration = frameHandle.generation
          val result = analyzeFrame(
            frame = frame,
            previewState = cameraState,
            faceDetector = faceDetector,
            state = state,
            analysisSnapshot = snapshot,
            frameCoordinator = frameCoordinator,
            frameHandle = frameHandle,
            isStableFrameMirrored = isStableFrameMirrored,
            faceImageExpansionRatio = faceImageExpansionRatio,
          ) ?: return@runAnalysisFrameWithLease

          createWithFailureCleanup(cleanup = { result.recycle() }) {
            frameCoordinator.submit(frameHandle, result) { currentResult ->
              if (!cameraState.isFrameTransformCurrent(currentResult.transformToken)) {
                state.resetTracking()
                return@submit
              }

              if (!state.publishAnalysisResult(currentResult.stateResult)) return@submit
              if (currentResult.stateResult.isStable) frameCoordinator.invalidate()

              if (currentResult.stateResult.isStable) {
                deliverStableFrame(currentResult.takeStableFrame(), stableFrameCallback)
              }
            }
          }
        }
      }
      val frameProcessor = createFacePreviewFrameProcessor(frameSource, state, frameCallback)
      CameraPreview(
        state = cameraState,
        devicesState = devicesState,
        cameraId = cameraId,
        mirrorMode = mirrorMode,
        contentScale = contentScale,
        onError = { error ->
          handlePreviewError(
            frameCoordinator = frameCoordinator,
            error = error,
            onError = { currentError -> errorCallback(currentError) },
          )
        },
        frameProcessor = frameProcessor,
      )
    }

    SideEffect {
      if (previewResolution != IntSize.Zero && !previewSize.isEmpty()) {
        frameCoordinator.markTransformReady()
      }
    }

    overlay()
  }
}

internal fun createFacePreviewFrameProcessor(
  source: FacePreviewFrameSource,
  state: FacePreviewState,
  onFrame: (CameraFrame) -> Unit,
): FrameProcessor {
  return when (source) {
    FacePreviewFrameSource.Preview -> FrameProcessor.Preview(onFrame)
    is FacePreviewFrameSource.PreviewSampled -> {
      // 保持采样模式，用最大间隔暂停截图，以保留当前相机会话。
      val intervalMillis = if (state.isStable.value || state.failure.value != null) Long.MAX_VALUE else source.intervalMillis
      FrameProcessor.PreviewSampled(intervalMillis, onFrame)
    }
  }
}

internal fun updateFaceDetectorIdentity(
  state: FacePreviewState,
  detector: Any,
  frameCoordinator: DetectedFrameCoordinator,
  invalidateFrames: () -> Unit = frameCoordinator::invalidate,
) {
  val detectorChanged = state.updateDetectorIdentity(detector)
  val coordinatorDetectorChanged = frameCoordinator.updateDetectorIdentity(detector)
  if (detectorChanged || coordinatorDetectorChanged) invalidateFrames()
  if (!detectorChanged) return
  // 失效期间已开始的帧仍可能把 shouldDetect 置为 false，最后再复位以保证可以继续检测。
  state.resetTracking()
}

internal fun handlePreviewError(
  frameCoordinator: DetectedFrameCoordinator,
  error: Throwable,
  onError: (Throwable) -> Unit,
) {
  // CameraPreview 允许旧会话已经开始的帧延迟报错，错误本身不能失效新会话。
  if (!frameCoordinator.isActive) return
  onError(error)
}

internal fun handlePreviewUnavailable(
  state: FacePreviewState,
  frameCoordinator: DetectedFrameCoordinator,
) {
  if (!frameCoordinator.isActive) return
  frameCoordinator.invalidateTransform()
  state.resetTracking()
}

internal fun handlePreviewSizeChanged(
  state: FacePreviewState,
  frameCoordinator: DetectedFrameCoordinator,
  size: IntSize,
) {
  if (state.previewSize.value == size.toSize()) return

  frameCoordinator.invalidateTransform()
  state.updatePreviewSize(size)
  state.resetTracking()
}

private const val DefaultFaceImageExpansionRatio = 0.5f
