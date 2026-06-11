/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shutdowntimer

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.OnBackPressedDispatcher
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R
import java.util.Date

private const val HOLD_TO_CONFIRM_MILLIS = 1200

private data class TimerAction(
    val id: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
)

private val ACTIONS = listOf(
    TimerAction(ShutdownTimerState.ACTION_SHUTDOWN, R.string.shutdown_timer_action_shutdown, R.drawable.ic_shutdown_timer_24dp),
    TimerAction(ShutdownTimerState.ACTION_REBOOT, R.string.shutdown_timer_action_reboot, R.drawable.ic_restart_24dp),
    TimerAction(ShutdownTimerState.ACTION_SUSPEND, R.string.shutdown_timer_action_suspend, R.drawable.ic_suspend_24dp),
)

private fun actionFor(id: String?): TimerAction =
    ACTIONS.firstOrNull { it.id == id } ?: ACTIONS.first()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShutdownTimerScreen(
    plugin: ShutdownTimerPlugin,
    device: Device,
    onBackPressedDispatcher: OnBackPressedDispatcher,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(plugin.state) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isReachable by remember { mutableStateOf(device.isReachable) }
    var selectedAction by remember { mutableStateOf(plugin.lastSelectedAction) }
    var actionPickerExpanded by rememberSaveable { mutableStateOf(false) }
    var minutesText by rememberSaveable { mutableStateOf("30") }
    var customInputVisible by rememberSaveable { mutableStateOf(false) }
    var presets by remember { mutableStateOf(plugin.presetMinutes) }

    DisposableEffect(plugin) {
        val listener = ShutdownTimerPlugin.StateListener { state = it }
        plugin.addStateListener(listener)
        onDispose {
            plugin.removeStateListener(listener)
        }
    }

    // Reachability changes reload the device's plugins, so this fires on
    // connect and disconnect.
    DisposableEffect(device) {
        val listener = Device.PluginsChangedListener { isReachable = it.isReachable }
        device.addPluginsChangedListener(listener)
        onDispose {
            device.removePluginsChangedListener(listener)
        }
    }

    // The last known state is stale after a reconnect
    LaunchedEffect(isReachable) {
        if (isReachable) {
            plugin.requestStatus()
        }
    }

    // Tick once a second while a timer is pending so the countdown stays current
    LaunchedEffect(state.isActive) {
        while (state.isActive) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

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
                    .padding(16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!isReachable) {
                    NotReachableCard(deviceName = device.name)
                }

                if (state.isActive) {
                    HoldToConfirmButton(
                        text = stringResource(R.string.shutdown_timer_hold_to_cancel),
                        icon = Icons.Default.Close,
                        enabled = isReachable,
                        onConfirm = plugin::cancelShutdown,
                        contentColor = MaterialTheme.colorScheme.error,
                        fillColor = MaterialTheme.colorScheme.errorContainer,
                    )
                }

                // The flexible middle pins the schedule block below to the
                // bottom of the screen
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val remainingMillis = state.remainingMillis(now)
                    // The plugin tracks the timer's full duration across
                    // screen visits and app restarts
                    val totalMillis = plugin.totalDurationMillis
                    val progress = if (state.isActive && totalMillis > 0) {
                        (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val deadlineLabel = if (state.isActive) {
                        val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
                        stringResource(
                            R.string.shutdown_timer_action_at_time,
                            stringResource(actionFor(state.action).label),
                            timeFormat.format(Date(state.deadline)),
                        )
                    } else {
                        null
                    }
                    CountdownRing(
                        isActive = state.isActive,
                        remainingMillis = remainingMillis,
                        progress = progress,
                        label = deadlineLabel,
                    )
                }

                ActionPicker(
                    selectedAction = selectedAction,
                    expanded = actionPickerExpanded,
                    enabled = isReachable,
                    onExpand = { actionPickerExpanded = true },
                    onSelect = {
                        selectedAction = it
                        plugin.lastSelectedAction = it
                        actionPickerExpanded = false
                    },
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    maxLines = 2,
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = minutesText == preset.toString(),
                            onClick = {
                                minutesText = preset.toString()
                                customInputVisible = false
                            },
                            enabled = isReachable,
                            label = {
                                Text(
                                    if (preset < 60 || preset % 60 != 0L) {
                                        stringResource(R.string.shutdown_timer_preset_minutes, preset)
                                    } else {
                                        stringResource(R.string.shutdown_timer_preset_hours, preset / 60)
                                    }
                                )
                            },
                        )
                    }
                    FilterChip(
                        selected = customInputVisible,
                        onClick = { customInputVisible = !customInputVisible },
                        enabled = isReachable,
                        label = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.shutdown_timer_add_custom),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }

                if (customInputVisible) {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.shutdown_timer_delay_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = isReachable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val minutes = minutesText.toLongOrNull()
                HoldToConfirmButton(
                    text = stringResource(R.string.shutdown_timer_hold_to_start),
                    icon = Icons.Default.PlayArrow,
                    enabled = isReachable && minutes != null && minutes > 0,
                    onConfirm = {
                        minutes?.let {
                            plugin.addPresetMinutes(it)
                            presets = plugin.presetMinutes
                            customInputVisible = false
                            plugin.scheduleShutdown(selectedAction, it * 60)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NotReachableCard(
    deviceName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.shutdown_timer_not_reachable, deviceName),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HoldToConfirmButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val fillFraction = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            val remainingFraction = 1f - fillFraction.value
            fillFraction.animateTo(
                targetValue = 1f,
                animationSpec = tween((HOLD_TO_CONFIRM_MILLIS * remainingFraction).toInt(), easing = LinearEasing),
            )
            currentOnConfirm()
            fillFraction.snapTo(0f)
        } else if (fillFraction.value > 0f) {
            fillFraction.animateTo(0f, tween(150))
        }
    }

    val effectiveContent = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .border(1.dp, effectiveContent, CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(fillFraction.value)
                .background(fillColor),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = effectiveContent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = effectiveContent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun CountdownRing(
    isActive: Boolean,
    remainingMillis: Long,
    progress: Float,
    label: String?,
    modifier: Modifier = Modifier,
) {
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (isActive && progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isActive) {
                Text(
                    text = DateUtils.formatElapsedTime(remainingMillis / 1_000),
                    style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                )
                label?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.shutdown_timer_no_timer),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionPicker(
    selectedAction: String,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fixed height so the layout does not jump between the two modes
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (expanded) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxSize()) {
                ACTIONS.forEachIndexed { index, action ->
                    SegmentedButton(
                        selected = action.id == selectedAction,
                        onClick = { onSelect(action.id) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ACTIONS.size),
                        enabled = enabled,
                        icon = {
                            Icon(
                                painter = painterResource(action.icon),
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        },
                    ) {
                        Text(stringResource(action.label), maxLines = 1)
                    }
                }
            }
        } else {
            val action = actionFor(selectedAction)
            OutlinedCard(
                onClick = onExpand,
                enabled = enabled,
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painter = painterResource(action.icon), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(action.label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.shutdown_timer_change_action),
                    )
                }
            }
        }
    }
}
