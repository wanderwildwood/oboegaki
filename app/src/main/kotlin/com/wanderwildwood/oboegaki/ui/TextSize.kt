package com.wanderwildwood.oboegaki.ui

import androidx.compose.runtime.mutableIntStateOf
import com.wanderwildwood.oboegaki.notes.Preferences

/** The words' size, Small, Medium or Large, applied to the whole app by scaling its text. */
object TextSize {
    val level = mutableIntStateOf(1)

    /** What the text scale is multiplied by. */
    fun factor(): Float = when (level.intValue) {
        0 -> 0.85f
        2 -> 1.2f
        else -> 1f
    }

    fun load(preferences: Preferences) {
        level.intValue = preferences.textSize
    }

    fun choose(preferences: Preferences, value: Int) {
        preferences.textSize = value
        level.intValue = value
    }
}
