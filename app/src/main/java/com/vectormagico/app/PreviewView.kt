package com.vectormagico.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/** Vista previa con zoom (pellizco), arrastre y doble toque para reencuadrar. */
class PreviewView(ctx: Context) : View(ctx) {

    var original: Bitmap? = null
    var layers: List<Pair<Int, Path>> = emptyList()
    var showVector = true
        set(v) { field = v; invalidate() }
    /** 0 = cuadros (transparente), 1 = blanco, 2 = negro */
    var bgMode = 0
        set(v) { field = v; invalidate() }

    private var imgW = 1
    private var imgH = 1
    private val mat = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checker = Paint()
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            mat.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })
    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true }
    })

    init {
        val tile = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        val p = Paint()
        p.color = Color.rgb(235, 235, 235); c.drawRect(0f, 0f, 32f, 32f, p)
        p.color = Color.rgb(205, 205, 205)
        c.drawRect(0f, 0f, 16f, 16f, p); c.drawRect(16f, 16f, 32f, 32f, p)
        checker.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun setContent(bitmap: Bitmap, w: Int, h: Int, paths: List<Pair<Int, Path>>) {
        original = bitmap
        imgW = w
        imgH = h
        layers = paths
        fit()
    }

    fun fit() {
        mat.reset()
        if (width > 0 && height > 0) {
            val s = min(width.toFloat() / imgW, height.toFloat() / imgH) * 0.94f
            mat.postScale(s, s)
            mat.postTranslate((width - imgW * s) / 2f, (height - imgH * s) / 2f)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = if (e.actionIndex == 0) 1 else 0
                if (i < e.pointerCount) { lastX = e.getX(i); lastY = e.getY(i) }
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                mat.postTranslate(e.x - lastX, e.y - lastY)
                lastX = e.x; lastY = e.y
                invalidate()
            } else if (e.pointerCount > 1) {
                lastX = e.getX(0); lastY = e.getY(0)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        when (bgMode) {
            0 -> canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), checker)
            1 -> canvas.drawColor(Color.WHITE)
            else -> canvas.drawColor(Color.BLACK)
        }
        canvas.save()
        canvas.concat(mat)
        if (showVector) {
            for ((color, path) in layers) {
                fill.color = color
                canvas.drawPath(path, fill)
            }
        } else {
            original?.let { canvas.drawBitmap(it, 0f, 0f, bmpPaint) }
        }
        canvas.restore()
    }
}
