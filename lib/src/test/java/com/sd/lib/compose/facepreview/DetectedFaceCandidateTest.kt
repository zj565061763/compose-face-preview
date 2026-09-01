package com.sd.lib.compose.facepreview

import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DetectedFaceCandidateTest {
  @Test
  fun firstFullyVisibleFace_skipsPartiallyVisibleLeadingFace() {
    val partiallyVisibleFace = candidate(rectF(-10f, 10f, 20f, 40f))
    val fullyVisibleFace = candidate(rectF(10f, 20f, 40f, 60f))

    val result = listOf(partiallyVisibleFace, fullyVisibleFace)
      .firstFullyVisibleFace(Size(100f, 100f))

    assertThat(result).isSameInstanceAs(fullyVisibleFace)
  }

  @Test
  fun firstFullyVisibleFace_preservesDetectorOrder() {
    val firstFace = candidate(rectF(10f, 20f, 40f, 60f))
    val secondFace = candidate(rectF(50f, 20f, 80f, 60f))

    val result = listOf(firstFace, secondFace).firstFullyVisibleFace(Size(100f, 100f))

    assertThat(result).isSameInstanceAs(firstFace)
  }

  @Test
  fun isFullyVisibleIn_anyBoundaryOutside_returnsFalse() {
    val previewSize = Size(100f, 100f)

    assertThat(rectF(-1f, 10f, 20f, 30f).isFullyVisibleIn(previewSize)).isFalse()
    assertThat(rectF(10f, -1f, 20f, 30f).isFullyVisibleIn(previewSize)).isFalse()
    assertThat(rectF(10f, 20f, 101f, 30f).isFullyVisibleIn(previewSize)).isFalse()
    assertThat(rectF(10f, 20f, 30f, 101f).isFullyVisibleIn(previewSize)).isFalse()
  }

  @Test
  fun isFullyVisibleIn_onPreviewBoundary_returnsTrue() {
    val result = rectF(0f, 0f, 100f, 100f).isFullyVisibleIn(Size(100f, 100f))

    assertThat(result).isTrue()
  }

  private fun candidate(previewFaceRect: RectF): DetectedFaceCandidate {
    return DetectedFaceCandidate(
      rawFaceRect = rectF(0f, 0f, 10f, 10f),
      previewFaceRect = previewFaceRect,
    )
  }

  /** android.jar 的 RectF 参数构造方法在 JVM 单测中不会写入字段。 */
  private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
    return RectF().apply {
      this.left = left
      this.top = top
      this.right = right
      this.bottom = bottom
    }
  }
}
