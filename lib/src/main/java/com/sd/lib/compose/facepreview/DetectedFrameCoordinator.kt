package com.sd.lib.compose.facepreview

import android.graphics.Matrix
import com.sd.lib.compose.camera.CameraFrameTransformToken
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 丢弃过期工作，合并待发布帧，并让分析错误优先终止当前帧 generation。 */
internal data class DetectedFrameHandle(
  val revision: Long,
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
  private val _frameRevision = AtomicLong()
  private val _generation = AtomicLong()
  private val _detectorIdentity = AtomicReference<Any?>()
  private val _errorStateLock = Any()
  private val _frameDispatcher = ConflatedExecutorDispatcher(
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

  /** 在分析 lease 内开始一帧，保留上一帧尚未发布的结果。 */
  fun beginFrame(detector: Any): DetectedFrameHandle? {
    if (_detectorIdentity.get() !== detector || !canAnalyzeFrame) return null
    val generation = _generation.get()
    val frameHandle = DetectedFrameHandle(
      revision = _frameRevision.get(),
      generation = generation,
    )
    return frameHandle.takeIf {
      _detectorIdentity.get() === detector && canAnalyzeFrame && isCurrentFrame(frameHandle)
    }
  }

  fun invalidate() {
    synchronized(_errorStateLock) {
      invalidateFrames()
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

  private fun invalidateFrames() {
    _frameRevision.incrementAndGet()
    _frameDispatcher.clear()
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
        onValue = onValue@{ currentError ->
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
        invalidateFrames()
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
    return isCurrentGeneration(frameHandle) && _frameRevision.get() == frameHandle.revision
  }

  private fun isCurrentGeneration(frameHandle: DetectedFrameHandle): Boolean {
    return isCurrentGeneration(frameHandle.generation)
  }

  private fun isCurrentGeneration(generation: Long): Boolean {
    return _isActive.get() && _generation.get() == generation
  }
}
