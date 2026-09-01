package com.sd.demo.compose.facepreview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sd.demo.compose.facepreview.theme.AppTheme
import com.sd.lib.compose.camera.rememberCameraDevicesState
import com.sd.lib.compose.facepreview.DefaultFacePreviewStability
import com.sd.lib.compose.facepreview.FacePreviewFrame
import com.sd.lib.compose.facepreview.FacePreviewFrameSource
import com.sd.lib.compose.facepreview.FacePreviewFramingStatus
import com.sd.lib.compose.facepreview.FacePreviewView
import com.sd.lib.compose.facepreview.rememberFacePreviewState
import com.sd.lib.facedetector.FaceDetector

class SampleActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Content()
      }
    }
  }
}

@Composable
private fun Content(
  modifier: Modifier = Modifier,
  faceDetector: FaceDetector = AppFaceDetector,
) {
  val context = LocalContext.current
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    hasCameraPermission = granted
  }

  LaunchedEffect(Unit) {
    if (!hasCameraPermission) {
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    if (hasCameraPermission) {
      CameraContent(faceDetector = faceDetector)
    } else {
      CameraPermissionContent { permissionLauncher.launch(Manifest.permission.CAMERA) }
    }
  }
}

@Composable
private fun CameraContent(
  modifier: Modifier = Modifier,
  faceDetector: FaceDetector,
) {
  val stability = remember { DefaultFacePreviewStability(sizeRatio = 0.3f) }
  val state = rememberFacePreviewState(stability)
  val devicesState = rememberCameraDevicesState()
  val devices by devicesState.devices
  val devicesLoading by devicesState.isLoading
  val framingStatus by stability.currentFramingStatus
  val failure by state.failure
  var selectedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
  var stableFrame by remember { mutableStateOf<FacePreviewFrame?>(null) }

  LaunchedEffect(devices, devicesLoading) {
    if (!devicesLoading && devices.none { it.cameraId == selectedCameraId }) {
      selectedCameraId = devices.firstOrNull()?.cameraId
    }
  }
  val selectedCamera = devices.firstOrNull { it.cameraId == selectedCameraId }

  DisposableEffect(stableFrame) {
    val frame = stableFrame
    onDispose { frame?.recycle() }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(0.5f)
        .aspectRatio(1f)
        .clip(CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      if (selectedCamera != null) {
        FacePreviewView(
          modifier = Modifier.fillMaxSize(),
          state = state,
          devicesState = devicesState,
          faceDetector = faceDetector,
          cameraId = selectedCamera.cameraId,
          frameSource = FacePreviewFrameSource.PreviewSampled.Default,
          onStableFrame = { frame -> stableFrame = frame },
        )
      } else {
        val message = when {
          devicesLoading -> "正在读取摄像头"
          devicesState.error.value != null -> "读取摄像头失败"
          else -> "未发现可用摄像头"
        }
        Text(text = message)
      }
    }

    when (framingStatus) {
      FacePreviewFramingStatus.FaceTooSmall -> "请靠近一点"
      FacePreviewFramingStatus.FaceTooLarge -> "请远离一点"
      FacePreviewFramingStatus.FaceTooCloseToEdge -> "请将人脸移到中央"
      else -> null
    }?.also { message ->
      Text(text = message)
    }

    if (failure != null) {
      Text(text = "人脸检测失败")
      Button(onClick = state::retry) {
        Text(text = "重试检测")
      }
    }

    selectedCamera?.also { camera ->
      Text(text = "cameraId=${camera.cameraId}, lens=${camera.lens ?: "UNKNOWN"}")
    }

    Button(
      enabled = devices.size > 1,
      onClick = {
        val currentIndex = devices.indexOfFirst { it.cameraId == selectedCameraId }
        if (currentIndex >= 0) {
          selectedCameraId = devices[(currentIndex + 1) % devices.size].cameraId
        }
      },
    ) {
      Text(text = "切换")
    }

    stableFrame?.also { frame ->
      Spacer(Modifier.height(16.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        frame.faceImage.also { image ->
          Image(
            modifier = Modifier.weight(1f),
            bitmap = remember(image) { image.asImageBitmap() },
            contentDescription = null,
          )
        }
        frame.image.also { image ->
          Image(
            modifier = Modifier.weight(1f),
            bitmap = remember(image) { image.asImageBitmap() },
            contentDescription = null,
          )
        }
      }

      Button(
        modifier = Modifier.padding(top = 16.dp),
        onClick = {
          stableFrame = null
          state.resetStability()
        },
      ) {
        Text(text = "清空稳定性")
      }
    }
  }
}

@Composable
private fun CameraPermissionContent(
  modifier: Modifier = Modifier,
  onRequestPermission: () -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "需要摄像头权限才能进行本地人脸检测",
      color = Color.White,
      style = MaterialTheme.typography.bodyLarge,
    )
    Button(
      modifier = Modifier.padding(top = 16.dp),
      onClick = onRequestPermission,
    ) {
      Text("授予摄像头权限")
    }
  }
}
