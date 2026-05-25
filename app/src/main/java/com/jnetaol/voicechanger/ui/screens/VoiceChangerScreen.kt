package com.jnetaol.voicechanger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jnetaol.voicechanger.AppDebug
import com.jnetaol.voicechanger.engine.AudioEngine
import com.jnetaol.voicechanger.ui.theme.*
import kotlinx.coroutines.delay

data class EffectItem(val label: String, val icon: String, val effect: AudioEngine.Effect)

val effects = listOf(
    EffectItem("Normal", "\uD83C\uDF99", AudioEngine.Effect.NORMAL),
    EffectItem("Chipmunk", "\uD83D\uDC3F", AudioEngine.Effect.CHIPMUNK),
    EffectItem("Deep", "\uD83D\uDC79", AudioEngine.Effect.DEEP),
    EffectItem("Robot", "\uD83E\uDD16", AudioEngine.Effect.ROBOT),
    EffectItem("Echo", "\uD83C\uDFD4", AudioEngine.Effect.ECHO),
    EffectItem("Alien", "\uD83D\uDC7D", AudioEngine.Effect.ALIEN),
    EffectItem("Megaphone", "\uD83D\uDCE2", AudioEngine.Effect.MEGAPHONE),
    EffectItem("Whisper", "\uD83E\uDD2B", AudioEngine.Effect.WHISPER)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChangerScreen() {
    val context = LocalContext.current
    val engine = remember { AudioEngine() }
    val state by engine.state.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    var recordingSeconds by remember { mutableIntStateOf(0) }
    var recordingTimerActive by remember { mutableStateOf(false) }

    LaunchedEffect(recordingTimerActive) {
        if (recordingTimerActive) {
            while (true) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val amplitudes = remember { mutableStateListOf<Float>() }
    val maxBars = 48

    LaunchedEffect(state.amplitude) {
        amplitudes.add(state.amplitude)
        if (amplitudes.size > maxBars) amplitudes.removeAt(0)
    }

    if (amplitudes.isEmpty()) {
        repeat(maxBars) { amplitudes.add(0.02f) }
    }

    val recordingSamples = remember { mutableStateOf(ShortArray(0)) }

    val scrollState = rememberScrollState()

    LaunchedEffect(state.isRecording) {
        if (!state.isRecording && recordingSeconds > 0) {
            val samples = engine.stopRecordingSamples()
            recordingSamples.value = samples
            recordingSeconds = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Voice Changer",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium,
            color = Brush.linearGradient(listOf(Accent2, Accent)).let { brush ->
                // Workaround for gradient text in Material3
                Accent2
            }
        )

        Text(
            text = "Real-time voice effects",
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // Status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface2)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.isRunning) Success
                        else if (!hasPermission) Danger
                        else TextSecondary
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = when {
                    !hasPermission -> "Microphone permission needed"
                    state.isRunning && state.isRecording -> "Recording..."
                    state.isRunning -> "Microphone active - speak to test!"
                    else -> "Tap Start to begin"
                },
                fontSize = 13.sp,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Visualizer
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface2)
        ) {
            val w = size.width
            val h = size.height
            val barWidth = w / maxBars

            for (i in 0 until minOf(amplitudes.size, maxBars)) {
                val amp = (amplitudes[i] * 0.9f).coerceIn(0f, 1f)
                val barHeight = h * amp
                val x = i * barWidth

                val gradient = Brush.verticalGradient(
                    listOf(Accent2.copy(alpha = 0.8f), Accent)
                )

                drawRect(
                    brush = gradient,
                    topLeft = Offset(x, h - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth - 2f, barHeight)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Effects grid
        Text(
            text = "Preset Effects",
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            items(effects) { item ->
                val isActive = state.effect == item.effect
                val bgColor by animateColorAsState(
                    if (isActive) Accent.copy(alpha = 0.3f) else DarkSurface2,
                )
                val borderColor by animateColorAsState(
                    if (isActive) Accent else Border,
                )

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                        .clickable { engine.setEffect(item.effect) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = item.icon, fontSize = 20.sp)
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isActive) Accent2 else TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pitch slider
        SliderControl(
            label = "Pitch Shift",
            value = state.pitchShift,
            valueRange = -12f..12f,
            steps = 23,
            valueSuffix = " semitones",
            formatValue = { "${if (it > 0) "+" else ""}${it.toInt()}" }
        ) { engine.setPitchShift(it) }

        SliderControl(
            label = "Effect Intensity",
            value = state.intensity * 100f,
            valueRange = 0f..100f,
            valueSuffix = "%",
            formatValue = { "${it.toInt()}" }
        ) { engine.setIntensity(it / 100f) }

        SliderControl(
            label = "Volume",
            value = state.volume * 100f,
            valueRange = 0f..100f,
            valueSuffix = "%",
            formatValue = { "${it.toInt()}" }
        ) { engine.setVolume(it / 100f) }

        Spacer(modifier = Modifier.height(16.dp))

        // Main controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasPermission) {
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow Mic", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = {
                        if (state.isRunning) engine.stop()
                        else engine.start()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRunning) Danger else Success
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (state.isRunning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (state.isRunning) "Stop" else "Start",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (state.isRunning) {
                Button(
                    onClick = {
                        if (state.isRecording) {
                            engine.stopRecordingSamples()
                            recordingTimerActive = false
                        } else {
                            engine.startRecordingSamples()
                            recordingSeconds = 0
                            recordingTimerActive = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                    enabled = state.isRunning
                ) {
                    Icon(
                        if (state.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = if (state.isRecording) Danger else Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (state.isRecording) "Stop Rec" else "Record",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (state.isRecording) {
            val mins = recordingSeconds / 60
            val secs = recordingSeconds % 60
            Text(
                text = "● Recording ${String.format("%02d:%02d", mins, secs)}",
                color = Danger,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (recordingSamples.value.isNotEmpty()) {
            Button(
                onClick = {
                    engine.playRecording(engine.recordingByteArray())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface2),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Play Back", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val wavBytes = engine.recordingWavBytes()
                    val outputFile = java.io.File(
                        context.getExternalFilesDir(null),
                        "voice_changer_${System.currentTimeMillis()}.wav"
                    )
                    outputFile.writeBytes(wavBytes)
                    android.util.Log.d("VoiceChanger", "Saved: ${outputFile.absolutePath}")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Recording", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    engine.clearRecording()
                    recordingSamples.value = ShortArray(0)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Danger.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Recording", fontWeight = FontWeight.SemiBold)
            }
        }

        if (state.isRunning && !state.isRecording && recordingSamples.value.isEmpty()) {
            Text(
                text = "Active: ${effects.find { it.effect == state.effect }?.label ?: "Normal"}",
                color = Accent2,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkSurface2)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .padding(top = 8.dp)
            )
        }

        // Debug / Error Log Viewer
        if (AppDebug.hasErrors()) {
            Spacer(modifier = Modifier.height(8.dp))
            var showErrors by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Danger.copy(alpha = 0.15f))
                    .border(1.dp, Danger.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable { showErrors = !showErrors }
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Danger,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${AppDebug.getErrorLog().size} error(s) detected — tap to view",
                        color = Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (showErrors) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Danger.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppDebug.getErrorLog().forEach { err ->
                        Text(
                            text = err,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = { AppDebug.clearLog() }) {
                        Text("Clear Log", color = Accent, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
fun SliderControl(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueSuffix: String = "",
    formatValue: (Float) -> String = { "%.0f".format(it) },
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = TextSecondary
            )
            Text(
                text = "${formatValue(value)}$valueSuffix",
                fontSize = 13.sp,
                color = Accent2,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Border
            )
        )
    }
}
