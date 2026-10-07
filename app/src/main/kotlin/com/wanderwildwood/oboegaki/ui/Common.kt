package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** An icon in a top bar, with a target a thumb can find. */
@Composable
fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Whether a destructive row is armed. It disarms itself after four seconds, so a stray tap does
 * not leave a live trigger for whoever picks the phone up next.
 */
@Composable
fun rememberArmed(): MutableState<Boolean> {
    val armed = remember { mutableStateOf(false) }
    LaunchedEffect(armed.value) {
        if (armed.value) {
            delay(4000)
            armed.value = false
        }
    }
    return armed
}

/** The dotted rule between rows, inset to where the text starts and ends. The same rule as Contacts'. */
@Composable
internal fun DottedRule(modifier: Modifier = Modifier, start: androidx.compose.ui.unit.Dp = 16.dp, end: androidx.compose.ui.unit.Dp = 16.dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .height(1.dp),
    ) {
        drawLine(
            color = ink,
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx().coerceAtMost(1.5f),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(2.3.dp.toPx(), 1.5.dp.toPx()),
            ),
        )
    }
}
