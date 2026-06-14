package com.example

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Range
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.GlassBackground
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlin.math.atan2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraApp() {
    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )

    if (cameraPermissionState.status.isGranted) {
        CameraScreen()
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Camera permission is required.",
                color = Color.White,
                modifier = Modifier.padding(16.dp)
            )
            Button(
                onClick = { cameraPermissionState.launchPermissionRequest() },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
            ) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // State for Horizon Lock
    var rollState by remember { mutableFloatStateOf(0f) }
    var isHorizonLockEnabled by remember { mutableStateOf(true) }

    // State for Camera Controls
    var is4k by remember { mutableStateOf(false) }
    var isUltrawide by remember { mutableStateOf(false) }
    var fpsLock by remember { mutableStateOf(true) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    // Sensor Manager setup for Horizon Lock
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        val sensorEventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR && isHorizonLockEnabled) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientationAngles = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    
                    // roll is typically orientationAngles[2]
                    // Depending on device display orientation, this might need mapping.
                    // Assuming portrait lock for now:
                    var rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                    
                    // Smooth the rotation (simple low-pass filter)
                    rollState = rollState + 0.2f * (rollDeg - rollState)
                } else if (!isHorizonLockEnabled) {
                    rollState = 0f
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        
        sensorManager.registerListener(sensorEventListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(sensorEventListener)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Edge-to-edge camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (isHorizonLockEnabled) {
                        rotationZ = -rollState // Apply opposite roll to horizon lock
                        
                        val rad = Math.toRadians(rollState.toDouble())
                        val cosA = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
                        val sinA = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
                        val aspect = size.height / size.width
                        
                        // Scale to prevent black borders when rotated
                        val minScale = kotlin.math.max(
                            cosA + aspect * sinA,
                            cosA + (1f / aspect) * sinA
                        )
                        // Add an extra 5% buffer to hide edges smoothly during fast rotation
                        val finalScale = minScale * 1.05f 
                        scaleX = finalScale
                        scaleY = finalScale
                    }
                },
            update = { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                    // Resolution Strategy
                    val resolution = if (is4k) Size(3840, 2160) else Size(1920, 1080)
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(resolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
                        .build()

                    val previewBuilder = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)

                    // Attempt 30 FPS Lock via Camera2Interop
                    if (fpsLock) {
                        val extender = Camera2Interop.Extender(previewBuilder)
                        extender.setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(30, 30)
                        )
                    }

                    val preview = previewBuilder.build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview
                        )
                        cameraControl = camera.cameraControl
                        
                        // Simulate lens switch via zoom ratio (0.5 for ultrawide if supported, 1.0 main)
                        cameraControl?.setZoomRatio(if (isUltrawide) 0.5f else 1.0f)

                    } catch (exc: Exception) {
                        exc.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Liquid Glass Floating Control Panel
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(GlassBackground)
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Top Row: Settings Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassToggle(
                        text = if (is4k) "4K" else "High",
                        isActive = is4k,
                        onClick = { is4k = !is4k }
                    )
                    GlassToggle(
                        text = if (isUltrawide) "0.5x UW" else "1x Wide",
                        isActive = isUltrawide,
                        onClick = { isUltrawide = !isUltrawide }
                    )
                    GlassToggle(
                        text = "Hz Lock",
                        isActive = isHorizonLockEnabled,
                        onClick = { isHorizonLockEnabled = !isHorizonLockEnabled }
                    )
                    GlassToggle(
                        text = "30 FPS",
                        isActive = fpsLock,
                        onClick = { fpsLock = !fpsLock }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Bottom Row: Capture Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Capture Button
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(4.dp, NeonCyan, CircleShape)
                            .clickable { /* Simulate capture */ }
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.8f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlassToggle(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) NeonCyan.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isActive) NeonCyan else Color.White.copy(alpha = 0.7f),
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
