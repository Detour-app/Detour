package com.jellemax.detour.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

private const val NAV_PATH = "/nav"

/** Turn state pushed from the phone app's NavRelay; mirrors NavEngine.Progress + NavInstruction. */
private data class NavState(
    val sign: Int,
    val distanceToTurnMeters: Double,
    val speedKmh: Double,
    val speedLimitKmh: Double?,
)

/** GraphHopper sign code -> maneuver arrow (mirrors phone app's ui/Navigation.kt). */
private fun signIcon(sign: Int): ImageVector = when (sign) {
    -98, -8 -> Icons.Default.UTurnLeft
    8 -> Icons.Default.UTurnRight
    -7 -> Icons.Default.ForkLeft
    7 -> Icons.Default.ForkRight
    -3 -> Icons.Default.TurnSharpLeft
    -2 -> Icons.Default.TurnLeft
    -1 -> Icons.Default.TurnSlightLeft
    1 -> Icons.Default.TurnSlightRight
    2 -> Icons.Default.TurnRight
    3 -> Icons.Default.TurnSharpRight
    4, 5 -> Icons.Default.SportsScore
    6 -> Icons.Default.RoundaboutLeft
    else -> Icons.Default.Straight
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "%.0f m".format(meters) else "%.1f km".format(meters / 1000)

class MainActivity : ComponentActivity() {

    private var navState by mutableStateOf<NavState?>(null)

    private val listener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != NAV_PATH) return@OnMessageReceivedListener
        val json = JSONObject(String(event.data))
        navState = if (json.has("stop")) null else NavState(
            sign = json.optInt("sign"),
            distanceToTurnMeters = json.optDouble("distanceToTurnMeters"),
            speedKmh = json.optDouble("speedKmh"),
            speedLimitKmh = if (json.isNull("speedLimitKmh")) null else json.optDouble("speedLimitKmh"),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NavScreen(navState) }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(listener)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(listener)
        super.onPause()
    }
}

/** Same amber-on-graphite identity as the phone, so the watch face matches. */
private val WearColors = Colors(
    primary = Color(0xFFE8B04B),
    onPrimary = Color(0xFF241C08),
    secondary = Color(0xFFC9B58A),
    onSecondary = Color(0xFF2A2410),
    background = Color(0xFF14170F),
    onBackground = Color(0xFFEDE9DB),
    surface = Color(0xFF24281D),
    onSurface = Color(0xFFEDE9DB),
    onSurfaceVariant = Color(0xFFB7AF98),
    error = Color(0xFFE4533A),
)

@Composable
private fun NavScreen(state: NavState?) {
    MaterialTheme(colors = WearColors) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                signIcon(state?.sign ?: 0),
                contentDescription = null,
                tint = MaterialTheme.colors.primary,
                modifier = Modifier
                    .size(88.dp)
                    .padding(bottom = 12.dp),
            )
            Text(
                state?.let { formatDistance(it.distanceToTurnMeters) } ?: "No route",
                style = MaterialTheme.typography.display1,
            )
            state?.let {
                // Over the limit by more than a small GPS-noise margin turns this red;
                // no limit number shown, so it stays a glance, not a read.
                val speeding = it.speedLimitKmh?.let { limit -> it.speedKmh > limit + 5 } ?: false
                Text(
                    "%.0f".format(it.speedKmh),
                    style = MaterialTheme.typography.caption1,
                    color = if (speeding) Color(0xFFE53935) else MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
