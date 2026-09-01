package com.sd.lib.compose.facepreview

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FacePreviewFrameSourceTest {
  @Test
  fun previewSampled_usesDefaultInterval() {
    assertThat(FacePreviewFrameSource.PreviewSampled.Default.intervalMillis).isEqualTo(200)
  }

  @Test
  fun previewSampled_rejectsNonPositiveInterval() {
    assertThrows(IllegalArgumentException::class.java) {
      FacePreviewFrameSource.PreviewSampled(intervalMillis = 0)
    }
  }
}
