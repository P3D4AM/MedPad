package com.medpad.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * mission 12 / item 12 — the "Allow all the time" flow classification.
 * Pure logic: the honest UI state and the available grant path, given the
 * raw permission facts and the OS version.
 */
class LocationPermTest {

    @Test
    fun `state classification`() {
        assertEquals(LocationPerm.State.NONE, LocationPerm.state(false, false))
        // background cannot exist without foreground on a real OS, but the
        // classifier treats it as the strongest fact it was given
        assertEquals(LocationPerm.State.ALL_THE_TIME, LocationPerm.state(false, true))
        assertEquals(LocationPerm.State.WHILE_IN_USE, LocationPerm.state(true, false))
        assertEquals(LocationPerm.State.ALL_THE_TIME, LocationPerm.state(true, true))
    }

    @Test
    fun `flow only exists from android 10`() {
        assertEquals(false, LocationPerm.flowAvailable(28))
        assertEquals(true, LocationPerm.flowAvailable(29))
        assertEquals(true, LocationPerm.flowAvailable(34))
    }

    @Test
    fun `direct dialog only on api 29 and 30`() {
        // Android 10/11: asking ALONE after the foreground grants can show
        // the system dialog with "Allow all the time"
        assertEquals(LocationPerm.DirectDialog.DIALOG, LocationPerm.directDialog(29))
        assertEquals(LocationPerm.DirectDialog.DIALOG, LocationPerm.directDialog(30))
        // Android 12+: the system auto-denies — the app settings page is the
        // only honest path
        assertEquals(LocationPerm.DirectDialog.SETTINGS_ONLY, LocationPerm.directDialog(31))
        assertEquals(LocationPerm.DirectDialog.SETTINGS_ONLY, LocationPerm.directDialog(34))
    }
}
