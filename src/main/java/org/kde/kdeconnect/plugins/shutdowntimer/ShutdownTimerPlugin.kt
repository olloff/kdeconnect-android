/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shutdowntimer

import android.content.Intent
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
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
 */
@LoadablePlugin
class ShutdownTimerPlugin : Plugin() {

    fun interface StateListener {
        fun onStateChanged(state: ShutdownTimerState)
    }

    var state: ShutdownTimerState = ShutdownTimerState.INACTIVE
        private set

    private val stateListeners = CopyOnWriteArrayList<StateListener>()

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_shutdowntimer)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_shutdowntimer_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE_SHUTDOWNTIMER) {
            return false
        }
        state = ShutdownTimerState.fromPacket(np)
        stateListeners.forEach { it.onStateChanged(state) }
        return true
    }

    /**
     * Schedules [action] (one of the ShutdownTimerState.ACTION_* constants)
     * on the remote device in [seconds] seconds, replacing any pending timer.
     */
    fun scheduleShutdown(action: String, seconds: Long) {
        val np = NetworkPacket(PACKET_TYPE_SHUTDOWNTIMER_REQUEST)
        np["setAction"] = action
        np["setSeconds"] = seconds
        device.sendPacket(np)
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
    }
}
