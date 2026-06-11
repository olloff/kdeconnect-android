/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shutdowntimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.kde.kdeconnect.KdeConnect

/**
 * Handles the "Cancel timer" action of the warning notification posted by
 * [ShutdownTimerPlugin].
 */
class ShutdownTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ShutdownTimerPlugin.ACTION_CANCEL_TIMER) {
            return
        }
        val deviceId = intent.getStringExtra(ShutdownTimerPlugin.EXTRA_DEVICE_ID) ?: return
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, ShutdownTimerPlugin::class.java) ?: return
        plugin.cancelShutdown()
        plugin.dismissWarningNotification()
    }
}
