package com.jellemax.detour.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jellemax.detour.R
import com.jellemax.detour.data.LogbookItem
import com.jellemax.detour.data.MonthWrapped
import com.jellemax.detour.data.TripCardFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** New towns named on the card before the rest collapse to "and 4 more". */
private const val WRAPPED_TOWNS_NAMED = 5

private val wrappedMonthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
private val wrappedTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val wrappedDayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

/**
 * The Month wrapped screen (#518), opened from a Logbook month header: the
 * month's distance and its highlights on one card, and Share. The image is
 * the card as drawn here, recorded into a graphics layer — what the rider
 * sees is what gets sent — and leaves through the trip card's share path.
 */
@Composable
fun MonthWrappedDialog(wrapped: MonthWrapped, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var error by remember { mutableStateOf<String?>(null) }

    fun share() = scope.launch {
        try {
            val bitmap = layer.toImageBitmap().asAndroidBitmap()
            val name = "detour-wrapped-%04d-%02d.png".format(Locale.US, wrapped.year, wrapped.month)
            val uri = withContext(Dispatchers.IO) { TripCardFile.writeForShare(context, name, bitmap) }
            context.startActivity(
                Intent.createChooser(TripCardFile.shareIntent(uri), context.getString(R.string.wrapped_share)),
            )
        } catch (e: ActivityNotFoundException) {
            error = context.getString(R.string.card_no_image_app)
        } catch (e: IOException) {
            // A write to our own cache: the likely cause is a full disk.
            error = context.getString(R.string.card_not_saved)
        } catch (e: IllegalArgumentException) {
            // FileProvider.getUriForFile refusing the path.
            error = context.getString(R.string.card_not_saved)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.history_close))
                }
                WrappedCard(wrapped, layer)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { share() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Share, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.wrapped_share))
                }
            }
        }
    }
}

/** The shareable card itself, recorded into [layer] on every draw. */
@Composable
private fun WrappedCard(wrapped: MonthWrapped, layer: GraphicsLayer) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                wrappedMonthFormat.format(monthStartMs(wrapped)),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.wrapped_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
        }
        Column {
            Text(
                formatDistanceKm(wrapped.meters),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
            )
            Text(
                pluralStringResource(R.plurals.history_rides, wrapped.rides, wrapped.rides),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onPrimaryContainer,
            )
        }
        WrappedHighlights(wrapped)
        Text(
            stringResource(R.string.wrapped_footer),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

/** One row per highlight the month recorded; a car-only month has no lean
 *  row rather than a 0° one. */
@Composable
private fun WrappedHighlights(wrapped: MonthWrapped) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        wrapped.twistiest?.let {
            val pct = (it.trip.drivingStats.twistinessScore * 100).roundToInt()
            WrappedRow(Icons.Outlined.Gesture, stringResource(R.string.wrapped_twistiest), "$pct%", it.title)
        }
        wrapped.earliestStart?.let {
            WrappedRow(
                Icons.Outlined.WbTwilight, stringResource(R.string.wrapped_earliest_start),
                wrappedTimeFormat.format(it.trip.startTimeMs), rideDetail(it),
            )
        }
        wrapped.favouriteWeekday?.let { iso ->
            val day = DayOfWeek.of(iso).getDisplayName(TextStyle.FULL, Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val n = wrapped.favouriteWeekdayRides
            WrappedRow(
                Icons.Outlined.Event, stringResource(R.string.wrapped_favourite_weekday),
                day, pluralStringResource(R.plurals.history_rides, n, n),
            )
        }
        wrapped.deepestLean?.let {
            WrappedRow(
                Icons.Outlined.TwoWheeler, stringResource(R.string.wrapped_deepest_lean),
                formatLeanAngle(it.trip.maxLeanAngleDeg), it.title,
            )
        }
        if (wrapped.newTowns.isNotEmpty()) {
            val named = wrapped.newTowns.take(WRAPPED_TOWNS_NAMED).joinToString(", ")
            val more = wrapped.newTowns.size - WRAPPED_TOWNS_NAMED
            WrappedRow(
                Icons.Outlined.LocationCity, stringResource(R.string.wrapped_new_towns),
                "${wrapped.newTowns.size}",
                if (more > 0) stringResource(R.string.wrapped_towns_more, named, more) else named,
            )
        }
    }
}

@Composable
private fun WrappedRow(icon: ImageVector, label: String, value: String, detail: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label, style = MaterialTheme.typography.labelMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Text(
                value, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer, maxLines = 2)
        }
    }
}

private fun rideDetail(ride: LogbookItem.Ride) = "${wrappedDayFormat.format(ride.trip.startTimeMs)} · ${ride.title}"

private fun monthStartMs(wrapped: MonthWrapped): Long =
    Calendar.getInstance().apply {
        clear()
        set(wrapped.year, wrapped.month - 1, 1)
    }.timeInMillis
