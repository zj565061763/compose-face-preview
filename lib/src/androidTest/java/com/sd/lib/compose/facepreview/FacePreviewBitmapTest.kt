package com.sd.lib.compose.facepreview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FacePreviewBitmapTest {
  @Test
  fun createFacePreviewFrame_quarterTurnReturnsWholeAndFaceImages() {
    val source = bitmap(
      width = 2,
      colors = intArrayOf(
        Color.RED, Color.GREEN,
        Color.BLUE, Color.CYAN,
        Color.MAGENTA, Color.YELLOW,
      ),
    )
    val previewMatrix = Matrix().apply {
      setValues(
        floatArrayOf(
          0f, -2f, 100f,
          3f, 0f, 200f,
          0f, 0f, 1f,
        ),
      )
    }

    val frame = source.createFacePreviewFrame(
      faceRect = RectF(0f, 1f, 2f, 3f),
      previewMatrix = previewMatrix,
      isMirrored = false,
      faceImageExpansionRatio = 0f,
    )

    checkNotNull(frame)
    val image = frame.image
    val faceImage = frame.faceImage
    try {
      assertThat(image).isNotSameInstanceAs(source)
      assertThat(image.width).isEqualTo(3)
      assertThat(image.height).isEqualTo(2)
      assertThat(image.pixels()).asList().containsExactly(
        Color.MAGENTA, Color.BLUE, Color.RED,
        Color.YELLOW, Color.CYAN, Color.GREEN,
      ).inOrder()
      assertThat(faceImage.width).isEqualTo(2)
      assertThat(faceImage.height).isEqualTo(2)
      assertThat(faceImage.pixels()).asList().containsExactly(
        Color.MAGENTA, Color.BLUE,
        Color.YELLOW, Color.CYAN,
      ).inOrder()
    } finally {
      frame.recycle()
      source.recycle()
    }
    assertThat(image.isRecycled).isTrue()
    assertThat(faceImage.isRecycled).isTrue()
  }

  @Test
  fun createFacePreviewFrame_expandsFaceImageWidthAndHeightByRatio() {
    val source = bitmap(
      width = 12,
      colors = IntArray(12 * 12) { index -> Color.rgb(index, 0, 0) },
    )

    val frame = source.createFacePreviewFrame(
      faceRect = RectF(2f, 2f, 10f, 10f),
      previewMatrix = Matrix(),
      isMirrored = false,
      faceImageExpansionRatio = 0.25f,
    )

    checkNotNull(frame)
    val faceImage = frame.faceImage
    try {
      assertThat(faceImage.width).isEqualTo(10)
      assertThat(faceImage.height).isEqualTo(10)
      assertThat(faceImage.getPixel(0, 0)).isEqualTo(Color.rgb(13, 0, 0))
      assertThat(faceImage.getPixel(9, 9)).isEqualTo(Color.rgb(130, 0, 0))
    } finally {
      frame.recycle()
      source.recycle()
    }
  }

  @Test
  fun createFacePreviewFrame_expansionOutsideSource_clampsToImageBounds() {
    val source = bitmap(
      width = 6,
      colors = IntArray(6 * 6) { index -> Color.rgb(index, 0, 0) },
    )

    val frame = source.createFacePreviewFrame(
      faceRect = RectF(0f, 0f, 4f, 4f),
      previewMatrix = Matrix(),
      isMirrored = false,
      faceImageExpansionRatio = 0.5f,
    )

    checkNotNull(frame)
    val faceImage = frame.faceImage
    try {
      assertThat(faceImage.width).isEqualTo(5)
      assertThat(faceImage.height).isEqualTo(5)
      assertThat(faceImage.getPixel(0, 0)).isEqualTo(Color.rgb(0, 0, 0))
      assertThat(faceImage.getPixel(4, 4)).isEqualTo(Color.rgb(28, 0, 0))
    } finally {
      frame.recycle()
      source.recycle()
    }
  }

  @Test
  fun createFacePreviewFrame_invalidExpansionRatio_throws() {
    val source = bitmap(width = 2, colors = intArrayOf(Color.RED, Color.BLUE))

    try {
      listOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { ratio ->
        val error = try {
          source.createFacePreviewFrame(
            faceRect = RectF(0f, 0f, 1f, 1f),
            previewMatrix = Matrix(),
            isMirrored = false,
            faceImageExpansionRatio = ratio,
          )
          null
        } catch (error: IllegalArgumentException) {
          error
        }
        assertThat(error).isNotNull()
      }
    } finally {
      source.recycle()
    }
  }

  @Test
  fun deliverStableFrame_callbackThrows_recyclesOwnedBitmaps() {
    val image = bitmap(width = 2, colors = intArrayOf(Color.RED, Color.BLUE))
    val faceImage = bitmap(width = 1, colors = intArrayOf(Color.GREEN))
    val expected = IllegalStateException("callback failed")

    val actual = try {
      deliverStableFrame(FacePreviewFrame(image, faceImage)) { throw expected }
      null
    } catch (error: Throwable) {
      error
    }

    assertThat(actual).isSameInstanceAs(expected)
    assertThat(image.isRecycled).isTrue()
    assertThat(faceImage.isRecycled).isTrue()
  }

  @Test
  fun createFacePreviewBitmap_identityCrop_preservesPixels() {
    val source = bitmap(
      width = 3,
      colors = intArrayOf(
        Color.RED, Color.GREEN, Color.BLUE,
        Color.CYAN, Color.MAGENTA, Color.YELLOW,
      ),
    )

    val result = source.createFacePreviewBitmap(
      faceRect = RectF(1f, 0f, 3f, 2f),
      previewMatrix = Matrix(),
      isMirrored = false,
    )

    checkNotNull(result)
    try {
      assertThat(result).isNotSameInstanceAs(source)
      assertThat(result.width).isEqualTo(2)
      assertThat(result.height).isEqualTo(2)
      assertThat(result.pixels()).asList().containsExactly(
        Color.GREEN, Color.BLUE,
        Color.MAGENTA, Color.YELLOW,
      ).inOrder()
    } finally {
      result.recycle()
      source.recycle()
    }
  }

  @Test
  fun createFacePreviewBitmap_fullIdentity_returnsOwnedCopy() {
    val source = bitmap(width = 2, colors = intArrayOf(Color.RED, Color.BLUE))

    val result = source.createFacePreviewBitmap(
      faceRect = RectF(0f, 0f, 2f, 1f),
      previewMatrix = Matrix(),
      isMirrored = false,
    )

    checkNotNull(result)
    try {
      assertThat(result).isNotSameInstanceAs(source)
      assertThat(result.pixels()).asList().containsExactly(Color.RED, Color.BLUE).inOrder()
    } finally {
      result.recycle()
      source.recycle()
    }
  }

  @Test
  fun createFacePreviewBitmap_scaledTranslatedQuarterTurn_keepsOnlyRotation() {
    val source = bitmap(
      width = 2,
      colors = intArrayOf(
        Color.RED, Color.GREEN,
        Color.BLUE, Color.CYAN,
        Color.MAGENTA, Color.YELLOW,
      ),
    )
    val previewMatrix = Matrix().apply {
      setValues(
        floatArrayOf(
          0f, -2f, 100f,
          3f, 0f, 200f,
          0f, 0f, 1f,
        ),
      )
    }

    val result = source.createFacePreviewBitmap(
      faceRect = RectF(0f, 0f, 2f, 3f),
      previewMatrix = previewMatrix,
      isMirrored = false,
    )

    checkNotNull(result)
    try {
      assertThat(result.width).isEqualTo(3)
      assertThat(result.height).isEqualTo(2)
      assertThat(result.pixels()).asList().containsExactly(
        Color.MAGENTA, Color.BLUE, Color.RED,
        Color.YELLOW, Color.CYAN, Color.GREEN,
      ).inOrder()
    } finally {
      result.recycle()
      source.recycle()
    }
  }

  @Test
  fun createFacePreviewBitmap_scaledTranslatedMirror_keepsOnlyMirror() {
    val source = bitmap(
      width = 3,
      colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE),
    )
    val previewMatrix = Matrix().apply {
      setValues(
        floatArrayOf(
          -2f, 0f, 100f,
          0f, 3f, 200f,
          0f, 0f, 1f,
        ),
      )
    }

    val result = source.createFacePreviewBitmap(
      faceRect = RectF(0f, 0f, 3f, 1f),
      previewMatrix = previewMatrix,
      isMirrored = true,
    )

    checkNotNull(result)
    try {
      assertThat(result.pixels()).asList().containsExactly(
        Color.BLUE, Color.GREEN, Color.RED,
      ).inOrder()
    } finally {
      result.recycle()
      source.recycle()
    }
  }

  @Test
  fun createFacePreviewBitmap_quarterTurnMirror_keepsCombinedOrientation() {
    val source = bitmap(
      width = 2,
      colors = intArrayOf(
        Color.RED, Color.GREEN,
        Color.BLUE, Color.CYAN,
        Color.MAGENTA, Color.YELLOW,
      ),
    )
    val previewMatrix = Matrix().apply {
      setValues(
        floatArrayOf(
          0f, 2f, 100f,
          3f, 0f, 200f,
          0f, 0f, 1f,
        ),
      )
    }

    val result = source.createFacePreviewBitmap(
      faceRect = RectF(0f, 0f, 2f, 3f),
      previewMatrix = previewMatrix,
      isMirrored = true,
    )

    checkNotNull(result)
    try {
      assertThat(result.width).isEqualTo(3)
      assertThat(result.height).isEqualTo(2)
      assertThat(result.pixels()).asList().containsExactly(
        Color.RED, Color.BLUE, Color.MAGENTA,
        Color.GREEN, Color.CYAN, Color.YELLOW,
      ).inOrder()
    } finally {
      result.recycle()
      source.recycle()
    }
  }

  @Test
  fun createFacePreviewBitmap_quarterTurnMirrorDisabled_keepsOnlyRotation() {
    val source = bitmap(
      width = 2,
      colors = intArrayOf(
        Color.RED, Color.GREEN,
        Color.BLUE, Color.CYAN,
        Color.MAGENTA, Color.YELLOW,
      ),
    )
    val previewMatrix = Matrix().apply {
      setValues(
        floatArrayOf(
          0f, 2f, 100f,
          3f, 0f, 200f,
          0f, 0f, 1f,
        ),
      )
    }

    val result = source.createFacePreviewBitmap(
      faceRect = RectF(0f, 0f, 2f, 3f),
      previewMatrix = previewMatrix,
      isMirrored = false,
    )

    checkNotNull(result)
    try {
      assertThat(result.width).isEqualTo(3)
      assertThat(result.height).isEqualTo(2)
      assertThat(result.pixels()).asList().containsExactly(
        Color.MAGENTA, Color.BLUE, Color.RED,
        Color.YELLOW, Color.CYAN, Color.GREEN,
      ).inOrder()
    } finally {
      result.recycle()
      source.recycle()
    }
  }

  private fun bitmap(width: Int, colors: IntArray): Bitmap {
    require(colors.size % width == 0)
    return Bitmap.createBitmap(colors, width, colors.size / width, Bitmap.Config.ARGB_8888)
  }

  private fun Bitmap.pixels(): IntArray {
    return IntArray(width * height).also { pixels ->
      getPixels(pixels, 0, width, 0, 0, width, height)
    }
  }
}
