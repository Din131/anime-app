package com.din131.aniview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wadah video fullscreen yang mengenali geser vertikal:
 * sisi kiri = kecerahan, sisi kanan = volume.
 * Ketukan biasa (termasuk ketuk 2x untuk maju/mundur) tetap diteruskan ke player.
 */
class GestureFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onGestureStart(side: Int)
        /** delta: perubahan relatif terhadap tinggi layar, positif = geser ke atas */
        fun onGestureDrag(side: Int, delta: Float)
        fun onGestureEnd()
    }

    var listener: Listener? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var side = 0          // -1 kiri, 1 kanan, 0 tidak aktif
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastY = ev.y
                dragging = false
                side = when {
                    ev.x < width * 0.4f -> -1
                    ev.x > width * 0.6f -> 1
                    else -> 0
                }
                // Hindari tepi atas/bawah (gestur sistem)
                if (ev.y < height * 0.08f || ev.y > height * 0.92f) side = 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> side = 0
            MotionEvent.ACTION_MOVE -> if (side != 0 && !dragging) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dy > slop * 2 && dy > dx * 1.5f) {
                    dragging = true
                    lastY = ev.y
                    listener?.onGestureStart(side)
                    return true // ambil alih; player menerima CANCEL
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (height > 0) listener?.onGestureDrag(side, (lastY - ev.y) / height)
                lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                listener?.onGestureEnd()
            }
        }
        return true
    }
}
