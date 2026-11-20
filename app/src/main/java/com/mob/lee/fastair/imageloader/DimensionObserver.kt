package com.mob.lee.fastair.imageloader

import android.util.Size
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Observes a view's first pre-draw to learn its laid-out size, then hands it off
 * for image decoding. Returns true so the frame is not dropped.
 */
class DimensionObserver(val scope: CoroutineScope, view: View) : ViewTreeObserver.OnPreDrawListener {

    val mRef: WeakReference<View>
    val channel = Channel<Size>(1)

    init {
        mRef = WeakReference<View>(view)
        val observer = view.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(this)
        } else {
            // No live observer (view detached): resolve immediately with the current size,
            // which may be 0 and is handled by the caller.
            scope.launch { channel.send(Size(view.width, view.height)) }
        }
    }

    override fun onPreDraw(): Boolean {
        val view = mRef.get()
        view?.let { v ->
            scope.launch { channel.send(Size(v.width, v.height)) }
            v.viewTreeObserver.removeOnPreDrawListener(this)
            mRef.clear()
        }
        // Keep this frame so layout and drawing proceed normally.
        return true
    }

    suspend fun receive(): Size {
        val value = withTimeoutOrNull(2000) { channel.receive() } ?: Size(0, 0)
        channel.close()
        return value
    }
}
