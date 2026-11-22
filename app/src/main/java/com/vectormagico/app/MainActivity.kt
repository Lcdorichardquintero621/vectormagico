package com.vectormagico.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val pickCode = 101
    private val primary = Color.rgb(91, 63, 216)
    private val bgColor = Color.rgb(246, 244, 251)
    private val textColor = Color.rgb(34, 32, 52)
    private val grayText = Color.rgb(107, 104, 128)

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var sbColors: SeekBar
    private lateinit var sbDetail: SeekBar
    private lateinit var sbSmooth: SeekBar
    private lateinit var swBg: Switch
    private lateinit var swHoles: Switch
    private lateinit var swQuality: Switch
    private lateinit var spSize: Spinner
    private lateinit var btnOriginal: Button
    private lateinit var btnVector: Button
    private lateinit var btnBg: Button

    private var srcUri: Uri? = null
    private var srcBitmap: Bitmap? = null
    private var srcPixels: IntArray? = null
    private var result: VResult? = null
    private var paths: List<Pair<Int, Path>> = emptyList()
    private var lastUri: Uri? = null
    private var lastMime = "image/png"
    @Volatile private var runId = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val it = intent
        if (it != null && it.action == Intent.ACTION_SEND && (it.type ?: "").startsWith("image/")) {
            @Suppress("DEPRECATION")
            val u = it.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) loadImage(u)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun roundBg(color: Int, radius: Int = 12): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun button(text: String, filled: Boolean): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(8), dp(10), dp(8), dp(10))
        b.background = roundBg(if (filled) primary else Color.rgb(228, 223, 247))
        b.setTextColor(if (filled) Color.WHITE else primary)
        return b
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun slider(parent: LinearLayout, name: String, min: Int, max: Int, init: Int, show: (Int) -> String): SeekBar {
        val tv = label("$name: ${show(init)}", 13f, textColor)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = init - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                tv.text = "$name: ${show(p + min)}"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { if (srcPixels != null) process() }
        })
        parent.addView(tv)
        parent.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sb
    }

    private fun toggle(parent: LinearLayout, text: String, checked: Boolean, onChange: () -> Unit): Switch {
        val s = Switch(this)
        s.text = text
        s.textSize = 14f
        s.setTextColor(textColor)
        s.isChecked = checked
        s.setPadding(0, dp(6), 0, dp(6))
        s.setOnCheckedChangeListener { _, _ -> onChange() }
        parent.addView(s)
        return s
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(16), dp(12), dp(16), dp(6))
        header.addView(label("VectorMágico", 22f, primary, true))
        header.addView(label("Imagen a vector, automático · listo para DTF", 12f, grayText))
        root.addView(header)

        preview = PreviewView(this)
        val pl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        pl.setMargins(dp(10), dp(4), dp(10), dp(4))
        root.addView(preview, pl)

        val tRow = LinearLayout(this)
        tRow.orientation = LinearLayout.HORIZONTAL
        tRow.setPadding(dp(10), 0, dp(10), 0)
        btnOriginal = button("Original", false)
        btnVector = button("Vector", true)
        btnBg = button("Fondo: cuadros", false)
        val tp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) } }
        tRow.addView(btnOriginal, tp())
        tRow.addView(btnVector, tp())
        tRow.addView(btnBg, tp())
        root.addView(tRow)
        btnOriginal.setOnClickListener { preview.showVector = false; styleToggles() }
        btnVector.setOnClickListener { preview.showVector = true; styleToggles() }
        btnBg.setOnClickListener { preview.bgMode = (preview.bgMode + 1) % 3; styleToggles() }

        status = label("Elige una imagen para empezar. Todo es automático.", 12f, grayText)
        status.setPadding(dp(16), dp(6), dp(16), dp(2))
        root.addView(status)

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(4), dp(16), dp(8))
        sbColors = slider(box, "Colores máx.", 2, 64, 24) { "$it (se ajusta solo)" }
        sbDetail = slider(box, "Detalle", 0, 100, 60) { "$it" }
        sbSmooth = slider(box, "Suavizado de curvas", 0, 100, 50) { "$it" }
        swBg = toggle(box, "Quitar fondo (transparente)", true) { if (srcPixels != null) process() }
        swHoles = toggle(box, "Quitar también huecos internos", false) { if (srcPixels != null) process() }
        swQuality = toggle(box, "Calidad alta (más lento)", false) { srcUri?.let { loadImage(it) } }
        box.addView(label("Tamaño de impresión (lado mayor, 300 dpi)", 13f, textColor))
        spSize = Spinner(this)
        spSize.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("10 cm", "20 cm", "30 cm", "40 cm"))
        spSize.setSelection(2)
        box.addView(spSize)
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(165)))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(dp(10), dp(4), dp(10), dp(10))
        val pick = button("Elegir imagen", true)
        pick.textSize = 16f
        pick.setOnClickListener { pickImage() }
        bottom.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val bSvg = button("SVG", false)
        val bPdf = button("PDF", false)
        val bPng = button("PNG DTF", false)
        val bShare = button("Compartir", false)
        bSvg.setOnClickListener { export("SVG") }
        bPdf.setOnClickListener { export("PDF") }
        bPng.setOnClickListener { export("PNG") }
        bShare.setOnClickListener { share() }
        for (b in listOf(bSvg, bPdf, bPng, bShare)) row.addView(b, tp())
        val rp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rp.topMargin = dp(6)
        bottom.addView(row, rp)
        root.addView(bottom)

        setContentView(root)
        styleToggles()
    }

    private fun styleToggles() {
        fun mark(b: Button, on: Boolean) {
            b.background = roundBg(if (on) primary else Color.rgb(228, 223, 247))
            b.setTextColor(if (on) Color.WHITE else primary)
        }
        mark(btnOriginal, !preview.showVector)
        mark(btnVector, preview.showVector)
        mark(btnBg, false)
        btnBg.text = when (preview.bgMode) { 0 -> "Fondo: cuadros"; 1 -> "Fondo: blanco"; else -> "Fondo: negro" }
    }

    private fun setStatus(msg: String) { runOnUiThread { status.text = msg } }

    // ------------------------------------------------------------ imagen

    @Suppress("DEPRECATION")
    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, "Elegir imagen"), pickCode)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == RESULT_OK) {
            data?.data?.let { loadImage(it) }
        }
    }

    private fun loadImage(uri: Uri) {
        srcUri = uri
        setStatus("Cargando imagen…")
        val maxSide = if (swQuality.isChecked) 2000 else 1200
        Thread {
            try {
                val bmp = decode(uri, maxSide)
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                runOnUiThread {
                    srcBitmap = bmp
                    srcPixels = px
                    process()
                }
            } catch (t: Throwable) {
                setStatus("No se pudo abrir la imagen: ${t.message}")
            }
        }.start()
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("formato no compatible")
        val orient = try {
            contentResolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val s = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (s < 1f) m.postScale(s, s)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        return bmp
    }

    // ------------------------------------------------------ vectorización

    private fun process() {
        val px = srcPixels ?: return
        val bmp = srcBitmap ?: return
        val w = bmp.width
        val h = bmp.height
        val params = VParams(
            maxColors = sbColors.progress + 2,
            detail = sbDetail.progress,
            smooth = sbSmooth.progress,
            removeBg = swBg.isChecked,
            removeHoles = swHoles.isChecked
        )
        val id = ++runId
        setStatus("Vectorizando…")
        Thread {
            try {
                val res = Vectorizer.run(px, w, h, params) { msg -> if (id == runId) setStatus(msg) }
                if (id != runId) return@Thread
                val p = Exporter.buildPaths(res)
                runOnUiThread {
                    if (id != runId) return@runOnUiThread
                    result = res
                    paths = p
                    preview.setContent(bmp, w, h, p)
                    preview.showVector = true
                    styleToggles()
                    status.text = "Listo: ${res.layers.size} colores. Toca dos veces para reencuadrar."
                }
            } catch (t: Throwable) {
                setStatus("Error al vectorizar: ${t.message}. Prueba con calidad normal.")
            }
        }.start()
    }

    // ----------------------------------------------------------- exportar

    private fun sizeCm(): Int = (spSize.selectedItemPosition + 1) * 10

    private fun export(kind: String) {
        val res = result
        if (res == null) {
            Toast.makeText(this, "Primero elige una imagen", Toast.LENGTH_SHORT).show()
            return
        }
        val p = paths
        val cm = sizeCm()
        setStatus("Guardando $kind…")
        Thread {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name: String
                val mime: String
                val bytes: ByteArray
                when (kind) {
                    "SVG" -> { name = "vectormagico_$stamp.svg"; mime = "image/svg+xml"; bytes = Exporter.svgBytes(res, cm) }
                    "PDF" -> { name = "vectormagico_$stamp.pdf"; mime = "application/pdf"; bytes = Exporter.pdfBytes(res, p, cm) }
                    else -> { name = "vectormagico_dtf_$stamp.png"; mime = "image/png"; bytes = Exporter.pngBytes(res, p, cm) }
                }
                val uri = Exporter.save(this, name, mime, bytes)
                runOnUiThread {
                    if (uri != null) {
                        lastUri = uri
                        lastMime = mime
                        status.text = "Guardado en Descargas/VectorMagico: $name"
                    } else {
                        status.text = "No se pudo guardar el archivo"
                    }
                }
            } catch (e: OutOfMemoryError) {
                setStatus("Memoria insuficiente: elige un tamaño de impresión menor")
            } catch (t: Throwable) {
                setStatus("Error al guardar: ${t.message}")
            }
        }.start()
    }

    private fun share() {
        val u = lastUri
        if (u == null) {
            Toast.makeText(this, "Guarda un archivo primero (SVG, PDF o PNG)", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND)
        i.type = lastMime
        i.putExtra(Intent.EXTRA_STREAM, u)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Compartir"))
    }
}
