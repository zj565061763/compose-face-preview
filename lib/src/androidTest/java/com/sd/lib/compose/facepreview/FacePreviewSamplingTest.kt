// 使用实际依赖的采样器验证截图次数，不模拟其间隔判断逻辑。
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.sd.lib.compose.facepreview

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.sd.lib.compose.camera.CameraAnalysisCoordinator
import com.sd.lib.compose.camera.CameraFrame
import com.sd.lib.compose.camera.CameraFrameTransformIdentity
import com.sd.lib.compose.camera.FrameProcessor
import com.sd.lib.compose.camera.PreviewSampledFrameDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FacePreviewSamplingTest {
  @Test
  fun sampledFrames_publishedStabilityPausesCaptureAndResetRestoresLatestInterval() {
    val state = createState()
    SampledCaptureCounter(state).use { sampler ->
      sampler.advanceBy(200)
      assertThat(sampler.captureCount).isEqualTo(1)

      lateinit var result: FacePreviewAnalysisResult
      onMainThread {
        state.withAnalysisFrameLease { snapshot ->
          result = checkNotNull(state.analyzeFrame(snapshot, syntheticFrame(snapshot.previewSize), false))
        }
        sampler.updateProcessor()
      }
      // 稳定结果交付前继续保留采样，允许过期结果被丢弃后恢复跟踪。
      sampler.advanceBy(200)
      assertThat(sampler.captureCount).isEqualTo(2)

      onMainThread {
        check(state.publishAnalysisResult(result))
        sampler.updateProcessor()
      }
      sampler.advanceBy(10_000)
      assertThat(sampler.captureCount).isEqualTo(2)

      onMainThread { sampler.updateProcessor(intervalMillis = 350) }
      sampler.advanceBy(10_000)
      assertThat(sampler.captureCount).isEqualTo(2)

      onMainThread {
        state.resetStability()
        sampler.updateProcessor(intervalMillis = 350)
      }
      sampler.advanceBy(1)
      assertThat(sampler.captureCount).isEqualTo(3)
      sampler.advanceBy(349)
      assertThat(sampler.captureCount).isEqualTo(3)
      sampler.advanceBy(1)
      assertThat(sampler.captureCount).isEqualTo(4)
    }
  }

  @Test
  fun sampledFrames_failurePausesCaptureAndRetryOrDetectorChangeResumes() {
    val state = createState()
    SampledCaptureCounter(state).use { sampler ->
      sampler.advanceBy(200)
      assertThat(sampler.captureCount).isEqualTo(1)

      onMainThread {
        state.stopDetectionAfterError(IllegalStateException("detection failed"))
        sampler.updateProcessor()
      }
      sampler.advanceBy(10_000)
      assertThat(sampler.captureCount).isEqualTo(1)

      onMainThread {
        state.retry()
        sampler.updateProcessor()
      }
      sampler.advanceBy(1)
      assertThat(sampler.captureCount).isEqualTo(2)

      onMainThread {
        state.stopDetectionAfterError(OutOfMemoryError("bitmap allocation failed"))
        sampler.updateProcessor()
      }
      sampler.advanceBy(10_000)
      assertThat(sampler.captureCount).isEqualTo(2)

      onMainThread {
        state.updateDetectorIdentity(Any())
        sampler.updateProcessor()
      }
      sampler.advanceBy(1)
      assertThat(sampler.captureCount).isEqualTo(3)
    }
  }

  private fun createState(): FacePreviewState {
    return FacePreviewState(
      stability = object : FacePreviewStability {
        override fun onFrame(frame: FacePreviewAnalysisFrame): Boolean = true
        override fun reset() = Unit
      },
    ).also { state ->
      onMainThread {
        state.updatePreviewSize(IntSize(100, 100))
        state.updateDetectorIdentity(Any())
      }
    }
  }

  private fun syntheticFrame(previewSize: Size): FacePreviewAnalysisFrame {
    return object : FacePreviewAnalysisFrame {
      override val cameraFrame: CameraFrame
        get() = error("Synthetic frame does not contain a CameraFrame.")
      override val rotationDegrees = 0
      override val imageFaceRect = Rect(20f, 20f, 60f, 60f)
      override val faceRect = imageFaceRect
      override val previewSize = previewSize
    }
  }

  private fun onMainThread(block: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
  }
}

private class SampledCaptureCounter(
  private val state: FacePreviewState,
) : AutoCloseable {
  private val _executor = Executors.newSingleThreadExecutor()
  private val _analysisCoordinator = CameraAnalysisCoordinator { _executor }
  private val _captureCount = AtomicInteger()
  private val _sessionIdentity = CameraFrameTransformIdentity()
  private var _elapsedMillis = 0L
  private var _frameProcessor = processor(intervalMillis = 200)
  private val _dispatcher = PreviewSampledFrameDispatcher(
    mainHandler = Handler(Looper.getMainLooper()),
    intervalMillis = { _frameProcessor.intervalMillis },
    captureFrame = { _, _ ->
      _captureCount.incrementAndGet()
      null
    },
    onFrame = {},
    onError = { throw it },
    elapsedRealtimeMillis = { _elapsedMillis },
    analysisCoordinator = _analysisCoordinator,
  ).also { it.start() }

  val captureCount: Int
    get() = _captureCount.get()

  fun updateProcessor(intervalMillis: Long = 200) {
    _frameProcessor = processor(intervalMillis)
  }

  fun advanceBy(millis: Long) {
    _elapsedMillis += millis
    _dispatcher.offer(_sessionIdentity, false)
    // 等待此前的采样任务完成，使截图次数断言不依赖真实时间或线程调度速度。
    _executor.submit {}.get(5, TimeUnit.SECONDS)
  }

  override fun close() {
    try {
      _dispatcher.close()
    } finally {
      _analysisCoordinator.close()
      _executor.shutdownNow()
    }
  }

  private fun processor(intervalMillis: Long): FrameProcessor.PreviewSampled {
    return createFacePreviewFrameProcessor(
      source = FacePreviewFrameSource.PreviewSampled(intervalMillis),
      state = state,
      onFrame = {},
    ) as FrameProcessor.PreviewSampled
  }
}
