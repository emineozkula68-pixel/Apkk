package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

// Advanced UI styling colors
val HudNeonGreen = Color(0xFF00FF66)
val HudNeonBlue = Color(0xFF00E5FF)
val HudNeonAmber = Color(0xFFFFB300)
val HudNeonRed = Color(0xFFFF3366)
val HudNeonPurple = Color(0xFFD500F9)

val HudDarkBg = Color(0xFF05080C)
val HudBgTranslucent = Color(0xCC080C14)
val HudCardBg = Color(0xE60E1520)

enum class VideoFilter {
    NORMAL, NIGHT_VISION, CYBER_AMBER, THERMAL_BLUE
}

enum class TargetCategory {
    ALL, PEOPLE, ELECTRONICS, FURNITURE, OTHER
}

data class RawDetection(
    val id: Int,
    val rect: Rect,
    val label: String,
    val confidence: Float,
    val firstDetectedMs: Long = System.currentTimeMillis()
)

data class FrameMetadata(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val isFront: Boolean
)

// Captured telemetry snapshot log
data class TelemetrySnapshot(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val targetsCount: Int,
    val details: List<String>,
    val filterMode: VideoFilter
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize TextToSpeech engine
        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            Log.e("TTS", "Failed to initialize TTS", e)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ObjectDetectorApp(
                            onSpeak = { text -> speakOut(text) }
                        )
                    }
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("tr", "TR"))
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsReady = true
                tts?.setSpeechRate(1.1f)
                speakOut("Sistem aktif. Yapay zeka gözlemleyici başlatıldı.")
            }
        }
    }

    private fun speakOut(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun ObjectDetectorApp(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (granted) {
                onSpeak("Kamera erişimi onaylandı. Sistem başlatılıyor.")
            }
        }
    )

    if (hasCameraPermission) {
        ObjectDetectorHUD(onSpeak = onSpeak)
    } else {
        PermissionRequiredScreen {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

@Composable
fun PermissionRequiredScreen(onRequestPermission: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudDarkBg),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(320.dp)) {
            drawCircle(
                color = HudNeonGreen.copy(alpha = 0.05f),
                radius = size.minDimension / 2f
            )
            drawCircle(
                color = HudNeonGreen.copy(alpha = 0.12f * pulseAlpha),
                radius = size.minDimension / 2.8f,
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f))
            )
            drawCircle(
                color = HudNeonGreen.copy(alpha = 0.25f),
                radius = size.minDimension / 4f,
                style = Stroke(width = 1.5f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = "Güvenli Sistem Erişimi",
                tint = HudNeonGreen,
                modifier = Modifier
                    .size(90.dp)
                    .border(2.dp, HudNeonGreen, CircleShape)
                    .padding(20.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "TARKAN TAKTİK RADAR",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Çift kamera akışı üzerinden anlık nesne/hedef tespiti, uzaklık kestirimi ve sesli koordinat raporlama özelliklerini çalıştırmak için kamera izni gereklidir.",
                color = Color.Gray,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(horizontal = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(44.dp))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF003314),
                    contentColor = HudNeonGreen
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .border(1.5.dp, HudNeonGreen, RoundedCornerShape(6.dp)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Adjust,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SİSTEMİ BAŞLAT",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ObjectDetectorHUD(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Advanced Custom Settings & State
    var isFrontCamera by remember { mutableStateOf(false) }
    var confidenceThreshold by remember { mutableFloatStateOf(0.35f) }
    var activeFilter by remember { mutableStateOf(VideoFilter.NORMAL) }
    var activeCategory by remember { mutableStateOf(TargetCategory.ALL) }

    // Display/Toggles
    var laserLinesEnabled by remember { mutableStateOf(true) }
    var audioFeedbackEnabled by remember { mutableStateOf(true) }
    var voiceAssistantEnabled by remember { mutableStateOf(true) }
    var coordinatesPercentMode by remember { mutableStateOf(false) }
    var isControlPanelExpanded by remember { mutableStateOf(true) }
    var showUIOverlays by remember { mutableStateOf(true) }

    // Live Target Tracking Logs & Snapshots Archives
    var rawDetections by remember { mutableStateOf<List<RawDetection>>(emptyList()) }
    var frameMetadata by remember { mutableStateOf<FrameMetadata?>(null) }
    var snapshotsList by remember { mutableStateOf<List<TelemetrySnapshot>>(emptyList()) }

    // Stats calculations
    var lastFrameTime by remember { mutableLongStateOf(0L) }
    var fpsValue by remember { mutableIntStateOf(30) }

    // Beeper for tactile scanning feedback
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        } catch (e: Exception) {
            null
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            try {
                toneGenerator?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // Voice announcer logic for target locks
    var lastAnnouncedTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(rawDetections) {
        val currentLabels = rawDetections.map { translateLabel(it.label) }.toSet()
        val newlyDiscovered = currentLabels - lastAnnouncedTargets

        if (newlyDiscovered.isNotEmpty()) {
            if (voiceAssistantEnabled) {
                val targetsJoined = newlyDiscovered.joinToString(", ")
                onSpeak("Hedef kilitlendi: $targetsJoined")
            } else if (audioFeedbackEnabled) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 80)
                } catch (e: Exception) {
                    Log.e("Sound", "Failed to beep", e)
                }
            }
        }
        lastAnnouncedTargets = currentLabels
    }

    val objectDetector = remember {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(options)
    }

    // Dynamic CameraX Stream Binding
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    LaunchedEffect(isFrontCamera, previewView) {
        val currentPreview = previewView ?: return@LaunchedEffect
        
        // Wait for 500ms to allow Android's AppOps permission state to fully propagate in the system
        kotlinx.coroutines.delay(500)
        
        // Double check permission before attempting to access CameraX
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("HUDCamera", "Camera permission is NOT granted inside LaunchedEffect.")
            return@LaunchedEffect
        }
        
        val cameraProvider = context.getCameraProvider()
        val cameraLens = if (isFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(cameraLens).build()

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(currentPreview.surfaceProvider)

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val now = System.currentTimeMillis()
            if (lastFrameTime != 0L) {
                val diff = now - lastFrameTime
                if (diff > 0) {
                    val currentFps = (1000 / diff).toInt()
                    fpsValue = if (fpsValue == 30) currentFps else (fpsValue * 0.9f + currentFps * 0.1f).toInt()
                }
            }
            lastFrameTime = now

            processImageProxy(
                imageProxy = imageProxy,
                detector = objectDetector,
                isFrontCamera = isFrontCamera,
                confidenceThreshold = confidenceThreshold,
                categoryFilter = activeCategory,
                onSuccess = { detections, metadata ->
                    rawDetections = detections
                    frameMetadata = metadata
                }
            )
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("HUDCamera", "Binding failure", e)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Live Interactive Camera View (With Video Tint Filters applied)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    // Color matrix overlays for night vision and tactical HUD themes
                    when (activeFilter) {
                        VideoFilter.NIGHT_VISION -> {
                            drawRect(
                                color = Color(0x3300FF33),
                                blendMode = BlendMode.Color
                            )
                            drawRect(
                                color = Color(0x1500FF00),
                                blendMode = BlendMode.Overlay
                            )
                        }
                        VideoFilter.CYBER_AMBER -> {
                            drawRect(
                                color = Color(0x3AFF9E00),
                                blendMode = BlendMode.Color
                            )
                        }
                        VideoFilter.THERMAL_BLUE -> {
                            drawRect(
                                color = Color(0x350022FF),
                                blendMode = BlendMode.Color
                            )
                            drawRect(
                                color = Color(0x20FF0033),
                                blendMode = BlendMode.Difference
                            )
                        }
                        VideoFilter.NORMAL -> { /* Clear feed */ }
                    }
                }
        ) {
            CameraPreview(modifier = Modifier.fillMaxSize()) { view ->
                previewView = view
            }
        }

        // 2. Neon HUD Bounding Boxes & Telemetry Render Layer
        if (showUIOverlays) {
            HUDOverlayCanvas(
                rawDetections = rawDetections,
                frameMetadata = frameMetadata,
                laserTrackerEnabled = laserLinesEnabled,
                coordinatesInPercent = coordinatesPercentMode,
                currentFilter = activeFilter
            )
        }

        // 3. Floating Custom Mode Panels (Filters & Category Selectors)
        if (showUIOverlays) {
            HUDSettingsSelectors(
                activeFilter = activeFilter,
                activeCategory = activeCategory,
                onFilterChange = {
                    activeFilter = it
                    if (voiceAssistantEnabled) {
                        val modeName = when (it) {
                            VideoFilter.NORMAL -> "Normal Görüntü"
                            VideoFilter.NIGHT_VISION -> "Gece Görüşü Filtresi"
                            VideoFilter.CYBER_AMBER -> "Krom Kehribar Görüşü"
                            VideoFilter.THERMAL_BLUE -> "Termal Spektrum Filtresi"
                        }
                        onSpeak("$modeName aktif.")
                    }
                },
                onCategoryChange = {
                    activeCategory = it
                    if (voiceAssistantEnabled) {
                        val catName = when (it) {
                            TargetCategory.ALL -> "Tüm nesneler"
                            TargetCategory.PEOPLE -> "Sadece insanlar"
                            TargetCategory.ELECTRONICS -> "Sadece elektronik cihazlar"
                            TargetCategory.FURNITURE -> "Sadece ev eşyaları"
                            TargetCategory.OTHER -> "Diğer nesneler"
                        }
                        onSpeak("$catName hedefleniyor.")
                    }
                }
            )
        }

        // 4. Tech Action Buttons Control Dock (Top Right Overlay)
        if (showUIOverlays) {
            HUDQuickActionsDock(
                isFrontCamera = isFrontCamera,
                soundEnabled = audioFeedbackEnabled,
                laserTrackerEnabled = laserLinesEnabled,
                voiceEnabled = voiceAssistantEnabled,
                onCameraToggle = { isFrontCamera = !isFrontCamera },
                onSoundToggle = { audioFeedbackEnabled = !audioFeedbackEnabled },
                onLaserToggle = { laserLinesEnabled = !laserLinesEnabled },
                onVoiceToggle = { voiceAssistantEnabled = !voiceAssistantEnabled }
            )
        }

        // 5. Tech System Telemetry Display (Top Left Overlay)
        if (showUIOverlays) {
            HUDStatsPanel(
                activeTargetsCount = rawDetections.size,
                fps = fpsValue,
                confidenceThreshold = confidenceThreshold,
                filterMode = activeFilter
            )
        }

        // 6. Cybernetic Analysis Drawer & Saved Snapshots Archive List (At Bottom)
        if (showUIOverlays) {
            HUDControlPanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                rawDetections = rawDetections,
                confidenceThreshold = confidenceThreshold,
                coordinatesInPercent = coordinatesPercentMode,
                isPanelExpanded = isControlPanelExpanded,
                snapshots = snapshotsList,
                onThresholdChange = { confidenceThreshold = it },
                onCoordinatesToggle = { coordinatesPercentMode = it },
                onPanelToggle = { isControlPanelExpanded = !isControlPanelExpanded },
                onCaptureSnapshot = {
                    val currentTime = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val newSnapshot = TelemetrySnapshot(
                        timestamp = currentTime,
                        targetsCount = rawDetections.size,
                        details = rawDetections.map { "${translateLabel(it.label)} (${(it.confidence * 100).roundToInt()}% @ [X: ${it.rect.centerX()}, Y: ${it.rect.centerY()}])" },
                        filterMode = activeFilter
                    )
                    snapshotsList = (listOf(newSnapshot) + snapshotsList).take(15) // Keep last 15
                    if (voiceAssistantEnabled) {
                        onSpeak("Telemetri verisi kara kutuya kaydedildi.")
                    }
                    try {
                        toneGenerator?.startTone(ToneGenerator.TONE_SUP_CONFIRM, 120)
                    } catch (e: Exception) {
                        Log.e("Tone", "Capture alert failed", e)
                    }
                },
                onClearLogs = {
                    snapshotsList = emptyList()
                    if (voiceAssistantEnabled) {
                        onSpeak("Kara kutu arşivi temizlendi.")
                    }
                }
            )
        }

        // 7. Futuristic Toggle HUD Visibility Button (Top Center Overlay)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(HudBgTranslucent)
                    .border(
                        1.5.dp,
                        if (showUIOverlays) HudNeonRed.copy(alpha = 0.7f) else HudNeonGreen,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        showUIOverlays = !showUIOverlays
                        if (voiceAssistantEnabled) {
                            onSpeak(if (showUIOverlays) "Arayüz panelleri aktif edildi." else "Temiz ekran modu aktif edildi.")
                        }
                    }
                    .defaultMinSize(minHeight = 40.dp)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (showUIOverlays) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (showUIOverlays) "HUD'u Gizle" else "HUD'u Göster",
                    tint = if (showUIOverlays) HudNeonRed else HudNeonGreen,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (showUIOverlays) "HUD GİZLE" else "HUD GÖSTER",
                    color = if (showUIOverlays) HudNeonRed else HudNeonGreen,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

@Composable
fun HUDSettingsSelectors(
    activeFilter: VideoFilter,
    activeCategory: TargetCategory,
    onFilterChange: (VideoFilter) -> Unit,
    onCategoryChange: (TargetCategory) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 180.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // A. Video Feed Custom Spectral Tint Filter Selector Row
        Row(
            modifier = Modifier
                .background(HudBgTranslucent, RoundedCornerShape(8.dp))
                .border(1.dp, HudNeonGreen.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SPEKTRUM:",
                color = HudNeonGreen,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(VideoFilter.values()) { filter ->
                    val filterLabel = when (filter) {
                        VideoFilter.NORMAL -> "NORMAL"
                        VideoFilter.NIGHT_VISION -> "NIGHT VISION"
                        VideoFilter.CYBER_AMBER -> "AMBER"
                        VideoFilter.THERMAL_BLUE -> "THERMAL"
                    }
                    val isSelected = activeFilter == filter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) HudNeonGreen.copy(alpha = 0.3f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) HudNeonGreen else HudNeonGreen.copy(alpha = 0.2f),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onFilterChange(filter) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = filterLabel,
                            color = if (isSelected) Color.White else Color.Gray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // B. Target Focus Category Selection Row
        Row(
            modifier = Modifier
                .background(HudBgTranslucent, RoundedCornerShape(8.dp))
                .border(1.dp, HudNeonGreen.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "HEDEF SEÇ:",
                color = HudNeonGreen,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(TargetCategory.values()) { category ->
                    val categoryLabel = when (category) {
                        TargetCategory.ALL -> "HEPSİ"
                        TargetCategory.PEOPLE -> "İNSANLAR"
                        TargetCategory.ELECTRONICS -> "CİHAZLAR"
                        TargetCategory.FURNITURE -> "EŞYALAR"
                        TargetCategory.OTHER -> "DİĞER"
                    }
                    val isSelected = activeCategory == category
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) HudNeonGreen.copy(alpha = 0.3f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) HudNeonGreen else HudNeonGreen.copy(alpha = 0.2f),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onCategoryChange(category) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = categoryLabel,
                            color = if (isSelected) Color.White else Color.Gray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HUDQuickActionsDock(
    isFrontCamera: Boolean,
    soundEnabled: Boolean,
    laserTrackerEnabled: Boolean,
    voiceEnabled: Boolean,
    onCameraToggle: () -> Unit,
    onSoundToggle: () -> Unit,
    onLaserToggle: () -> Unit,
    onVoiceToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(55.dp)
                .background(HudBgTranslucent, RoundedCornerShape(12.dp))
                .border(1.dp, HudNeonGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Front/Back lens toggle
            IconButton(
                onClick = onCameraToggle,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isFrontCamera) HudNeonGreen.copy(alpha = 0.2f) else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FlipCameraAndroid,
                    contentDescription = "Kamera Değiştir",
                    tint = if (isFrontCamera) HudNeonGreen else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Real-time Türk Vocal Assistant Speech On/Off Toggle
            IconButton(
                onClick = onVoiceToggle,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (voiceEnabled) HudNeonBlue.copy(alpha = 0.2f) else Color.Transparent)
            ) {
                Icon(
                    imageVector = if (voiceEnabled) Icons.Filled.RecordVoiceOver else Icons.Filled.VoiceOverOff,
                    contentDescription = "Sesli Asistan",
                    tint = if (voiceEnabled) HudNeonBlue else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Laser telemetry crossline tracker toggle
            IconButton(
                onClick = onLaserToggle,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (laserTrackerEnabled) HudNeonGreen.copy(alpha = 0.2f) else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Filled.Radar,
                    contentDescription = "Lazer İzleyici",
                    tint = if (laserTrackerEnabled) HudNeonGreen else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Sound Beep alarm warnings toggle
            IconButton(
                onClick = onSoundToggle,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (soundEnabled) HudNeonAmber.copy(alpha = 0.2f) else Color.Transparent)
            ) {
                Icon(
                    imageVector = if (soundEnabled) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                    contentDescription = "Bip Uyarıları",
                    tint = if (soundEnabled) HudNeonAmber else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun HUDStatsPanel(
    activeTargetsCount: Int,
    fps: Int,
    confidenceThreshold: Float,
    filterMode: VideoFilter
) {
    val infiniteTransition = rememberInfiniteTransition(label = "core_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier
            .padding(16.dp)
            .width(185.dp)
            .background(HudBgTranslucent, RoundedCornerShape(12.dp))
            .border(1.dp, HudNeonGreen.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // System operational state
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(HudNeonGreen.copy(alpha = pulseAlpha))
            )
            Text(
                text = "TAKİPÇİ SİSTEMİ ÇEVRİMİÇİ",
                color = HudNeonGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp
            )
        }

        Divider(color = HudNeonGreen.copy(alpha = 0.15f), thickness = 1.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "HEDEF SAYISI:", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                text = String.format("%02d ADET", activeTargetsCount),
                color = if (activeTargetsCount > 0) HudNeonGreen else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "TARAMA HIZI:", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(text = "$fps FPS", color = HudNeonGreen, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "EŞİK HASSASİYET:", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                text = "${(confidenceThreshold * 100).roundToInt()}%",
                color = HudNeonGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "SPEKTRAL TİNT:", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                text = filterMode.name,
                color = when (filterMode) {
                    VideoFilter.NORMAL -> Color.White
                    VideoFilter.NIGHT_VISION -> HudNeonGreen
                    VideoFilter.CYBER_AMBER -> HudNeonAmber
                    VideoFilter.THERMAL_BLUE -> HudNeonBlue
                },
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun HUDControlPanel(
    modifier: Modifier = Modifier,
    rawDetections: List<RawDetection>,
    confidenceThreshold: Float,
    coordinatesInPercent: Boolean,
    isPanelExpanded: Boolean,
    snapshots: List<TelemetrySnapshot>,
    onThresholdChange: (Float) -> Unit,
    onCoordinatesToggle: (Boolean) -> Unit,
    onPanelToggle: () -> Unit,
    onCaptureSnapshot: () -> Unit,
    onClearLogs: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HudCardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(1.dp, HudNeonGreen.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Toggle Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPanelToggle() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.QueryStats,
                        contentDescription = null,
                        tint = HudNeonGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "TAKTIK KONTROL VE ARŞİV PANELİ",
                        color = HudNeonGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
                Icon(
                    imageVector = if (isPanelExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = "Görünürlük",
                    tint = HudNeonGreen
                )
            }

            AnimatedVisibility(
                visible = isPanelExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Divider(color = HudNeonGreen.copy(alpha = 0.2f), thickness = 1.dp)

                    // A. Laser Capture Action Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onCaptureSnapshot,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HudNeonGreen.copy(alpha = 0.2f),
                                contentColor = HudNeonGreen
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .border(1.dp, HudNeonGreen, RoundedCornerShape(4.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Camera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "KARA KUTU KAYIT AL",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }

                        if (snapshots.isNotEmpty()) {
                            Button(
                                onClick = onClearLogs,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = HudNeonRed.copy(alpha = 0.2f),
                                    contentColor = HudNeonRed
                                ),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .border(1.dp, HudNeonRed.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = "Temizle",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // B. Sensitivity Control (Slider)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Yapay Zeka Tespit Hassasiyeti (Eşik Değeri):",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${(confidenceThreshold * 100).roundToInt()}%",
                                color = HudNeonGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = confidenceThreshold,
                            onValueChange = onThresholdChange,
                            valueRange = 0.20f..0.80f,
                            colors = SliderDefaults.colors(
                                thumbColor = HudNeonGreen,
                                activeTrackColor = HudNeonGreen,
                                inactiveTrackColor = HudNeonGreen.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.height(20.dp)
                        )
                    }

                    // C. Coordinates Mapping Toggle Option
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Matematiksel Koordinat Türü:",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onCoordinatesToggle(false) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(3.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!coordinatesInPercent) HudNeonGreen else Color.Transparent,
                                    contentColor = if (!coordinatesInPercent) Color.Black else Color.White
                                ),
                                modifier = Modifier
                                    .height(26.dp)
                                    .border(1.dp, HudNeonGreen, RoundedCornerShape(3.dp))
                            ) {
                                Text(text = "Piksel", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            Button(
                                onClick = { onCoordinatesToggle(true) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(3.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (coordinatesInPercent) HudNeonGreen else Color.Transparent,
                                    contentColor = if (coordinatesInPercent) Color.Black else Color.White
                                ),
                                modifier = Modifier
                                    .height(26.dp)
                                    .border(1.dp, HudNeonGreen, RoundedCornerShape(3.dp))
                            ) {
                                Text(text = "Oran %", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    Divider(color = HudNeonGreen.copy(alpha = 0.15f), thickness = 1.dp)

                    // Tab System (Live Targets Tracker or Saved Blackbox Telemetries)
                    var currentSubTab by remember { mutableStateOf(0) } // 0 = Live Tracker, 1 = Black Box Archives

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "ANLIK HEDEFLER (${rawDetections.size})",
                            color = if (currentSubTab == 0) HudNeonGreen else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { currentSubTab = 0 }
                                .padding(vertical = 4.dp)
                        )
                        Text(
                            text = "KARA KUTU ARŞİVİ (${snapshots.size})",
                            color = if (currentSubTab == 1) HudNeonGreen else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { currentSubTab = 1 }
                                .padding(vertical = 4.dp)
                        )
                    }

                    if (currentSubTab == 0) {
                        // Live telemetry targets list
                        if (rawDetections.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(95.dp)
                                    .border(1.dp, HudNeonGreen.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "TARANIYOR...\n(Görüş alanında nesne tespit edilemedi)",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(115.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(rawDetections) { detection ->
                                    TargetLogItem(detection = detection, coordinatesInPercent = coordinatesInPercent)
                                }
                            }
                        }
                    } else {
                        // Black box snapshot logs
                        if (snapshots.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(95.dp)
                                    .border(1.dp, HudNeonGreen.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "ARŞİV BOŞ\n(Yukarıdaki butondan anlık kayıt alabilirsiniz)",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(115.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(snapshots) { snap ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(HudNeonBlue.copy(alpha = 0.04f), RoundedCornerShape(4.dp))
                                            .border(1.dp, HudNeonBlue.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Zaman: ${snap.timestamp}",
                                                color = HudNeonBlue,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "${snap.targetsCount} Hedef Kaydedildi",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        snap.details.forEach { detail ->
                                            Text(
                                                text = "» $detail",
                                                color = Color.Gray,
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TargetLogItem(
    detection: RawDetection,
    coordinatesInPercent: Boolean
) {
    val durationSec = (System.currentTimeMillis() - detection.firstDetectedMs) / 1000
    val isLocked = durationSec >= 2

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isLocked) HudNeonRed.copy(alpha = 0.05f) else HudNeonGreen.copy(alpha = 0.04f),
                RoundedCornerShape(4.dp)
            )
            .border(
                1.dp,
                if (isLocked) HudNeonRed.copy(alpha = 0.25f) else HudNeonGreen.copy(alpha = 0.15f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isLocked) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
                contentDescription = null,
                tint = if (isLocked) HudNeonRed else HudNeonGreen,
                modifier = Modifier.size(14.dp)
            )
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = translateLabel(detection.label),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (isLocked) HudNeonRed.copy(alpha = 0.2f) else HudNeonGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (isLocked) "KİLİTLENDİ" else "TARANIYOR",
                            color = if (isLocked) HudNeonRed else HudNeonGreen,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text(
                    text = "Doğruluk Skoru: ${(detection.confidence * 100).roundToInt()}% | Süre: ${durationSec}s",
                    color = Color.Gray,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            val rect = detection.rect
            // Calculate a simulated distance approximation based on ML Kit bounding box size
            val estDistance = (1300f / (rect.height() + 1f)).roundToInt() / 10f
            Text(
                text = "Mesafe: ~${estDistance}m",
                color = if (isLocked) HudNeonRed else HudNeonGreen,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "L:${rect.left}, T:${rect.top}, R:${rect.right}, B:${rect.bottom}",
                color = Color.Gray,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun HUDOverlayCanvas(
    rawDetections: List<RawDetection>,
    frameMetadata: FrameMetadata?,
    laserTrackerEnabled: Boolean,
    coordinatesInPercent: Boolean,
    currentFilter: VideoFilter
) {
    val textMeasurer = rememberTextMeasurer()

    val infiniteTransition = rememberInfiniteTransition(label = "scanning_line")
    val sweepProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan"
    )

    // Glowing circle lock ring scale animation
    val scalePulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Main theme color based on selected visual spectrum filter
    val themeColor = when (currentFilter) {
        VideoFilter.NORMAL -> HudNeonGreen
        VideoFilter.NIGHT_VISION -> HudNeonGreen
        VideoFilter.CYBER_AMBER -> HudNeonAmber
        VideoFilter.THERMAL_BLUE -> HudNeonBlue
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val cx = w / 2f
        val cy = h / 2f

        // A. Draw military-grade reticle circles
        drawCircle(
            color = themeColor.copy(alpha = 0.08f),
            radius = 130.dp.toPx() * scalePulse,
            style = Stroke(width = 1f)
        )
        drawCircle(
            color = themeColor.copy(alpha = 0.15f),
            radius = 60.dp.toPx(),
            style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 15f), 0f))
        )
        drawCircle(
            color = themeColor.copy(alpha = 0.3f),
            radius = 8.dp.toPx(),
            style = Stroke(width = 1.5f)
        )

        // Crosslines of the radar sight
        drawLine(
            color = themeColor.copy(alpha = 0.25f),
            start = Offset(cx - 70.dp.toPx(), cy),
            end = Offset(cx - 15.dp.toPx(), cy),
            strokeWidth = 1.5f
        )
        drawLine(
            color = themeColor.copy(alpha = 0.25f),
            start = Offset(cx + 15.dp.toPx(), cy),
            end = Offset(cx + 70.dp.toPx(), cy),
            strokeWidth = 1.5f
        )
        drawLine(
            color = themeColor.copy(alpha = 0.25f),
            start = Offset(cx, cy - 70.dp.toPx()),
            end = Offset(cx, cy - 15.dp.toPx()),
            strokeWidth = 1.5f
        )
        drawLine(
            color = themeColor.copy(alpha = 0.25f),
            start = Offset(cx, cy + 15.dp.toPx()),
            end = Offset(cx, cy + 70.dp.toPx()),
            strokeWidth = 1.5f
        )

        // B. Scrolling high-tech scanning bar
        val barY = sweepProgress * h
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    themeColor.copy(alpha = 0.2f),
                    Color.Transparent
                )
            ),
            topLeft = Offset(0f, barY - 20.dp.toPx()),
            size = Size(w, 40.dp.toPx())
        )
        drawLine(
            color = themeColor.copy(alpha = 0.65f),
            start = Offset(0f, barY),
            end = Offset(w, barY),
            strokeWidth = 2f
        )

        // C. Bounding boxes and telemetry calculations for detected targets
        if (frameMetadata != null) {
            for (detection in rawDetections) {
                // Determine lock state based on duration present in frame
                val duration = System.currentTimeMillis() - detection.firstDetectedMs
                val isLocked = duration >= 2000
                val boxColor = if (isLocked) HudNeonRed else themeColor

                val screenRect = transformRect(
                    rect = detection.rect,
                    imageWidth = frameMetadata.width,
                    imageHeight = frameMetadata.height,
                    rotation = frameMetadata.rotation,
                    viewWidth = w,
                    viewHeight = h,
                    isFrontCamera = frameMetadata.isFront
                )

                // 1. Draw fill background of bounding box
                drawRect(
                    color = boxColor.copy(alpha = 0.1f),
                    topLeft = Offset(screenRect.left, screenRect.top),
                    size = Size(screenRect.width(), screenRect.height())
                )

                // 2. Draw modern cross brackets at coordinates corners
                drawTargetCorners(
                    rect = screenRect,
                    color = boxColor,
                    strokeWidth = 3.dp.toPx(),
                    cornerLength = minOf(screenRect.width() * 0.25f, 22.dp.toPx())
                )

                // 3. Draw thin connecting borders
                drawRect(
                    color = boxColor.copy(alpha = 0.35f),
                    topLeft = Offset(screenRect.left, screenRect.top),
                    size = Size(screenRect.width(), screenRect.height()),
                    style = Stroke(width = 1.2f)
                )

                // 4. Draw tracker vector line to core reticle center
                if (laserTrackerEnabled) {
                    val targetX = screenRect.centerX()
                    val targetY = screenRect.centerY()

                    // Glowing center pointer
                    drawCircle(
                        color = boxColor,
                        radius = 4.5f.dp.toPx(),
                        center = Offset(targetX, targetY)
                    )
                    drawCircle(
                        color = boxColor.copy(alpha = 0.45f * scalePulse),
                        radius = 12.dp.toPx(),
                        center = Offset(targetX, targetY),
                        style = Stroke(width = 1.2f)
                    )

                    // Connecting tracking laser line
                    drawLine(
                        color = boxColor.copy(alpha = 0.45f),
                        start = Offset(cx, cy),
                        end = Offset(targetX, targetY),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    )
                }

                // 5. Draw customized telemetry tags with text metrics
                val trLabel = translateLabel(detection.label)
                val pct = (detection.confidence * 100).roundToInt()
                val estDistance = (1300f / (detection.rect.height() + 1f)).roundToInt() / 10f

                val textCoords = if (coordinatesInPercent) {
                    val pX = ((screenRect.centerX() / w) * 100).roundToInt()
                    val pY = ((screenRect.centerY() / h) * 100).roundToInt()
                    "KOORD: X $pX% | Y $pY%"
                } else {
                    val pxX = screenRect.centerX().roundToInt()
                    val pxY = screenRect.centerY().roundToInt()
                    "KOORD: X ${pxX}px | Y ${pxY}px"
                }

                val statusText = if (isLocked) "LOCK_ON" else "ACQUIRING..."
                val telemetryInfo = "▲ $trLabel [$pct%]\n$textCoords\nDIS: ~${estDistance}m | $statusText"

                val layoutResult = textMeasurer.measure(
                    text = telemetryInfo,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                )

                val paddingX = 14f
                val paddingY = 12f
                val containerWidth = layoutResult.size.width + paddingX * 2
                val containerHeight = layoutResult.size.height + paddingY * 2

                val tagLeft = screenRect.left
                val tagTop = screenRect.top - containerHeight - 6f

                // Tag Background container box
                drawRect(
                    color = HudBgTranslucent,
                    topLeft = Offset(tagLeft, tagTop),
                    size = Size(containerWidth, containerHeight)
                )

                // Neon left indicator band
                drawLine(
                    color = boxColor,
                    start = Offset(tagLeft, tagTop),
                    end = Offset(tagLeft, tagTop + containerHeight),
                    strokeWidth = 3f
                )

                // Top visual neon subline
                drawLine(
                    color = boxColor.copy(alpha = 0.4f),
                    start = Offset(tagLeft, tagTop),
                    end = Offset(tagLeft + containerWidth * 0.35f, tagTop),
                    strokeWidth = 1.5f
                )

                drawText(
                    textLayoutResult = layoutResult,
                    topLeft = Offset(tagLeft + paddingX, tagTop + paddingY)
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
fun processImageProxy(
    imageProxy: ImageProxy,
    detector: ObjectDetector,
    isFrontCamera: Boolean,
    confidenceThreshold: Float,
    categoryFilter: TargetCategory,
    onSuccess: (List<RawDetection>, FrameMetadata) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { detectedObjects ->
                val detections = detectedObjects.mapNotNull { obj ->
                    val id = obj.trackingId ?: obj.hashCode()
                    val labelObj = obj.labels.firstOrNull()
                    val label = labelObj?.text ?: "Nesne"
                    val confidence = labelObj?.confidence ?: 0.5f

                    // Map label to categorized target groups
                    val category = getLabelCategory(label)

                    val isAllowedByCategory = when (categoryFilter) {
                        TargetCategory.ALL -> true
                        TargetCategory.PEOPLE -> category == TargetCategory.PEOPLE
                        TargetCategory.ELECTRONICS -> category == TargetCategory.ELECTRONICS
                        TargetCategory.FURNITURE -> category == TargetCategory.FURNITURE
                        TargetCategory.OTHER -> category == TargetCategory.OTHER
                    }

                    if (confidence >= confidenceThreshold && isAllowedByCategory) {
                        RawDetection(id, obj.boundingBox, label, confidence)
                    } else {
                        null
                    }
                }
                val metadata = FrameMetadata(
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rotation = imageProxy.imageInfo.rotationDegrees,
                    isFront = isFrontCamera
                )
                onSuccess(detections, metadata)
            }
            .addOnFailureListener { e ->
                Log.e("HUDAnalyzer", "MLKit Object Processing failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

// Categorizer map helper
fun getLabelCategory(label: String): TargetCategory {
    return when (label.lowercase()) {
        "person" -> TargetCategory.PEOPLE
        "phone", "computer", "laptop", "tablet", "screen", "television" -> TargetCategory.ELECTRONICS
        "chair", "table", "bed", "sofa", "desk", "furniture", "home good" -> TargetCategory.FURNITURE
        else -> TargetCategory.OTHER
    }
}

// Custom Draw Bounding Box Target Corner brackets helper
fun DrawScope.drawTargetCorners(
    rect: RectF,
    color: Color,
    strokeWidth: Float,
    cornerLength: Float
) {
    val path = Path()

    // 1. Top-Left
    path.moveTo(rect.left + cornerLength, rect.top)
    path.lineTo(rect.left, rect.top)
    path.lineTo(rect.left, rect.top + cornerLength)

    // 2. Top-Right
    path.moveTo(rect.right - cornerLength, rect.top)
    path.lineTo(rect.right, rect.top)
    path.lineTo(rect.right, rect.top + cornerLength)

    // 3. Bottom-Left
    path.moveTo(rect.left + cornerLength, rect.bottom)
    path.lineTo(rect.left, rect.bottom)
    path.lineTo(rect.left, rect.bottom - cornerLength)

    // 4. Bottom-Right
    path.moveTo(rect.right - cornerLength, rect.bottom)
    path.lineTo(rect.right, rect.bottom)
    path.lineTo(rect.right, rect.bottom - cornerLength)

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth)
    )
}

// Coordinate Mapper Function
fun transformRect(
    rect: Rect,
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int,
    viewWidth: Float,
    viewHeight: Float,
    isFrontCamera: Boolean
): RectF {
    val rotatedWidth = if (rotation == 90 || rotation == 270) imageHeight else imageWidth
    val rotatedHeight = if (rotation == 90 || rotation == 270) imageWidth else imageHeight

    var left = rect.left.toFloat()
    var top = rect.top.toFloat()
    var right = rect.right.toFloat()
    var bottom = rect.bottom.toFloat()

    val transLeft: Float
    val transTop: Float
    val transRight: Float
    val transBottom: Float

    when (rotation) {
        90 -> {
            transLeft = imageHeight - bottom
            transTop = left
            transRight = imageHeight - top
            transBottom = right
        }
        270 -> {
            transLeft = top
            transTop = imageWidth - right
            transRight = bottom
            transBottom = imageWidth - left
        }
        180 -> {
            transLeft = imageWidth - right
            transTop = imageHeight - bottom
            transRight = imageWidth - left
            transBottom = imageHeight - top
        }
        else -> {
            transLeft = left
            transTop = top
            transRight = right
            transBottom = bottom
        }
    }

    val mirLeft: Float
    val mirRight: Float
    if (isFrontCamera) {
        mirLeft = rotatedWidth - transRight
        mirRight = rotatedWidth - transLeft
    } else {
        mirLeft = transLeft
        mirRight = transRight
    }
    val mirTop = transTop
    val mirBottom = transBottom

    val scaleX = viewWidth / rotatedWidth
    val scaleY = viewHeight / rotatedHeight
    val scale = maxOf(scaleX, scaleY)

    val scaledWidth = rotatedWidth * scale
    val scaledHeight = rotatedHeight * scale
    val offsetX = (viewWidth - scaledWidth) / 2f
    val offsetY = (viewHeight - scaledHeight) / 2f

    return RectF(
        mirLeft * scale + offsetX,
        mirTop * scale + offsetY,
        mirRight * scale + offsetX,
        mirBottom * scale + offsetY
    )
}

// Camera Helper Suspend Function
suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { future ->
        future.addListener({
            continuation.resume(future.get())
        }, ContextCompat.getMainExecutor(this))
    }
}

// Common everyday objects label translator
fun translateLabel(label: String): String {
    return when (label.lowercase()) {
        "home good" -> "Ev Eşyası"
        "fashion good" -> "Giyim / Moda"
        "food" -> "Yiyecek / Gıda"
        "place" -> "Mekan / Yapı"
        "plant" -> "Bitki / Çiçek"
        "person" -> "İnsan / Birey"
        "cat" -> "Kedi"
        "dog" -> "Köpek"
        "bird" -> "Kuş"
        "car" -> "Araba"
        "bicycle" -> "Bisiklet"
        "motorcycle" -> "Motosiklet"
        "phone" -> "Telefon"
        "computer" -> "Bilgisayar"
        "laptop" -> "Dizüstü Bilgisayar"
        "book" -> "Kitap"
        "chair" -> "Sandalye"
        "table" -> "Masa"
        "cup" -> "Kupa / Bardak"
        "bottle" -> "Şişe"
        "keys" -> "Anahtarlar"
        "pen" -> "Kalem"
        "toy" -> "Oyuncak"
        else -> {
            label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onViewCreated: (PreviewView) -> Unit
) {
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                onViewCreated(this)
            }
        },
        modifier = modifier
    )
}

