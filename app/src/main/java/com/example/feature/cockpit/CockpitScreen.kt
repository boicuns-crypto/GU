package com.example.feature.cockpit

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import com.example.ui.theme.CockpitIcons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.feature.telemetry.ThermalZone
import com.example.feature.telemetry.VagTelemetryState
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.TelemetryEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.cos
import kotlin.math.sin

// VAG Digital Cockpit Thermal Colors
private val VagColdBlue = Color(0xFF00B0FF)
private val VagWarmupCyan = Color(0xFF00E5FF)
private val VagOptimalGreen = Color(0xFF00E676)
private val VagWarningRed = Color(0xFFFF5252)

/**
 * Module 4 (`:feature:cockpit-ui`): VAG Digital Cockpit Widescreen Dashboard for Jetta VS7 (MQB).
 *
 * Divided into the exact 3-zone automotive layout:
 * 1. **Left Zone (50% width)**: Embedded Navigation Map (`AndroidView` with `MqbNavigationMapView`)
 *    and a floating **Next Maneuver Banner** + speed/gear HUD pill on top.
 * 2. **Top-Right Zone (50% width, upper half)**: **4 Custom Compose `Canvas` Radial Gauges**
 *    (`Engine Oil Temp °C`, `Gearbox ATF Temp °C`, `Turbo Boost Bar`, `Battery Voltage V`) with
 *    dynamic warm-up color coding.
 * 3. **Bottom-Right Zone (50% width, lower half)**: **Media Controller Widget** connected to
 *    `MediaSessionManager` with Album Art, track metadata, and oversized 60dp touch buttons.
 */
@Composable
fun CockpitScreen(
    telemetry: VagTelemetryState,
    mediaState: CockpitMediaState,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onTriggerWarmUpSweep: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = ObsidianBackground
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val isWideCockpit = maxWidth >= 560.dp

            if (isWideCockpit) {
                // Canonical Widescreen Automotive Layout (50% Left Map / 50% Right Telemetry + Media)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ZONE 1: Left 50% — Navigation Map Container + Floating Next Maneuver Banner
                    NavigationMapZone(
                        telemetry = telemetry,
                        modifier = Modifier
                            .weight(0.5f)
                            .fillMaxHeight()
                    )

                    // RIGHT 50%: Top Telemetry (4 Canvas Gauges) + Bottom Media Player
                    Column(
                        modifier = Modifier
                            .weight(0.5f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ZONE 2: Top-Right — 4 Custom VAG Canvas Gauges
                        VagTelemetryGaugesZone(
                            telemetry = telemetry,
                            onTriggerWarmUpSweep = onTriggerWarmUpSweep,
                            modifier = Modifier
                                .weight(0.54f)
                                .fillMaxWidth()
                        )

                        // ZONE 3: Bottom-Right — Media Widget with Album Art & Large Finger Buttons
                        VagMediaPlayerZone(
                            mediaState = mediaState,
                            onPlayPause = onPlayPause,
                            onNextTrack = onNextTrack,
                            onPreviousTrack = onPreviousTrack,
                            onRequestNotificationAccess = onRequestNotificationAccess,
                            modifier = Modifier
                                .weight(0.46f)
                                .fillMaxWidth()
                        )
                    }
                }
            } else {
                // Portrait phone preview adaptation of the exact same 3 zones
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NavigationMapZone(
                        telemetry = telemetry,
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxWidth()
                    )
                    VagTelemetryGaugesZone(
                        telemetry = telemetry,
                        onTriggerWarmUpSweep = onTriggerWarmUpSweep,
                        modifier = Modifier
                            .weight(0.35f)
                            .fillMaxWidth()
                    )
                    VagMediaPlayerZone(
                        mediaState = mediaState,
                        onPlayPause = onPlayPause,
                        onNextTrack = onNextTrack,
                        onPreviousTrack = onPreviousTrack,
                        onRequestNotificationAccess = onRequestNotificationAccess,
                        modifier = Modifier
                            .weight(0.27f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ============================================================================
// ZONE 1: NAVIGATION MAP CONTAINER + FLOATING NEXT MANEUVER HUD
// ============================================================================

@Composable
private fun NavigationMapZone(
    telemetry: VagTelemetryState,
    modifier: Modifier = Modifier
) {
    var maneuverIndex by remember { mutableIntStateOf(0) }
    val maneuvers = remember {
        listOf(
            Triple("350 m", "Turn Right • Kutuzovsky Prospekt", "M-1 Highway • ETA 18 min"),
            Triple("1.2 km", "Keep Left • Third Ring Road (TTK)", "Fastest Route • No Cameras"),
            Triple("480 m", "Exit Ramp • Zvenigorodskoye Shosse", "Speed Limit 80 km/h")
        )
    }
    val (distanceText, streetText, subRouteText) = maneuvers[maneuverIndex % maneuvers.size]

    Card(
        modifier = modifier.testTag("cockpit_zone_navigation"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Native AndroidView MapView Container rendering the dark MQB 3D-perspective vector road map
            AndroidView(
                factory = { ctx -> MqbNavigationMapView(ctx) },
                update = { mapView ->
                    mapView.updateTelemetry(telemetry.speedKmh, telemetry.engineRpm)
                },
                modifier = Modifier.fillMaxSize()
            )

            // Top Floating Next Maneuver Banner (VAG Digital Cockpit style)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.TopCenter)
                    .clickable { maneuverIndex++ }
                    .testTag("next_maneuver_banner"),
                color = ObsidianBackground.copy(alpha = 0.90f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElectricCyan.copy(alpha = 0.16f))
                            .border(1.dp, ElectricCyan, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = CockpitIcons.TurnRight,
                            contentDescription = "Next Maneuver Turn",
                            tint = ElectricCyan,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = distanceText,
                                style = MaterialTheme.typography.titleLarge,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = subRouteText,
                                style = MaterialTheme.typography.labelSmall,
                                color = TelemetryEmerald
                            )
                        }
                        Text(
                            text = streetText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Bottom Speedometer + AQ250 Gear Indicator Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Surface(
                    color = ObsidianBackground.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = CockpitIcons.Speed,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "${telemetry.speedKmh}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = JetBrainsMonoFamily,
                            color = TextPrimary
                        )
                        Text(
                            text = "km/h",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Surface(
                    color = ObsidianBackground.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, TelemetryEmerald.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${telemetry.engineRpm} RPM",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(TelemetryEmerald.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = telemetry.currentGear,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = TelemetryEmerald
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom Android `View` Map container rendering a dark VAG MIB3/MQB vector road topology,
 * ego-vehicle navigation chevron, route polyline, and speed-adaptive grid.
 */
private class MqbNavigationMapView(context: Context) : View(context) {
    private var currentSpeedKmh: Int = 78
    private var phaseOffset: Float = 0f

    private val bgPaint = Paint().apply {
        color = AndroidColor.parseColor("#080D18")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#132038")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val secondaryRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#1C2C4C")
        strokeWidth = 18f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#4400E5FF")
        strokeWidth = 28f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val routeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#00E5FF")
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dashedCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#88F0F6FC")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 18f), 0f)
    }
    private val egoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#00E676")
        style = Paint.Style.FILL
    }
    private val routePath = AndroidPath()
    private val crossRoadPath = AndroidPath()
    private val egoPath = AndroidPath()

    fun updateTelemetry(speedKmh: Int, rpm: Int) {
        currentSpeedKmh = speedKmh
        phaseOffset = (phaseOffset + (speedKmh * 0.12f) + (rpm * 0.001f)) % 64f
        invalidate()
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Perspective grid lines
        val step = 64f
        var y = phaseOffset
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }
        var x = 0f
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }

        // Intersecting arterial roads
        crossRoadPath.reset()
        crossRoadPath.moveTo(0f, h * 0.62f)
        crossRoadPath.quadTo(w * 0.48f, h * 0.52f, w, h * 0.36f)
        canvas.drawPath(crossRoadPath, secondaryRoadPaint)

        // Active highlighted navigation route
        routePath.reset()
        routePath.moveTo(w * 0.46f, h * 0.92f)
        routePath.cubicTo(
            w * 0.46f, h * 0.62f,
            w * 0.42f, h * 0.44f,
            w * 0.72f, h * 0.26f
        )
        canvas.drawPath(routePath, routeGlowPaint)
        canvas.drawPath(routePath, routeCorePaint)
        canvas.drawPath(routePath, dashedCenterPaint)

        // Ego vehicle chevron at (w * 0.46f, h * 0.74f)
        val cx = w * 0.46f
        val cy = h * 0.74f
        egoPath.reset()
        egoPath.moveTo(cx, cy - 22f)
        egoPath.lineTo(cx + 16f, cy + 16f)
        egoPath.lineTo(cx, cy + 8f)
        egoPath.lineTo(cx - 16f, cy + 16f)
        egoPath.close()
        canvas.drawPath(egoPath, egoPaint)
    }
}

// ============================================================================
// ZONE 2: TOP-RIGHT — 4 CUSTOM VAG DIGITAL COCKPIT CANVAS GAUGES
// ============================================================================

@Composable
private fun VagTelemetryGaugesZone(
    telemetry: VagTelemetryState,
    onTriggerWarmUpSweep: () -> Unit,
    modifier: Modifier = Modifier
) {
    val oilColor = thermalZoneColor(telemetry.oilThermalZone)
    val atfColor = thermalZoneColor(telemetry.gearboxThermalZone)
    val boostColor = when {
        telemetry.boostBar < 0f -> ElectricCyan
        telemetry.boostBar <= 1.25f -> TelemetryEmerald
        else -> AmberSignal
    }
    val voltageColor = when {
        telemetry.voltageV < 12.1f -> VagWarningRed
        telemetry.voltageV in 13.5f..14.8f -> VagOptimalGreen
        else -> AmberSignal
    }

    Card(
        modifier = modifier.testTag("cockpit_zone_telemetry"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(oilColor)
                    )
                    Text(
                        text = "VAG UDS 0x22 • EA211 1.4 TSI (7E0) & AQ250 (7E1)",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTriggerWarmUpSweep() }
                        .testTag("warmup_sweep_button"),
                    color = ObsidianSurfaceHigh,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "Warm-Up Test",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 4 Custom Canvas Gauges in a single responsive Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VagRadialCanvasGauge(
                    title = "OIL TEMP",
                    valueText = "${telemetry.oilTempC.toInt()}°C",
                    subBadge = telemetry.oilThermalZone.label,
                    fraction = ((telemetry.oilTempC - 40f) / 100f).coerceIn(0f, 1f),
                    accentColor = oilColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                VagRadialCanvasGauge(
                    title = "ATF AQ250",
                    valueText = "${telemetry.gearboxTempC.toInt()}°C",
                    subBadge = telemetry.gearboxThermalZone.label,
                    fraction = ((telemetry.gearboxTempC - 30f) / 100f).coerceIn(0f, 1f),
                    accentColor = atfColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                VagRadialCanvasGauge(
                    title = "BOOST",
                    valueText = "%+.2f".format(telemetry.boostBar),
                    subBadge = "PK +${"%.2f".format(telemetry.peakBoostBar)} B",
                    fraction = ((telemetry.boostBar + 0.8f) / 2.3f).coerceIn(0f, 1f),
                    accentColor = boostColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                VagRadialCanvasGauge(
                    title = "VOLTAGE",
                    valueText = "${"%.1f".format(telemetry.voltageV)}V",
                    subBadge = "IAT ${telemetry.intakeAirTempC.toInt()}°C",
                    fraction = ((telemetry.voltageV - 11.0f) / 4.5f).coerceIn(0f, 1f),
                    accentColor = voltageColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/**
 * Custom VAG Digital Cockpit radial gauge drawn on Jetpack Compose [Canvas] with tick marks,
 * animated sweep arc, glowing needle indicator, and thermal warm-up badge.
 */
@Composable
private fun VagRadialCanvasGauge(
    title: String,
    valueText: String,
    subBadge: String,
    fraction: Float,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = title
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianBackground.copy(alpha = 0.65f))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                val diameter = minOf(size.width, size.height)
                val strokeW = (diameter * 0.10f).coerceAtLeast(6f)
                val arcSize = Size(diameter - strokeW, diameter - strokeW)
                val topLeft = Offset(
                    (size.width - arcSize.width) / 2f,
                    (size.height - arcSize.height) / 2f
                )

                // Background 240-degree gauge track (from 150° to 390°)
                drawArc(
                    color = ObsidianSurfaceHigh,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )

                // Tick marks around the bezel
                val cx = size.width / 2f
                val cy = size.height / 2f
                val outerRadius = (diameter - strokeW) / 2f
                for (i in 0..8) {
                    val angleDeg = 150f + (i * 30f)
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val rStart = outerRadius - strokeW * 0.8f
                    val rEnd = outerRadius - strokeW * 0.2f
                    drawLine(
                        color = ObsidianBorder,
                        start = Offset(
                            cx + (cos(rad) * rStart).toFloat(),
                            cy + (sin(rad) * rStart).toFloat()
                        ),
                        end = Offset(
                            cx + (cos(rad) * rEnd).toFloat(),
                            cy + (sin(rad) * rEnd).toFloat()
                        ),
                        strokeWidth = 2f
                    )
                }

                // Active value sweep arc with thermal color
                val sweep = 240f * animatedFraction
                drawArc(
                    color = accentColor,
                    startAngle = 150f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )

                // Glowing tip dot at current needle angle
                val tipAngleRad = Math.toRadians((150f + sweep).toDouble())
                val tipX = cx + (cos(tipAngleRad) * outerRadius).toFloat()
                val tipY = cy + (sin(tipAngleRad) * outerRadius).toFloat()
                drawCircle(
                    color = Color.White,
                    radius = strokeW * 0.48f,
                    center = Offset(tipX, tipY)
                )
            }

            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        Surface(
            color = accentColor.copy(alpha = 0.16f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = subBadge,
                fontSize = 9.sp,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                maxLines = 1
            )
        }
    }
}

private fun thermalZoneColor(zone: ThermalZone): Color = when (zone) {
    ThermalZone.COLD -> VagColdBlue
    ThermalZone.WARMING -> VagWarmupCyan
    ThermalZone.OPTIMAL -> VagOptimalGreen
    ThermalZone.WARNING -> VagWarningRed
}

// ============================================================================
// ZONE 3: BOTTOM-RIGHT — MEDIA SESSION WIDGET WITH OVERSIZED FINGER BUTTONS
// ============================================================================

@Composable
private fun VagMediaPlayerZone(
    mediaState: CockpitMediaState,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("cockpit_zone_media"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Row: Album Art + Track Metadata + Notification Access Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art Thumbnail
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(CyberBlue, ElectricCyan)
                            )
                        )
                        .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = mediaState.albumArtBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Album Art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = CockpitIcons.Album,
                            contentDescription = "Album Art Disc",
                            tint = ObsidianBackground,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mediaState.playerAppTitle.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!mediaState.hasNotificationAccess) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onRequestNotificationAccess() }
                                    .testTag("grant_media_access_btn"),
                                color = AmberSignal.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = CockpitIcons.NotificationsActive,
                                        contentDescription = "Grant Media Session Access",
                                        tint = AmberSignal,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Link Session",
                                        fontSize = 9.sp,
                                        color = AmberSignal
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = mediaState.trackTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${mediaState.artistName} • ${mediaState.albumName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Progress Bar
            LinearProgressIndicator(
                progress = { mediaState.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ElectricCyan,
                trackColor = ObsidianSurfaceHigh
            )

            // Large Touch-Friendly Automotive Transport Buttons (Minimum 52dp height)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AutomotiveMediaButton(
                    icon = CockpitIcons.SkipPrevious,
                    contentDescription = "Previous Track",
                    isPrimary = false,
                    testTag = "cockpit_media_prev_btn",
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    onClick = onPreviousTrack
                )

                AutomotiveMediaButton(
                    icon = if (mediaState.isPlaying) CockpitIcons.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (mediaState.isPlaying) "Pause" else "Play",
                    isPrimary = true,
                    testTag = "cockpit_media_play_pause_btn",
                    modifier = Modifier
                        .weight(1.3f)
                        .height(52.dp),
                    onClick = onPlayPause
                )

                AutomotiveMediaButton(
                    icon = CockpitIcons.SkipNext,
                    contentDescription = "Next Track",
                    isPrimary = false,
                    testTag = "cockpit_media_next_btn",
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    onClick = onNextTrack
                )
            }
        }
    }
}

@Composable
private fun AutomotiveMediaButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isPrimary: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (isPrimary) ElectricCyan else ObsidianSurfaceHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isPrimary) ElectricCyan else ObsidianBorder
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isPrimary) Color(0xFF002227) else TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
