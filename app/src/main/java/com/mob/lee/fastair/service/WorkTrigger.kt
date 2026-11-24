package com.mob.lee.fastair.service

/**
 * Decides when a feature should be told to do work.
 *
 * This exists as a separate, pure object because the rule is not obvious and
 * getting it wrong is silent: a file queued after the first transfer finished
 * sits in the database forever with nothing to show the user, because a session
 * is published exactly once and its listener already ran.
 */
object WorkTrigger {

    /**
     * What a lifecycle event should do.
     *
     * @param onSessionReady run the feature's per-session setup (start the
     *   receive loop, drain the queue once).
     * @param onWorkRequested re-check for queued work without redoing setup.
     */
    data class Decision(
        val onSessionReady: Boolean,
        val onWorkRequested: Boolean
    )

    /**
     * A new session was published.
     *
     * Only setup: the initial drain is part of it, and requesting work as well
     * would drain twice.
     */
    val SESSION_READY = Decision(onSessionReady = true, onWorkRequested = false)

    /**
     * The component was started, bound, or otherwise poked while a session is
     * already live. This is the "send another file" path.
     */
    val WORK_REQUESTED = Decision(onSessionReady = false, onWorkRequested = true)

    /** No live session: nothing to do, and nothing to remember for later. */
    val NOTHING = Decision(onSessionReady = false, onWorkRequested = false)

    /**
     * A lifecycle event arrived.
     *
     * @param hasSession whether a session is currently established
     * @param isNewSession whether this event is the session's first appearance
     */
    fun decide(hasSession: Boolean, isNewSession: Boolean): Decision = when {
        !hasSession -> NOTHING
        isNewSession -> SESSION_READY
        else -> WORK_REQUESTED
    }
}
