package com.sd.lib.compose.facepreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FacePreviewGeometryTest {
  @Test
  fun calculateSizeRatio_returnsWidthAndHeightRatio() {
    val result = checkNotNull(
      calculateSizeRatio(
        rect = Rect(40f, 60f, 60f, 140f),
        size = Size(100f, 200f),
      )
    )

    assertThat(result.width).isWithin(0.0001f).of(0.2f)
    assertThat(result.height).isWithin(0.0001f).of(0.4f)
  }

  @Test
  fun calculateSizeRatio_invalidFaceOrPreview_returnsNull() {
    assertThat(
      calculateSizeRatio(Rect.Zero, Size(100f, 100f))
    ).isNull()
    assertThat(
      calculateSizeRatio(Rect(40f, 40f, 60f, 60f), Size.Zero)
    ).isNull()
    assertThat(
      calculateSizeRatio(Rect(0f, 0f, Float.POSITIVE_INFINITY, 10f), Size(100f, 100f))
    ).isNull()
  }

  @Test
  fun calculateMarginRatio_returnsEdgeRatios() {
    val result = calculateMarginRatio(
      rect = Rect(20f, 40f, 70f, 120f),
      size = Size(100f, 200f),
    )

    assertThat(result).isEqualTo(Rect(0.2f, 0.2f, 0.3f, 0.4f))
  }

  @Test
  fun calculateMarginRatio_invalidFaceOrPreview_returnsNull() {
    assertThat(
      calculateMarginRatio(Rect.Zero, Size(100f, 100f))
    ).isNull()
    assertThat(
      calculateMarginRatio(Rect(40f, 40f, 60f, 60f), Size.Zero)
    ).isNull()
    assertThat(
      calculateMarginRatio(Rect(0f, 0f, Float.POSITIVE_INFINITY, 10f), Size(100f, 100f))
    ).isNull()
  }
}
