package com.bettergi.pocket.pathing.input

import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.ceil

/** One serialized, bounded stream. No periodic lift/re-press. Logical fingers retain their
 * pointer IDs and downTime until the caller actually releases them. All injection is off UI. */
object ShizukuTouchClient : TouchClient {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "BetterGI-Shizuku") })
    private val submitLock = Any()
    private data class Transport(val manager: Any, val method: Method)
    @Volatile private var transport: Transport? = null
    @Volatile private var fault = false
    @Volatile private var active = false
    private data class Finger(val id: Int, var x: Float, var y: Float)
    private val fingers = LinkedHashMap<String, Finger>()
    private var downTime = 0L
    private const val PAD = "pad"
    private const val DRAG = "drag"
    private const val TAP = "instant"
    private const val PINCH = "pinch2"
    private const val WAIT_FOR_RESULT = 1
    private const val TAG = "BetterGI.Shizuku"

    fun authorized(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun isReady(): Boolean = authorized() && transport != null && !fault
    fun safeForFallback(): Boolean = !active && worker.activeCount == 0 && worker.queue.isEmpty()
    fun disconnected() { transport = null; fault = true }

    /** Reconnect must not forget fingers left in an uncertain state. */
    fun reconnect() {
        synchronized(submitLock) {
            if (!authorized()) return
            worker.queue.clear()
            try {
                worker.execute {
                    try {
                        val binder = SystemServiceHelper.getSystemService("input") ?: error("input service unavailable")
                        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                        val manager = stub.getMethod("asInterface", android.os.IBinder::class.java)
                            .invoke(null, ShizukuBinderWrapper(binder)) ?: error("input manager unavailable")
                        val method = Class.forName("android.hardware.input.IInputManager")
                            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                        transport = Transport(manager, method)
                        fault = !clearStream()
                    } catch (e: Throwable) {
                        transport = null
                        fault = true
                        Log.w(TAG, "Connection failed", e)
                    }
                }
            } catch (_: Exception) { fault = true }
        }
    }

    private fun enqueue(action: () -> Boolean): Boolean {
        val future = synchronized(submitLock) {
            if (!isReady()) return false
            try {
                worker.submit<Boolean> {
                    if (!isReady()) false else try {
                        action().also { if (!it) fail() }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Input failed", e)
                        fail()
                        false
                    }
                }
            } catch (_: Exception) { fault = true; return false }
        }
        // The real-time dialogue caller is on UI; the navigation worker waits for delivery
        // before starting its existing delays, especially the 60 ms approach pulse.
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        return try { future.get(3000L, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        catch (_: Exception) { fault = true; false }
    }

    private fun fail() {
        fault = true
        Log.w(TAG, "Input rejected; attempting confirmed cancellation")
        clearStream()
    }

    private fun send(action: Int): Boolean {
        val t = transport ?: return false
        if (!authorized() || fingers.isEmpty()) return false
        val values = fingers.values.toList()
        val props = Array(values.size) { i -> MotionEvent.PointerProperties().apply {
            id = values[i].id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(values.size) { i -> MotionEvent.PointerCoords().apply {
            x = values[i].x; y = values[i].y
            pressure = if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP ||
                (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_CANCEL) 0f else 1f
            size = 1f
        } }
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, values.size,
            props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try {
            t.method.invoke(t.manager, ev, WAIT_FOR_RESULT) == true
        } catch (e: Throwable) {
            Log.w(TAG, "Injection failed", e)
            false
        } finally { ev.recycle() }
    }

    private fun down(key: String, x: Float, y: Float): Boolean {
        if (x < 0 || y < 0) return false
        val old = fingers[key]
        if (old != null) {
            if (old.x == x && old.y == y) return true
            old.x = x; old.y = y
            return send(MotionEvent.ACTION_MOVE)
        }
        val id = (0..31).firstOrNull { n -> fingers.values.none { it.id == n } } ?: return false
        if (fingers.isEmpty()) downTime = SystemClock.uptimeMillis()
        fingers[key] = Finger(id, x, y)
        active = true
        val i = fingers.size - 1
        return send(if (i == 0) MotionEvent.ACTION_DOWN else
            MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun up(key: String): Boolean {
        if (key !in fingers) return true
        val i = fingers.keys.indexOf(key)
        val action = if (fingers.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        if (!send(action)) return false
        fingers.remove(key)
        active = fingers.isNotEmpty()
        if (!active) downTime = 0L
        return true
    }

    private fun clearStream(): Boolean {
        if (fingers.isEmpty()) { active = false; return true }
        if (!send(MotionEvent.ACTION_CANCEL)) { fault = true; return false }
        fingers.clear()
        active = false
        downTime = 0L
        return true
    }

    private fun interpolate(targets: Map<String, Pair<Float, Float>>, duration: Long): Boolean {
        val starts = targets.keys.associateWith { k ->
            val p = fingers[k] ?: return false
            p.x to p.y
        }
        val ms = duration.coerceAtLeast(1L)
        val steps = ceil(ms / 8.0).toInt().coerceIn(1, 1000)
        val start = SystemClock.uptimeMillis()
        for (i in 1..steps) {
            val wait = start + ms * i / steps - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val fraction = i.toFloat() / steps
            for ((k, end) in targets) {
                val p = fingers[k] ?: return false
                val from = starts.getValue(k)
                p.x = from.first + (end.first - from.first) * fraction
                p.y = from.second + (end.second - from.second) * fraction
            }
            if (!send(MotionEvent.ACTION_MOVE)) return false
        }
        return true
    }

    override fun padHold(x: Int, y: Int) = buttonDown(PAD, x, y)
    override fun padRelease() = buttonUp(PAD)
    override fun buttonDown(id: String, x: Int, y: Int) = enqueue { down(id, x.toFloat(), y.toFloat()) }
    override fun buttonUp(id: String) = enqueue { up(id) }
    override fun tap(x: Int, y: Int, durationMs: Long) = enqueue {
        if (!down(TAP, x.toFloat(), y.toFloat())) false else {
            SystemClock.sleep(durationMs.coerceAtLeast(1L))
            up(TAP)
        }
    }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = enqueue {
        down(TAP, x1.toFloat(), y1.toFloat()) &&
            interpolate(mapOf(TAP to (x2.toFloat() to y2.toFloat())), durationMs) && up(TAP)
    }
    override fun dragBegin(x: Int, y: Int) = buttonDown(DRAG, x, y)
    override fun dragStep(x: Int, y: Int, durationMs: Long) = enqueue {
        interpolate(mapOf(DRAG to (x.toFloat() to y.toFloat())), durationMs)
    }
    override fun dragEnd() = buttonUp(DRAG)
    override fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int, durationMs: Long) = enqueue {
        val y = centerY.toFloat()
        down(TAP, centerX - startSpan / 2f, y) &&
            down(PINCH, centerX + startSpan / 2f, y) &&
            interpolate(mapOf(TAP to (centerX - endSpan / 2f to y), PINCH to (centerX + endSpan / 2f to y)), durationMs) &&
            up(PINCH) && up(TAP)
    }
    private fun releaseStream(): Boolean {
        if (fault) return clearStream()
        for (key in fingers.keys.toList().asReversed()) {
            if (!up(key)) { fault = true; return clearStream() }
        }
        return true
    }

    override fun releaseAll(): Boolean = synchronized(submitLock) {
        worker.queue.clear()
        try { worker.execute { releaseStream() }; true } catch (_: Exception) { false }
    }
    override fun releaseAllAndWait(timeoutMs: Long): Boolean {
        val future = synchronized(submitLock) {
            worker.queue.clear()
            try { worker.submit<Boolean> { releaseStream() } } catch (_: Exception) { return false }
        }
        var interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) { fault = true; return false }
                try { return future.get(left, TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { interrupted = true }
                catch (_: TimeoutException) { fault = true; return false }
                catch (_: Exception) { fault = true; return false }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
