package com.wanderwildwood.oboegaki.ui

import androidx.compose.runtime.mutableIntStateOf
import com.wanderwildwood.oboegaki.notes.Preferences

/** The words' size, Smaller, Standard or Larger, applied to the whole app by scaling its text. */
object TextSize {
    val level = mutableIntStateOf(1)

    /** What the text scale is multiplied by. */
    fun factor(): Float = factorFor(level.intValue)

    /** The scale for a level: 0 Smaller, 1 Standard, 2 Larger. Anything else is Standard. */
    fun factorFor(level: Int): Float = when (level) {
        0 -> SMALLER
        2 -> LARGER
        else -> 1f
    }

    const val SMALLER = 0.75f
    const val LARGER = 1.2f

    fun load(preferences: Preferences) {
        level.intValue = preferences.textSize
    }

    fun choose(preferences: Preferences, value: Int) {
        preferences.textSize = value
        level.intValue = value
    }
}
