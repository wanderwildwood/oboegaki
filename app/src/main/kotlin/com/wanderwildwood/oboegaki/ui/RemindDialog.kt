package com.wanderwildwood.oboegaki.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.time.DatePickerFormatterMMD
import com.mudita.mmd.components.time.DatePickerMMD
import com.mudita.mmd.components.time.TimeInputMMD
import com.mudita.mmd.components.time.rememberDatePickerMMDState
import com.mudita.mmd.components.time.rememberTimeInputMMDState
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.reminderAt
import com.wanderwildwood.oboegaki.notes.reminderTime
import com.wanderwildwood.oboegaki.remind.Times
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * "Remind me at…", for a note or one item on a list: a day and a time, and Set. It says
 * plainly that the reminder rings on this phone, since the note may be on others too.
 *
 * [current]: the reminder already there, if any; [here]: whether this phone set it. [onCalendar],
 * when given, is the other way to be reminded: a calendar app's new event.
 */
@Composable
fun RemindDialog(
    heading: String,
    current: String?,
    here: Boolean,
    onSet: (String) -> Unit,
    onRemove: (() -> Unit)?,
    onCalendar: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val start = remember {
        current?.let(::reminderTime)
            ?: LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
    }
    var day by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime()) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var past by remember { mutableStateOf(false) }
    var exact by remember { mutableStateOf(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) }
    // Read again on coming back from the permission's page.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exact = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = heading, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        PickRow(stringResource(R.string.remind_day), Times.relativeDay(context, day)) { pickingDay = true }
        PickRow(stringResource(R.string.remind_time), Times.time(context, day.atTime(time))) { pickingTime = true }
        Spacer(Modifier.height(10.dp))
        TextMMD(
            text = stringResource(if (current != null && !here) R.string.remind_elsewhere else R.string.remind_this_phone),
            style = MaterialTheme.typography.labelSmall,
        )
        if (!exact) {
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.remind_exact), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedButtonMMD(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { TextMMD(text = stringResource(R.string.remind_allow), style = MaterialTheme.typography.bodySmall) }
        }
        if (past) {
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.remind_past), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.remind_cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            ButtonMMD(
                onClick = {
                    val at = day.atTime(time)
                    if (!at.isAfter(LocalDateTime.now())) {
                        past = true
                    } else {
                        onSet(reminderAt(at))
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                TextMMD(
                    text = stringResource(if (current != null && !here) R.string.remind_set_here else R.string.remind_set),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (onRemove != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButtonMMD(onClick = onRemove, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.remind_remove), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onCalendar != null) {
            TextMMD(
                text = stringResource(R.string.remind_calendar),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onCalendar).padding(top = 14.dp, bottom = 4.dp),
            )
        }
    }

    if (pickingDay) {
        DateDialog(initial = day, onPick = { day = it; past = false }, onDismiss = { pickingDay = false })
    }
    if (pickingTime) {
        TimeDialog(initial = time, onPick = { time = it; past = false }, onDismiss = { pickingTime = false })
    }
}

@Composable
private fun PickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextMMD(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
    HorizontalDividerMMD()
}

/**
 * Mudita's time input: the hour and the minute typed, the way the Kompakt's own clock sets
 * them. Always on the 24-hour clock: in 12-hour mode MMD 1.0.2 sets a typed hour as it stands,
 * so "6" with PM showing came out as 6 in the morning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimeInputMMDState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    EInkDialog(onDismiss = onDismiss) {
        TimeInputMMD(state = state, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        PickerButtons(onCancel = onDismiss, onOk = {
            onPick(LocalTime.of(state.hour, state.minute))
            onDismiss()
        })
    }
}

/**
 * Mudita's own date picker, on the whole screen: it is Material's picker underneath and wants
 * 360dp of width, which a dialog's rim on a 366dp screen does not leave it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberDatePickerMMDState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    // MMD hands its formatter UTC midnight; read in the phone's zone it is the day before
    // anywhere west of Greenwich, so it is read in UTC here.
    val formatter = object : DatePickerFormatterMMD {
        override fun formatMonthYear(monthMillis: Long?, locale: java.util.Locale): String? =
            monthMillis?.let {
                val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(d)
            }

        override fun formatDate(dateMillis: Long?, locale: java.util.Locale, forContentDescription: Boolean): String? =
            dateMillis?.let { Times.day(context, Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                DatePickerMMD(state = state, dateFormatter = formatter, title = null, headline = null, showModeToggle = false)
                Spacer(Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 20.dp)) {
                    PickerButtons(onCancel = onDismiss, onOk = {
                        state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        onDismiss()
                    })
                }
            }
        }
    }
}

@Composable
private fun PickerButtons(onCancel: () -> Unit, onOk: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        OutlinedButtonMMD(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) {
            TextMMD(text = stringResource(R.string.remind_cancel), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        ButtonMMD(onClick = onOk, modifier = Modifier.weight(1f).height(48.dp)) {
            TextMMD(text = stringResource(R.string.dav_ok), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A reminder's time, small, with an alarm clock in front: what shows on a note or an item. */
@Composable
fun ReminderLabel(at: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier = modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_reminder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 3.dp).height(14.dp).width(14.dp),
        )
        TextMMD(
            text = reminderTime(at)?.let { Times.whenShort(context, it) } ?: at,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
