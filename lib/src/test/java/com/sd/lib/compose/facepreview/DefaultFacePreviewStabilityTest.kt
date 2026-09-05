package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import com.sd.lib.compose.camera.CameraFrame
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DefaultFacePreviewStabilityTest {
  @Test
  fun stability_defaultDurationRequiresFiveHundredMilliseconds() {
    val clock = TestMonotonicClock()
    val stability = DefaultFacePreviewStability(
      timeSourceMillis = clock::now,
      sizeRatio = 0.4f,
      horizontalMarginRatio = 0f,
      verticalMarginRatio = 0f,
    )
    val rect = Rect(35f, 30f, 65f, 70f)
    val previewSize = Size(100f, 100f)

    assertThat(stability.onFrame(rect, previewSize)).isFalse()
    clock.advanceBy(499L)
    assertThat(stability.onFrame(rect, previewSize)).isFalse()
    clock.advanceBy(1L)

    assertThat(stability.onFrame(rect, previewSize)).isTrue()
  }

  @Test
  fun stability_largerWidthOrHeightReachesThreshold_returnsTrue() {
    val widthStability = createStability(
      sizeRatio = 0.4f,
    )
    val heightStability = createStability(
      sizeRatio = 0.4f,
    )

    val previewSize = Size(100f, 100f)
    val widthResult = widthStability.onFrame(
      faceRect = Rect(30f, 35.5f, 70f, 64.5f),
      previewSize = previewSize,
    )
    val heightResult = heightStability.onFrame(
      faceRect = Rect(35.5f, 30f, 64.5f, 70f),
      previewSize = previewSize,
    )

    assertThat(widthResult).isTrue()
    assertThat(heightResult).isTrue()
  }

  @Test
  fun stability_bothSidesBelowSizeThreshold_returnsFalse() {
    val stability = createStability(
      sizeRatio = 0.4f,
    )

    val result = stability.onFrame(
      faceRect = Rect(30.5f, 30.5f, 69.5f, 69.5f),
      previewSize = Size(100f, 100f),
    )

    assertThat(result).isFalse()
  }

  @Test
  fun stability_defaultSizeRatio_checksLargerSideAgainstTenPercent() {
    val qualifyingStability = DefaultFacePreviewStability(
      horizontalMarginRatio = 0f,
      verticalMarginRatio = 0f,
      requiredStableDurationMillis = 0L,
    )
    val undersizedStability = DefaultFacePreviewStability(
      horizontalMarginRatio = 0f,
      verticalMarginRatio = 0f,
      requiredStableDurationMillis = 0L,
    )
    val previewSize = Size(100f, 100f)

    assertThat(
      qualifyingStability.onFrame(Rect(45.5f, 45f, 54.5f, 55f), previewSize)
    ).isTrue()
    assertThat(
      undersizedStability.onFrame(Rect(45.5f, 45.5f, 54.5f, 54.5f), previewSize)
    ).isFalse()
  }

  @Test
  fun stability_invalidFrameClearsStillAndFramingStatus() {
    val stability = createStability(
      sizeRatio = 0.6f,
    )
    assertThat(stability.isStill.value).isFalse()
    assertThat(stability.currentFramingStatus.value).isNull()

    val result = stability.onFrame(
      faceRect = Rect(20f, 40f, 70f, 120f),
      previewSize = Size(100f, 200f),
    )

    assertThat(result).isFalse()
    assertThat(stability.isStill.value).isTrue()
    assertThat(stability.currentFramingStatus.value).isEqualTo(FacePreviewFramingStatus.FaceTooSmall)

    stability.onFrame(Rect.Zero, Size(100f, 200f))

    assertThat(stability.isStill.value).isFalse()
    assertThat(stability.currentFramingStatus.value).isNull()
  }

  @Test
  fun stability_currentFramingStatus_reportsFaceGuidance() {
    val previewSize = Size(100f, 100f)
    val small = createStability(
      sizeRatio = 0.1f,
      horizontalMarginRatio = 0.1f,
      verticalMarginRatio = 0.1f,
    )
    val large = createStability(
      sizeRatio = 0.1f,
      horizontalMarginRatio = 0.1f,
      verticalMarginRatio = 0.1f,
    )
    val closeToEdge = createStability(
      sizeRatio = 0.1f,
      horizontalMarginRatio = 0.1f,
      verticalMarginRatio = 0.1f,
    )
    val clock = TestMonotonicClock()
    val suitable = createStability(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
      sizeRatio = 0.1f,
      horizontalMarginRatio = 0.1f,
      verticalMarginRatio = 0.1f,
    )

    small.onFrame(Rect(45.5f, 45.5f, 54.5f, 54.5f), previewSize)
    large.onFrame(Rect(10f, 20f, 90f, 80f), previewSize)
    closeToEdge.onFrame(Rect(5f, 30f, 45f, 70f), previewSize)
    val firstSuitableResult = suitable.onFrame(Rect(30f, 30f, 70f, 70f), previewSize)

    assertThat(small.currentFramingStatus.value).isEqualTo(FacePreviewFramingStatus.FaceTooSmall)
    assertThat(large.currentFramingStatus.value).isEqualTo(FacePreviewFramingStatus.FaceTooLarge)
    assertThat(closeToEdge.currentFramingStatus.value).isEqualTo(FacePreviewFramingStatus.FaceTooCloseToEdge)
    assertThat(firstSuitableResult).isFalse()
    assertThat(suitable.isStill.value).isFalse()
    assertThat(suitable.currentFramingStatus.value).isNull()

    clock.advanceBy(500L)
    val secondSuitableResult = suitable.onFrame(Rect(30f, 30f, 70f, 70f), previewSize)

    assertThat(secondSuitableResult).isTrue()
    assertThat(suitable.isStill.value).isTrue()
    assertThat(suitable.currentFramingStatus.value).isEqualTo(FacePreviewFramingStatus.FaceSuitable)
  }

  @Test
  fun stability_reset_clearsStillAndFramingStatus() {
    val stability = createStability()
    stability.onFrame(
      faceRect = Rect(20f, 40f, 70f, 120f),
      previewSize = Size(100f, 200f),
    )

    stability.reset()

    assertThat(stability.isStill.value).isFalse()
    assertThat(stability.currentFramingStatus.value).isNull()
  }

  @Test
  fun stability_movementClearsStillAndKeepsFramingStatus() {
    val clock = TestMonotonicClock()
    val stability = createStability(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
      sizeRatio = 0.1f,
      horizontalMarginRatio = 0.1f,
      verticalMarginRatio = 0.1f,
    )
    val previewSize = Size(100f, 100f)
    val initialRect = Rect(30f, 30f, 70f, 70f)
    stability.onFrame(initialRect, previewSize)
    clock.advanceBy(500L)
    stability.onFrame(initialRect, previewSize)
    assertThat(stability.isStill.value).isTrue()
    assertThat(stability.currentFramingStatus.value).isEqualTo(FacePreviewFramingStatus.FaceSuitable)

    stability.onFrame(Rect(45f, 30f, 85f, 70f), previewSize)

    assertThat(stability.isStill.value).isFalse()
    assertThat(stability.currentFramingStatus.value).isEqualTo(FacePreviewFramingStatus.FaceSuitable)
  }

  @Test
  fun stability_framesBelowSizeThreshold_doNotReturnStable() {
    val clock = TestMonotonicClock()
    val stability = createStability(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
      sizeRatio = 0.4f,
    )
    val previewSize = Size(100f, 100f)
    val undersizedRect = Rect(30.5f, 30.5f, 69.5f, 69.5f)
    val validRect = Rect(35f, 30f, 65f, 70f)

    assertThat(stability.onFrame(undersizedRect, previewSize)).isFalse()
    clock.advanceBy(500L)
    assertThat(stability.onFrame(undersizedRect, previewSize)).isFalse()
    assertThat(stability.onFrame(validRect, previewSize)).isFalse()
    clock.advanceBy(500L)
    assertThat(stability.onFrame(validRect, previewSize)).isTrue()
  }

  @Test
  fun stability_unspecifiedSize_returnsFalse() {
    val stability = createStability()

    val result = stability.onFrame(Rect(0f, 0f, 100f, 100f), Size.Unspecified)

    assertThat(result).isFalse()
  }

  @Test
  fun stability_unspecifiedSize_resetsStableDuration() {
    val clock = TestMonotonicClock()
    val stability = createStability(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )
    val faceRect = Rect(35f, 35f, 65f, 65f)
    val previewSize = Size(100f, 100f)
    assertThat(stability.onFrame(faceRect, previewSize)).isFalse()
    clock.advanceBy(500L)

    assertThat(stability.onFrame(faceRect, Size.Unspecified)).isFalse()
    assertThat(stability.onFrame(faceRect, previewSize)).isFalse()
    clock.advanceBy(500L)
    assertThat(stability.onFrame(faceRect, previewSize)).isTrue()
  }

  @Test(expected = IllegalArgumentException::class)
  fun stability_negativeSizeRatio_throws() {
    createStability(
      sizeRatio = -0.1f,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun stability_negativeHorizontalMarginRatio_throws() {
    createStability(
      horizontalMarginRatio = -0.1f,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun stability_negativeVerticalMarginRatio_throws() {
    createStability(
      verticalMarginRatio = -0.1f,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun stability_negativeRequiredStableDuration_throws() {
    createStability(requiredStableDurationMillis = -1L)
  }

  @Test
  fun stability_defaultMargins_requireMoreThanTenPercentOnEverySide() {
    val stability = DefaultFacePreviewStability(
      sizeRatio = 0f,
      requiredStableDurationMillis = 0L,
    )
    val previewSize = Size(100f, 100f)

    assertThat(stability.onFrame(Rect(10f, 11f, 89f, 89f), previewSize)).isFalse()
    assertThat(stability.onFrame(Rect(11f, 11f, 89f, 89f), previewSize)).isTrue()
  }

  @Test
  fun stability_horizontalMargin_requiresBothSidesAboveThreshold() {
    val stability = createStability(
      sizeRatio = 0f,
      horizontalMarginRatio = 0.1f,
    )
    val previewSize = Size(100f, 100f)

    assertThat(stability.onFrame(Rect(10f, 20f, 89f, 80f), previewSize)).isFalse()
    assertThat(stability.onFrame(Rect(11f, 20f, 90f, 80f), previewSize)).isFalse()
    assertThat(stability.onFrame(Rect(11f, 20f, 89f, 80f), previewSize)).isTrue()
  }

  @Test
  fun stability_verticalMargin_requiresBothSidesAboveThreshold() {
    val stability = createStability(
      sizeRatio = 0f,
      verticalMarginRatio = 0.1f,
    )
    val previewSize = Size(100f, 100f)

    assertThat(stability.onFrame(Rect(20f, 10f, 80f, 89f), previewSize)).isFalse()
    assertThat(stability.onFrame(Rect(20f, 11f, 80f, 90f), previewSize)).isFalse()
    assertThat(stability.onFrame(Rect(20f, 11f, 80f, 89f), previewSize)).isTrue()
  }

  @Test
  fun stability_sameRectBeforeRequiredDuration_isNotStill() {
    val clock = TestMonotonicClock()
    val stability = createStability(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )
    val rect = Rect(35f, 35f, 65f, 65f)
    val previewSize = Size(100f, 100f)

    assertThat(stability.onFrame(rect, previewSize)).isFalse()
    clock.advanceBy(499L)
    assertThat(stability.onFrame(rect, previewSize)).isFalse()
    clock.advanceBy(1L)
    assertThat(stability.onFrame(rect, previewSize)).isTrue()
  }

  @Test
  fun stability_emptyFace_resetsStability() {
    val clock = TestMonotonicClock()
    val stability = createStability(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )
    val rect = Rect(35f, 35f, 65f, 65f)
    val previewSize = Size(100f, 100f)
    stability.onFrame(rect, previewSize)
    clock.advanceBy(500L)
    assertThat(stability.onFrame(rect, previewSize)).isTrue()

    assertThat(stability.onFrame(Rect.Zero, previewSize)).isFalse()
    assertThat(stability.onFrame(rect, previewSize)).isFalse()
    clock.advanceBy(500L)
    assertThat(stability.onFrame(rect, previewSize)).isTrue()
  }

  @Test
  fun stability_reset_requiresStableDurationAgain() {
    val clock = TestMonotonicClock()
    val stability = createStability(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )
    val rect = Rect(35f, 35f, 65f, 65f)
    val previewSize = Size(100f, 100f)
    assertThat(stability.onFrame(rect, previewSize)).isFalse()
    clock.advanceBy(500L)
    assertThat(stability.onFrame(rect, previewSize)).isTrue()

    stability.reset()

    assertThat(stability.onFrame(rect, previewSize)).isFalse()
    clock.advanceBy(500L)
    assertThat(stability.onFrame(rect, previewSize)).isTrue()
  }

  private fun createStability(
    requiredStableDurationMillis: Long = 0L,
    monotonicTimeMillis: () -> Long = { 0L },
    sizeRatio: Float = 0.3f,
    horizontalMarginRatio: Float = 0f,
    verticalMarginRatio: Float = 0f,
  ) = DefaultFacePreviewStability(
    timeSourceMillis = monotonicTimeMillis,
    sizeRatio = sizeRatio,
    requiredStableDurationMillis = requiredStableDurationMillis,
    maxCenterMovementRatio = 0.1f,
    maxSizeChangeRatio = 0.1f,
    horizontalMarginRatio = horizontalMarginRatio,
    verticalMarginRatio = verticalMarginRatio,
  )

  private fun FacePreviewStability.onFrame(
    faceRect: Rect,
    previewSize: Size,
  ): Boolean {
    return onFrame(
      object : FacePreviewAnalysisFrame {
        override val cameraFrame: CameraFrame
          get() = error("Synthetic frame does not contain a CameraFrame.")
        override val rotationDegrees = 0
        override val imageFaceRect = faceRect
        override val faceRect = faceRect
        override val previewSize = previewSize
      }
    )
  }
}
