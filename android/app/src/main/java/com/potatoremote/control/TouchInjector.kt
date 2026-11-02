package com.potatoremote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Rejoue les gestes d'un spectateur avec les API publiques d'Android.
 *
 * Un geste continu est maintenu par segments (`continueStroke`) : le doigt
 * reste posé entre deux messages, ce qui permet l'appui long, le glissement
 * et le défilement. Si Android refuse la continuation, le tracé complet est
 * rejoué en une fois à la fin du geste (repli).
 */
class TouchInjector(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var fallbackPath: Path? = null
    private var fallbackStart = 0L
    private var tickScheduled = false

    private val tick = Runnable { keepAlive() }

    fun down(x: Float, y: Float): Boolean {
        if (active) cancel()
        active = true
        lastX = x
        lastY = y
        fallbackStart = SystemClock.uptimeMillis()
        fallbackPath = Path().apply { moveTo(x, y) }

        val path = Path().apply { moveTo(x, y) }
        val next = try {
            GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
        } catch (error: Throwable) {
            Log.w(TAG, "Impossible de créer le geste", error)
            active = false
            return false
        }
        if (!dispatch(next)) {
            active = false
            return false
        }
        stroke = next
        scheduleTick()
        return true
    }

    fun move(x: Float, y: Float) {
        if (!active) return
        val segment = Path().apply {
            moveTo(lastX, lastY)
            lineTo(x, y)
        }
        fallbackPath?.lineTo(x, y)
        lastX = x
        lastY = y
        continueWith(segment, SEGMENT_MS, true)
    }

    fun up(x: Float, y: Float) {
        if (!active) return
        val segment = Path().apply {
            moveTo(lastX, lastY)
            lineTo(x, y)
        }
        fallbackPath?.lineTo(x, y)
        finish(segment, x, y)
    }

    fun cancel() {
        if (!active) return
        finish(Path().apply { moveTo(lastX, lastY) }, lastX, lastY)
    }

    private fun finish(segment: Path, x: Float, y: Float) {
        handler.removeCallbacks(tick)
        tickScheduled = false
        active = false
        val fallbackDuration = (SystemClock.uptimeMillis() - fallbackStart).coerceIn(40L, 1200L)

        var dispatched = false
        val current = stroke
        if (current != null) {
            val finalStroke = try {
                current.continueStroke(segment, 0, 40, false)
            } catch (error: Throwable) {
                Log.w(TAG, "Continuation refusée", error)
                null
            }
            if (finalStroke != null) dispatched = dispatch(finalStroke)
        }

        if (!dispatched) {
            fallbackPath?.lineTo(x, y)
            val path = fallbackPath
            if (path != null) {
                try {
                    val single = GestureDescription.StrokeDescription(path, 0, fallbackDuration, false)
                    service.dispatchGesture(
                        GestureDescription.Builder().addStroke(single).build(),
                        null,
                        handler
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "Repli du geste impossible", error)
                }
            }
        }

        stroke = null
        fallbackPath = null
    }

    private fun continueWith(path: Path, duration: Long, willContinue: Boolean) {
        val current = stroke ?: return
        val next = try {
            current.continueStroke(path, 0, duration, willContinue)
        } catch (error: Throwable) {
            Log.w(TAG, "Segment ignoré", error)
            return
        }
        if (dispatch(next)) {
            stroke = next
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        handler.postDelayed(tick, SEGMENT_MS - 30)
    }

    /** Maintient le doigt posé (permet l'appui long et évite l'annulation). */
    private fun keepAlive() {
        tickScheduled = false
        if (!active) return
        val current = stroke ?: return
        // Un chemin sans contour peut être refusé par Android : on ajoute un
        // déplacement infime pour maintenir le doigt posé sans clic parasite.
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(lastX + 0.01f, lastY + 0.01f)
        }
        val next = try {
            current.continueStroke(path, 0, SEGMENT_MS, true)
        } catch (error: Throwable) {
            active = false
            return
        }
        if (dispatch(next)) {
            stroke = next
            scheduleTick()
        } else {
            active = false
        }
    }

    private fun dispatch(strokeDescription: GestureDescription.StrokeDescription): Boolean {
        return try {
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(strokeDescription).build(),
                null,
                handler
            )
        } catch (error: Throwable) {
            Log.w(TAG, "dispatchGesture refusé", error)
            false
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        active = false
        stroke = null
        fallbackPath = null
    }

    companion object {
        private const val TAG = "PotatoTouch"
        private const val SEGMENT_MS = 120L
    }
}
