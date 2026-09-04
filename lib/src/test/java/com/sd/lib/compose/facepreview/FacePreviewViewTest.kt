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

  @Test
  fun createWithFailureCleanup_successDoesNotRunCleanup() {
    var cleanupCount = 0

    val result = createWithFailureCleanup(
      cleanup = { cleanupCount++ },
      create = { "result" },
    )

    assertThat(result).isEqualTo("result")
    assertThat(cleanupCount).isEqualTo(0)
  }

  @Test
  fun createWithFailureCleanup_creationFailsRunsCleanupAndPreservesFailure() {
    val expected = OutOfMemoryError("expected")
    var cleanupCount = 0

    val actual = try {
      createWithFailureCleanup<Unit>(
        cleanup = { cleanupCount++ },
        create = { throw expected },
      )
      null
    } catch (error: Throwable) {
      error
    }

    assertThat(actual).isSameInstanceAs(expected)
    assertThat(cleanupCount).isEqualTo(1)
  }

  @Test
  fun createWithFailureCleanup_cleanupFailsAddsSuppressedFailure() {
    val expected = OutOfMemoryError("expected")
    val cleanupFailure = IllegalStateException("cleanup")

    val actual = try {
      createWithFailureCleanup<Unit>(
        cleanup = { throw cleanupFailure },
        create = { throw expected },
      )
      null
    } catch (error: Throwable) {
      error
    }

    assertThat(actual).isSameInstanceAs(expected)
    assertThat(expected.suppressed).asList().containsExactly(cleanupFailure)
  }
}
