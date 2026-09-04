package com.sd.lib.compose.facepreview

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.sd.lib.compose.camera.CameraDevicesState
import com.sd.lib.compose.camera.CameraFrame
import com.sd.lib.compose.camera.CameraFrameTransformToken
import com.sd.lib.compose.camera.CameraMirrorMode
import com.sd.lib.compose.camera.CameraPreview
import com.sd.lib.compose.camera.CameraPreviewState
import com.sd.lib.compose.camera.FrameProcessor
import com.sd.lib.compose.camera.rememberCameraDevicesState
import com.sd.lib.compose.camera.rememberCameraPreviewState
import com.sd.lib.facedetector.FaceDetector
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import android.graphics.Rect as AndroidRect

/**
 * 基于 [FaceDetector] 的 Compose 人脸预览。
 *
 * 人脸分析：
 *
 * - [faceDetector] 和 [FacePreviewStability.onFrame] 在相机分析线程同步调用，[FacePreviewState] 状态和外部回调在主线程发布。
 * - 按检测结果顺序使用第一个完全位于预览区域内的人脸，没有符合条件的人脸时清空人脸框。
 * - 达到稳定条件后暂停分析；调用 [FacePreviewState.resetStability] 可重置稳定状态并继续识别。
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
 * - 人脸分析抛出 [Exception] 或发生 [OutOfMemoryError] 后暂停分析，将故障发布到 [FacePreviewState.failure] 并调用 [onError]。
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
      val frameCallback: (CameraFrame) -> Unit = frameCallback@ { frame ->
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
        var analyzedFrame: AnalyzedFaceFrame? = null
        state.runAnalysisFrameWithLease(
          initialAnalysisGeneration = initialAnalysisGeneration,
          onFailure = { analysisGeneration, error ->
            analyzedFrame?.also { currentFrame ->
              try {
                currentFrame.recycle()
              } catch (recycleError: Throwable) {
                if (error !== recycleError) error.addSuppressed(recycleError)
              }
            }
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
          analyzedFrame = result

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
          analyzedFrame = null
        }
      }
      val frameProcessor = when (val source = frameSource) {
        FacePreviewFrameSource.Preview -> FrameProcessor.Preview { frame -> frameCallback(frame) }
        is FacePreviewFrameSource.PreviewSampled -> FrameProcessor.PreviewSampled(source.intervalMillis) { frame ->
          frameCallback(frame)
        }
      }
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

internal fun Throwable.isRecoverableFaceAnalysisFailure(): Boolean {
  return this is Exception || this is OutOfMemoryError
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

/** 丢弃过期工作，合并待发布帧，并让分析错误优先终止当前帧 generation。 */
internal data class DetectedFrameHandle(
  val sequence: Long,
  val generation: Long,
)

private class DetectedFrameErrorHandle(
  val generation: Long,
  val analysisGeneration: Long,
  val transformToken: CameraFrameTransformToken,
) {
  var isDeliveryClaimed = false
}

internal class DetectedFrameCoordinator(
  executor: Executor,
) {
  private val _isActive = AtomicBoolean(true)
  private val _isTransformReady = AtomicBoolean(false)
  private val _sequence = AtomicLong()
  private val _generation = AtomicLong()
  private val _detectorIdentity = AtomicReference<Any?>()
  private val _errorStateLock = Any()
  private val _frameDispatcher = ConflatedExecutorDispatcher<AnalyzedFaceFrame>(
    executor = executor,
    onDispose = AnalyzedFaceFrame::recycle,
  )
  private val _errorDispatcher = ConflatedExecutorDispatcher<Throwable>(executor)
  @Volatile
  private var _pendingError: DetectedFrameErrorHandle? = null
  private var _transformToken: CameraFrameTransformToken? = null
  private var _previewMatrixValues: FloatArray? = null

  val isActive: Boolean
    get() = _isActive.get()

  /** 当前预览变换已经发布且没有待处理错误时，分析线程才可以开始处理帧。 */
  val canAnalyzeFrame: Boolean
    get() = canAnalyzeFrameWithoutErrorBarrier() && _pendingError == null

  val currentGeneration: Long
    get() = _generation.get()

  /** 在 CameraPreview 的布局和镜像 SideEffect 完成后开放分析 */
  fun markTransformReady() {
    if (_isActive.get()) _isTransformReady.set(true)
  }

  /** 绑定当前检测器；返回是否从另一个已经绑定的实例切换。 */
  fun updateDetectorIdentity(detector: Any): Boolean {
    val previousDetector = _detectorIdentity.getAndSet(detector)
    return previousDetector != null && previousDetector !== detector
  }

  /** 丢弃已经不属于当前分析 generation 或预览变换的待发布错误 */
  fun prepareFrame(
    transformToken: CameraFrameTransformToken,
    analysisGeneration: Long,
    isFrameCurrent: () -> Boolean,
  ): Boolean {
    synchronized(_errorStateLock) {
      if (!canAnalyzeFrameWithoutErrorBarrier()) return false

      val pendingError = _pendingError ?: return true
      if (pendingError.isDeliveryClaimed) return false
      if (
        pendingError.analysisGeneration == analysisGeneration &&
        pendingError.transformToken.isSameTransform(transformToken)
      ) {
        return false
      }
      if (!isFrameCurrent()) return false

      _pendingError = null
      _errorDispatcher.clear()
      return canAnalyzeFrameWithoutErrorBarrier()
    }
  }

  fun beginFrame(detector: Any): DetectedFrameHandle? {
    if (_detectorIdentity.get() !== detector || !canAnalyzeFrame) return null
    val generation = _generation.get()
    val frameHandle = DetectedFrameHandle(
      sequence = advance(),
      generation = generation,
    )
    return frameHandle.takeIf {
      _detectorIdentity.get() === detector && canAnalyzeFrame && isCurrentFrame(frameHandle)
    }
  }

  fun invalidate() {
    synchronized(_errorStateLock) {
      advance()
      _generation.incrementAndGet()
      _pendingError = null
      _errorDispatcher.clear()
    }
  }

  /** 失效待发布帧，并阻止新帧使用尚未完成更新的预览矩阵。 */
  fun invalidateTransform() {
    _isTransformReady.set(false)
    invalidate()
  }

  fun close() {
    if (_isActive.compareAndSet(true, false)) {
      _isTransformReady.set(false)
      invalidate()
    }
  }

  /** 在分析线程记录最新预览变换；帧已经过期时返回 `null`。 */
  fun updateTransform(
    frameHandle: DetectedFrameHandle,
    token: CameraFrameTransformToken,
    previewMatrix: Matrix,
  ): Boolean? {
    if (!isCurrentFrame(frameHandle)) return null
    val previousToken = _transformToken
    val previousMatrixValues = _previewMatrixValues
    val matrixValues = FloatArray(9).also(previewMatrix::getValues)
    if (!isCurrentFrame(frameHandle)) return null
    _transformToken = token
    _previewMatrixValues = matrixValues
    return previousToken != null &&
      (!previousToken.isSameTransform(token) ||
        previousMatrixValues == null ||
        !previousMatrixValues.contentEquals(matrixValues))
  }

  private fun advance(): Long {
    val frameSequence = _sequence.incrementAndGet()
    _frameDispatcher.clear()
    return frameSequence
  }

  fun submit(
    frameHandle: DetectedFrameHandle,
    frame: AnalyzedFaceFrame,
    onFrame: (AnalyzedFaceFrame) -> Unit,
  ) {
    _frameDispatcher.submit(
      value = frame,
      isCurrent = { isCurrentFrame(frameHandle) },
      onValue = onFrame,
    )
  }

  fun submitError(
    generation: Long,
    analysisGeneration: Long,
    transformToken: CameraFrameTransformToken,
    error: Throwable,
    isErrorCurrent: () -> Boolean = { true },
    onError: (Throwable) -> Unit,
  ): Boolean {
    val errorHandle = beginError(
      generation = generation,
      analysisGeneration = analysisGeneration,
      transformToken = transformToken,
    ) ?: return false

    try {
      _errorDispatcher.submit(
        value = error,
        isCurrent = { isCurrentGeneration(generation) && _pendingError === errorHandle },
        onValue = onValue@ { currentError ->
          if (!claimErrorDelivery(errorHandle, isErrorCurrent)) return@onValue
          try {
            onError(currentError)
          } finally {
            completeError(errorHandle)
          }
        },
      )
    } catch (dispatchError: Throwable) {
      completeError(errorHandle)
      throw dispatchError
    }
    return true
  }

  private fun beginError(
    generation: Long,
    analysisGeneration: Long,
    transformToken: CameraFrameTransformToken,
  ): DetectedFrameErrorHandle? {
    synchronized(_errorStateLock) {
      if (!canAnalyzeFrameWithoutErrorBarrier() || !isCurrentGeneration(generation)) return null
      if (_pendingError != null) return null

      val errorHandle = DetectedFrameErrorHandle(
        generation = generation,
        analysisGeneration = analysisGeneration,
        transformToken = transformToken,
      )
      _pendingError = errorHandle
      return try {
        advance()
        errorHandle
      } catch (error: Throwable) {
        if (_pendingError === errorHandle) _pendingError = null
        throw error
      }
    }
  }

  private fun claimErrorDelivery(
    errorHandle: DetectedFrameErrorHandle,
    isErrorCurrent: () -> Boolean,
  ): Boolean {
    synchronized(_errorStateLock) {
      if (
        _pendingError !== errorHandle ||
        errorHandle.isDeliveryClaimed ||
        !isCurrentGeneration(errorHandle.generation) ||
        !isErrorCurrent()
      ) {
        if (_pendingError === errorHandle && !errorHandle.isDeliveryClaimed) _pendingError = null
        return false
      }

      errorHandle.isDeliveryClaimed = true
      return true
    }
  }

  private fun completeError(errorHandle: DetectedFrameErrorHandle) {
    synchronized(_errorStateLock) {
      if (_pendingError === errorHandle) _pendingError = null
    }
  }

  private fun canAnalyzeFrameWithoutErrorBarrier(): Boolean {
    return _isActive.get() && _isTransformReady.get()
  }

  private fun isCurrentFrame(frameHandle: DetectedFrameHandle): Boolean {
    return isCurrentGeneration(frameHandle) && _sequence.get() == frameHandle.sequence
  }

  private fun isCurrentGeneration(frameHandle: DetectedFrameHandle): Boolean {
    return isCurrentGeneration(frameHandle.generation)
  }

  private fun isCurrentGeneration(generation: Long): Boolean {
    return _isActive.get() && _generation.get() == generation
  }
}

private fun analyzeFrame(
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

private fun CameraFrame.createFacePreviewFrame(
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

private const val DefaultFaceImageExpansionRatio = 0.5f

private fun requireValidFaceImageExpansionRatio(ratio: Float) {
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

private fun RectF.toComposeRect(): Rect {
  return Rect(left = left, top = top, right = right, bottom = bottom)
}
