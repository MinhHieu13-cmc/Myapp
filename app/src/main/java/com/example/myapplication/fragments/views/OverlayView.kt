package com.example.myapplication.fragments.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import vn.cmcati.core.models.LivenessResult

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<LivenessResult>()

    private val realPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val fakePaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textSize = 30f
    }
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 160
        style = Paint.Style.FILL
    }

    fun clear() {
        results = listOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        results.forEach { drawBox(canvas, it) }
    }

    private fun drawBox(canvas: Canvas, trackedObject: LivenessResult) {
        val left = trackedObject.boundingBox.x1 * width
        val top = trackedObject.boundingBox.y1 * height
        val right = trackedObject.boundingBox.x2 * width
        val bottom = trackedObject.boundingBox.y2 * height

        val score = trackedObject.livenessScore ?: 0f
        val isReal = score > 0.5f
        val label = if (isReal) "Real" else "Fake"
        val boxPaint = if (isReal) realPaint else fakePaint

        canvas.drawRect(left, top, right, bottom, boxPaint)

        val drawableText = "$label: ${String.format("%.2f", score)}"
        val textWidth = textPaint.measureText(drawableText)
        val textHeight = textPaint.textSize

        canvas.drawRect(left, top - textHeight - 5f, left + textWidth + 10f, top, backgroundPaint)
        canvas.drawText(drawableText, left + 5f, top - 5f, textPaint)
    }

    fun setResults(trackedObjects: List<LivenessResult>) {
        results = trackedObjects // Giới hạn số box đã được xử lý ở HomeFragment
        invalidate()
    }
}