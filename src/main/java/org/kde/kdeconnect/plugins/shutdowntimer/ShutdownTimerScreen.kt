/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shutdowntimer

import android.text.format.DateUtils
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

@Composable
fun ShutdownTimerScreen(
    plugin: ShutdownTimerPlugin,
    device: Device,
    onBackPressedDispatcher: OnBackPressedDispatcher,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(plugin.state) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedAction by remember { mutableStateOf(ShutdownTimerState.ACTION_SHUTDOWN) }
    var minutesText by remember { mutableStateOf("30") }

    DisposableEffect(plugin) {
        val listener = ShutdownTimerPlugin.StateListener { state = it }
        plugin.addStateListener(listener)
        onDispose {
            plugin.removeStateListener(listener)
        }
    }

    // Tick once a second while a timer is pending so the countdown stays current
    LaunchedEffect(state.isActive) {
        while (state.isActive) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val actionLabels = mapOf(
        ShutdownTimerState.ACTION_SHUTDOWN to stringResource(R.string.shutdown_timer_action_shutdown),
        ShutdownTimerState.ACTION_REBOOT to stringResource(R.string.shutdown_timer_action_reboot),
        ShutdownTimerState.ACTION_SUSPEND to stringResource(R.string.shutdown_timer_action_suspend),
    )

    KdeTheme(context) {
        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            topBar = {
                KdeTopAppBar(
                    title = stringResource(R.string.pref_plugin_shutdowntimer),
                    subTitle = device.name,
                    navIconOnClick = { onBackPressedDispatcher.onBackPressed() },
                    navIconDescription = stringResource(androidx.appcompat.R.string.abc_action_bar_up_description),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Current status of the remote timer
                if (state.isActive) {
                    val remainingSeconds = state.remainingMillis(now) / 1_000
                    val countdown = DateUtils.formatElapsedTime(remainingSeconds)
                    Text(
                        text = when (state.action) {
                            ShutdownTimerState.ACTION_REBOOT -> stringResource(R.string.shutdown_timer_pending_reboot, countdown)
                            ShutdownTimerState.ACTION_SUSPEND -> stringResource(R.string.shutdown_timer_pending_suspend, countdown)
                            else -> stringResource(R.string.shutdown_timer_pending_shutdown, countdown)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedButton(onClick = plugin::cancelShutdown) {
                        Text(stringResource(R.string.shutdown_timer_cancel))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.shutdown_timer_no_timer),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                HorizontalDivider()

                // Schedule a new timer (replaces a pending one)
                actionLabels.forEach { (action, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selectedAction == action, onClick = { selectedAction = action }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedAction == action, onClick = { selectedAction = action })
                        Text(label)
                    }
                }

                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.shutdown_timer_delay_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )

                val minutes = minutesText.toLongOrNull()
                Button(
                    enabled = minutes != null,
                    onClick = {
                        minutes?.let { plugin.scheduleShutdown(selectedAction, it * 60) }
                    },
                ) {
                    Text(stringResource(R.string.shutdown_timer_schedule))
                }
            }
        }
    }
}
