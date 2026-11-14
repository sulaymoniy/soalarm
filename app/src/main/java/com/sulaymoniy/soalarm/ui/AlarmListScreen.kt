package com.sulaymoniy.soalarm.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Intent
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import com.sulaymoniy.soalarm.ring.WashTestActivity
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sulaymoniy.soalarm.R
import com.sulaymoniy.soalarm.alarm.AlarmTime
import com.sulaymoniy.soalarm.data.Alarm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

private const val NEW_ALARM = 0L

/** Top of the app: the alarm list, or the editor when one is open. */
@Composable
fun SoAlarmRoot(vm: AlarmViewModel = viewModel()) {
    val context = LocalContext.current
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // null = list, 0 = new alarm, otherwise the id being edited.
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }

    // --- Permissions: re-checked every time the app comes back to the front. ---
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val missing = remember(refresh) { Permissions.missing(context) }

    var askedAt by remember { mutableLongStateOf(0L) }
    var askCameraNext by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        // An instant "no" means Android won't show the pop-up again: open Settings instead.
        if (!ok && SystemClock.elapsedRealtime() - askedAt < 400) openSettings(context, Need.Camera)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (!ok && !askCameraNext && SystemClock.elapsedRealtime() - askedAt < 400) {
            openSettings(context, Need.Notifications)
        }
        if (askCameraNext) {
            askCameraNext = false
            if (Need.Camera in Permissions.missing(context)) {
                askedAt = SystemClock.elapsedRealtime()
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    fun request(need: Need) {
        askedAt = SystemClock.elapsedRealtime()
        when (need) {
            Need.Notifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            Need.Camera -> cameraLauncher.launch(Manifest.permission.CAMERA)
            else -> openSettings(context, need)
        }
    }

    /** The right moment: right after the first alarm is saved, ask for the two pop-up permissions. */
    fun askEssentialsAfterSave() {
        val now = Permissions.missing(context)
        when {
            Need.Notifications in now -> {
                askCameraNext = Need.Camera in now
                request(Need.Notifications)
            }
            Need.Camera in now -> request(Need.Camera)
        }
    }

    val showRingsIn: (ZonedDateTime?) -> Unit = { trigger ->
        if (trigger != null) scope.launch { snackbar.showSnackbar(AlarmTime.ringsInText(trigger)) }
    }

    val editing = editingId
    if (editing != null) {
        BackHandler { editingId = null }
        AlarmEditorScreen(
            initial = if (editing == NEW_ALARM) null else alarms?.firstOrNull { it.id == editing },
            onCancel = { editingId = null },
            onSave = { alarm ->
                editingId = null
                vm.save(alarm) { trigger ->
                    showRingsIn(trigger)
                    askEssentialsAfterSave()
                }
            },
            onDelete = { alarm ->
                editingId = null
                vm.delete(alarm)
            },
        )
    } else {
        AlarmListScreen(
            alarms = alarms,
            missing = missing,
            snackbar = snackbar,
            onAdd = { editingId = NEW_ALARM },
            onEdit = { editingId = it.id },
            onToggle = { alarm, on -> vm.setEnabled(alarm, on) { if (on) showRingsIn(it) } },
            onAllow = ::request,
            onTestWash = { context.startActivity(Intent(context, WashTestActivity::class.java)) },
        )
    }
}

private fun openSettings(context: Context, need: Need) {
    val intent = Permissions.settingsIntent(context, need) ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some phones hide this screen; nothing else we can do.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmListScreen(
    alarms: List<Alarm>?,
    missing: List<Need>,
    snackbar: SnackbarHostState,
    onAdd: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onAllow: (Need) -> Unit,
    onTestWash: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Refresh "rings in" texts every 30 seconds.
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            delay(30_000)
            value = ZonedDateTime.now()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SoAlarm", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Test wash check") },
                            onClick = {
                                menuOpen = false
                                onTestWash()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add alarm")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (missing.isNotEmpty()) {
                item(key = "permissions") { PermissionsCard(missing, onAllow) }
            }
            when {
                alarms == null -> Unit
                alarms.isEmpty() -> item(key = "empty") { EmptyState() }
                else -> items(alarms, key = { it.id }) { alarm ->
                    AlarmCard(alarm, now, onClick = { onEdit(alarm) }, onToggle = { onToggle(alarm, it) })
                }
            }
        }
    }
}

@Composable
private fun AlarmCard(alarm: Alarm, now: ZonedDateTime, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val dim = if (alarm.enabled) 1f else 0.5f
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    AlarmTime.formatTime(context, alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                val subtitle = listOf(alarm.label.trim(), AlarmTime.daysText(alarm.days))
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                )
                if (alarm.enabled) {
                    Text(
                        AlarmTime.ringsInText(AlarmTime.nextTrigger(alarm, now), now),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PermissionsCard(missing: List<Need>, onAllow: (Need) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "A few settings so your alarms always ring",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            missing.forEach { need ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(need.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(need.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(onClick = { onAllow(need) }) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⏰💧", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("No alarms yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tap + to add one. To stop it in the morning, you'll wash your hand with soap for 20 seconds. One hand is enough.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
    }
}
