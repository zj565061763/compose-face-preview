package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import com.sd.lib.compose.camera.CameraFrameTransformToken
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.Executor

@RunWith(JUnit4::class)
class DetectedFrameCoordinatorTest {
  @Test
  fun submit_whileMainTaskIsPending_reusesSingleQueuedTaskForLatestFrame() {
    val queuedTasks = mutableListOf<Runnable>()
    val queuedExecutor = Executor(queuedTasks::add)
    val coordinator = readyCoordinator(queuedExecutor)
    var publishedFrame: AnalyzedFaceFrame? = null
    val firstFrame = analyzedFrame()
    val secondFrame = analyzedFrame()

    coordinator.submit(
      frameHandle = checkNotNull(coordinator.beginFrame(TestDetector)),
      frame = firstFrame,
    ) { publishedFrame = it }
    coordinator.submit(
      frameHandle = checkNotNull(coordinator.beginFrame(TestDetector)),
      frame = secondFrame,
    ) { publishedFrame = it }

    assertThat(queuedTasks).hasSize(1)
    queuedTasks.single().run()
    assertThat(publishedFrame).isSameInstanceAs(secondFrame)
  }

  @Test
  fun submitError_invalidatesPendingFrameAndBlocksLaterFramesUntilDelivery() {
    val queuedTasks = mutableListOf<Runnable>()
    val coordinator = readyCoordinator(Executor(queuedTasks::add))
    val pendingFrame = analyzedFrame()
    var publishedFrame: AnalyzedFaceFrame? = null
    coordinator.submit(
      frameHandle = checkNotNull(coordinator.beginFrame(TestDetector)),
      frame = pendingFrame,
    ) { publishedFrame = it }
    val errorFrameHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    val errorTransform = transformToken()
    val expected = OutOfMemoryError("expected")
    var reported: Throwable? = null

    assertThat(
      coordinator.submitTestError(
        frameHandle = errorFrameHandle,
        error = expected,
        frameTransformToken = errorTransform,
      ) { reported = it }
    ).isTrue()

    assertThat(coordinator.canAnalyzeFrame).isFalse()
    assertThat(
      coordinator.prepareFrame(
        transformToken = errorTransform,
        analysisGeneration = 0L,
        isFrameCurrent = { true },
      )
    ).isFalse()
    assertThat(coordinator.beginFrame(TestDetector)).isNull()
    assertThat(queuedTasks).hasSize(2)
    queuedTasks[0].run()
    assertThat(publishedFrame).isNull()
    queuedTasks[1].run()
    assertThat(reported).isSameInstanceAs(expected)
    assertThat(coordinator.canAnalyzeFrame).isTrue()
  }

  @Test
  fun prepareFrame_currentNewTransformDiscardsPendingError() {
    val queuedTasks = mutableListOf<Runnable>()
    val coordinator = readyCoordinator(Executor(queuedTasks::add))
    val oldTransform = transformToken()
    val currentTransform = transformToken()
    val errorFrameHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    var reported: Throwable? = null
    coordinator.submitTestError(
      frameHandle = errorFrameHandle,
      error = IllegalStateException("stale transform"),
      analysisGeneration = 3L,
      frameTransformToken = oldTransform,
    ) { reported = it }

    val canAnalyze = coordinator.prepareFrame(
      transformToken = currentTransform,
      analysisGeneration = 3L,
      isFrameCurrent = { true },
    )

    assertThat(canAnalyze).isTrue()
    assertThat(coordinator.beginFrame(TestDetector)).isNotNull()
    queuedTasks.single().run()
    assertThat(reported).isNull()
  }

  @Test
  fun prepareFrame_currentNewAnalysisGenerationDiscardsPendingError() {
    val queuedTasks = mutableListOf<Runnable>()
    val coordinator = readyCoordinator(Executor(queuedTasks::add))
    val frameTransformToken = transformToken()
    val errorFrameHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    var reported: Throwable? = null
    coordinator.submitTestError(
      frameHandle = errorFrameHandle,
      error = IllegalStateException("stale analysis"),
      analysisGeneration = 3L,
      frameTransformToken = frameTransformToken,
    ) { reported = it }

    val canAnalyze = coordinator.prepareFrame(
      transformToken = frameTransformToken,
      analysisGeneration = 4L,
      isFrameCurrent = { true },
    )

    assertThat(canAnalyze).isTrue()
    queuedTasks.single().run()
    assertThat(reported).isNull()
  }

  @Test
  fun handlePreviewError_afterCoordinatorClosed_ignoresDelayedError() {
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    val expected = IllegalStateException("stale preview")
    var reported: Throwable? = null
    coordinator.close()

    handlePreviewError(
      frameCoordinator = coordinator,
      error = expected,
    ) {
      reported = it
    }

    assertThat(coordinator.isActive).isFalse()
    assertThat(reported).isNull()
  }

  @Test
  fun handlePreviewError_whileCoordinatorActive_reportsError() {
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    val expected = IllegalStateException("current preview")
    var reported: Throwable? = null

    handlePreviewError(
      frameCoordinator = coordinator,
      error = expected,
    ) {
      reported = it
    }

    assertThat(coordinator.isActive).isTrue()
    assertThat(reported).isSameInstanceAs(expected)
  }

  @Test
  fun handlePreviewError_fromOldSession_doesNotInvalidateCurrentSession() {
    val coordinator = readyCoordinator(DirectExecutor)
    val currentHandle = checkNotNull(coordinator.beginFrame(TestDetector))

    handlePreviewError(
      frameCoordinator = coordinator,
      error = IllegalStateException("old session"),
      onError = {},
    )
    var reported: Throwable? = null
    val expected = IllegalStateException("current session")
    coordinator.submitTestError(currentHandle, expected) { reported = it }

    assertThat(reported).isSameInstanceAs(expected)
  }

  @Test
  fun handlePreviewUnavailable_invalidatesPendingSessionWork() {
    val state = FacePreviewState(
      stability = statelessStability(),
    )
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    coordinator.updateDetectorIdentity(TestDetector)
    coordinator.markTransformReady()
    val pendingHandle = checkNotNull(coordinator.beginFrame(TestDetector))

    handlePreviewUnavailable(state, coordinator)
    var reported: Throwable? = null
    coordinator.submitTestError(
      pendingHandle,
      IllegalStateException("unavailable preview"),
    ) { reported = it }

    assertThat(reported).isNull()
    assertThat(coordinator.canAnalyzeFrame).isFalse()
  }

  @Test
  fun transformReadiness_blocksFramesUntilCurrentTransformIsPublished() {
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    coordinator.updateDetectorIdentity(TestDetector)

    assertThat(coordinator.canAnalyzeFrame).isFalse()
    assertThat(coordinator.beginFrame(TestDetector)).isNull()
    coordinator.markTransformReady()
    assertThat(coordinator.canAnalyzeFrame).isTrue()
    assertThat(coordinator.beginFrame(TestDetector)).isNotNull()

    coordinator.invalidateTransform()
    assertThat(coordinator.canAnalyzeFrame).isFalse()
    assertThat(coordinator.beginFrame(TestDetector)).isNull()
    coordinator.markTransformReady()
    assertThat(coordinator.canAnalyzeFrame).isTrue()

    coordinator.close()
    assertThat(coordinator.canAnalyzeFrame).isFalse()
  }

  @Test
  fun handlePreviewSizeChanged_sameSizeKeepsCurrentWorkAndTransform() {
    val state = FacePreviewState(stability = statelessStability())
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    handlePreviewSizeChanged(state, coordinator, IntSize(100, 100))
    coordinator.updateDetectorIdentity(TestDetector)
    coordinator.markTransformReady()
    val snapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(snapshot)
    val frameHandle = checkNotNull(coordinator.beginFrame(TestDetector))

    handlePreviewSizeChanged(state, coordinator, IntSize(100, 100))
    var reported: Throwable? = null
    val expected = IllegalStateException("current size")
    coordinator.submitTestError(frameHandle, expected) { reported = it }

    assertThat(coordinator.canAnalyzeFrame).isTrue()
    assertThat(state.isAnalysisGenerationCurrent(snapshot.generation)).isTrue()
    assertThat(reported).isSameInstanceAs(expected)
  }

  @Test
  fun handlePreviewSizeChanged_newSizeInvalidatesWorkAndBlocksTransform() {
    val state = FacePreviewState(stability = statelessStability())
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    handlePreviewSizeChanged(state, coordinator, IntSize(100, 100))
    coordinator.updateDetectorIdentity(TestDetector)
    coordinator.markTransformReady()
    val snapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(snapshot)
    val frameHandle = checkNotNull(coordinator.beginFrame(TestDetector))

    handlePreviewSizeChanged(state, coordinator, IntSize(200, 100))
    var reported: Throwable? = null
    coordinator.submitTestError(frameHandle, IllegalStateException("stale size")) { reported = it }

    assertThat(coordinator.canAnalyzeFrame).isFalse()
    assertThat(state.isAnalysisGenerationCurrent(snapshot.generation)).isFalse()
    assertThat(reported).isNull()
  }

  @Test
  fun updateFaceDetectorIdentity_equalButDistinctDetector_discardsOldError() {
    val state = FacePreviewState(
      stability = statelessStability(),
    )
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    val firstDetector = EqualDetectorIdentity(value = 1)
    val equalDetector = EqualDetectorIdentity(value = 1)
    updateFaceDetectorIdentity(
      state = state,
      detector = firstDetector,
      frameCoordinator = coordinator,
    )
    coordinator.markTransformReady()
    val oldHandle = checkNotNull(coordinator.beginFrame(firstDetector))
    var reported: Throwable? = null

    updateFaceDetectorIdentity(
      state = state,
      detector = equalDetector,
      frameCoordinator = coordinator,
    )
    coordinator.submitTestError(
      frameHandle = oldHandle,
      error = IllegalStateException("stale detector"),
    ) {
      reported = it
    }

    assertThat(reported).isNull()
  }

  @Test
  fun updateFaceDetectorIdentity_switchBlocksCallbacksOnBothSidesOfSideEffect() {
    val state = FacePreviewState(stability = statelessStability())
    val coordinator = DetectedFrameCoordinator(DirectExecutor)
    val oldDetector = Any()
    val newDetector = Any()
    updateFaceDetectorIdentity(
      state = state,
      detector = oldDetector,
      frameCoordinator = coordinator,
    )
    coordinator.markTransformReady()

    // 新 lambda 可能先于提交检测器身份的 SideEffect 对分析线程可见。
    assertThat(coordinator.beginFrame(newDetector)).isNull()

    updateFaceDetectorIdentity(
      state = state,
      detector = newDetector,
      frameCoordinator = coordinator,
    )

    // 旧 lambda 也可能已经被分析线程取出，但延迟到 SideEffect 之后才进入回调。
    assertThat(coordinator.beginFrame(oldDetector)).isNull()
    assertThat(coordinator.beginFrame(newDetector)).isNotNull()
  }

  @Test
  fun submitError_whileErrorIsPending_keepsFirstTerminalError() {
    val queuedTasks = mutableListOf<Runnable>()
    val queuedExecutor = Executor(queuedTasks::add)
    val coordinator = readyCoordinator(queuedExecutor)
    val frameHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    val firstError = IllegalStateException("first")
    val secondError = IllegalStateException("second")
    var reported: Throwable? = null

    assertThat(coordinator.submitTestError(frameHandle, firstError) { reported = it }).isTrue()
    assertThat(coordinator.submitTestError(frameHandle, secondError) { reported = it }).isFalse()

    assertThat(queuedTasks).hasSize(1)
    queuedTasks.single().run()
    assertThat(reported).isSameInstanceAs(firstError)
  }

  @Test
  fun submitError_afterNewFrame_reportsError() {
    val coordinator = readyCoordinator(DirectExecutor)
    val errorHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    checkNotNull(coordinator.beginFrame(TestDetector))
    val expected = IllegalStateException("expected")
    var reported: Throwable? = null

    coordinator.submitTestError(errorHandle, expected) { reported = it }

    assertThat(reported).isSameInstanceAs(expected)
  }

  @Test
  fun submitError_withCapturedGeneration_reportsCurrentError() {
    val coordinator = readyCoordinator(DirectExecutor)
    val generation = coordinator.currentGeneration
    val expected = OutOfMemoryError("expected")
    var reported: Throwable? = null

    coordinator.submitTestError(generation, expected) { reported = it }

    assertThat(reported).isSameInstanceAs(expected)
  }

  @Test
  fun submitError_invalidatedBeforeMainExecution_discardsError() {
    var pendingTask: Runnable? = null
    val queuedExecutor = Executor { pendingTask = it }
    val coordinator = readyCoordinator(queuedExecutor)
    val errorHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    var reported: Throwable? = null

    coordinator.submitTestError(errorHandle, IllegalStateException("stale")) {
      reported = it
    }
    coordinator.invalidate()
    checkNotNull(pendingTask).run()

    assertThat(reported).isNull()
  }

  @Test
  fun submitError_transformChangedBeforeMainExecution_discardsError() {
    var isTransformCurrent = true
    var pendingTask: Runnable? = null
    val queuedExecutor = Executor { pendingTask = it }
    val coordinator = readyCoordinator(queuedExecutor)
    val errorHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    var reported: Throwable? = null

    coordinator.submitTestError(
      frameHandle = errorHandle,
      error = IllegalStateException("stale transform"),
      isErrorCurrent = { isTransformCurrent },
    ) {
      reported = it
    }
    isTransformCurrent = false
    checkNotNull(pendingTask).run()

    assertThat(reported).isNull()
  }

  @Test
  fun submitError_stabilityResetBeforeMainExecution_discardsError() {
    val state = FacePreviewState(
      stability = statelessStability(),
    )
    state.updatePreviewSize(IntSize(100, 100))
    val snapshot = checkNotNull(state.beginAnalysisFrame())
    state.endAnalysisFrame(snapshot)
    var pendingTask: Runnable? = null
    val queuedExecutor = Executor { pendingTask = it }
    val coordinator = readyCoordinator(queuedExecutor)
    val errorHandle = checkNotNull(coordinator.beginFrame(TestDetector))
    var reported: Throwable? = null

    coordinator.submitTestError(
      frameHandle = errorHandle,
      error = IllegalStateException("stale analysis"),
      isErrorCurrent = { state.isAnalysisGenerationCurrent(snapshot.generation) },
    ) {
      reported = it
    }
    state.resetStability()
    checkNotNull(pendingTask).run()

    assertThat(reported).isNull()
  }

  private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
  }

  private fun DetectedFrameCoordinator.submitTestError(
    frameHandle: DetectedFrameHandle,
    error: Throwable,
    analysisGeneration: Long = 0L,
    frameTransformToken: CameraFrameTransformToken = transformToken(),
    isErrorCurrent: () -> Boolean = { true },
    onError: (Throwable) -> Unit,
  ): Boolean {
    return submitError(
      generation = frameHandle.generation,
      analysisGeneration = analysisGeneration,
      transformToken = frameTransformToken,
      error = error,
      isErrorCurrent = isErrorCurrent,
      onError = onError,
    )
  }

  private fun DetectedFrameCoordinator.submitTestError(
    generation: Long,
    error: Throwable,
    analysisGeneration: Long = 0L,
    frameTransformToken: CameraFrameTransformToken = transformToken(),
    isErrorCurrent: () -> Boolean = { true },
    onError: (Throwable) -> Unit,
  ): Boolean {
    return submitError(
      generation = generation,
      analysisGeneration = analysisGeneration,
      transformToken = frameTransformToken,
      error = error,
      isErrorCurrent = isErrorCurrent,
      onError = onError,
    )
  }

  private fun readyCoordinator(executor: Executor): DetectedFrameCoordinator {
    return DetectedFrameCoordinator(executor).also {
      it.updateDetectorIdentity(TestDetector)
      it.markTransformReady()
    }
  }

  private fun analyzedFrame(): AnalyzedFaceFrame {
    return AnalyzedFaceFrame(
      stateResult = FacePreviewAnalysisResult(
        generation = 0L,
        faceRect = Rect.Zero,
        isStable = false,
      ),
      transformToken = transformToken(),
      stableFrame = null,
    )
  }

  private fun statelessStability(): FacePreviewStability {
    return object : FacePreviewStability {
      override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = false

      override fun reset() = Unit
    }
  }

  private fun transformToken(): CameraFrameTransformToken {
    val constructor = CameraFrameTransformToken::class.java.declaredConstructors.single()
    constructor.isAccessible = true
    return constructor.newInstance(
      *arrayOfNulls<Any>(constructor.parameterCount)
    ) as CameraFrameTransformToken
  }

  private data class EqualDetectorIdentity(
    val value: Int,
  )

  private object TestDetector
}
