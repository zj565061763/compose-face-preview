package com.sd.lib.compose.facepreview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FacePreviewViewTest {
  @Test
  fun recoverableAnalysisFailure_exceptionOrOutOfMemory_returnsTrue() {
    assertThat(IllegalStateException().isRecoverableFaceAnalysisFailure()).isTrue()
    assertThat(OutOfMemoryError().isRecoverableFaceAnalysisFailure()).isTrue()
  }

  @Test
  fun recoverableAnalysisFailure_otherError_returnsFalse() {
    assertThat(AssertionError().isRecoverableFaceAnalysisFailure()).isFalse()
    assertThat(LinkageError().isRecoverableFaceAnalysisFailure()).isFalse()
  }

  @Test
  fun requireStableFacePreviewFrame_nullThrowsRecoverableFailure() {
    val actual = try {
      requireStableFacePreviewFrame(null)
      null
    } catch (error: Throwable) {
      error
    }

    assertThat(actual).isInstanceOf(IllegalStateException::class.java)
    assertThat(actual?.message).isEqualTo("Failed to convert the stable camera frame to FacePreviewFrame.")
    assertThat(checkNotNull(actual).isRecoverableFaceAnalysisFailure()).isTrue()
  }
}
