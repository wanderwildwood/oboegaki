package com.wanderwildwood.oboegaki.ui

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Whether the on-screen keyboard is showing, asked of the window itself after every layout. The
 * app is not drawn edge to edge (the window is resized for the keyboard instead), and then
 * Compose's own keyboard inset stays at zero, so a check on it never saw the keyboard; the
 * window's insets still say.
 */
@Composable
fun rememberKeyboardUp(): Boolean {
    val view = LocalView.current
    var up by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            up = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return up
}
