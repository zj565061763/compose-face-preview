package com.sd.lib.compose.facepreview

import android.graphics.RectF
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * 创建并记住 [FacePreviewState]。
 *
 * [stability] 作为 Compose `remember` key 发生变化时（按 `equals` 比较）会创建新的状态。
 * 稳定算法的配置依赖 Compose 状态时，调用方应先使用相同配置作为 key 记住算法实例，
 * 再传入此方法。同一个正在使用的 [FacePreviewStability] 实例不能在多个状态间共享。
 */
@Composable
fun rememberFacePreviewState(
  stability: FacePreviewStability = remember { DefaultFacePreviewStability() },
): FacePreviewState {
  return remember(stability) { FacePreviewState(stability) }
}

/**
 * [FacePreviewView] 的状态。每个正在组合的 FacePreviewView 必须使用独立实例，
 * 不能在多个预览间共享；注入的 [FacePreviewStability] 也必须由此状态独占。
 */
@Stable
class FacePreviewState(
  val stability: FacePreviewStability,
) {
  private val _previewSize = mutableStateOf(Size.Zero)
  private val _faceRect = mutableStateOf(Rect.Zero)
  private val _isStable = mutableStateOf(false)
  private val _failure = mutableStateOf<Throwable?>(null)

  private val _previewSizeSnapshot = AtomicReference(Size.Zero)
  private val _analysisControl = AtomicReference(FacePreviewAnalysisControl())
  // 单个会话只有一个分析线程，但旧会话已开始的回调可能与新会话重叠。
  private val _analysisLease = AtomicReference<Any?>(null)
  private var _appliedAnalysisGeneration: Long? = null
  private var _detectorIdentity: WeakReference<Any>? = null
  private var _hasDetectorIdentity = false

  /** 预览画面的实际布局尺寸 */
  val previewSize: State<Size>
    get() = _previewSize

  /** 人脸矩形，未检测到完全位于预览区域内的人脸或已经达到稳定状态时为 [Rect.Zero] */
  val faceRect: State<Rect>
    get() = _faceRect

  /** 人脸是否达到注入的稳定性算法要求 */
  val isStable: State<Boolean>
    get() = _isStable

  /** 当前导致人脸分析暂停的故障，不包含相机会话故障 */
  val failure: State<Throwable?>
    get() = _failure

  /**
   * 将稳定状态重置为 `false`，使后续稳定帧可以再次触发回调。
   *
   * 此方法也会恢复因检测异常而暂停的帧分析。
   * 稳定性算法会在下一分析帧到达时，在分析线程调用 `reset()`，不会跨线程访问算法内部状态。
   */
  @MainThread
  fun resetStability() {
    restartAnalysis()
  }

  /** 清除当前人脸分析故障并重新处理后续帧；没有故障时不执行操作 */
  @MainThread
  fun retry() {
    if (_failure.value == null) return
    restartAnalysis()
  }

  /** 可在相机分析线程读取，稳定后为 `false`。 */
  internal val shouldDetectFace: Boolean
    get() = _analysisControl.get().shouldDetect

  /** 判断异步回调是否仍属于当前分析 generation */
  internal fun isAnalysisGenerationCurrent(generation: Long): Boolean {
    return _analysisControl.get().generation == generation
  }

  /** 返回当前分析 generation，用于在分析 snapshot 尚未创建时校验异步错误。 */
  internal val currentAnalysisGeneration: Long
    get() = _analysisControl.get().generation

  /** 为一帧取得一致的分析 generation 和预览尺寸 */
  internal fun beginAnalysisFrame(): FacePreviewAnalysisSnapshot? {
    val lease = Any()
    if (!_analysisLease.compareAndSet(null, lease)) return null

    val control = _analysisControl.get()
    val previewSize = _previewSizeSnapshot.get()
    if (
      !control.shouldDetect ||
      previewSize.width <= 0f ||
      previewSize.height <= 0f
    ) {
      check(_analysisLease.compareAndSet(lease, null))
      return null
    }

    return try {
      FacePreviewAnalysisSnapshot(
        generation = control.generation,
        previewSize = previewSize,
        lease = lease,
      )
    } catch (error: Throwable) {
      check(_analysisLease.compareAndSet(lease, null))
      throw error
    }
  }

  /** 结束分析帧并允许当前或新相机会话处理下一帧 */
  internal fun endAnalysisFrame(snapshot: FacePreviewAnalysisSnapshot) {
    check(_analysisLease.compareAndSet(snapshot.lease, null)) {
      "Face preview analysis frame was already ended."
    }
  }

  /** 在相机分析线程串行调用稳定性算法 */
  internal fun analyzeFrame(
    snapshot: FacePreviewAnalysisSnapshot,
    frame: FacePreviewAnalysisFrame,
    resetForTransformChange: Boolean,
  ): FacePreviewAnalysisResult? {
    val before = _analysisControl.get()
    if (
      before.generation != snapshot.generation ||
      !before.shouldDetect ||
      before.isPausedByError
    ) {
      return null
    }

    resetStabilityIfNeeded(snapshot.generation, resetForTransformChange)

    val stabilityReached = stability.onFrame(frame)
    val after = _analysisControl.get()
    if (
      after.generation != snapshot.generation ||
      !after.shouldDetect ||
      after.isPausedByError
    ) {
      return null
    }

    val isStable = !frame.faceRect.isEmpty && stabilityReached
    if (isStable) {
      val stopped = after.copy(shouldDetect = false)
      if (!_analysisControl.compareAndSet(after, stopped)) return null
    }
    return FacePreviewAnalysisResult(
      generation = snapshot.generation,
      faceRect = frame.faceRect,
      isStable = isStable,
    )
  }

  /** 恢复当前帧在结果发布前停止的检测；仅持有该帧 lease 时生效。 */
  internal fun recoverAfterAnalysisFailure(snapshot: FacePreviewAnalysisSnapshot) {
    while (true) {
      if (_analysisLease.get() !== snapshot.lease) return

      val current = _analysisControl.get()
      if (
        current.generation != snapshot.generation ||
        current.shouldDetect ||
        current.isPausedByError
      ) {
        return
      }

      if (_analysisControl.compareAndSet(current, current.copy(shouldDetect = true))) return
    }
  }

  /** 在主线程发布已经完成的分析结果；generation 过期时返回 `false`。 */
  internal fun publishAnalysisResult(result: FacePreviewAnalysisResult): Boolean {
    if (_analysisControl.get().generation != result.generation) return false
    _faceRect.value = if (result.isStable) Rect.Zero else result.faceRect
    _isStable.value = result.isStable
    return true
  }

  /** 清空尚未完成的稳定性累计；已经交付的稳定状态保持不变。 */
  internal fun resetTracking() {
    if (_isStable.value) return

    val current = _analysisControl.get()
    updateAnalysisControl(
      shouldDetect = !current.isPausedByError,
      isPausedByError = current.isPausedByError,
    )
    _faceRect.value = Rect.Zero
  }

  /** 检测异常后暂停后续帧，等待显式重置或检测器配置变化。 */
  internal fun stopDetectionAfterError(error: Throwable) {
    updateAnalysisControl(shouldDetect = false, isPausedByError = true)
    _failure.value = error
    _faceRect.value = Rect.Zero
    _isStable.value = false
  }

  /** 更新检测器身份；返回实例是否变化，并在变化时解除异常暂停、重新开始跟踪。 */
  internal fun updateDetectorIdentity(detector: Any): Boolean {
    val previousDetector = _detectorIdentity?.get()
    if (_hasDetectorIdentity && previousDetector === detector) return false

    val detectorChanged = _hasDetectorIdentity
    _hasDetectorIdentity = true
    _detectorIdentity = WeakReference(detector)
    if (!detectorChanged) return false

    _failure.value = null
    if (!_isStable.value) {
      updateAnalysisControl(shouldDetect = true, isPausedByError = false)
      _faceRect.value = Rect.Zero
    }
    return true
  }

  internal fun updatePreviewSize(size: IntSize) {
    val previewSize = size.toSize()
    _previewSizeSnapshot.set(previewSize)
    _previewSize.value = previewSize
  }

  private fun updateAnalysisControl(
    shouldDetect: Boolean,
    isPausedByError: Boolean,
  ) {
    while (true) {
      val current = _analysisControl.get()
      val updated = FacePreviewAnalysisControl(
        generation = current.generation + 1,
        shouldDetect = shouldDetect,
        isPausedByError = isPausedByError,
      )
      if (_analysisControl.compareAndSet(current, updated)) return
    }
  }

  private fun restartAnalysis() {
    updateAnalysisControl(shouldDetect = true, isPausedByError = false)
    _failure.value = null
    _isStable.value = false
  }

  private fun resetStabilityIfNeeded(
    generation: Long,
    force: Boolean = false,
  ) {
    if (_appliedAnalysisGeneration == generation && !force) return
    stability.reset()
    _appliedAnalysisGeneration = generation
  }
}

/** 在持有独占分析 lease 时执行 [block]，发生任何异常也会释放 lease。 */
internal inline fun FacePreviewState.withAnalysisFrameLease(
  block: (FacePreviewAnalysisSnapshot) -> Unit,
) {
  val snapshot = beginAnalysisFrame() ?: return
  var blockFailure: Throwable? = null
  try {
    block(snapshot)
  } catch (error: Throwable) {
    blockFailure = error
    throw error
  } finally {
    try {
      endAnalysisFrame(snapshot)
    } catch (releaseError: Throwable) {
      val failure = blockFailure
      if (failure == null) throw releaseError
      if (failure !== releaseError) failure.addSuppressed(releaseError)
    }
  }
}

/** 统一处理 lease 获取、分析和释放异常；分析异常在仍持有 lease 时恢复并报告。 */
internal inline fun FacePreviewState.runAnalysisFrameWithLease(
  initialAnalysisGeneration: Long,
  onFailure: (analysisGeneration: Long, error: Throwable) -> Unit,
  block: (FacePreviewAnalysisSnapshot) -> Unit,
) {
  var analysisGeneration = initialAnalysisGeneration
  var reportedBlockFailure: Throwable? = null
  var isReportingBlockFailure = false
  try {
    withAnalysisFrameLease { snapshot ->
      analysisGeneration = snapshot.generation
      try {
        block(snapshot)
      } catch (error: Throwable) {
        recoverAfterAnalysisFailure(snapshot)
        isReportingBlockFailure = true
        onFailure(analysisGeneration, error)
        reportedBlockFailure = error
        isReportingBlockFailure = false
        // 让 withAnalysisFrameLease 把随后发生的释放失败附加到已经上报的原始异常。
        throw error
      }
    }
  } catch (error: Throwable) {
    if (isReportingBlockFailure) throw error
    if (reportedBlockFailure === error) return
    onFailure(analysisGeneration, error)
  }
}

private data class FacePreviewAnalysisControl(
  val generation: Long = 0L,
  val shouldDetect: Boolean = true,
  val isPausedByError: Boolean = false,
)

internal data class FacePreviewAnalysisSnapshot(
  val generation: Long,
  val previewSize: Size,
  internal val lease: Any,
)

internal data class FacePreviewAnalysisResult(
  val generation: Long,
  val faceRect: Rect,
  val isStable: Boolean,
)

internal fun RectF.isFullyVisibleIn(size: Size): Boolean {
  if (
    right <= left ||
    bottom <= top ||
    !left.isFinite() ||
    !top.isFinite() ||
    !right.isFinite() ||
    !bottom.isFinite() ||
    !size.width.isFinite() ||
    !size.height.isFinite() ||
    size.width <= 0f ||
    size.height <= 0f
  ) {
    return false
  }
  return left >= 0f && top >= 0f && right <= size.width && bottom <= size.height
}
