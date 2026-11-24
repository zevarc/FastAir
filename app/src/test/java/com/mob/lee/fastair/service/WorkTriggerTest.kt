package com.mob.lee.fastair.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The failure this guards against is silent: queue a file after the first
 * transfer finished and nothing happens — no error, no progress, an empty
 * screen — because a session is published once and its listener already ran.
 */
class WorkTriggerTest {

    @Test
    fun `a new session runs setup only`() {
        val decision = WorkTrigger.decide(hasSession = true, isNewSession = true)

        assertEquals(true, decision.onSessionReady)
        // Setup already drains once; requesting work too would drain twice.
        assertEquals(false, decision.onWorkRequested)
    }

    @Test
    fun `an event on an existing session requests work`() {
        val decision = WorkTrigger.decide(hasSession = true, isNewSession = false)

        // This is the second file: setup must not run again, but the queue must
        // be re-checked or the record is never sent.
        assertEquals(false, decision.onSessionReady)
        assertEquals(true, decision.onWorkRequested)
    }

    @Test
    fun `without a session nothing happens`() {
        val decision = WorkTrigger.decide(hasSession = false, isNewSession = false)

        assertEquals(false, decision.onSessionReady)
        assertEquals(false, decision.onWorkRequested)
    }

    @Test
    fun `a session appearing is never treated as a work request alone`() {
        // Guarding the exact regression: if the first emission only requested
        // work, the receive loop would never start.
        val decision = WorkTrigger.decide(hasSession = true, isNewSession = true)

        assertEquals(true, decision.onSessionReady)
    }
}
