/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shutdowntimer

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Schedule, view and cancel delayed power actions (shutdown, reboot,
 * suspend) on the paired device.
 *
 * Android itself cannot power off without root, so this plugin only acts as
 * the controlling side: it receives kdeconnect.shutdowntimer status packets
 * and sends kdeconnect.shutdowntimer.request control packets. See the
 * desktop plugin's README (plugins/shutdowntimer in kdeconnect-kde) for the
 * full protocol description.
 *
 * While a timer is pending it also posts a local warning notification a
 * configurable time before the deadline, so the user can still cancel the
 * action from the phone.
 */
@LoadablePlugin
class ShutdownTimerPlugin : Plugin() {

    fun interface StateListener {
        fun onStateChanged(state: ShutdownTimerState)
    }

    var state: ShutdownTimerState = ShutdownTimerState.INACTIVE
        private set

    private val stateListeners = CopyOnWriteArrayList<StateListener>()

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val warningRunnable = Runnable { showWarningNotification() }

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_shutdowntimer)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_shutdowntimer_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE_SHUTDOWNTIMER) {
            return false
        }
        state = ShutdownTimerState.fromPacket(np)
        recordTotalDuration()
        rescheduleWarning()
        stateListeners.forEach { it.onStateChanged(state) }
        return true
    }

    override fun onDestroy() {
        // Keep an already-visible warning: the timer keeps running on the
        // remote device even while it is unreachable.
        handler.removeCallbacks(warningRunnable)
    }

    /**
     * Schedules [action] (one of the ShutdownTimerState.ACTION_* constants)
     * on the remote device in [seconds] seconds, replacing any pending timer.
     */
    fun scheduleShutdown(action: String, seconds: Long) {
        requestedSeconds = seconds
        requestedAtMillis = System.currentTimeMillis()
        val np = NetworkPacket(PACKET_TYPE_SHUTDOWNTIMER_REQUEST)
        np["setAction"] = action
        np["setSeconds"] = seconds
        device.sendPacket(np)
    }

    /**
     * The action the user last picked in the UI, persisted per device.
     */
    var lastSelectedAction: String
        get() = preferences?.getString(PREF_LAST_ACTION, null) ?: ShutdownTimerState.ACTION_SHUTDOWN
        set(value) {
            preferences?.edit { putString(PREF_LAST_ACTION, value) }
        }

    /**
     * Wall-clock duration of the currently pending timer in milliseconds, or
     * 0 when unknown. The protocol only carries the deadline, so this is the
     * requested duration when this device scheduled the timer, and otherwise
     * the time measured from when the first status packet for the current
     * deadline arrived. Persisted so countdown progress survives leaving the
     * screen and even an app restart.
     */
    val totalDurationMillis: Long
        get() {
            val prefs = preferences ?: return 0
            if (!state.isActive || prefs.getLong(PREF_TOTAL_DEADLINE, 0) != state.deadline) {
                return 0
            }
            return prefs.getLong(PREF_TOTAL_MILLIS, 0)
        }

    private var requestedSeconds = 0L
    private var requestedAtMillis = 0L

    private fun recordTotalDuration() {
        val prefs = preferences ?: return
        if (!state.isActive || prefs.getLong(PREF_TOTAL_DEADLINE, 0) == state.deadline) {
            return
        }
        val now = System.currentTimeMillis()
        val total = if (requestedSeconds > 0 && now - requestedAtMillis < REQUEST_MATCH_WINDOW_MILLIS) {
            requestedSeconds * 1_000
        } else {
            state.deadline - now
        }
        prefs.edit {
            putLong(PREF_TOTAL_DEADLINE, state.deadline)
            putLong(PREF_TOTAL_MILLIS, total.coerceAtLeast(1))
        }
    }

    /**
     * Cancels the pending timer on the remote device, if any.
     */
    fun cancelShutdown() {
        val np = NetworkPacket(PACKET_TYPE_SHUTDOWNTIMER_REQUEST)
        np["cancel"] = true
        device.sendPacket(np)
    }

    /**
     * Asks the remote device to broadcast its current timer status.
     */
    fun requestStatus() {
        val np = NetworkPacket(PACKET_TYPE_SHUTDOWNTIMER_REQUEST)
        np["requestStatus"] = true
        device.sendPacket(np)
    }

    fun addStateListener(listener: StateListener) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: StateListener) {
        stateListeners.remove(listener)
    }

    private val warningMinutes: Long
        get() = preferences?.getString(PREF_WARNING_MINUTES, null)?.toLongOrNull() ?: DEFAULT_WARNING_MINUTES

    private fun rescheduleWarning() {
        handler.removeCallbacks(warningRunnable)
        if (!state.isActive) {
            dismissWarningNotification()
            return
        }
        val warningMillis = warningMinutes * 60_000
        if (warningMillis <= 0) {
            return
        }
        val delayMillis = state.deadline - warningMillis - System.currentTimeMillis()
        if (delayMillis > 0) {
            handler.postDelayed(warningRunnable, delayMillis)
        }
        // A timer shorter than the warning lead gets no extra notification:
        // the user just scheduled it knowingly.
    }

    private fun showWarningNotification() {
        val state = state
        if (!state.isActive) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val remainingMillis = state.remainingMillis()
        val countdown = DateUtils.formatElapsedTime(remainingMillis / 1_000)
        val title = when (state.action) {
            ShutdownTimerState.ACTION_REBOOT -> context.getString(R.string.shutdown_timer_warning_reboot, countdown)
            ShutdownTimerState.ACTION_SUSPEND -> context.getString(R.string.shutdown_timer_warning_suspend, countdown)
            else -> context.getString(R.string.shutdown_timer_warning_shutdown, countdown)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(context, ShutdownTimerActivity::class.java)
            .putExtra("deviceId", device.deviceId)
        val cancelIntent = Intent(context, ShutdownTimerReceiver::class.java)
            .setAction(ACTION_CANCEL_TIMER)
            .putExtra(EXTRA_DEVICE_ID, device.deviceId)

        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setContentTitle(title)
            .setContentText(device.name)
            .setSmallIcon(R.drawable.ic_shutdown_timer_24dp)
            .setContentIntent(PendingIntent.getActivity(context, notificationId, openIntent, flags))
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.shutdown_timer_notification_cancel),
                PendingIntent.getBroadcast(context, notificationId, cancelIntent, flags),
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            // The warning is pointless once the action has fired
            .setTimeoutAfter(remainingMillis)
            .build()
        context.getSystemService<NotificationManager>()?.notify(notificationId, notification)
    }

    fun dismissWarningNotification() {
        context.getSystemService<NotificationManager>()?.cancel(notificationId)
    }

    private val notificationId: Int
        get() = (device.deviceId + pluginKey).hashCode()

    override fun hasSettings(): Boolean = true

    override fun supportsDeviceSpecificSettings(): Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment =
        PluginSettingsFragment.newInstance(pluginKey, R.xml.shutdowntimerplugin_preferences)

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.pref_plugin_shutdowntimer),
            R.drawable.ic_shutdown_timer_24dp,
        ) { parentActivity ->
            val intent = Intent(parentActivity, ShutdownTimerActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        }
    )

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_SHUTDOWNTIMER)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_SHUTDOWNTIMER_REQUEST)

    companion object {
        private const val PACKET_TYPE_SHUTDOWNTIMER = "kdeconnect.shutdowntimer"
        private const val PACKET_TYPE_SHUTDOWNTIMER_REQUEST = "kdeconnect.shutdowntimer.request"

        const val ACTION_CANCEL_TIMER = "org.kde.kdeconnect.plugins.shutdowntimer.CANCEL_TIMER"
        const val EXTRA_DEVICE_ID = "deviceId"

        const val PREF_WARNING_MINUTES = "shutdown_timer_warning_minutes"
        const val DEFAULT_WARNING_MINUTES = 5L

        private const val PREF_LAST_ACTION = "shutdown_timer_last_action"
        private const val PREF_TOTAL_DEADLINE = "shutdown_timer_total_deadline"
        private const val PREF_TOTAL_MILLIS = "shutdown_timer_total_millis"

        // A status packet this soon after our own schedule request is assumed
        // to be its acknowledgement, so the requested duration is exact.
        private const val REQUEST_MATCH_WINDOW_MILLIS = 30_000L
    }
}
