package com.bettergi.pocket.pathing.input

import android.os.Handler
import android.os.Looper
import com.bettergi.pocket.overlay.OverlayWindowController
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Window operations stay on UI. Navigation input stays on its worker, so the Shizuku
 * client can acknowledge a DOWN before PathExecutor starts its original movement timer. */
class ShizukuPassthroughClient(
    private val overlay: OverlayWindowController,
    private val delegate: TouchClient,
) : TouchClient {
    private val main = Handler(Looper.getMainLooper())
    private val armed = HashSet<String>()
    private var dragX = 0
    private var dragY = 0

    override fun isReady() = delegate.isReady()
    private fun <T> ui(f: () -> T): T {
        if (Looper.myLooper() == main.looper) return f()
        val task = FutureTask<T> { f() }
        check(main.post(task)) { "Overlay is unavailable" }
        try { return task.get(3, TimeUnit.SECONDS) }
        catch (e: InterruptedException) { task.cancel(false); Thread.currentThread().interrupt(); throw e }
        catch (e: Exception) { task.cancel(false); throw e }
    }
    private fun arm(key: String, x1: Int, y1: Int, x2: Int, y2: Int): Boolean = ui {
        if (key in armed) false else overlay.prepareAreaPassthrough(x1, y1, x2, y2).also {
            if (it) armed.add(key)
        }
    }
    private fun disarm(key: String) = ui {
        if (armed.remove(key)) overlay.restoreClickPassthrough()
    }
    private fun hold(key: String, x: Int, y: Int, action: () -> Boolean): Boolean {
        val changed = arm(key, x, y, x, y)
        if (changed && Looper.myLooper() != main.looper) Thread.sleep(64L)
        val ok = action()
        if (!ok && changed) disarm(key)
        return ok
    }
    private fun instant(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long, action: () -> Boolean): Boolean {
        val changed = ui { overlay.prepareAreaPassthrough(x1, y1, x2, y2) }
        val onUi = Looper.myLooper() == main.looper
        // A WindowManager flag update needs time to reach the input dispatcher.
        if (changed && onUi) {
            // Avoid scheduling a click which could outlive a backend switch or route start.
            overlay.restoreClickPassthrough()
            return false
        }
        try {
            if (changed) Thread.sleep(64L)
            return action()
        } finally {
            if (changed) {
                if (onUi) main.postDelayed({ overlay.restoreClickPassthrough() }, duration + 40L)
                else main.post { overlay.restoreClickPassthrough() }
            }
        }
    }
    override fun padHold(x: Int, y: Int) = hold("pad", x, y) { delegate.padHold(x, y) }
    override fun padRelease(): Boolean = delegate.padRelease().also { disarm("pad") }
    override fun buttonDown(id: String, x: Int, y: Int) = hold("button:$id", x, y) { delegate.buttonDown(id, x, y) }
    override fun buttonUp(id: String) = delegate.buttonUp(id).also { disarm("button:$id") }
    override fun tap(x: Int, y: Int, durationMs: Long) = instant(x, y, x, y, durationMs) { delegate.tap(x, y, durationMs) }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        instant(x1, y1, x2, y2, durationMs) { delegate.swipe(x1, y1, x2, y2, durationMs) }
    override fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int, durationMs: Long): Boolean {
        val radius = (maxOf(startSpan, endSpan) + 1) / 2
        return instant(centerX-radius, centerY, centerX+radius, centerY, durationMs) {
            delegate.pinch(centerX, centerY, startSpan, endSpan, durationMs)
        }
    }
    override fun dragBegin(x: Int, y: Int): Boolean {
        dragX = x; dragY = y
        return hold("drag", x, y) { delegate.dragBegin(x, y) }
    }
    override fun dragStep(x: Int, y: Int, durationMs: Long): Boolean {
        val changed = arm("drag", dragX, dragY, x, y)
        if (changed && Looper.myLooper() != main.looper) Thread.sleep(64L)
        dragX = x; dragY = y
        return delegate.dragStep(x, y, durationMs)
    }
    override fun dragEnd() = delegate.dragEnd().also { disarm("drag") }
    private fun restoreAll() {
        main.post {
            repeat(armed.size) { overlay.restoreClickPassthrough() }
            armed.clear()
        }
    }
    override fun releaseAll() = delegate.releaseAll().also { restoreAll() }
    override fun releaseAllAndWait(timeoutMs: Long) = delegate.releaseAllAndWait(timeoutMs).also { restoreAll() }
}
