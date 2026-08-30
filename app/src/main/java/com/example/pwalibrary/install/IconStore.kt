package com.example.pwalibrary.install

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min

object IconStore {

    private const val ICON_PX = 192

    /** Adaptive icons are masked; only the centre ~66% is guaranteed visible. */
    private const val ADAPTIVE_PX = 288
    private const val ADAPTIVE_SAFE_FRACTION = 0.66f

    /** Bounds the decode attempts a hostile or merely sloppy manifest can demand. */
    private const val MAX_CANDIDATES = 24

    /** BitmapFactory decodes neither SVG nor ICO, so those are skipped, not attempted. */
    private val RASTER_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")

    private val FALLBACK_PATHS = listOf(
        "icon.png",
        "icon.webp",
        "icons/icon-512.png",
        "icons/icon-192.png",
        "icons/icon.png",
        "apple-touch-icon.png",
        "favicon.png"
    )

    /**
     * Picks the best icon out of the extracted app and writes a normalised PNG.
     * Returns null when the app ships nothing usable, in which case callers fall
     * back to [letterBitmap].
     *
     * Candidates are tried until one decodes rather than committing to the first
     * one that exists: a file being present says nothing about BitmapFactory
     * being able to read it, and giving up there would discard a perfectly good
     * icon sitting behind a truncated or mislabelled one.
     */
    fun extract(
        context: Context,
        uuid: String,
        appDir: File,
        manifest: WebManifest?,
        htmlIcons: List<String>
    ): File? {
        val bitmap = candidatePaths(manifest, htmlIcons)
            .asSequence()
            .mapNotNull { resolveInside(appDir, it) }
            .filter { it.isFile }
            .mapNotNull { decodeScaled(it, ICON_PX) }
            .firstOrNull()
            ?: return null

        val dest = Storage.iconFile(context, uuid)
        return try {
            dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dest
        } catch (e: Exception) {
            dest.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Ordered by how much the app has actually said about each candidate: what
     * the manifest declares, then what the page links to, then the guesses.
     */
    private fun candidatePaths(manifest: WebManifest?, htmlIcons: List<String>): List<String> {
        val fromManifest = manifest?.icons.orEmpty()
            .filter { isRasterisable(it.src) }
            .sortedByDescending { it.maxSize }
            .map { it.src }
        val fromHtml = htmlIcons.filter { isRasterisable(it) }
        return (fromManifest + fromHtml + FALLBACK_PATHS).distinct().take(MAX_CANDIDATES)
    }

    /**
     * The query has to come off before the extension is read: generated
     * manifests and hand-written links alike cache-bust with "icon.png?v=5",
     * and testing the extension of "png?v=5" drops a usable icon.
     */
    private fun isRasterisable(src: String): Boolean {
        val path = src.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase() in RASTER_EXTENSIONS
    }

    /** Resolves a manifest-relative icon path, refusing anything outside the app dir. */
    private fun resolveInside(appDir: File, rawPath: String): File? {
        val cleaned = rawPath.substringBefore('?').substringBefore('#')
            .removePrefix("./")
            .removePrefix("/")
        if (cleaned.isBlank()) return null
        // Absolute URLs point outside the bundle; there is no network here anyway.
        if (Uri.parse(cleaned).scheme != null) return null

        val file = File(appDir, cleaned)
        val root = appDir.canonicalPath + File.separator
        return if (file.canonicalPath.startsWith(root)) file else null
    }

    /**
     * Null for anything unreadable, including a file whose header parses and
     * whose pixels do not — a truncated PNG reports its size happily and then
     * fails. Throwing here would fail the whole import over one bad icon, and
     * the caller has both a next candidate and a letter tile to fall back on.
     */
    private fun decodeScaled(file: File, targetPx: Int): Bitmap? = try {
        decodeScaledOrThrow(file, targetPx)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        // A zip is free to declare dimensions it has no intention of honouring.
        null
    }

    private fun decodeScaledOrThrow(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val longest = max(decoded.width, decoded.height)
        if (longest <= targetPx) return decoded

        val scale = targetPx.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            max(1, (decoded.width * scale).toInt()),
            max(1, (decoded.height * scale).toInt()),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= targetPx && h / 2 >= targetPx) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Flat coloured tile with the app's initial, used when no icon ships in the zip. */
    fun letterBitmap(name: String, sizePx: Int = ICON_PX): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = colorFor(name)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val radius = sizePx * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, fill)

        val letter = name.trim().take(1).uppercase().ifBlank { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.5f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val metrics = text.fontMetrics
        val baseline = sizePx / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(letter, sizePx / 2f, baseline, text)
        return bitmap
    }

    /** Deterministic pastel-ish hue so the same app always gets the same colour. */
    private fun colorFor(name: String): Int {
        val hue = ((name.hashCode() % 360) + 360) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.72f))
    }

    /**
     * Builds a full-bleed bitmap for [androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap],
     * insetting the artwork into the safe zone so launcher masking cannot crop it.
     */
    fun adaptiveBitmap(iconFile: File?, name: String): Bitmap {
        val source = iconFile?.takeIf { it.isFile }?.let { decodeScaled(it, ADAPTIVE_PX) }
            ?: letterBitmap(name, ADAPTIVE_PX)

        val out = Bitmap.createBitmap(ADAPTIVE_PX, ADAPTIVE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val safe = (ADAPTIVE_PX * ADAPTIVE_SAFE_FRACTION).toInt()
        val scale = min(safe.toFloat() / source.width, safe.toFloat() / source.height)
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        val left = (ADAPTIVE_PX - w) / 2
        val top = (ADAPTIVE_PX - h) / 2

        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        source.recycle()
        return out
    }
}
