/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shutdowntimer

import org.kde.kdeconnect.NetworkPacket

/**
 * The last known state of the remote device's shutdown timer, as reported
 * by a kdeconnect.shutdowntimer status packet.
 *
 * @param isActive whether a timer is currently pending on the remote device
 * @param action one of [ACTION_SHUTDOWN], [ACTION_REBOOT], [ACTION_SUSPEND]; null while inactive
 * @param deadline when the action fires, in milliseconds since epoch (UTC); 0 while inactive
 */
data class ShutdownTimerState(
    val isActive: Boolean,
    val action: String? = null,
    val deadline: Long = 0,
) {
    /**
     * Milliseconds until the remote action fires, never negative.
     * The deadline is an absolute timestamp, so the countdown can be
     * recomputed locally without further status packets.
     */
    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        if (isActive) (deadline - nowMillis).coerceAtLeast(0) else 0

    companion object {
        const val ACTION_SHUTDOWN = "shutdown"
        const val ACTION_REBOOT = "reboot"
        const val ACTION_SUSPEND = "suspend"

        val INACTIVE = ShutdownTimerState(isActive = false)

        fun fromPacket(np: NetworkPacket): ShutdownTimerState {
            if (!np.getBoolean("isActive", false)) {
                return INACTIVE
            }
            return ShutdownTimerState(
                isActive = true,
                action = np.getStringOrNull("action"),
                deadline = np.getLong("deadline", 0),
            )
        }
    }
}
