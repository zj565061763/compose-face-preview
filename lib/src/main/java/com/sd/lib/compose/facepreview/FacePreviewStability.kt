package com.sd.lib.compose.facepreview

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/**
 * 判断连续人脸检测帧是否稳定。
 *
 * [onFrame] 和 [reset] 均由 FacePreviewView 串行地在相机分析线程调用。
 * 实现必须在 [reset] 中清空所有跨帧累计状态。
 * 相机会话重建后分析线程实例可能变化，因此实现不应依赖固定的线程身份。
 */
interface FacePreviewStability {
  /**
   * 帧回调，调用时机：
   *
   * - 仅在人脸分析已启用、未因错误暂停且尚未稳定时调用，并非在相机预览期间始终调用。
   * - 每个进入人脸分析流程的帧都会调用；没有完全位于预览区域内的人脸时也会调用，此时 [FacePreviewAnalysisFrame.faceRect] 为 [Rect.Zero]。
   * - 返回 `true` 且当前帧包含有效人脸后，后续分析停止，不再调用；调用 [FacePreviewState.resetStability] 或其他内部恢复流程后，才会继续处理后续帧。
   *
   * 返回人脸是否稳定；没有完全位于预览区域内的人脸时返回值会被忽略。
   */
  fun onFrame(frame: FacePreviewAnalysisFrame): Boolean

  /** 清空所有内部稳定性状态，准备重新处理后续帧。 */
  fun reset()
}

/** 人脸静止后计算的当前帧取景状态，仅描述人脸框大小和位置 */
enum class FacePreviewFramingStatus {
  /** 人脸太小了 */
  FaceTooSmall,

  /** 人脸太大了，已经无法同时满足对应方向的两侧边距要求 */
  FaceTooLarge,

  /** 人脸大小允许，但当前位置太靠近预览边缘 */
  FaceTooCloseToEdge,

  /** 当前帧的人脸框大小和位置都合适，不代表已经满足持续静止时长要求 */
  FaceSuitable,
}

/**
 * 使用尺寸、预览边距、持续静止时长和变化阈值的默认稳定性算法。
 *
 * [sizeRatio] 是人脸框宽高占预览宽高比例中较大值的最小阈值；
 * [horizontalMarginRatio] 要求人脸框左、右边距分别与预览宽度的比例都严格大于该值；
 * [verticalMarginRatio] 要求人脸框上、下边距分别与预览高度的比例都严格大于该值。
 * [requiredStableDurationMillis] 是判定静止所需的持续时长，默认 500 毫秒；
 * [maxCenterMovementRatio] 分别以当前稳定段锚点人脸框的宽高为基准，限制后续帧中心点在水平和竖直方向的最大移动比例；
 * [maxSizeChangeRatio] 分别以锚点人脸框的宽高为基准，限制后续帧宽高的最大变化比例。
 * 这三个参数共同决定 [isStill]；只有 [isStill] 为 `true` 时才会计算 [currentFramingStatus]。
 */
class DefaultFacePreviewStability internal constructor(
  val sizeRatio: Float = DefaultSizeRatio,
  val horizontalMarginRatio: Float = DefaultHorizontalMarginRatio,
  val verticalMarginRatio: Float = DefaultVerticalMarginRatio,
  requiredStableDurationMillis: Long = DefaultRequiredStableDurationMillis,
  maxCenterMovementRatio: Float = DefaultMaxCenterMovementRatio,
  maxSizeChangeRatio: Float = DefaultMaxSizeChangeRatio,
  timeSourceMillis: () -> Long,
) : FacePreviewStability {

  constructor(
    sizeRatio: Float = DefaultSizeRatio,
    horizontalMarginRatio: Float = DefaultHorizontalMarginRatio,
    verticalMarginRatio: Float = DefaultVerticalMarginRatio,
    requiredStableDurationMillis: Long = DefaultRequiredStableDurationMillis,
    maxCenterMovementRatio: Float = DefaultMaxCenterMovementRatio,
    maxSizeChangeRatio: Float = DefaultMaxSizeChangeRatio,
  ) : this(
    sizeRatio = sizeRatio,
    horizontalMarginRatio = horizontalMarginRatio,
    verticalMarginRatio = verticalMarginRatio,
    requiredStableDurationMillis = requiredStableDurationMillis,
    maxCenterMovementRatio = maxCenterMovementRatio,
    maxSizeChangeRatio = maxSizeChangeRatio,
    timeSourceMillis = ::monotonicTimeMillis,
  )

  init {
    require(sizeRatio.isFinite() && sizeRatio >= 0f) { "sizeRatio must be finite and non-negative." }
    require(horizontalMarginRatio.isFinite() && horizontalMarginRatio >= 0f) { "horizontalMarginRatio must be finite and non-negative." }
    require(verticalMarginRatio.isFinite() && verticalMarginRatio >= 0f) { "verticalMarginRatio must be finite and non-negative." }
  }

  private val _tracker = FaceStabilityTracker(
    requiredStableDurationMillis = requiredStableDurationMillis,
    maxCenterMovementRatio = maxCenterMovementRatio,
    maxSizeChangeRatio = maxSizeChangeRatio,
    monotonicTimeMillis = timeSourceMillis,
  )

  private val _isStill = mutableStateOf(false)
  private val _currentFramingStatus = mutableStateOf<FacePreviewFramingStatus?>(null)

  /** 人脸框是否满足持续静止时长要求，无有效数据或调用 [reset] 后为 `false` */
  val isStill: State<Boolean>
    get() = _isStill

  /** 最近一次人脸静止时计算的取景状态；无有效数据或调用 [reset] 后为 `null`，有效但未静止的帧不会清空 */
  val currentFramingStatus: State<FacePreviewFramingStatus?>
    get() = _currentFramingStatus

  override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean {
    val faceRect = frame.faceRect
    val previewSize = frame.previewSize

    val calculatedSizeRatio = calculateSizeRatio(faceRect, previewSize)
    val calculatedMarginRatio = calculateMarginRatio(faceRect, previewSize)
    val calculatedIsStill: Boolean
    val calculatedFramingStatus: FacePreviewFramingStatus?

    if (calculatedSizeRatio == null || calculatedMarginRatio == null) {
      _tracker.reset()
      calculatedIsStill = false
      calculatedFramingStatus = null
    } else {
      calculatedIsStill = _tracker.update(faceRect)
      calculatedFramingStatus = if (calculatedIsStill) calculateStatus(calculatedSizeRatio, calculatedMarginRatio) else _currentFramingStatus.value
    }

    Snapshot.withMutableSnapshot {
      _isStill.value = calculatedIsStill
      _currentFramingStatus.value = calculatedFramingStatus
    }

    return calculatedIsStill &&
      calculatedFramingStatus == FacePreviewFramingStatus.FaceSuitable
  }

  override fun reset() {
    _tracker.reset()
    Snapshot.withMutableSnapshot {
      _isStill.value = false
      _currentFramingStatus.value = null
    }
  }

  private fun calculateStatus(
    sizeRatio: Size,
    marginRatio: Rect,
  ): FacePreviewFramingStatus {
    if (sizeRatio.maxDimension < this.sizeRatio) return FacePreviewFramingStatus.FaceTooSmall

    val maximumWidthRatio = 1f - horizontalMarginRatio * 2f
    val maximumHeightRatio = 1f - verticalMarginRatio * 2f
    if (sizeRatio.width >= maximumWidthRatio || sizeRatio.height >= maximumHeightRatio) {
      return FacePreviewFramingStatus.FaceTooLarge
    }

    val hasRequiredEdgeMargins = marginRatio.hasRequiredEdgeMargins(
      horizontalMarginRatio = horizontalMarginRatio,
      verticalMarginRatio = verticalMarginRatio,
    )
    return if (hasRequiredEdgeMargins) {
      FacePreviewFramingStatus.FaceSuitable
    } else {
      FacePreviewFramingStatus.FaceTooCloseToEdge
    }
  }
}

private const val DefaultSizeRatio = 0.1f

private const val DefaultHorizontalMarginRatio = 0.1f
private const val DefaultVerticalMarginRatio = 0.1f

private const val DefaultRequiredStableDurationMillis = 500L
private const val DefaultMaxCenterMovementRatio = 0.1f
private const val DefaultMaxSizeChangeRatio = 0.1f

private fun monotonicTimeMillis(): Long = System.nanoTime() / 1_000_000L

internal fun calculateSizeRatio(
  rect: Rect,
  size: Size,
): Size? {
  if (
    rect.isEmpty ||
    size.isEmpty() ||
    !rect.width.isFinite() ||
    !rect.height.isFinite() ||
    !size.width.isFinite() ||
    !size.height.isFinite()
  ) {
    return null
  }

  val widthRatio = rect.width / size.width
  val heightRatio = rect.height / size.height
  if (!widthRatio.isFinite() || !heightRatio.isFinite() || widthRatio <= 0f || heightRatio <= 0f) return null
  return Size(widthRatio, heightRatio)
}

internal fun calculateMarginRatio(
  rect: Rect,
  size: Size,
): Rect? {
  if (
    rect.isEmpty ||
    size.isEmpty() ||
    !rect.left.isFinite() ||
    !rect.top.isFinite() ||
    !rect.right.isFinite() ||
    !rect.bottom.isFinite() ||
    !rect.width.isFinite() ||
    !rect.height.isFinite() ||
    !size.width.isFinite() ||
    !size.height.isFinite()
  ) {
    return null
  }

  val leftRatio = rect.left / size.width
  val topRatio = rect.top / size.height
  val rightRatio = (size.width - rect.right) / size.width
  val bottomRatio = (size.height - rect.bottom) / size.height
  if (!leftRatio.isFinite() || !topRatio.isFinite() || !rightRatio.isFinite() || !bottomRatio.isFinite()) return null
  return Rect(leftRatio, topRatio, rightRatio, bottomRatio)
}

internal fun Rect.hasRequiredEdgeMargins(
  horizontalMarginRatio: Float,
  verticalMarginRatio: Float,
): Boolean {
  return left > horizontalMarginRatio &&
    right > horizontalMarginRatio &&
    top > verticalMarginRatio &&
    bottom > verticalMarginRatio
}

internal class FaceStabilityTracker(
  private val requiredStableDurationMillis: Long,
  private val maxCenterMovementRatio: Float,
  private val maxSizeChangeRatio: Float,
  private val monotonicTimeMillis: () -> Long = ::monotonicTimeMillis,
) {
  private var _anchor: Rect? = null
  private var _stableStartTimeMillis: Long? = null

  init {
    require(requiredStableDurationMillis >= 0L) { "requiredStableDurationMillis must be non-negative." }
    require(maxCenterMovementRatio.isFinite() && maxCenterMovementRatio >= 0f) { "maxCenterMovementRatio must be finite and non-negative." }
    require(maxSizeChangeRatio.isFinite() && maxSizeChangeRatio >= 0f) { "maxSizeChangeRatio must be finite and non-negative." }
  }

  fun update(current: Rect): Boolean {
    val currentTimeMillis = monotonicTimeMillis()
    val currentAnchor = _anchor
    if (currentAnchor == null || !current.isCloseTo(currentAnchor)) {
      _anchor = current
      _stableStartTimeMillis = currentTimeMillis
      return requiredStableDurationMillis == 0L
    }

    val stableStartTimeMillis = checkNotNull(_stableStartTimeMillis)
    return currentTimeMillis - stableStartTimeMillis >= requiredStableDurationMillis
  }

  fun reset() {
    _anchor = null
    _stableStartTimeMillis = null
  }

  private fun Rect.isCloseTo(anchor: Rect): Boolean {
    val centerIsStable = abs(center.x - anchor.center.x) <= anchor.width * maxCenterMovementRatio &&
      abs(center.y - anchor.center.y) <= anchor.height * maxCenterMovementRatio

    val sizeIsStable = abs(width - anchor.width) <= anchor.width * maxSizeChangeRatio &&
      abs(height - anchor.height) <= anchor.height * maxSizeChangeRatio

    return centerIsStable && sizeIsStable
  }
}
