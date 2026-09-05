package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(JUnit4::class)
class FacePreviewStateTest {
  @Test
  fun analyzeFrame_passesFrameDataToCustomStability() {
    var receivedFrame: FacePreviewAnalysisFrame? = null
    val state = FacePreviewState(
      stability = statelessStability { frame ->
        receivedFrame = frame
        false
      },
    )
    state.updatePreviewSize(IntSize(100, 200))
    val imageFaceRect = Rect(1f, 2f, 21f, 42f)
    val faceRect = Rect(10f, 20f, 30f, 60f)

    state.processFrame(
      faceRect = faceRect,
      imageFaceRect = imageFaceRect,
    )

    assertThat(receivedFrame?.imageFaceRect).isEqualTo(imageFaceRect)
    assertThat(receivedFrame?.faceRect).isEqualTo(faceRect)
    assertThat(receivedFrame?.previewSize).isEqualTo(Size(100f, 200f))
  }

  @Test
  fun analyzeFrame_newStateResetsReusedStabilityBeforeFirstFrame() {
    var currentTimeMillis = 0L
    val stability = DefaultFacePreviewStability(
      timeSourceMillis = { currentTimeMillis },
      sizeRatio = 0f,
      requiredStableDurationMillis = 500L,
    )
    val firstState = FacePreviewState(stability)
    val faceRect = Rect(25f, 25f, 75f, 75f)
    firstState.updatePreviewSize(IntSize(100, 100))
    assertThat(firstState.processFrame(faceRect)?.isStable).isFalse()

    val secondState = FacePreviewState(stability)
    currentTimeMillis = 500L
    secondState.updatePreviewSize(IntSize(100, 100))
    assertThat(secondState.processFrame(faceRect)?.isStable).isFalse()
    currentTimeMillis = 1_000L
    assertThat(secondState.processFrame(faceRect)?.isStable).isTrue()
  }

  @Test
  fun analyzeFrame_missingFace_ignoresStableResult() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))

    val result = state.processFrame(Rect.Zero)

    assertThat(result?.isStable).isFalse()
    assertThat(state.faceRect.value).isEqualTo(Rect.Zero)
    assertThat(state.isStable.value).isFalse()
    assertThat(state.shouldDetectFace).isTrue()
  }

  @Test
  fun analyzeFrame_stableResultStopsDetectionUntilReset() {
    var stable = false
    var onFrameCount = 0
    var resetCount = 0
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean {
          onFrameCount++
          return stable
        }

        override fun reset() {
          resetCount++
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    val faceRect = Rect(10f, 20f, 30f, 40f)

    assertThat(state.processFrame(faceRect)?.isStable).isFalse()
    stable = true
    assertThat(state.processFrame(faceRect)?.isStable).isTrue()
    assertThat(onFrameCount).isEqualTo(2)
    assertThat(state.shouldDetectFace).isFalse()
    assertThat(state.processFrame(faceRect)).isNull()

    stable = false
    state.resetStability()

    assertThat(resetCount).isEqualTo(1)
    assertThat(state.isStable.value).isFalse()
    assertThat(state.shouldDetectFace).isTrue()
    assertThat(state.processFrame(faceRect)?.isStable).isFalse()
    assertThat(resetCount).isEqualTo(2)
    assertThat(onFrameCount).isEqualTo(3)
  }

  @Test
  fun resetTracking_clearsUiImmediatelyAndResetsAlgorithmOnNextFrame() {
    var resetCount = 0
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = false

        override fun reset() {
          resetCount++
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    val faceRect = Rect(10f, 20f, 30f, 40f)
    state.processFrame(faceRect)

    state.resetTracking()

    assertThat(resetCount).isEqualTo(1)
    assertThat(state.faceRect.value).isEqualTo(Rect.Zero)
    assertThat(state.shouldDetectFace).isTrue()
    state.processFrame(faceRect)
    assertThat(resetCount).isEqualTo(2)
  }

  @Test
  fun resetTracking_keepsDeliveredStableState() {
    var resetCount = 0
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = true

        override fun reset() {
          resetCount++
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    state.processFrame(Rect(10f, 20f, 30f, 40f))

    state.resetTracking()

    assertThat(resetCount).isEqualTo(1)
    assertThat(state.isStable.value).isTrue()
    assertThat(state.shouldDetectFace).isFalse()
  }

  @Test
  fun resetTracking_discardsStableResultThatWasNotPublished() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))
    val result = checkNotNull(
      state.processFrame(
        faceRect = Rect(10f, 20f, 30f, 40f),
        publish = false,
      )
    )
    assertThat(state.shouldDetectFace).isFalse()
    assertThat(state.isStable.value).isFalse()

    state.resetTracking()

    assertThat(state.shouldDetectFace).isTrue()
    assertThat(state.publishAnalysisResult(result)).isFalse()
    assertThat(state.isStable.value).isFalse()
  }

  @Test
  fun resetTracking_preservesDetectorPause() {
    val state = stateWithResult(false)
    val expected = IllegalStateException("expected")
    state.updatePreviewSize(IntSize(100, 100))
    state.stopDetectionAfterError(expected)

    state.resetTracking()

    assertThat(state.faceRect.value).isEqualTo(Rect.Zero)
    assertThat(state.failure.value).isSameInstanceAs(expected)
    assertThat(state.shouldDetectFace).isFalse()
  }

  @Test
  fun stopDetectionAfterError_discardsQueuedResultAndPauses() {
    var onFrameCount = 0
    val expected = IllegalStateException("expected")
    val state = FacePreviewState(
      stability = statelessStability {
        onFrameCount++
        false
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    val queuedResult = checkNotNull(
      state.processFrame(
        faceRect = Rect(10f, 20f, 30f, 40f),
        publish = false,
      )
    )

    state.stopDetectionAfterError(expected)

    assertThat(state.publishAnalysisResult(queuedResult)).isFalse()
    assertThat(state.faceRect.value).isEqualTo(Rect.Zero)
    assertThat(state.isStable.value).isFalse()
    assertThat(state.failure.value).isSameInstanceAs(expected)
    assertThat(state.shouldDetectFace).isFalse()
    assertThat(state.processFrame(Rect(10f, 20f, 30f, 40f))).isNull()
    assertThat(onFrameCount).isEqualTo(1)
  }

  @Test
  fun stopDetectionAfterError_replacesStableStateWithFailure() {
    val state = stateWithResult(true)
    val expected = IllegalStateException("expected")
    state.updatePreviewSize(IntSize(100, 100))
    state.processFrame(Rect(10f, 20f, 30f, 40f))

    state.stopDetectionAfterError(expected)

    assertThat(state.isStable.value).isFalse()
    assertThat(state.failure.value).isSameInstanceAs(expected)
    assertThat(state.shouldDetectFace).isFalse()
  }

  @Test
  fun resetStability_afterError_resumesAndResetsOnAnalysisThread() {
    var resetCount = 0
    val expected = IllegalStateException("expected")
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = false

        override fun reset() {
          resetCount++
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    state.stopDetectionAfterError(expected)

    state.resetStability()

    assertThat(resetCount).isEqualTo(0)
    assertThat(state.failure.value).isNull()
    assertThat(state.shouldDetectFace).isTrue()
    state.processFrame(Rect(10f, 20f, 30f, 40f))
    assertThat(resetCount).isEqualTo(1)
  }

  @Test
  fun retry_afterError_clearsFailureAndResumesOnAnalysisThread() {
    var resetCount = 0
    val expected = IllegalStateException("expected")
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = false

        override fun reset() {
          resetCount++
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    state.stopDetectionAfterError(expected)

    state.retry()

    assertThat(resetCount).isEqualTo(0)
    assertThat(state.failure.value).isNull()
    assertThat(state.shouldDetectFace).isTrue()
    state.processFrame(Rect(10f, 20f, 30f, 40f))
    assertThat(resetCount).isEqualTo(1)
  }

  @Test
  fun retry_withoutFailure_preservesStableResult() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))
    state.processFrame(Rect(10f, 20f, 30f, 40f))

    state.retry()

    assertThat(state.failure.value).isNull()
    assertThat(state.isStable.value).isTrue()
    assertThat(state.shouldDetectFace).isFalse()
  }

  @Test
  fun updateDetectorIdentity_newDetectorRestartsDetectionAfterError() {
    var resetCount = 0
    val expected = IllegalStateException("expected")
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = false

        override fun reset() {
          resetCount++
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    val firstDetector = Any()
    assertThat(state.updateDetectorIdentity(firstDetector)).isFalse()
    state.stopDetectionAfterError(expected)

    assertThat(state.updateDetectorIdentity(firstDetector)).isFalse()
    assertThat(state.failure.value).isSameInstanceAs(expected)
    assertThat(state.shouldDetectFace).isFalse()
    assertThat(state.updateDetectorIdentity(Any())).isTrue()
    assertThat(state.failure.value).isNull()
    assertThat(state.shouldDetectFace).isTrue()
    assertThat(resetCount).isEqualTo(0)

    state.processFrame(Rect(10f, 20f, 30f, 40f))
    assertThat(resetCount).isEqualTo(1)
  }

  @Test
  fun updateDetectorIdentity_equalButDistinctDetectorReportsChange() {
    val state = stateWithResult(false)
    val firstDetector = EqualDetectorIdentity(value = 1)
    val equalDetector = EqualDetectorIdentity(value = 1)

    assertThat(firstDetector).isEqualTo(equalDetector)
    assertThat(firstDetector).isNotSameInstanceAs(equalDetector)
    assertThat(state.updateDetectorIdentity(firstDetector)).isFalse()
    assertThat(state.updateDetectorIdentity(equalDetector)).isTrue()
  }

  @Test
  fun updateFaceDetectorIdentity_frameStabilizesDuringInvalidation_resumesDetection() {
    val state = stateWithResult(true)
    state.updatePreviewSize(IntSize(100, 100))
    val coordinator = DetectedFrameCoordinator(Executor(Runnable::run))
    val firstDetector = Any()
    updateFaceDetectorIdentity(
      state = state,
      detector = firstDetector,
      frameCoordinator = coordinator,
      invalidateFrames = coordinator::invalidate,
    )
    var staleResult: FacePreviewAnalysisResult? = null

    updateFaceDetectorIdentity(
      state = state,
      detector = Any(),
      frameCoordinator = coordinator,
      invalidateFrames = {
        val snapshot = checkNotNull(state.beginAnalysisFrame())
        coordinator.invalidate()
        staleResult = try {
          state.analyzeFrame(
            snapshot = snapshot,
            frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), snapshot.previewSize),
            resetForTransformChange = false,
          )
        } finally {
          state.endAnalysisFrame(snapshot)
        }
        assertThat(staleResult?.isStable).isTrue()
        assertThat(state.shouldDetectFace).isFalse()
      },
    )

    assertThat(state.shouldDetectFace).isTrue()
    assertThat(state.isStable.value).isFalse()
    assertThat(state.publishAnalysisResult(checkNotNull(staleResult))).isFalse()
  }

  @Test
  fun analyzeFrame_transformChangeResetsBeforeCurrentFrame() {
    val events = mutableListOf<String>()
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean {
          events += "frame"
          return false
        }

        override fun reset() {
          events += "reset"
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    state.processFrame(Rect(10f, 20f, 30f, 40f))

    state.processFrame(
      faceRect = Rect(10f, 20f, 30f, 40f),
      resetForTransformChange = true,
    )

    assertThat(events).containsExactly("reset", "frame", "reset", "frame").inOrder()
  }

  @Test
  fun analyzeFrame_snapshotInvalidatedBeforeAnalysisDoesNotInvokeStability() {
    var onFrameCount = 0
    val state = FacePreviewState(
      stability = statelessStability {
        onFrameCount++
        false
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    val snapshot = checkNotNull(state.beginAnalysisFrame())
    state.resetTracking()

    val result = try {
      state.analyzeFrame(
        snapshot = snapshot,
        frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), snapshot.previewSize),
        resetForTransformChange = false,
      )
    } finally {
      state.endAnalysisFrame(snapshot)
    }

    assertThat(result).isNull()
    assertThat(onFrameCount).isEqualTo(0)
  }

  @Test
  fun resetStability_duringAnalysisDiscardsResultAndResetsOnNextAnalysisFrame() {
    val frameEntered = CountDownLatch(1)
    val releaseFrame = CountDownLatch(1)
    val callbackThreads = mutableListOf<Thread>()
    var resetCount = 0
    val state = FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean {
          callbackThreads += Thread.currentThread()
          frameEntered.countDown()
          check(releaseFrame.await(5, TimeUnit.SECONDS))
          return true
        }

        override fun reset() {
          callbackThreads += Thread.currentThread()
          resetCount++
        }
      },
    )
    state.updatePreviewSize(IntSize(100, 100))
    val executor = Executors.newSingleThreadExecutor()

    try {
      val firstSnapshot = checkNotNull(state.beginAnalysisFrame())
      val firstResult = executor.submit<FacePreviewAnalysisResult?> {
        try {
          state.analyzeFrame(
            snapshot = firstSnapshot,
            frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), firstSnapshot.previewSize),
            resetForTransformChange = false,
          )
        } finally {
          state.endAnalysisFrame(firstSnapshot)
        }
      }
      assertThat(frameEntered.await(5, TimeUnit.SECONDS)).isTrue()

      state.resetStability()
      assertThat(state.beginAnalysisFrame()).isNull()
      releaseFrame.countDown()

      assertThat(firstResult.get(5, TimeUnit.SECONDS)).isNull()
      assertThat(resetCount).isEqualTo(1)

      val secondSnapshot = checkNotNull(state.beginAnalysisFrame())
      val secondResult = executor.submit<FacePreviewAnalysisResult?> {
        try {
          state.analyzeFrame(
            snapshot = secondSnapshot,
            frame = syntheticFrame(Rect(10f, 20f, 30f, 40f), secondSnapshot.previewSize),
            resetForTransformChange = false,
          )
        } finally {
          state.endAnalysisFrame(secondSnapshot)
        }
      }.get(5, TimeUnit.SECONDS)

      assertThat(secondResult?.isStable).isTrue()
      assertThat(resetCount).isEqualTo(2)
      assertThat(callbackThreads).hasSize(4)
      assertThat(callbackThreads.distinct()).hasSize(1)
    } finally {
      releaseFrame.countDown()
      executor.shutdownNow()
    }
  }

  private fun FacePreviewState.processFrame(
    faceRect: Rect,
    imageFaceRect: Rect = faceRect,
    resetForTransformChange: Boolean = false,
    publish: Boolean = true,
  ): FacePreviewAnalysisResult? {
    val snapshot = beginAnalysisFrame() ?: return null
    return try {
      val result = analyzeFrame(
        snapshot = snapshot,
        frame = syntheticFrame(faceRect, snapshot.previewSize, imageFaceRect),
        resetForTransformChange = resetForTransformChange,
      ) ?: return null
      if (publish) check(publishAnalysisResult(result))
      result
    } finally {
      endAnalysisFrame(snapshot)
    }
  }

  private data class EqualDetectorIdentity(
    val value: Int,
  )
}
