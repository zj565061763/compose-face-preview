package com.sd.lib.compose.facepreview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FacePreviewFrameTest {
  @Test
  fun type_doesNotExposeDataClassCopyApi() {
    val methodNames = FacePreviewFrame::class.java.declaredMethods.map { it.name }

    assertThat(methodNames).doesNotContain("copy")
    assertThat(methodNames).doesNotContain("component1")
    assertThat(methodNames).doesNotContain("component2")
  }
}
