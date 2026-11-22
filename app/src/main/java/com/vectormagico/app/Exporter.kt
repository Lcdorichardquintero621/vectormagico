package com.vectormagico.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.roundToInt

object Exporter {

    const val DPI = 300

    fun buildPaths(res: VResult): List<Pair<Int, Path>> = res.layers.map { layer ->
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        for (lp in layer.loops) {
            path.moveTo(lp[0], lp[1])
            var i = 2
            while (i + 5 < lp.size) {
                path.cubicTo(lp[i], lp[i + 1], lp[i + 2], lp[i + 3], lp[i + 4], lp[i + 5])
                i += 6
            }
            path.close()
        }
        Pair(layer.color, path)
    }

    private fun drawAll(canvas: Canvas, paths: List<Pair<Int, Path>>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        for ((color, path) in paths) {
            paint.color = color
            canvas.drawPath(path, paint)
        }
    }

    /** PNG transparente del vector a [cm] cm de lado mayor y 300 dpi. */
    fun pngBytes(res: VResult, paths: List<Pair<Int, Path>>, cm: Int): ByteArray {
        val longPx = (cm / 2.54f * DPI).roundToInt()
        val scale = longPx.toFloat() / max(res.width, res.height)
        val bw = max(1, (res.width * scale).roundToInt())
        val bh = max(1, (res.height * scale).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        drawAll(canvas, paths)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return withDpi(baos.toByteArray(), DPI)
    }

    /** Inserta el bloque pHYs para que el PNG diga que está a 300 dpi. */
    private fun withDpi(png: ByteArray, dpi: Int): ByteArray {
        if (png.size < 40) return png
        val ppm = (dpi / 0.0254).roundToInt()
        val type = "pHYs".toByteArray(Charsets.US_ASCII)
        val data = ByteBuffer.allocate(9).putInt(ppm).putInt(ppm).put(1.toByte()).array()
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        val out = ByteArrayOutputStream(png.size + 21)
        out.write(png, 0, 33) // firma + bloque IHDR
        out.write(ByteBuffer.allocate(4).putInt(9).array())
        out.write(type)
        out.write(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        out.write(png, 33, png.size - 33)
        return out.toByteArray()
    }

    /** PDF con curvas vectoriales, del tamaño físico elegido. */
    fun pdfBytes(res: VResult, paths: List<Pair<Int, Path>>, cm: Int): ByteArray {
        val longPts = cm / 2.54f * 72f
        val scale = longPts / max(res.width, res.height)
        val pw = max(1, (res.width * scale).roundToInt())
        val ph = max(1, (res.height * scale).roundToInt())
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
        page.canvas.scale(pw.toFloat() / res.width, ph.toFloat() / res.height)
        drawAll(page.canvas, paths)
        doc.finishPage(page)
        val baos = ByteArrayOutputStream()
        doc.writeTo(baos)
        doc.close()
        return baos.toByteArray()
    }

    fun svgBytes(res: VResult, cm: Int): ByteArray {
        return Vectorizer.toSvg(res, cm * 10f).toByteArray(Charsets.UTF_8)
    }

    /** Guarda en Descargas/VectorMagico y devuelve el Uri. */
    fun save(ctx: Context, name: String, mime: String, data: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VectorMagico")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: return null
        return uri
    }
}
