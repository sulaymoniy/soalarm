package com.sulaymoniy.soalarm.ui

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sulaymoniy.soalarm.R
import com.sulaymoniy.soalarm.alarm.AlarmTime
import com.sulaymoniy.soalarm.data.Alarm
import com.sulaymoniy.soalarm.data.Days
import java.time.LocalTime
import java.time.format.TextStyle

/** Add or edit one alarm. [initial] is null for a new alarm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(
    initial: Alarm?,
    onCancel: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: (Alarm) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val start = initial ?: LocalTime.now().plusHours(1).let { Alarm(hour = it.hour, minute = 0) }
    val timeState = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    var days by rememberSaveable { mutableIntStateOf(start.days) }
    var label by rememberSaveable { mutableStateOf(start.label) }

    val draft = (initial ?: Alarm(hour = 0, minute = 0)).copy(
        hour = timeState.hour,
        minute = timeState.minute,
        days = days,
        label = label.trim(),
        enabled = true,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "New alarm" else "Edit alarm") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
                actions = {
                    TextButton(onClick = { onSave(draft) }) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TimePicker(state = timeState)

            Text(
                AlarmTime.ringsInText(AlarmTime.nextTrigger(draft)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Repeat: ${AlarmTime.daysText(days)}", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Days.ORDER.forEach { day ->
                        DayToggle(
                            letter = day.getDisplayName(TextStyle.NARROW, locale),
                            description = day.getDisplayName(TextStyle.FULL, locale),
                            selected = Days.has(days, day),
                            onClick = { days = Days.toggle(days, day) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(40) },
                label = { Text("Label (optional)") },
                placeholder = { Text("e.g. Work") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (initial != null) {
                OutlinedButton(
                    onClick = { onDelete(initial) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                    Text("Delete alarm", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun DayToggle(letter: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        // Screen readers read the full day name instead of one letter.
        modifier = Modifier
            .size(42.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, fontWeight = FontWeight.SemiBold)
        }
    }
}
