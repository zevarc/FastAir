package com.mob.lee.fastair.io

/**
 * Lifecycle of a [SocketService] connection. Reported through
 * [SocketService.SocketStateListener.onStateChanged] so callers can tell a
 * transient drop, which recovers on its own, from a final shutdown.
 */
enum class ConnectionState {
    /** Nothing has started yet. */
    IDLE,
    /** Reaching out to the peer, or waiting for one to arrive. */
    CONNECTING,
    /** A peer is attached; data may flow. */
    CONNECTED,
    /** The peer went away and another attempt is pending or in flight. */
    RECONNECTING,
    /** Shut down for good by [SocketService.close]. Terminal. */
    CLOSED
}
