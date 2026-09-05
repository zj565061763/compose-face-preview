package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FacePreviewAnalysisLeaseTest {
  @Test
  fun beginAnalysisFrame_withoutPreviewSize_returnsNull() {
    val state = stateWithResult(false)

    assertThat(state.beginAnalysisFrame()).isNull()
  }

  @Test
  fun beginAnalysisFrame_whilePreviousSessionFrameIsRunning_returnsNull() {
    val state = stateWithResult(false)
    state.updatePreviewSize(IntSize(100, 100))
    val firstSnapshot = checkNotNull(state.beginAnalysisFrame())

    assertThat(state.beginAnalysisFrame()).isNull()

    state.endAnalysisFrame(firstSnapshot)
    val nextSnapshot = state.beginAnalysisFrame()
    assertThat(nextSnapshot).isNotNull()
    state.endAnalysisFrame(checkNotNull(nextSnapshot))
  }

  @Test
  fun withAnalysisFrameLease_outOfMemory_releasesLease() {
    val state = stateWithResult(false)
    state.updatePreviewSize(IntSize(100, 100))
    val expected = OutOfMemoryError("expected")

    val actual = try {
      state.withAnalysisFrameLease { throw expected }
      null
    } catch (error: Throwable) {
      error
    }

    assertThat(actual).isSameInstanceAs(expected)
    val nextSnapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(nextSnapshot)
  }

  @Test
  fun withAnalysisFrameLease_blockAndReleaseFail_preservesBlockFailure() {
    val state = stateWithResult(false)
    state.updatePreviewSize(IntSize(100, 100))
    val expected = OutOfMemoryError("expected")

    val actual = try {
      state.withAnalysisFrameLease { snapshot ->
        state.endAnalysisFrame(snapshot)
        throw expected
      }
      null
    } catch (error: Throwable) {
      error
    }

    assertThat(actual).isSameInstanceAs(expected)
    assertThat(actual?.suppressed).hasLength(1)
    assertThat(actual?.suppressed?.single()).isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun runAnalysisFrameWithLease_outOfMemory_recoversAndReportsBeforeRelease() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))
    val initialAnalysisGeneration = state.currentAnalysisGeneration
    val expected = OutOfMemoryError("expected")
    var reportedGeneration: Long? = null
    var reported: Throwable? = null
    var leaseWasHeldWhileReporting = false

    state.runAnalysisFrameWithLease(
      initialAnalysisGeneration = initialAnalysisGeneration,
      onFailure = { analysisGeneration, error ->
        reportedGeneration = analysisGeneration
        reported = error
        leaseWasHeldWhileReporting = state.beginAnalysisFrame() == null
      },
    ) { snapshot ->
      val result = state.analyzeFrame(
        snapshot = snapshot,
        frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), snapshot.previewSize),
        resetForTransformChange = false,
      )
      assertThat(result?.isStable).isTrue()
      assertThat(state.shouldDetectFace).isFalse()
      throw expected
    }

    assertThat(reportedGeneration).isEqualTo(initialAnalysisGeneration)
    assertThat(reported).isSameInstanceAs(expected)
    assertThat(state.shouldDetectFace).isTrue()
    assertThat(leaseWasHeldWhileReporting).isTrue()
    val nextSnapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(nextSnapshot)
  }

  @Test
  fun runAnalysisFrameWithLease_releaseFailure_reportsAfterReleaseAttempt() {
    val state = stateWithResult(false)
    state.updatePreviewSize(IntSize(100, 100))
    val initialAnalysisGeneration = state.currentAnalysisGeneration
    var reportedGeneration: Long? = null
    var reported: Throwable? = null
    var nextSnapshot: FacePreviewAnalysisSnapshot? = null

    state.runAnalysisFrameWithLease(
      initialAnalysisGeneration = initialAnalysisGeneration,
      onFailure = { analysisGeneration, error ->
        reportedGeneration = analysisGeneration
        reported = error
        nextSnapshot = state.beginAnalysisFrame()
      },
    ) { snapshot ->
      state.endAnalysisFrame(snapshot)
    }

    assertThat(reportedGeneration).isEqualTo(initialAnalysisGeneration)
    assertThat(reported).isInstanceOf(IllegalStateException::class.java)
    assertThat(reported?.message).isEqualTo("Face preview analysis frame was already ended.")
    state.endAnalysisFrame(checkNotNull(nextSnapshot))
  }

  @Test
  fun runAnalysisFrameWithLease_blockAndReleaseFail_reportsBlockOnceWithSuppressedRelease() {
    val state = stateWithResult(false)
    state.updatePreviewSize(IntSize(100, 100))
    val initialAnalysisGeneration = state.currentAnalysisGeneration
    val expected = OutOfMemoryError("expected")
    var reportCount = 0
    var reportedGeneration: Long? = null
    var reported: Throwable? = null

    state.runAnalysisFrameWithLease(
      initialAnalysisGeneration = initialAnalysisGeneration,
      onFailure = { analysisGeneration, error ->
        reportCount++
        reportedGeneration = analysisGeneration
        reported = error
      },
    ) { snapshot ->
      state.endAnalysisFrame(snapshot)
      throw expected
    }

    assertThat(reportCount).isEqualTo(1)
    assertThat(reportedGeneration).isEqualTo(initialAnalysisGeneration)
    assertThat(reported).isSameInstanceAs(expected)
    assertThat(expected.suppressed).hasLength(1)
    assertThat(expected.suppressed.single()).isInstanceOf(IllegalStateException::class.java)
    assertThat(expected.suppressed.single().message).isEqualTo("Face preview analysis frame was already ended.")
    val nextSnapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(nextSnapshot)
  }

  @Test
  fun recoverAfterAnalysisFailure_unpublishedStableResultResumesDetection() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))
    val snapshot = checkNotNull(state.beginAnalysisFrame())

    try {
      val result = state.analyzeFrame(
        snapshot = snapshot,
        frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), snapshot.previewSize),
        resetForTransformChange = false,
      )
      assertThat(result?.isStable).isTrue()
      assertThat(state.shouldDetectFace).isFalse()

      state.recoverAfterAnalysisFailure(snapshot)

      assertThat(state.shouldDetectFace).isTrue()
    } finally {
      state.endAnalysisFrame(snapshot)
    }

    val nextSnapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(nextSnapshot)
  }

  @Test
  fun recoverAfterAnalysisFailure_staleSnapshotDoesNotResumeCurrentStableResult() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))
    val staleSnapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(staleSnapshot)
    state.resetStability()
    val currentSnapshot = checkNotNull(state.beginAnalysisFrame())

    try {
      val result = state.analyzeFrame(
        snapshot = currentSnapshot,
        frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), currentSnapshot.previewSize),
        resetForTransformChange = false,
      )
      assertThat(result?.isStable).isTrue()
      assertThat(state.shouldDetectFace).isFalse()

      state.recoverAfterAnalysisFailure(staleSnapshot)

      assertThat(state.shouldDetectFace).isFalse()
    } finally {
      state.endAnalysisFrame(currentSnapshot)
    }
  }

  @Test
  fun recoverAfterAnalysisFailure_releasedLeaseDoesNotResumeNextStableResult() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))
    val failedSnapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(failedSnapshot)
    val currentSnapshot = checkNotNull(state.beginAnalysisFrame())

    try {
      val currentResult = checkNotNull(
        state.analyzeFrame(
          snapshot = currentSnapshot,
          frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), currentSnapshot.previewSize),
          resetForTransformChange = false,
        )
      )
      assertThat(currentResult.isStable).isTrue()
      assertThat(state.shouldDetectFace).isFalse()

      state.recoverAfterAnalysisFailure(failedSnapshot)

      assertThat(state.shouldDetectFace).isFalse()
      assertThat(state.publishAnalysisResult(currentResult)).isTrue()
      assertThat(state.isStable.value).isTrue()
    } finally {
      state.endAnalysisFrame(currentSnapshot)
    }
  }
}
