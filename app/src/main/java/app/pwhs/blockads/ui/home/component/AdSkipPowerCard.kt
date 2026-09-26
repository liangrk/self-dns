package app.pwhs.blockads.ui.home.component

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.service.AdSkipUiState

/**
 * Home-screen card for the accessibility ad-skip feature. Mirrors the
 * PowerButton visual language: glowing circular icon with tri-state
 * coloring (off / waiting for permission / running) and a step-by-step
 * enablement guide dialog.
 */
@Composable
fun AdSkipPowerCard(
    state: AdSkipUiState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showGuide by remember { mutableStateOf(false) }

    val active = state == AdSkipUiState.RUNNING
    val waiting = state == AdSkipUiState.NEED_PERMISSION
    val accent = when {
        active -> MaterialTheme.colorScheme.primary
        waiting -> Color(0xFF2196F3)
        else -> MaterialTheme.colorScheme.outline
    }
    val subtitle = when {
        active -> stringResource(R.string.ad_skip_state_running)
        waiting -> stringResource(R.string.ad_skip_state_wait)
        else -> stringResource(R.string.ad_skip_state_off)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                when {
                    active -> onToggle(false)
                    else -> showGuide = true
                }
            }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .shadow(
                        8.dp, CircleShape,
                        ambientColor = accent.copy(alpha = 0.3f),
                        spotColor = accent.copy(alpha = 0.3f)
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.2f), MaterialTheme.colorScheme.surface)
                        )
                    )
                    .border(
                        2.dp,
                        Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.5f))),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ad_skip_home_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent
                )
            }
            // status dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(stringResource(R.string.ad_skip_guide_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.ad_skip_guide_step1),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.ad_skip_guide_step2),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.ad_skip_guide_step3),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGuide = false
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    } catch (_: Exception) {
                    }
                }) {
                    Text(stringResource(R.string.ad_skip_guide_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGuide = false }) {
                    Text(stringResource(R.string.ad_skip_guide_later))
                }
            }
        )
    }
}
