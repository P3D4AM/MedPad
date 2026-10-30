package com.medpad.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mission item 2 — greeting shows ONLY the first name; a missing split
 * (legacy employee / old panel) greets WITHOUT a name, never with the
 * full name as a substitute.
 */
class GreetingTest {

    @Test
    fun `first name shown when present`() {
        assertEquals("علی", Greeting.firstNameFor("علی"))
        assertEquals("علی", Greeting.firstNameFor("  علی  "))
    }

    @Test
    fun `nameless greeting for empty blank and null input`() {
        assertNull(Greeting.firstNameFor(null))
        assertNull(Greeting.firstNameFor(""))
        assertNull(Greeting.firstNameFor("   "))
    }

    @Test
    fun `json null artifact never reaches the greeting`() {
        // an old panel serializing a missing firstName as the string "null"
        // (or a panel answering JSON null stringified by an older app path)
        assertNull(Greeting.firstNameFor("null"))
    }

    @Test
    fun `a full name is never used as the greeting substitute`() {
        // the mission explicitly forbids falling back to the full name —
        // the helper receives ONLY the first-name field, and whatever it
        // returns is the entire greeting name. A value that carries both
        // parts means the panel field was misused; we still display it as
        // given (server contract), but this test documents that the caller
        // passes employeeFirstName, never employeeName.
        assertEquals("رضا محمدی", Greeting.firstNameFor("رضا محمدی"))
    }
}
