package com.familyguardpro.applock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.familyguardpro.R

/**
 * Custom Pattern Lock View
 * 3x3 grid pattern lock similar to Android lock screen
 */
class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    companion object {
        private const val GRID_SIZE = 3
        private const val DOT_RADIUS = 20f
        private const val DOT_RADIUS_SELECTED = 30f
        private const val LINE_WIDTH = 8f
    }
    
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH
        strokeCap = Paint.Cap.ROUND
    }
    
    // Colors
    private val normalColor = context.getColor(R.color.primary)
    private val selectedColor = context.getColor(R.color.primary_dark)
    private val errorColor = 0xFFE53935.toInt()
    
    // Pattern state
    private val selectedDots = mutableListOf<Int>()
    private val dotCenters = Array(9) { floatArrayOf(0f, 0f) }
    private var currentX = 0f
    private var currentY = 0f
    private var isDrawing = false
    private var isPathVisible = true
    
    private var listener: OnPatternListener? = null
    
    interface OnPatternListener {
        fun onPatternComplete(pattern: String)
    }
    
    fun setOnPatternListener(listener: OnPatternListener) {
        this.listener = listener
    }
    
    fun setPathVisible(visible: Boolean) {
        isPathVisible = visible
        invalidate()
    }
    
    fun clearPattern() {
        selectedDots.clear()
        invalidate()
    }
    
    fun showError() {
        dotPaint.color = errorColor
        linePaint.color = errorColor
        invalidate()
        
        postDelayed({
            clearPattern()
            dotPaint.color = normalColor
            linePaint.color = selectedColor
            invalidate()
        }, 500)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        val cellWidth = w / GRID_SIZE.toFloat()
        val cellHeight = h / GRID_SIZE.toFloat()
        
        for (i in 0 until 9) {
            val row = i / GRID_SIZE
            val col = i % GRID_SIZE
            dotCenters[i][0] = cellWidth * col + cellWidth / 2
            dotCenters[i][1] = cellHeight * row + cellHeight / 2
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw lines between selected dots
        if (isPathVisible && selectedDots.size > 1) {
            linePaint.color = selectedColor
            for (i in 1 until selectedDots.size) {
                val from = dotCenters[selectedDots[i - 1]]
                val to = dotCenters[selectedDots[i]]
                canvas.drawLine(from[0], from[1], to[0], to[1], linePaint)
            }
        }
        
        // Draw line to current touch position
        if (isPathVisible && isDrawing && selectedDots.isNotEmpty()) {
            val lastDot = dotCenters[selectedDots.last()]
            canvas.drawLine(lastDot[0], lastDot[1], currentX, currentY, linePaint)
        }
        
        // Draw dots
        for (i in 0 until 9) {
            val isSelected = selectedDots.contains(i)
            dotPaint.color = if (isSelected) selectedColor else normalColor
            val radius = if (isSelected) DOT_RADIUS_SELECTED else DOT_RADIUS
            canvas.drawCircle(dotCenters[i][0], dotCenters[i][1], radius, dotPaint)
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                clearPattern()
                isDrawing = true
                handleTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                handleTouch(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                invalidate()
                
                if (selectedDots.size >= 4) {
                    val pattern = selectedDots.joinToString("")
                    listener?.onPatternComplete(pattern)
                } else {
                    showError()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun handleTouch(x: Float, y: Float) {
        for (i in 0 until 9) {
            if (!selectedDots.contains(i)) {
                val dx = x - dotCenters[i][0]
                val dy = y - dotCenters[i][1]
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                
                if (distance < 60f) { // Touch radius
                    selectedDots.add(i)
                    invalidate()
                    break
                }
            }
        }
    }
}
