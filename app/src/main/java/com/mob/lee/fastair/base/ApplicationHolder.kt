package com.mob.lee.fastair.base

import android.content.Context

/**
 * The application context, kept here so infrastructure that is not a component
 * (the shared [Connection] runtime) can build an Android-bound dependency
 * without every caller having to thread a context through.
 */
object ApplicationHolder {
    lateinit var context: Context
}
