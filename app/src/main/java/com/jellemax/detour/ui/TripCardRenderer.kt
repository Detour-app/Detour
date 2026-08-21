package com.jellemax.detour.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.CardData
import com.jellemax.detour.data.CardPoint
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripCardFile
import com.jellemax.detour.data.TripCardGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The card's fixed pixel size — see the design spec's "one 1080x1350 portrait
 *  card covers most social targets". Independent of the device's own density:
 *  the card is a fixed-size export, not a screen layout. */
private const val CARD_WIDTH_PX = 1080
private const val CARD_HEIGHT_PX = 1350

/**
 * Renders [cardData] offscreen and returns the captured bitmap once ready.
 * `null` on the first frame (nothing captured yet) — callers gate the actual
 * share on this becoming non-null.
 */
@Composable
fun rememberTripCardBitmap(
    cardData: CardData,
    routeColorHex: String,
    dark: Boolean,
    trimmed: Boolean,
): State<ImageBitmap?> {
    val bitmapState = remember(cardData, routeColorHex, dark, trimmed) { mutableStateOf<ImageBitmap?>(null) }
    val graphicsLayer = rememberGraphicsLayer()
    val density = Density(density = 1f) // 1px == 1px: the card is exported at a fixed pixel size.

    // Zero-size, clipped parent: the child below is still placed and drawn
    // (which is what lets graphicsLayer.record capture it) but nothing of it
    // is visible on screen, since the parent clips to an empty box.
    //
    // CompositionLocalProvider(LocalDensity provides density) below is what
    // actually makes the export density-independent: a Dp value doesn't
    // carry the density it was computed with, so the with(density){...toDp()}
    // conversions above would otherwise be resolved against the real
    // LocalDensity.current (the device's own screen density) once measured
    // — silently exporting a 3x/2x-scaled bitmap on a real phone instead of
    // the fixed 1080x1350px card this whole function promises. Installing
    // `density` as the ambient LocalDensity for this subtree makes both the
    // Dp *computation* and its later *measurement* use the same density=1f,
    // so 1dp really does measure out to 1px here.
    CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.size(0.dp).clipToBounds()) {
            Box(
                Modifier
                    // requiredSize, not size: the outer Box above forces this
                    // subtree's *incoming* constraints to a fixed 0x0 (that's how
                    // it reports zero footprint to its own parent). A plain
                    // size() coerces its target into whatever incoming range it's
                    // given, which would collapse this box to 0x0 right along
                    // with its parent. requiredSize() ignores the incoming
                    // constraints and forces the real 1080x1350 card size on the
                    // content below regardless — the overflow beyond the outer
                    // Box's 0x0 bounds is exactly what clipToBounds() above hides
                    // from the real screen, while the graphicsLayer capture below
                    // (which runs in this box's own draw phase, before that outer
                    // clip is applied) still sees the full-size content.
                    .requiredSize(with(density) { CARD_WIDTH_PX.toDp() }, with(density) { CARD_HEIGHT_PX.toDp() })
                    .drawWithContent {
                        graphicsLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(graphicsLayer)
                    },
            ) {
                TripCardContent(cardData, routeColorHex, dark, trimmed)
            }
        }
    }

    LaunchedEffect(cardData, routeColorHex, dark, trimmed) {
        // The draw phase above needs at least one composition/layout/draw
        // pass to have run before the layer holds anything; a single
        // withFrameNanos wait is enough since the Box is already in the tree.
        androidx.compose.runtime.withFrameNanos { }
        bitmapState.value = graphicsLayer.toImageBitmap()
    }

    return bitmapState
}

/**
 * The entire "Share trip card" flow: trim toggle, render, write, launch the
 * share intent. [points] is null while the caller is still loading the trace
 * (HistoryScreen's row menu only holds a capped thumbnail, so it loads the
 * full trace lazily after the dialog opens) — shown as a loading state
 * rather than delaying the dialog's appearance.
 */
@Composable
fun TripCardShareDialog(trip: Trip, points: List<LatLon>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullRoute by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val routeColor by Settings.routeColor.collectAsStateWithLifecycle()
    // isAppDarkTheme (Theme.kt, same package) resolves the app's own Theme
    // setting (AUTO/LIGHT/DARK/SYSTEM) — not bare isSystemInDarkTheme() —
    // which is what makes the card's "amber at night, blue at day" match
    // what TripDetailScreen/MapScreen/etc already show, per the spec.
    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val dark = isAppDarkTheme(themePref)
    val routeColorHex = RouteColors.hex(routeColor, dark)

    val cardData = points?.let { pts -> remember(pts, fullRoute) { TripCardGeometry.build(trip, pts, full = fullRoute) } }
    val bitmap = cardData?.let { rememberTripCardBitmap(it, routeColorHex, dark = dark, trimmed = !fullRoute).value }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share trip card") },
        text = {
            Column {
                when {
                    points == null -> Text("Loading route…")
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> {
                        if (!fullRoute) Text("Route trimmed near start/end for privacy.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = fullRoute, onCheckedChange = { fullRoute = it })
                            Text("Include full route")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bitmap != null,
                onClick = {
                    val bmp = bitmap ?: return@TextButton
                    scope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                TripCardFile.writeForShare(context, trip, bmp.asAndroidBitmap())
                            }
                            context.startActivity(Intent.createChooser(
                                TripCardFile.shareIntent(uri), "Share trip card"))
                            onDismiss()
                        } catch (e: ActivityNotFoundException) {
                            error = "No app to receive an image"
                        } catch (e: IOException) {
                            error = "Card export failed: ${e.message}"
                        }
                    }
                },
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Matches iOS's `#0B1220` dark / white light pair (`TripCardRenderer.swift`),
 *  itself `RouteColors.DRIVEN_TOWARDS_DARK`/`DRIVEN_TOWARDS_LIGHT` — reused
 *  here for consistency rather than inventing a new pair for this screen. */
private val CARD_BACKGROUND_DARK = Color(0xFF0B1220)
private val CARD_BACKGROUND_LIGHT = Color.White
private val CARD_TEXT_DARK = Color.White
private val CARD_TEXT_LIGHT = Color.Black

@Composable
private fun TripCardContent(cardData: CardData, routeColorHex: String, dark: Boolean, trimmed: Boolean) {
    val trip = cardData.trip
    val routeColor = Color(android.graphics.Color.parseColor(routeColorHex))
    val backgroundColor = if (dark) CARD_BACKGROUND_DARK else CARD_BACKGROUND_LIGHT
    val textColor = if (dark) CARD_TEXT_DARK else CARD_TEXT_LIGHT
    // Pin the text color explicitly rather than letting Text() inherit
    // ambient LocalContentColor: MainActivity wraps the whole app in a
    // Surface{}, so without this the captured subtree would pick up
    // onSurface (near-white in the app's dark theme) regardless of what
    // background this card itself decided to paint.
    CompositionLocalProvider(LocalContentColor provides textColor) {
        Column(
            Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(48.dp),
        ) {
            if (cardData.points.isNotEmpty()) {
                // weight(1f), not fillMaxSize(): a fillMaxSize() child inside this
                // Column would claim the Column's entire measured height for
                // itself, since Column gives an unweighted child loose (not
                // shrink-to-fit) constraints — pushing the stat rows below fully
                // off the bottom of the fixed-height card.
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 24.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        // cardData.points are already normalized to preserve
                        // aspect ratio (shared TripCardGeometry divides both
                        // axes by the same larger span). Mapping them onto the
                        // full (non-square) measured box here would re-stretch
                        // that aspect-corrected shape, so map into a centered
                        // *square* sub-region instead.
                        val s = minOf(size.width, size.height)
                        val offsetX = (size.width - s) / 2f
                        val offsetY = (size.height - s) / 2f
                        fun toOffset(p: CardPoint) = Offset(offsetX + p.x * s, offsetY + p.y * s)

                        val path = androidx.compose.ui.graphics.Path()
                        cardData.points.forEachIndexed { i, p ->
                            val offset = toOffset(p)
                            if (i == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                        }
                        drawPath(path, routeColor, style = Stroke(width = 10f, cap = StrokeCap.Round))
                        cardData.destination?.let { d ->
                            drawCircle(routeColor, radius = 14f, center = toOffset(d))
                        }
                    }
                }
            }
            if (trimmed) {
                // Design spec: "a small caption under the route" — this is
                // what actually lands in the exported PNG, distinct from the
                // dialog's own (pre-share) caption above the Share button.
                Text(
                    "Route trimmed near start/end for privacy.",
                    fontSize = 22.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // Explicit fontSize, not the bare Material type scale: this
            // subtree is pinned to density=1f for a fixed-pixel export (see
            // rememberTripCardBitmap), which makes sp resolve 1:1 to px —
            // MaterialTheme.typography's ~22sp/~16sp values were calibrated
            // for real screen density and render far too small on a
            // 1080px-wide image at density 1.
            Text(
                trip.mode.label + " · " + formatDate(trip.startTimeMs),
                style = MaterialTheme.typography.titleLarge,
                fontSize = 44.sp,
            )
            Column(Modifier.padding(top = 16.dp)) {
                statRow("Distance", formatDistanceKm(trip.distanceMeters))
                statRow("Duration", formatDurationHistory(trip.durationMs))
                statRow("Avg speed", formatSpeedKmh(trip.avgSpeedMps))
                statRow("Top speed", formatSpeedKmh(trip.topSpeedMps))
                cardData.peakLeanDeg?.let { statRow("Peak lean", formatLeanAngle(it)) }
                cardData.peakGForce?.let { statRow("Peak g", formatGForce(it)) }
            }
        }
    }
}

@Composable
private fun statRow(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyLarge, fontSize = 28.sp)
}
