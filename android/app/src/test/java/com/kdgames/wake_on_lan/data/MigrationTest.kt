package com.kdgames.wake_on_lan.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.kdgames.wake_on_lan.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** S05: v1 → v2 (Device.sleepHibernates, K23) Kaan'ın cihazındaki veriyi korumalı. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    // Room 2.8 + Robolectric: sürücü tam yolla açtığı için ad olarak tam yol verilir.
    private val name: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("mig.db").absolutePath

    @Test
    fun v1den_v2ye_cihazlar_korunur_sleepHibernates_false() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO Device (id, name, ip, mac, hostname, iconKey, colorIndex, probePorts, sortOrder, agentId, agentKeyAlias, agentPort, hibernateAvailable, shutdownMode) " +
                    "VALUES (1, 'Ofis PC', '192.168.1.26', 'D8:BB:C1:0A:2F:91', NULL, 'pc', 0, '3389,445,135', 0, NULL, NULL, 40900, 1, 'FORCE')",
            )
        }
        helper.runMigrationsAndValidate(name, 2, true).use { db ->
            db.query("SELECT name, sleepHibernates FROM Device").use { c ->
                c.moveToFirst()
                assertEquals("Ofis PC", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
        }
    }

    @Test
    fun v2den_v3e_zamanlayicilar_korunur_notify_false() {
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                "INSERT INTO Device (id, name, ip, mac, hostname, iconKey, colorIndex, probePorts, sortOrder, agentId, agentKeyAlias, agentPort, hibernateAvailable, sleepHibernates, shutdownMode) " +
                    "VALUES (1, 'Ofis PC', '192.168.1.26', NULL, NULL, 'pc', 0, '3389,445,135', 0, NULL, NULL, 40900, 1, 0, 'FORCE')",
            )
            db.execSQL(
                "INSERT INTO Schedule (id, deviceId, action, type, daysMask, minuteOfDay, ssid, enabled, skippedOccurrence, skipNextEvent) " +
                    "VALUES (1, 1, 'WAKE', 'TIME', 31, 510, NULL, 1, NULL, 0)",
            )
        }
        helper.runMigrationsAndValidate(name, 3, true).use { db ->
            db.query("SELECT minuteOfDay, notify FROM Schedule").use { c ->
                c.moveToFirst()
                assertEquals(510, c.getInt(0))
                assertEquals(0, c.getInt(1))
            }
        }
    }
}
