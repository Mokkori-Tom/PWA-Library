package io.github.mokkori_tom.pwalibrary.install

import org.json.JSONObject

/** The subset of the W3C web app manifest this app actually uses. */
data class WebManifest(
    val id: String? = null,
    val name: String? = null,
    val shortName: String? = null,
    val description: String? = null,
    val startUrl: String? = null,
    /** Read only to spot a build that expects to be served under a subpath. */
    val scope: String? = null,
    val display: String? = null,
    val themeColor: String? = null,
    val version: String? = null,
    val icons: List<Icon> = emptyList()
) {
    data class Icon(val src: String, val maxSize: Int, val type: String?)

    companion object {

        fun parse(json: String): WebManifest? = try {
            val o = JSONObject(json)
            WebManifest(
                id = o.optStringOrNull("id"),
                name = o.optStringOrNull("name"),
                shortName = o.optStringOrNull("short_name"),
                description = o.optStringOrNull("description"),
                startUrl = o.optStringOrNull("start_url"),
                scope = o.optStringOrNull("scope"),
                display = o.optStringOrNull("display"),
                themeColor = o.optStringOrNull("theme_color"),
                // Not part of the spec, but commonly present and useful to show.
                version = o.optStringOrNull("version"),
                icons = parseIcons(o)
            )
        } catch (e: Exception) {
            null
        }

        private fun parseIcons(o: JSONObject): List<WebManifest.Icon> {
            val arr = o.optJSONArray("icons") ?: return emptyList()
            val out = ArrayList<WebManifest.Icon>(arr.length())
            for (i in 0 until arr.length()) {
                val io = arr.optJSONObject(i) ?: continue
                val src = io.optStringOrNull("src") ?: continue
                out += WebManifest.Icon(
                    src = src,
                    maxSize = parseMaxSize(io.optStringOrNull("sizes")),
                    type = io.optStringOrNull("type")
                )
            }
            return out
        }

        /** "48x48 96x96" -> 96. "any" or missing -> 0. */
        private fun parseMaxSize(sizes: String?): Int {
            if (sizes.isNullOrBlank()) return 0
            return sizes.split(' ', '\t').mapNotNull { token ->
                token.substringBefore('x', "").toIntOrNull()
            }.maxOrNull() ?: 0
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).takeIf { it.isNotBlank() }
        }
    }
}
