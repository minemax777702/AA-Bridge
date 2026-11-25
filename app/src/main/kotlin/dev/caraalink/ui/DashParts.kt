package dev.caraalink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.caraalink.R
import dev.caraalink.ui.theme.Dash

enum class RingMode { IDLE, SPIN, FULL, ALERT }

@Suppress("UNUSED_PARAMETER")
fun Modifier.dashGlow(glow: Color): Modifier = background(Dash.Background)

@Composable
fun StatusRing(mode: RingMode, icon: Painter, label: String, modifier: Modifier = Modifier) {
    val color = when (mode) {
        RingMode.IDLE, RingMode.SPIN -> Dash.Teal
        RingMode.ALERT -> Dash.Amber
        RingMode.FULL -> Color(0xFF8DD8B1)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp).background(color.copy(alpha = 0.11f), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center) {
            if (mode == RingMode.SPIN) CircularProgressIndicator(Modifier.size(64.dp), color = color, strokeWidth = 2.dp)
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(34.dp))
        }
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

enum class Lamp { ON, OFF, ALERT }

@Composable
fun Telltale(icon: Painter, label: String, lamp: Lamp) {
    val color = when (lamp) {
        Lamp.ON -> Dash.Teal
        Lamp.ALERT -> Dash.Amber
        Lamp.OFF -> Dash.Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun GlowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp)) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AmberButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Dash.Amber, contentColor = Color(0xFF392509))) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DashLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Byline(modifier: Modifier = Modifier) {
    Text("${stringResource(R.string.made_by)} · v${dev.caraalink.BuildConfig.VERSION_NAME} (${dev.caraalink.BuildConfig.GIT_SHA})",
        modifier, color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun DashTopBar(onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Dash.GlowTeal, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_stat_bridge), contentDescription = null,
                    tint = Dash.Teal, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ui_brand_name), style = MaterialTheme.typography.titleLarge.copy(fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp))
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.background(Dash.Tile, RoundedCornerShape(16.dp))) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings), tint = Dash.Muted)
        }
    }
}
