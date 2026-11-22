package com.vectormagico.app

import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ajustes de la vectorización. Los valores por defecto son el modo automático. */
class VParams(
    val maxColors: Int = 24,
    val detail: Int = 60,
    val smooth: Int = 50,
    val removeBg: Boolean = true,
    val removeHoles: Boolean = false
)

/** Una capa de un solo color. Cada contorno: x0,y0 y luego 6 floats por curva (c1x,c1y,c2x,c2y,x,y). */
class VLayer(val color: Int, val loops: List<FloatArray>)

class VResult(val width: Int, val height: Int, val layers: List<VLayer>)

object Vectorizer {
    private const val ISO = 0.45f

    fun run(src: IntArray, w: Int, h: Int, p: VParams, progress: (String) -> Unit = {}): VResult {
        progress("Analizando colores…")
        val pixels = prefilter(src, w, h)
        val centers = quantize(pixels, p.maxColors.coerceIn(2, 64))
        val k = centers.size
        val cr = IntArray(k) { (centers[it] shr 16) and 255 }
        val cg = IntArray(k) { (centers[it] shr 8) and 255 }
        val cb = IntArray(k) { centers[it] and 255 }

        val labels = IntArray(w * h)
        for (i in 0 until w * h) {
            val c = pixels[i]
            if ((c ushr 24) < 128) { labels[i] = -1; continue }
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            var best = 0
            var bd = Int.MAX_VALUE
            for (j in 0 until k) {
                val dr = r - cr[j]; val dg = g - cg[j]; val db = b - cb[j]
                val d = dr * dr + dg * dg + db * db
                if (d < bd) { bd = d; best = j }
            }
            labels[i] = best
        }

        if (p.removeBg) {
            progress("Quitando el fondo…")
            removeBackground(labels, w, h, k, p.removeHoles)
        }

        val counts = IntArray(k)
        for (l in labels) if (l >= 0) counts[l]++
        val order = (0 until k).filter { counts[it] > 0 }.sortedByDescending { counts[it] }

        val eps = 0.9f - p.detail * 0.007f
        val minArea = 40f - p.detail * 0.37f
        val cornerAngle = 35f + p.smooth * 0.6f

        val bw = w + 2
        val bh = h + 2
        val hoff = bw * bh
        val f = FloatArray(bw * bh)
        val tmp = FloatArray(bw * bh)
        val next = IntArray(2 * bw * bh)
        val visited = BooleanArray(2 * bw * bh)
        val layers = ArrayList<VLayer>()
        val ids = IntArray(4)
        val exits = BooleanArray(4)
        val pt = FloatArray(2)

        for ((n, l) in order.withIndex()) {
            progress("Vectorizando capa ${n + 1} de ${order.size}…")
            java.util.Arrays.fill(f, 0f)
            for (y in 0 until h) {
                val row = (y + 1) * bw + 1
                val lr = y * w
                for (x in 0 until w) if (labels[lr + x] == l) f[row + x] = 1f
            }
            // desenfoque [1 2 1] horizontal y vertical: suaviza los bordes de píxeles
            for (y in 0 until bh) {
                val r = y * bw
                for (x in 1 until bw - 1) tmp[r + x] = (f[r + x - 1] + 2f * f[r + x] + f[r + x + 1]) * 0.25f
            }
            for (y in 1 until bh - 1) {
                val r = y * bw
                for (x in 0 until bw) f[r + x] = (tmp[r - bw + x] + 2f * tmp[r + x] + tmp[r + bw + x]) * 0.25f
            }

            java.util.Arrays.fill(next, -1)
            java.util.Arrays.fill(visited, false)
            for (y in 0 until bh - 1) {
                for (x in 0 until bw - 1) {
                    val ia = f[y * bw + x] >= ISO
                    val ib = f[y * bw + x + 1] >= ISO
                    val ic = f[(y + 1) * bw + x + 1] >= ISO
                    val id = f[(y + 1) * bw + x] >= ISO
                    if (ia == ib && ib == ic && ic == id) continue
                    var cnt = 0
                    // perímetro en sentido horario: arriba, derecha, abajo, izquierda
                    if (ia != ib) { ids[cnt] = y * bw + x; exits[cnt] = ia; cnt++ }
                    if (ib != ic) { ids[cnt] = hoff + y * bw + x + 1; exits[cnt] = ib; cnt++ }
                    if (ic != id) { ids[cnt] = (y + 1) * bw + x; exits[cnt] = ic; cnt++ }
                    if (id != ia) { ids[cnt] = hoff + y * bw + x; exits[cnt] = id; cnt++ }
                    for (i in 0 until cnt) if (exits[i]) next[ids[i]] = ids[(i + 1) % cnt]
                }
            }

            val loops = ArrayList<FloatArray>()
            var raw = FloatArray(1024)
            for (start in 0 until 2 * bw * bh) {
                if (next[start] == -1 || visited[start]) continue
                var cnt = 0
                var cur = start
                while (cur != -1 && !visited[cur]) {
                    visited[cur] = true
                    edgePoint(cur, f, bw, hoff, pt)
                    if (cnt * 2 + 2 > raw.size) raw = raw.copyOf(raw.size * 2)
                    raw[cnt * 2] = pt[0] - 0.5f
                    raw[cnt * 2 + 1] = pt[1] - 0.5f
                    cnt++
                    cur = next[cur]
                }
                if (cnt < 4) continue
                if (abs(area(raw, cnt)) < minArea) continue
                averageLoop(raw, cnt)
                val keep = simplify(raw, cnt, eps)
                if (keep.size < 3) continue
                loops.add(smoothLoop(raw, keep, cornerAngle))
            }
            if (loops.isNotEmpty()) layers.add(VLayer(centers[l] or (0xFF shl 24), loops))
        }
        progress("Listo")
        return VResult(w, h, layers)
    }

    /** Desenfoque [1 2 1] de los colores: quita el ruido de JPEG sin mover los bordes. */
    private fun prefilter(src: IntArray, w: Int, h: Int): IntArray {
        if (w < 3 || h < 3) return src
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (pass in 0..1) {
            val from = if (pass == 0) src else tmp
            val to = if (pass == 0) tmp else out
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val a: Int; val b: Int; val c: Int
                if (pass == 0) {
                    a = from[y * w + max(0, x - 1)]; b = from[i]; c = from[y * w + min(w - 1, x + 1)]
                } else {
                    a = from[max(0, y - 1) * w + x]; b = from[i]; c = from[min(h - 1, y + 1) * w + x]
                }
                val ca = a ushr 24; val cb = b ushr 24; val cc = c ushr 24
                if (cb < 128) { to[i] = b; continue }
                // los píxeles transparentes no aportan color
                val wa = if (ca >= 128) 1 else 0
                val wc = if (cc >= 128) 1 else 0
                val tw = wa + 2 + wc
                var r = 2 * ((b shr 16) and 255)
                var g = 2 * ((b shr 8) and 255)
                var bl = 2 * (b and 255)
                if (wa == 1) { r += (a shr 16) and 255; g += (a shr 8) and 255; bl += a and 255 }
                if (wc == 1) { r += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255 }
                to[i] = (0xFF shl 24) or ((r / tw) shl 16) or ((g / tw) shl 8) or (bl / tw)
            }
        }
        return out
    }

    private fun edgePoint(id: Int, f: FloatArray, bw: Int, hoff: Int, out: FloatArray) {
        if (id < hoff) {
            val y = id / bw
            val x = id % bw
            val a = f[id]
            val b = f[id + 1]
            out[0] = x + (ISO - a) / (b - a)
            out[1] = y.toFloat()
        } else {
            val i = id - hoff
            val y = i / bw
            val x = i % bw
            val a = f[i]
            val b = f[i + bw]
            out[0] = x.toFloat()
            out[1] = y + (ISO - a) / (b - a)
        }
    }

    private fun area(p: FloatArray, n: Int): Float {
        var s = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return s * 0.5f
    }

    /** Promedio móvil de 5 puntos sobre el contorno cerrado: quita el temblor de los bordes. */
    private fun averageLoop(p: FloatArray, n: Int) {
        if (n < 8) return
        val ox = FloatArray(n) { p[it * 2] }
        val oy = FloatArray(n) { p[it * 2 + 1] }
        for (i in 0 until n) {
            var sx = 0f
            var sy = 0f
            for (d in -2..2) {
                val j = (i + d + n) % n
                sx += ox[j]; sy += oy[j]
            }
            p[i * 2] = sx / 5f
            p[i * 2 + 1] = sy / 5f
        }
    }

    /** Douglas-Peucker para contornos cerrados. Devuelve los índices que se conservan. */
    private fun simplify(p: FloatArray, n: Int, eps: Float): IntArray {
        var far = 0
        var fd = -1f
        for (i in 1 until n) {
            val dx = p[i * 2] - p[0]; val dy = p[i * 2 + 1] - p[1]
            val d = dx * dx + dy * dy
            if (d > fd) { fd = d; far = i }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, far))
        stack.addLast(intArrayOf(far, n))
        while (stack.isNotEmpty()) {
            val s = stack.removeLast()
            val a = s[0]; val b = s[1]
            if (b - a < 2) continue
            val ax = p[(a % n) * 2]; val ay = p[(a % n) * 2 + 1]
            val bx = p[(b % n) * 2]; val by = p[(b % n) * 2 + 1]
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            var md = -1f
            var mi = -1
            for (i in a + 1 until b) {
                val px = p[(i % n) * 2]; val py = p[(i % n) * 2 + 1]
                val d = if (len < 1e-6f) sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
                else abs((px - ax) * dy - (py - ay) * dx) / len
                if (d > md) { md = d; mi = i }
            }
            if (md > eps && mi >= 0) {
                keep[mi % n] = true
                stack.addLast(intArrayOf(a, mi))
                stack.addLast(intArrayOf(mi, b))
            }
        }
        var c = 0
        for (i in 0 until n) if (keep[i]) c++
        val out = IntArray(c)
        c = 0
        for (i in 0 until n) if (keep[i]) out[c++] = i
        return out
    }

    /** Convierte los puntos conservados en curvas Bézier suaves, dejando las esquinas marcadas. */
    private fun smoothLoop(p: FloatArray, idx: IntArray, cornerAngle: Float): FloatArray {
        val m = idx.size
        val qx = FloatArray(m) { p[idx[it] * 2] }
        val qy = FloatArray(m) { p[idx[it] * 2 + 1] }
        val segLen = FloatArray(m) { // largo del tramo i -> i+1
            val j = (it + 1) % m
            sqrt((qx[j] - qx[it]) * (qx[j] - qx[it]) + (qy[j] - qy[it]) * (qy[j] - qy[it]))
        }
        val tx = FloatArray(m)
        val ty = FloatArray(m)
        val hl = FloatArray(m) // largo del tirador; 0 = esquina
        for (i in 0 until m) {
            val pr = (i + m - 1) % m
            val nx = (i + 1) % m
            val v1x = qx[i] - qx[pr]; val v1y = qy[i] - qy[pr]
            val v2x = qx[nx] - qx[i]; val v2y = qy[nx] - qy[i]
            val l1 = segLen[pr]; val l2 = segLen[i]
            if (l1 < 1e-4f || l2 < 1e-4f) { hl[i] = 0f; continue }
            val cos = ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
            val ang = Math.toDegrees(acos(cos).toDouble()).toFloat()
            if (ang > cornerAngle) { hl[i] = 0f; continue }
            var dx = qx[nx] - qx[pr]; var dy = qy[nx] - qy[pr]
            val dl = sqrt(dx * dx + dy * dy)
            if (dl < 1e-4f) { hl[i] = 0f; continue }
            dx /= dl; dy /= dl
            tx[i] = dx; ty[i] = dy
            hl[i] = min(l1, l2) / 3f
        }
        val out = FloatArray(2 + 6 * m)
        out[0] = qx[0]; out[1] = qy[0]
        for (i in 0 until m) {
            val j = (i + 1) % m
            val o = 2 + i * 6
            out[o] = qx[i] + tx[i] * hl[i]
            out[o + 1] = qy[i] + ty[i] * hl[i]
            out[o + 2] = qx[j] - tx[j] * hl[j]
            out[o + 3] = qy[j] - ty[j] * hl[j]
            out[o + 4] = qx[j]
            out[o + 5] = qy[j]
        }
        return out
    }

    // ---------------------------------------------------------------- fondo

    private fun removeBackground(labels: IntArray, w: Int, h: Int, k: Int, holes: Boolean) {
        val hist = IntArray(k)
        var transparent = 0
        var total = 0
        fun see(i: Int) { total++; if (labels[i] < 0) transparent++ else hist[labels[i]]++ }
        for (x in 0 until w) { see(x); see((h - 1) * w + x) }
        for (y in 1 until h - 1) { see(y * w); see(y * w + w - 1) }
        if (transparent * 2 > total) return // ya tiene fondo transparente
        var bg = 0
        for (i in 1 until k) if (hist[i] > hist[bg]) bg = i
        if (hist[bg].toFloat() / max(1, total - transparent) < 0.35f) return // la foto llena el cuadro
        val queue = IntArray(w * h)
        var qh = 0
        var qt = 0
        fun push(i: Int) { if (labels[i] == bg) { labels[i] = -2; queue[qt++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        for (i in labels.indices) {
            if (labels[i] == -2) labels[i] = -1
            else if (holes && labels[i] == bg) labels[i] = -1
        }
    }

    // ------------------------------------------------------------- colores

    private class KM(val centers: FloatArray, val counts: IntArray, val meanDist: Float)

    private fun quantize(px: IntArray, maxK: Int): IntArray {
        val stride = max(1, px.size / 24000)
        val sr = ArrayList<Int>()
        var i = 0
        while (i < px.size) {
            if ((px[i] ushr 24) >= 128) sr.add(px[i])
            i += stride
        }
        if (sr.isEmpty()) return intArrayOf(0)
        val n = sr.size
        val r = FloatArray(n) { ((sr[it] shr 16) and 255).toFloat() }
        val g = FloatArray(n) { ((sr[it] shr 8) and 255).toFloat() }
        val b = FloatArray(n) { (sr[it] and 255).toFloat() }

        val ks = listOf(2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64).filter { it < maxK } + maxK
        var best: KM? = null
        for (k in ks) {
            val km = kmeans(r, g, b, n, k)
            best = km
            if (km.meanDist < 8f) break // ya representa bien la imagen: no hacen falta más colores
        }
        val km = best!!
        // unir colores casi iguales
        val cs = ArrayList<FloatArray>()
        val cnt = ArrayList<Int>()
        for (c in 0 until km.counts.size) {
            if (km.counts[c] == 0) continue
            cs.add(floatArrayOf(km.centers[c * 3], km.centers[c * 3 + 1], km.centers[c * 3 + 2]))
            cnt.add(km.counts[c])
        }
        while (cs.size > 1) {
            var bi = -1; var bj = -1; var bd = 22f * 22f
            for (a in 0 until cs.size) for (c in a + 1 until cs.size) {
                val d = dist2(cs[a], cs[c])
                if (d < bd) { bd = d; bi = a; bj = c }
            }
            if (bi < 0) break
            val wa = cnt[bi].toFloat(); val wb = cnt[bj].toFloat()
            for (t in 0..2) cs[bi][t] = (cs[bi][t] * wa + cs[bj][t] * wb) / (wa + wb)
            cnt[bi] = cnt[bi] + cnt[bj]
            cs.removeAt(bj); cnt.removeAt(bj)
        }
        // quitar colores "de borde": mezclas pequeñas entre otros dos colores (halos del antialias)
        var changed = true
        while (changed && cs.size > 2) {
            changed = false
            val total = cnt.sum().toFloat()
            var worst = -1
            for (c in 0 until cs.size) {
                if (cnt[c] / total >= 0.04f) continue
                if (worst >= 0 && cnt[c] >= cnt[worst]) continue
                for (a in 0 until cs.size) for (b in a + 1 until cs.size) {
                    if (a == c || b == c) continue
                    if (isBlend(cs[c], cs[a], cs[b])) worst = c
                }
            }
            if (worst >= 0) { cs.removeAt(worst); cnt.removeAt(worst); changed = true }
        }
        return IntArray(cs.size) {
            ((cs[it][0].roundToInt().coerceIn(0, 255) shl 16) or
                (cs[it][1].roundToInt().coerceIn(0, 255) shl 8) or
                cs[it][2].roundToInt().coerceIn(0, 255))
        }
    }

    private fun isBlend(c: FloatArray, a: FloatArray, b: FloatArray): Boolean {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        val l2 = dx * dx + dy * dy + dz * dz
        if (l2 < 900f) return false
        val t = ((c[0] - a[0]) * dx + (c[1] - a[1]) * dy + (c[2] - a[2]) * dz) / l2
        if (t < 0.05f || t > 0.95f) return false
        val px = a[0] + dx * t - c[0]; val py = a[1] + dy * t - c[1]; val pz = a[2] + dz * t - c[2]
        return px * px + py * py + pz * pz < 34f * 34f
    }

    private fun dist2(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return x * x + y * y + z * z
    }

    private fun kmeans(r: FloatArray, g: FloatArray, b: FloatArray, n: Int, k: Int): KM {
        val rnd = Random(42)
        val c = FloatArray(k * 3)
        val d = FloatArray(n) { Float.MAX_VALUE }
        var first = rnd.nextInt(n)
        var made = 0
        while (made < k) {
            c[made * 3] = r[first]; c[made * 3 + 1] = g[first]; c[made * 3 + 2] = b[first]
            made++
            var sum = 0.0
            for (i in 0 until n) {
                val dr = r[i] - r[first]; val dg = g[i] - g[first]; val db = b[i] - b[first]
                val dd = dr * dr + dg * dg + db * db
                if (dd < d[i]) d[i] = dd
                sum += d[i]
            }
            if (made == k) break
            if (sum <= 0.0) { first = rnd.nextInt(n); continue }
            var t = rnd.nextDouble() * sum
            var pick = n - 1
            for (i in 0 until n) { t -= d[i]; if (t <= 0.0) { pick = i; break } }
            first = pick
        }
        val counts = IntArray(k)
        val sums = FloatArray(k * 3)
        var meanDist = 0f
        for (iter in 0 until 10) {
            java.util.Arrays.fill(counts, 0)
            java.util.Arrays.fill(sums, 0f)
            var tot = 0.0
            for (i in 0 until n) {
                var bj = 0
                var bd = Float.MAX_VALUE
                for (j in 0 until k) {
                    val dr = r[i] - c[j * 3]; val dg = g[i] - c[j * 3 + 1]; val db = b[i] - c[j * 3 + 2]
                    val dd = dr * dr + dg * dg + db * db
                    if (dd < bd) { bd = dd; bj = j }
                }
                counts[bj]++
                sums[bj * 3] += r[i]; sums[bj * 3 + 1] += g[i]; sums[bj * 3 + 2] += b[i]
                tot += sqrt(bd.toDouble())
            }
            meanDist = (tot / n).toFloat()
            for (j in 0 until k) if (counts[j] > 0) {
                c[j * 3] = sums[j * 3] / counts[j]
                c[j * 3 + 1] = sums[j * 3 + 1] / counts[j]
                c[j * 3 + 2] = sums[j * 3 + 2] / counts[j]
            }
        }
        return KM(c, counts, meanDist)
    }

    // ----------------------------------------------------------------- SVG

    private fun fmt(v: Float): String {
        val s = ((v * 100f).roundToInt() / 100f).toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** widthMm > 0 fija el tamaño físico del SVG (alto proporcional). */
    fun toSvg(res: VResult, widthMm: Float = 0f): String {
        val sb = StringBuilder(64 * 1024)
        val size = if (widthMm > 0f)
            "width=\"${fmt(widthMm)}mm\" height=\"${fmt(widthMm * res.height / res.width)}mm\""
        else "width=\"${res.width}\" height=\"${res.height}\""
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${res.width} ${res.height}\" $size>\n")
        for (layer in res.layers) {
            sb.append("<path fill=\"#").append(String.format("%06x", layer.color and 0xFFFFFF))
                .append("\" fill-rule=\"evenodd\" d=\"")
            for (lp in layer.loops) {
                sb.append('M').append(fmt(lp[0])).append(' ').append(fmt(lp[1]))
                var i = 2
                while (i + 5 < lp.size) {
                    sb.append('C')
                    for (t in 0..5) { if (t > 0) sb.append(' '); sb.append(fmt(lp[i + t])) }
                    i += 6
                }
                sb.append('Z')
            }
            sb.append("\"/>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }
}
