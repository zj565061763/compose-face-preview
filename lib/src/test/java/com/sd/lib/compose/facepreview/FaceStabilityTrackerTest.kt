package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FaceStabilityTrackerTest {
  @Test
  fun update_smallMovementForRequiredDuration_isStable() {
    val clock = TestMonotonicClock()
    val tracker = createTracker(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )

    assertThat(tracker.update(Rect(0f, 0f, 100f, 100f))).isFalse()
    clock.advanceBy(250L)
    assertThat(tracker.update(Rect(2f, 1f, 102f, 101f))).isFalse()
    clock.advanceBy(250L)
    assertThat(tracker.update(Rect(-2f, 2f, 98f, 102f))).isTrue()
  }

  @Test
  fun update_largeMovement_resetsStableState() {
    val clock = TestMonotonicClock()
    val tracker = createTracker(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )
    tracker.update(Rect(0f, 0f, 100f, 100f))
    clock.advanceBy(500L)
    assertThat(tracker.update(Rect(2f, 2f, 102f, 102f))).isTrue()

    assertThat(tracker.update(Rect(30f, 0f, 130f, 100f))).isFalse()
    clock.advanceBy(500L)
    assertThat(tracker.update(Rect(32f, 1f, 132f, 101f))).isTrue()
  }

  @Test
  fun update_largeSizeChange_resetsStableState() {
    val clock = TestMonotonicClock()
    val tracker = createTracker(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )
    tracker.update(Rect(0f, 0f, 100f, 100f))
    clock.advanceBy(500L)

    assertThat(tracker.update(Rect(-10f, -10f, 110f, 110f))).isFalse()
    clock.advanceBy(500L)
    assertThat(tracker.update(Rect(-9f, -9f, 111f, 111f))).isTrue()
  }

  @Test
  fun reset_requiresStabilityToBeEstablishedAgain() {
    val clock = TestMonotonicClock()
    val tracker = createTracker(
      requiredStableDurationMillis = 500L,
      monotonicTimeMillis = clock::now,
    )
    tracker.update(Rect(0f, 0f, 100f, 100f))
    clock.advanceBy(500L)
    assertThat(tracker.update(Rect(1f, 1f, 101f, 101f))).isTrue()

    tracker.reset()

    assertThat(tracker.update(Rect(1f, 1f, 101f, 101f))).isFalse()
    clock.advanceBy(500L)
    assertThat(tracker.update(Rect(1f, 1f, 101f, 101f))).isTrue()
  }

  private fun createTracker(
    requiredStableDurationMillis: Long,
    monotonicTimeMillis: () -> Long,
  ) = FaceStabilityTracker(
    requiredStableDurationMillis = requiredStableDurationMillis,
    maxCenterMovementRatio = 0.1f,
    maxSizeChangeRatio = 0.1f,
    monotonicTimeMillis = monotonicTimeMillis,
  )
}
