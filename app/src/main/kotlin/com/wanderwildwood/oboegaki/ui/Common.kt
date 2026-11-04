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
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

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
 * Whether a destructive row is armed. It stays armed until the next other touch, Back or leaving
 * the screen (shared Armed.kt), so a stray tap never leaves a live trigger behind.
 */
@Composable
fun rememberArmed(): MutableState<Boolean> = rememberArmedFlag()

/**
 * Whether the window is short: the phone turned on its side, where the Kompakt is about 360 dp
 * tall (600 dp upright). Screens give up fixed heights and floating buttons' room by this.
 */
fun isShortWindow(heightDp: Int): Boolean = heightDp < SHORT_WINDOW_DP

private const val SHORT_WINDOW_DP = 480

/** How tall a dialog's own list may be: [wanted], and no more than what is left once the dialog's frame and the bar above it are allowed for. */
fun dialogListHeightDp(wanted: Int, windowHeightDp: Int): Int = wanted.coerceAtMost((windowHeightDp - DIALOG_FRAME_DP).coerceAtLeast(MIN_DIALOG_LIST_DP))

private const val DIALOG_FRAME_DP = 120
private const val MIN_DIALOG_LIST_DP = 120

@Composable
fun rememberShortWindow(): Boolean = isShortWindow(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp)

@Composable
fun rememberWindowHeightDp(): Int = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
