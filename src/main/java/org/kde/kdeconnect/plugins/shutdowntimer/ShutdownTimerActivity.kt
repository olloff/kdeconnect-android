/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shutdowntimer

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.KdeConnect

class ShutdownTimerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra("deviceId")
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, ShutdownTimerPlugin::class.java)
        val device = KdeConnect.getInstance().getDevice(deviceId)
        if (plugin == null || device == null) {
            finish()
            return
        }

        // The remote state might be stale (e.g. the activity was opened
        // right after reconnecting), so ask for a fresh status.
        plugin.requestStatus()

        setContent {
            ShutdownTimerScreen(plugin, device, onBackPressedDispatcher)
        }
    }
}
