package com.sd.lib.compose.facepreview

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun FacePreviewRectView(
  modifier: Modifier = Modifier,
  rect: Rect,
  color: Color = Color.Green,
  strokeWidth: Dp = 1.dp,
  cornerRadius: Dp = 0.dp,
) {
  Canvas(modifier = modifier) {
    if (rect.isEmpty) return@Canvas
    val strokeWidthPx = strokeWidth.toPx()
    val boxCornerRadiusPx = cornerRadius.toPx()
    drawRoundRect(
      color = color,
      topLeft = rect.topLeft,
      size = rect.size,
      cornerRadius = CornerRadius(boxCornerRadiusPx),
      style = Stroke(width = strokeWidthPx),
    )
  }
}
