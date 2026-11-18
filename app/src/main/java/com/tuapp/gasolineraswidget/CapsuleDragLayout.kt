package com.tuapp.gasolineraswidget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Contenedor raiz de la capsula flotante.
 *
 * Intercepta el gesto de arrastre en cualquier punto de la capsula: barra
 * superior, botones, filas y precio. Un toque limpio (sin superar el
 * touchSlop del sistema) sigue llegando al hijo para que su onClick funcione;
 * en cuanto el dedo se desplaza lo suficiente, el hijo recibe un CANCEL y el
 * contenedor mueve la ventana.
 */
@SuppressLint("ClickableViewAccessibility")
class CapsuleDragLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnDragListener {
        fun onDragStart()
        fun onDragMove(dx: Int, dy: Int)
        fun onDragEnd()
    }

    var onDragListener: OnDragListener? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var siguiendo = false
    private var arrastrando = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                siguiendo = true
                arrastrando = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (siguiendo &&
                    !arrastrando &&
                    superaUmbral(ev.rawX - downX, ev.rawY - downY)
                ) {
                    arrastrando = true
                    onDragListener?.onDragStart()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                siguiendo = false
                arrastrando = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                siguiendo = true
                arrastrando = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!siguiendo) return false

                val dx = ev.rawX - downX
                val dy = ev.rawY - downY

                if (!arrastrando && superaUmbral(dx, dy)) {
                    arrastrando = true
                    onDragListener?.onDragStart()
                }

                if (arrastrando) {
                    onDragListener?.onDragMove(dx.toInt(), dy.toInt())
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (arrastrando) {
                    onDragListener?.onDragEnd()
                }
                siguiendo = false
                arrastrando = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (arrastrando) {
                    onDragListener?.onDragEnd()
                }
                siguiendo = false
                arrastrando = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun superaUmbral(dx: Float, dy: Float): Boolean {
        return abs(dx) > touchSlop || abs(dy) > touchSlop
    }
}
