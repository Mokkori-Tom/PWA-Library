package com.example.pwalibrary.install

/**
 * The path arithmetic the installer does before anything is written.
 *
 * Kept free of android.* for the same reason [HtmlHead] is: both decide things
 * whose failure is silent on a device — a manifest that is simply never found,
 * an app that opens to a blank page — and finding that out on the phone costs a
 * round trip. Compiled and exercised on the desktop JVM by tools/typecheck.sh.
 */
object InstallPaths {

    /**
     * A build deployed under a subpath nests a few levels at most. The cap is
     * here so a manifest cannot ask for an arbitrarily deep tree.
     */
    private const val MAX_SUBPATH_SEGMENTS = 4

    /**
     * Resolves an href written in the root index.html to the name of a zip
     * entry, or null when it does not name one.
     *
     * Refused: another origin (`https://…`, `//cdn…`, `data:`), and anything
     * containing `..`. The second is blunt — some of those would land back
     * inside the bundle — but a manifest link that climbs is unheard of, and
     * the cost of being wrong here is reading a file the zip did not offer.
     * Being refused only means falling back to the behaviour that existed
     * before the link was followed at all.
     */
    fun entryForHref(rootPrefix: String, href: String): String? {
        val path = href.trim().substringBefore('?').substringBefore('#')
        if (path.isEmpty()) return null
        if (path.startsWith("//") || hasScheme(path)) return null

        // A site-absolute href points at the root of what gets served, which is
        // the root of the bundle.
        val segments = ArrayList<String>()
        for (segment in path.removePrefix("/").split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> return null
                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return null
        return rootPrefix + segments.joinToString("/")
    }

    /**
     * The directory a build expects to be served from, when the zip does not
     * already contain it. "" when the app belongs at the root, which is almost
     * always.
     *
     * A build made with a base of `/app/` emits `/app/assets/…` throughout, but
     * the zip is still made from the output directory, so those paths resolve
     * to nothing and the app opens blank. Extracting one level down puts the
     * files where the build already believes they are, which costs nothing for
     * every other app and needs no special case in the serving path.
     *
     * [existingTopLevel] guards the ambiguous case: a zip that really does
     * contain the directory it names is taken at its word and left alone.
     */
    fun installSubpath(
        scope: String?,
        startUrl: String?,
        existingTopLevel: Set<String>
    ): String {
        val segments = declaredDirectory(scope) ?: declaredDirectory(startUrl) ?: return ""
        if (segments.first() in existingTopLevel) return ""
        return segments.joinToString("/") + "/"
    }

    /**
     * The directory part of a site-absolute manifest URL, as path segments.
     *
     * Only site-absolute values count. A relative `scope` ("." is the common
     * one) is relative to the manifest, which already sits at the root, so it
     * asks for nothing. A full URL is ignored rather than parsed: builds that
     * deploy to a subpath write the path form, and guessing at an authority
     * would be reasoning about a host this app never talks to.
     */
    private fun declaredDirectory(raw: String?): List<String>? {
        val value = raw?.trim()?.substringBefore('?')?.substringBefore('#') ?: return null
        if (!value.startsWith("/") || value.startsWith("//")) return null

        val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null

        // "/app/" is a directory; "/app/index.html" names a file inside one.
        val segments = if (value.endsWith("/")) parts else parts.dropLast(1)
        if (segments.isEmpty() || segments.size > MAX_SUBPATH_SEGMENTS) return null
        return segments
    }

    /** True for "https:", "data:", "mailto:" — but not for a path like "a/b:c". */
    private fun hasScheme(value: String): Boolean {
        val colon = value.indexOf(':')
        if (colon <= 0) return false
        val slash = value.indexOf('/')
        if (slash in 0 until colon) return false
        if (!value[0].isLetter()) return false
        return value.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }
}
