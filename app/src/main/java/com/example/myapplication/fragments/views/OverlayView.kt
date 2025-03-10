package com.example.myapplication.fragments.views

import vn.cmcati.core.models.LivenessResult
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.example.myapplication.R

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<LivenessResult>()
    private var boxPaint = Paint()
    private var textPaint = Paint()
    private var landmarkPaint = Paint()
    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        textPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE

        landmarkPaint.color = Color.RED
        landmarkPaint.style = Paint.Style.FILL
        landmarkPaint.strokeWidth = 10f
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        results.forEach {
            drawBox(canvas, it)
        }

    }

    private fun drawBox(canvas: Canvas, trackedObject: LivenessResult) {
        val left = trackedObject.boundingBox.x1 * width
        val top = trackedObject.boundingBox.y1 * height
        val right = trackedObject.boundingBox.x2 * width
        val bottom = trackedObject.boundingBox.y2 * height
        Log.d("OverlayView", "width: $width, Height: $height")

        val drawableText = if (trackedObject.livenessScore != null) {
            "Liveness: ${trackedObject.livenessScore}"
        } else {
            "Unknown"
        }

        canvas.drawRoundRect(left, top, right, bottom, 15.0F, 15.0F, boxPaint)
        canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
    }

    fun setResults(trackedObjects: List<LivenessResult>) {
        results = trackedObjects
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}