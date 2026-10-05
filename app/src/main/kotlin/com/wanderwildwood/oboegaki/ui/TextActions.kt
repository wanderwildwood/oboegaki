package com.wanderwildwood.oboegaki.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The menu over selected text, as the Kompakt needs it.
 *
 * **Its overflow given back.** The Kompakt's selection bar is laid out the stock way, buttons
 * placed by width until it is full, but Mudita draws no ⋮ for the rest, so everything past the
 * first two or three is silently gone — Paste, Select all, and every app that acts on text,
 * Define among them. This keeps the first [KEEP] items as Compose ordered them and adds a ⋮
 * that lists the rest in the same order. Nothing is moved ahead of Compose's own items.
 *
 * **Answers written back** ([value] and [onValue] given). Compose starts the apps that act on text with
 * `startActivity`, which tells them the text is read-only and has no way to take an answer
 * back, so a thesaurus could look a word up and never put another in its place. In a field,
 * Compose's entries are replaced by the same apps under the same labels, started for a result.
 * The answer goes over the range that was selected, and only if that range still holds what was
 * sent; otherwise nothing is written, rather than the wrong thing.
 *
 * The Typewriter's file (TextActions.kt there, commit 0aab491), for a field that holds a
 * TextFieldValue rather than a TextFieldState: the answer arrives as a new value through
 * [onValue]. The host needs `<queries>` for PROCESS_TEXT in its manifest.
 */
@Composable
fun Modifier.textActions(value: TextFieldValue? = null, onValue: ((TextFieldValue) -> Unit)? = null): Modifier {
    val context = LocalContext.current
    val now by rememberUpdatedState(value)
    val update by rememberUpdatedState(onValue)
    val writable = value != null && onValue != null
    val view = LocalView.current
    val apps = remember {
        val query = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        // The flags-object overload is Android 13; the Kompakt is 12.
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(query, 0).filter { it.activityInfo.exported }
    }
    val actions = remember {
        apps.filter { it.activityInfo.packageName !in NOT_SHOWN }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(context.packageManager).toString() }
    }
    // Where read-only text is selected, Compose's own entries stay, since only they know the
    // selection; the ones left out are known by their label.
    val hiddenLabels = remember {
        apps.filter { it.activityInfo.packageName in NOT_SHOWN }.map { it.loadLabel(context.packageManager).toString() }.toSet()
    }
    val folded = remember { mutableListOf<TextContextMenuItem>() }
    val kept = remember { IntArray(1) }
    // The last press, in window coordinates: the selection starts where the finger went down,
    // and the list of the rest opens beside it.
    val press = remember { FloatArray(2) }
    val origin = remember { FloatArray(2) }

    val sent = remember { arrayOfNulls<Pair<TextRange, String>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (range, text) = sent[0] ?: return@rememberLauncherForActivityResult
        sent[0] = null
        val answer = result.data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val current = now?.text
        val write = update
        if (current == null || write == null || result.resultCode != Activity.RESULT_OK || answer == null) return@rememberLauncherForActivityResult
        if (range.max > current.length || current.substring(range.min, range.max) != text) return@rememberLauncherForActivityResult
        write(TextFieldValue(current.take(range.min) + answer + current.drop(range.max), TextRange(range.min + answer.length)))
    }

    return this
        .onGloballyPositioned { origin[0] = it.positionInWindow().x; origin[1] = it.positionInWindow().y }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                    if (change.pressed) {
                        press[0] = origin[0] + change.position.x
                        press[1] = origin[1] + change.position.y
                    }
                }
            }
        }
        .filterTextContextMenuComponents { component ->
            when {
                component.key == More -> folded.isNotEmpty()
                writable && component.key is ProcessTextKey -> false
                component.key is ProcessTextKey && component is TextContextMenuItem && component.label in hiddenLabels -> false
                component !is TextContextMenuItem -> false // separators: the bar has no room for them
                kept[0] < KEEP -> { kept[0]++; true }
                else -> { folded += component; false }
            }
        }
        .appendTextContextMenuComponents {
            folded.clear()
            kept[0] = 0
            val range = now?.selection
            if (writable && range != null && !range.collapsed) {
                actions.forEachIndexed { i, (component, label) ->
                    item(key = Action(i), label = label) {
                        val text = now!!.text.substring(range.min, range.max)
                        sent[0] = range to text
                        runCatching {
                            launcher.launch(
                                Intent(Intent.ACTION_PROCESS_TEXT)
                                    .setType("text/plain")
                                    .setComponent(component)
                                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
                            )
                        }.onFailure { sent[0] = null }
                        close()
                    }
                }
            }
            item(key = More, label = "⋮") { showRest(view, folded.toList(), this, press[1]) }
        }
}

/**
 * The rest as a list, under the press or above it, clear of the bar. A pop-up that never takes
 * focus, not a dialog, so the selection survives. Choosing an item presses it in the menu's own
 * session, exactly as pressing it on the bar would have.
 */
private fun showRest(view: View, items: List<TextContextMenuItem>, session: TextContextMenuSession, pressY: Float) {
    if (items.isEmpty()) return
    val context = view.context
    val dp = context.resources.displayMetrics.density
    lateinit var popup: PopupWindow
    val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke((2 * dp).toInt(), Color.BLACK)
            cornerRadius = 12 * dp
        }
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        for (item in items) {
            addView(TextView(context).apply {
                text = item.label
                textSize = 18f
                setTextColor(Color.BLACK)
                setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
                setOnClickListener { popup.dismiss(); item.onClick(session) }
            })
        }
    }
    popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        isOutsideTouchable = true
        elevation = 0f
    }
    list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val root = view.rootView
    val below = (pressY + 52 * dp).toInt()
    val y = if (below + list.measuredHeight <= root.height) below
        else (pressY - 88 * dp - list.measuredHeight).toInt().coerceAtLeast(0)
    val x = ((root.width - list.measuredWidth) / 2).coerceAtLeast(0)
    popup.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
}

/** How many of Compose's items stay on the bar before the ⋮; Mudita's bar fits three buttons. */
private const val KEEP = 2

/**
 * Text apps left out of the menu by his choice (2026-10-05): EinkBro's entry is an online
 * dictionary that duplicates Define, and EinkBro has no setting to withdraw it.
 */
private val NOT_SHOWN = setOf("info.plateaukao.einkbro")

/** Menu entries of this file's own, so the filter above does not take them out again. */
private data class Action(val index: Int)
private object More
