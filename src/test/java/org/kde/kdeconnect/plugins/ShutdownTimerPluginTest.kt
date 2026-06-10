/*
 * SPDX-FileCopyrightText: 2026 olloff <olloff@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.shutdowntimer.ShutdownTimerPlugin
import org.kde.kdeconnect.plugins.shutdowntimer.ShutdownTimerState

class ShutdownTimerPluginTest {

    private fun executeWithMocks(test: (device: Device, plugin: ShutdownTimerPlugin) -> Unit) {
        val plugin = ShutdownTimerPlugin()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
        }
        val device = mockk<Device> {
            every { name } returns "Test Device"
            every { sendPacket(any()) } returns Unit
        }
        plugin.setContext(context, device)
        test(device, plugin)
    }

    private fun statusPacket(isActive: Boolean, action: String? = null, deadline: Long = 0) =
        NetworkPacket("kdeconnect.shutdowntimer").also { np ->
            np["isActive"] = isActive
            if (action != null) {
                np["action"] = action
                np["deadline"] = deadline
            }
        }

    @Test
    fun parsesActiveStatusPacket() {
        executeWithMocks { _, plugin ->
            val handled = plugin.onPacketReceived(statusPacket(true, ShutdownTimerState.ACTION_REBOOT, 1234567890L))

            assertTrue(handled)
            assertTrue(plugin.state.isActive)
            assertEquals(ShutdownTimerState.ACTION_REBOOT, plugin.state.action)
            assertEquals(1234567890L, plugin.state.deadline)
        }
    }

    @Test
    fun parsesInactiveStatusPacket() {
        executeWithMocks { _, plugin ->
            plugin.onPacketReceived(statusPacket(true, ShutdownTimerState.ACTION_SHUTDOWN, 1234567890L))
            plugin.onPacketReceived(statusPacket(false))

            assertEquals(ShutdownTimerState.INACTIVE, plugin.state)
        }
    }

    @Test
    fun rejectsWrongPacketType() {
        executeWithMocks { _, plugin ->
            val handled = plugin.onPacketReceived(NetworkPacket("kdeconnect.ping"))

            assertFalse(handled)
            assertFalse(plugin.state.isActive)
        }
    }

    @Test
    fun notifiesStateListeners() {
        executeWithMocks { _, plugin ->
            var observed: ShutdownTimerState? = null
            val listener = ShutdownTimerPlugin.StateListener { observed = it }

            plugin.addStateListener(listener)
            plugin.onPacketReceived(statusPacket(true, ShutdownTimerState.ACTION_SUSPEND, 42L))
            assertEquals(plugin.state, observed)

            plugin.removeStateListener(listener)
            plugin.onPacketReceived(statusPacket(false))
            assertTrue(observed!!.isActive) // not updated after removal
        }
    }

    @Test
    fun scheduleSendsRequestPacket() {
        executeWithMocks { device, plugin ->
            plugin.scheduleShutdown(ShutdownTimerState.ACTION_SHUTDOWN, 1800)

            verify(exactly = 1) {
                device.sendPacket(match { np ->
                    np.type == "kdeconnect.shutdowntimer.request" //
                        && np.getString("setAction") == "shutdown" //
                        && np.getLong("setSeconds", -1) == 1800L
                })
            }
        }
    }

    @Test
    fun cancelSendsRequestPacket() {
        executeWithMocks { device, plugin ->
            plugin.cancelShutdown()

            verify(exactly = 1) {
                device.sendPacket(match { np ->
                    np.type == "kdeconnect.shutdowntimer.request" && np.getBoolean("cancel", false)
                })
            }
        }
    }

    @Test
    fun requestStatusSendsRequestPacket() {
        executeWithMocks { device, plugin ->
            plugin.requestStatus()

            verify(exactly = 1) {
                device.sendPacket(match { np ->
                    np.type == "kdeconnect.shutdowntimer.request" && np.getBoolean("requestStatus", false)
                })
            }
        }
    }

    @Test
    fun remainingTimeIsDerivedFromDeadline() {
        val state = ShutdownTimerState(isActive = true, action = ShutdownTimerState.ACTION_SHUTDOWN, deadline = 100_000)

        assertEquals(40_000, state.remainingMillis(nowMillis = 60_000))
        assertEquals(0, state.remainingMillis(nowMillis = 100_001)) // never negative
        assertEquals(0, ShutdownTimerState.INACTIVE.remainingMillis(nowMillis = 0))
    }
}
