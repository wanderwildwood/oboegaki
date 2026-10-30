package com.wanderwildwood.oboegaki.ui

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression for the crash when picking a day for a task or a note's reminder (0.2.9): Material3's
 * date picker draws icons from material-icons-core, which 1.4 stopped bringing along, and the
 * missing class (Icons$Filled) killed the app. The dependency must stay declared.
 */
class DatePickerClassesTest {
    @Test
    fun theIconsTheDatePickerDrawsAreOnTheClasspath() {
        for (name in listOf(
            "androidx.compose.material.icons.Icons\$Filled",
            "androidx.compose.material.icons.Icons",
            "androidx.compose.material3.DatePickerKt",
        )) assertNotNull(name, Class.forName(name))
    }
}
