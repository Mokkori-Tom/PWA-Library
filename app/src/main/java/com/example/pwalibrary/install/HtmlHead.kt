package com.example.pwalibrary.install

import java.nio.charset.Charset

/**
 * The two things worth reading out of a page's own head when the zip ships no
 * manifest: the name the page calls itself, and the icons it links to.
 *
 * Kept free of android.* imports on purpose. The patterns below fail silently
 * when they are wrong — a title simply does not appear — and finding that out
 * on the device costs a round trip, so this file is compiled and exercised on
 * the desktop JVM instead. See samples/TESTING.md.
 */
data class HtmlHead(
    val title: String?,
    /** hrefs exactly as the page wrote them, most promising first. */
    val iconHrefs: List<String>
) {
    companion object {

        val EMPTY = HtmlHead(null, emptyList())

        /**
         * Reads what the head declares. Callers pass a bounded prefix of the
         * file, so anything sitting past that point is simply absent.
         */
        fun parse(bytes: ByteArray): HtmlHead {
            if (bytes.isEmpty()) return EMPTY
            val region = headRegion(decode(bytes))
            return HtmlHead(titleIn(region), iconsIn(region))
        }

        // ------------------------------------------------------------ decoding

        /**
         * The page's own encoding, not this app's. A title is the one thing here
         * that is routinely non-ASCII, so guessing UTF-8 unconditionally would
         * mangle exactly the case this exists to serve.
         *
         * Unrelated to the ISO-8859-1 handling in LocalFilePathHandler: that one
         * preserves bytes it is about to hand back, this one has to produce real
         * characters.
         */
        private fun decode(bytes: ByteArray): String {
            bomAt(bytes)?.let { (charset, skip) ->
                return String(bytes, skip, bytes.size - skip, charset)
            }
            // One byte per char, so the ASCII declaration can be found without
            // knowing the encoding it is declaring.
            val sniff = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)
            val declared = META_CHARSET.find(sniff)?.groupValues?.get(1)?.let { charsetOrNull(it) }
            return String(bytes, declared ?: Charsets.UTF_8)
        }

        private fun bomAt(bytes: ByteArray): Pair<Charset, Int>? {
            fun at(vararg expected: Int): Boolean =
                bytes.size >= expected.size &&
                    expected.indices.all { (bytes[it].toInt() and 0xFF) == expected[it] }
            return when {
                at(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
                at(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
                at(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
                else -> null
            }
        }

        private fun charsetOrNull(name: String): Charset? = try {
            if (Charset.isSupported(name)) Charset.forName(name) else null
        } catch (e: Exception) {
            null
        }

        // ------------------------------------------------------------- scanning

        /**
         * Everything before the head ends.
         *
         * Cutting at <svg matters as much as cutting at </head> and <body>: SVG
         * has a <title> element of its own, so a page whose head is never closed
         * would otherwise donate an illustration's label as the app name.
         */
        private fun headRegion(text: String): String {
            val end = HEAD_END.find(text)?.range?.first ?: return text
            return text.substring(0, end)
        }

        private fun titleIn(region: String): String? {
            val raw = TITLE.find(region)?.groupValues?.get(1) ?: return null
            val title = unescape(raw)
                .replace('\u00a0', ' ')
                .replace(WHITESPACE, " ")
                .trim()
                .take(MAX_TITLE_CHARS)
                .trim()
            if (title.isEmpty()) return null
            // Scaffolds and editors emit these. They are not names anyone chose,
            // and the zip's file name is likelier to say something real.
            if (title.lowercase() in GENERIC_TITLES) return null
            return title
        }

        private fun iconsIn(region: String): List<String> {
            val found = ArrayList<Pair<String, Int>>()
            for (tag in LINK_TAG.findAll(region)) {
                val attrs = attributesOf(tag.value)
                val rel = attrs["rel"].orEmpty().lowercase()
                    .split(' ', '\t', '\n', '\r')
                    .filter { it.isNotBlank() }
                if (rel.none { it in ICON_RELS }) continue

                val href = unescape(attrs["href"].orEmpty()).trim()
                if (href.isEmpty()) continue

                val declared = largestSize(attrs["sizes"])
                val size = when {
                    declared > 0 -> declared
                    // apple-touch-icon is 180x180 by convention and almost never
                    // says so, which would otherwise sort it below a 16px favicon.
                    rel.any { it.startsWith("apple-touch-icon") } -> 180
                    else -> 0
                }
                found += href to size
            }
            return found
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
                .take(MAX_ICONS)
        }

        /** "48x48 96x96" -> 96. "any" or missing -> 0. Mirrors WebManifest. */
        private fun largestSize(sizes: String?): Int {
            if (sizes.isNullOrBlank()) return 0
            return sizes.lowercase().split(' ', '\t')
                .mapNotNull { it.substringBefore('x', "").toIntOrNull() }
                .maxOrNull() ?: 0
        }

        /** First occurrence wins, matching how a parser treats a duplicated attribute. */
        private fun attributesOf(tag: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (m in ATTRIBUTE.findAll(tag)) {
                val name = m.groupValues[1].lowercase()
                val value = m.groupValues[2]
                    .ifEmpty { m.groupValues[3] }
                    .ifEmpty { m.groupValues[4] }
                if (!out.containsKey(name)) out[name] = value
            }
            return out
        }

        /**
         * Single pass, so "&amp;lt;" decodes to "&lt;" rather than to "<".
         * Unknown references are left as written.
         */
        private fun unescape(text: String): String = ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.drop(2).toIntOrNull(16)) ?: m.value
                body.startsWith("#") ->
                    codePoint(body.drop(1).toIntOrNull()) ?: m.value
                else -> NAMED_ENTITIES[body.lowercase()] ?: m.value
            }
        }

        private fun codePoint(value: Int?): String? =
            if (value != null && Character.isValidCodePoint(value) && value != 0) {
                String(Character.toChars(value))
            } else {
                null
            }

        // ------------------------------------------------------------- patterns

        private const val SNIFF_BYTES = 1024
        private const val MAX_TITLE_CHARS = 60
        private const val MAX_ICONS = 12

        private val GENERIC_TITLES = setOf(
            "document", "untitled", "untitled document", "index", "index.html"
        )

        /** rel is a token list, so "shortcut icon" is matched by its "icon" token. */
        private val ICON_RELS = setOf(
            "icon", "apple-touch-icon", "apple-touch-icon-precomposed", "mask-icon"
        )

        private val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">",
            "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0"
        )

        private val WHITESPACE = Regex("""\s+""")
        private val HEAD_END = Regex("""</head\b|<body\b|<svg\b""", RegexOption.IGNORE_CASE)
        private val TITLE = Regex(
            """<title\b[^>]*>(.*?)</title\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val ATTRIBUTE = Regex(
            """([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>=]+))"""
        )
        /** Covers <meta charset> and the charset inside an http-equiv content. */
        private val META_CHARSET = Regex(
            """<meta[^>]+charset\s*=\s*["']?\s*([a-zA-Z0-9_\-:.]+)""",
            RegexOption.IGNORE_CASE
        )
        private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);""")
    }
}
