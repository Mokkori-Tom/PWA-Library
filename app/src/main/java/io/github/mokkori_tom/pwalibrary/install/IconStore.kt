package io.github.mokkori_tom.pwalibrary.install

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object IconStore {

    private const val ICON_PX = 192

    /** Adaptive icons are masked; only the centre ~66% is guaranteed visible. */
    private const val ADAPTIVE_PX = 288
    private const val ADAPTIVE_SAFE_FRACTION = 0.66f

    /** Bounds the decode attempts a hostile or merely sloppy manifest can demand. */
    private const val MAX_CANDIDATES = 24

    /**
     * Ceiling on an image the user picks. Well above any icon and any camera
     * photo, and low enough that a mistaken pick cannot fill internal storage
     * while it is being copied out of the provider.
     */
    private const val MAX_PICKED_BYTES = 32L * 1024 * 1024

    /** BitmapFactory decodes neither SVG nor ICO, so those are skipped, not attempted. */
    private val RASTER_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")

    /** The same refusal, stated the way a data URI states its type. */
    private val NON_RASTER_MEDIA = setOf(
        "image/svg+xml", "image/x-icon", "image/vnd.microsoft.icon"
    )

    private const val DATA_PREFIX = "data:"

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
            .mapNotNull { decodeCandidate(appDir, it) }
            .firstOrNull()
            ?: return null

        return writePng(bitmap, Storage.iconFile(context, uuid))
    }

    /**
     * Normalises an image the user picked into this app's custom icon slot.
     *
     * Null when the pick is not an image this device can decode, or is larger
     * than [MAX_PICKED_BYTES]; the caller keeps whatever icon was already there.
     */
    fun importCustom(context: Context, uuid: String, source: Uri): File? {
        val temp = File.createTempFile("pick-", ".img", context.cacheDir)
        return try {
            val input = context.contentResolver.openInputStream(source) ?: return null
            if (!copyBounded(input, temp, MAX_PICKED_BYTES)) return null
            // Decoded from the copy rather than the stream: sizing needs two
            // passes over the same bytes, and a content uri is not rewindable.
            decodeScaled(temp, ICON_PX)?.let { writePng(it, Storage.customIconFile(context, uuid)) }
        } catch (e: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    /** False when the source runs past [limit], leaving a partial file to delete. */
    private fun copyBounded(input: InputStream, dest: File, limit: Long): Boolean {
        var total = 0L
        input.use { src ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) return false
                    out.write(buf, 0, n)
                }
            }
        }
        return true
    }

    private fun writePng(bitmap: Bitmap, dest: File): File? = try {
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        dest
    } catch (e: Exception) {
        dest.delete()
        null
    } finally {
        bitmap.recycle()
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
     * Turns one candidate into a bitmap, whichever form it arrived in: a path
     * into the app's own files, or an image carried inline in the manifest or
     * the page.
     */
    private fun decodeCandidate(appDir: File, src: String): Bitmap? =
        if (isDataUri(src)) {
            decodeDataUri(src)?.let { decodeScaled(it, ICON_PX) }
        } else {
            resolveInside(appDir, src)?.takeIf { it.isFile }?.let { decodeScaled(it, ICON_PX) }
        }

    /**
     * Whether a candidate is worth attempting at all.
     *
     * A file is judged by its extension and a data URI by its declared media
     * type, which is the better evidence of the two when it exists. For a file
     * the query has to come off first: generated manifests and hand-written
     * links alike cache-bust with "icon.png?v=5", and testing the extension of
     * "png?v=5" throws away a usable icon.
     */
    private fun isRasterisable(src: String): Boolean {
        if (isDataUri(src)) {
            val media = mediaTypeOf(src)
            return media.startsWith("image/") && media !in NON_RASTER_MEDIA
        }
        val path = src.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase() in RASTER_EXTENSIONS
    }

    private fun isDataUri(src: String): Boolean =
        src.regionMatches(0, DATA_PREFIX, 0, DATA_PREFIX.length, ignoreCase = true)

    /** "data:image/png;base64,AAA" -> "image/png". Blank when none is declared. */
    private fun mediaTypeOf(src: String): String =
        src.substring(DATA_PREFIX.length)
            .substringBefore(',')
            .substringBefore(';')
            .trim()
            .lowercase()

    /**
     * The bytes behind a base64 data URI.
     *
     * Base64 payloads only. The percent-encoded form is legal but is used in
     * practice for SVG, which cannot be rasterised here regardless.
     *
     * No length cap of its own: a manifest is read at up to 1MB and a page's
     * head at up to 256KB, so the string reaching here is already bounded. A
     * data URI cut off by either limit never arrives — truncated JSON fails to
     * parse, and a link tag with no closing bracket does not match.
     */
    private fun decodeDataUri(src: String): ByteArray? {
        val header = src.substring(DATA_PREFIX.length).substringBefore(',')
        if (header.split(';').none { it.trim().equals("base64", ignoreCase = true) }) return null
        // Attribute values may be wrapped across lines; the decoder is not
        // obliged to tolerate that.
        val payload = src.substringAfter(',', "").filterNot { it.isWhitespace() }
        if (payload.isEmpty()) return null
        return try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
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

    private fun decodeScaled(file: File, targetPx: Int): Bitmap? =
        decodeScaled(targetPx) { BitmapFactory.decodeFile(file.absolutePath, it) }

    private fun decodeScaled(bytes: ByteArray, targetPx: Int): Bitmap? =
        decodeScaled(targetPx) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }

    /**
     * Two passes over the same source: the first reads the dimensions, the
     * second decodes at a sample size that will not blow up on a large image.
     *
     * Null for anything the decoder refuses outright. It does **not** catch a
     * partially readable image: BitmapFactory returns a truncated PNG as the
     * rows it managed to read rather than as a failure, so a corrupt icon is
     * adopted rather than skipped (HANDOVER "4."). Throwing is not an option
     * either — that would fail the whole import over one bad icon, when the
     * caller has both a next candidate and a letter tile to fall back on.
     */
    private fun decodeScaled(targetPx: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            }
            decode(opts)?.let { shrunkToFit(it, targetPx) }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        // A zip is free to declare dimensions it has no intention of honouring.
        null
    }

    private fun shrunkToFit(decoded: Bitmap, targetPx: Int): Bitmap {
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
