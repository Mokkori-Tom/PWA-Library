import com.example.pwalibrary.install.HtmlHead

/**
 * Checks HtmlHead without a device or a Gradle build.
 *
 * HtmlHead is the one part of the installer that is pure text handling, and the
 * one whose failures are silent — a title that simply does not appear, an icon
 * link that is quietly skipped. Run by tools/typecheck.sh.
 */

var pass = 0
var fail = 0

fun check(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        pass++
        println("  ok    $label")
    } else {
        fail++
        println("  FAIL  $label")
        println("          expected: $expected")
        println("          actual:   $actual")
    }
}

fun title(html: String): String? = HtmlHead.parse(html.toByteArray(Charsets.UTF_8)).title
fun icons(html: String): List<String> = HtmlHead.parse(html.toByteArray(Charsets.UTF_8)).iconHrefs

fun main() {
    println("--- title ---")
    check("plain", title("<html><head><title>Field Survey</title></head><body>"), "Field Survey")
    check(
        "japanese + entity",
        title("<html><head><meta charset=\"utf-8\"><title>現場メモ &amp; ログ</title></head>"),
        "現場メモ & ログ"
    )
    check("whitespace collapsed", title("<head><title>\n  Two   Words\n</title></head>"), "Two Words")
    check("generic 'Document' rejected", title("<head><title>Document</title></head>"), null)
    check("generic 'index' rejected", title("<head><title>  INDEX </title></head>"), null)
    check("blank rejected", title("<head><title>   </title></head>"), null)
    check("missing", title("<head><meta charset=utf-8></head>"), null)
    check("unterminated title", title("<head><title>oops</head>"), null)
    check("title in body ignored", title("<head></head><body><title>Body</title>"), null)
    check("svg title not stolen", title("<html><svg><title>logo</title></svg>"), null)
    check("attributes on tag", title("<head><title data-x=\"1\">Named</title></head>"), "Named")
    check("uppercase tag", title("<HEAD><TITLE>Shouty</TITLE></HEAD>"), "Shouty")
    check("numeric entity", title("<head><title>Bob&#39;s &#x26; Co</title></head>"), "Bob's & Co")
    check("single pass entity", title("<head><title>&amp;lt;</title></head>"), "&lt;")
    check("unknown entity kept", title("<head><title>a &bogus; b</title></head>"), "a &bogus; b")
    check("nbsp becomes space", title("<head><title>a&nbsp;&nbsp;b</title></head>"), "a b")
    check(
        "60 char cap",
        title("<head><title>" + "x".repeat(80) + "</title></head>"),
        "x".repeat(60)
    )
    check("empty input", HtmlHead.parse(ByteArray(0)).title, null)

    println("--- charset ---")
    val sjis = "<html><head><meta charset=\"Shift_JIS\"><title>現場メモ</title></head>"
    check("shift_jis via meta", HtmlHead.parse(sjis.toByteArray(charset("Shift_JIS"))).title, "現場メモ")
    val httpEquiv = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=EUC-JP\">" +
        "<title>現場メモ</title></head>"
    check("euc-jp via http-equiv", HtmlHead.parse(httpEquiv.toByteArray(charset("EUC-JP"))).title, "現場メモ")
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        "<head><title>現場</title></head>".toByteArray(Charsets.UTF_8)
    check("utf-8 BOM", HtmlHead.parse(bom).title, "現場")
    check(
        "bogus charset falls back to utf-8",
        title("<head><meta charset=\"not-a-charset\"><title>現場</title></head>"),
        "現場"
    )

    println("--- icons ---")
    check(
        "sizes descending",
        icons(
            "<head>" +
                "<link rel=\"icon\" sizes=\"32x32\" href=\"small.png\">" +
                "<link rel=\"icon\" sizes=\"512x512\" href=\"big.png\">" +
                "<link rel=\"icon\" sizes=\"192x192\" href=\"mid.png\">" +
                "</head>"
        ),
        listOf("big.png", "mid.png", "small.png")
    )
    check(
        "shortcut icon token",
        icons("<head><link rel=\"shortcut icon\" href=\"favicon.ico\"></head>"),
        listOf("favicon.ico")
    )
    check(
        "apple-touch-icon defaults to 180",
        icons(
            "<head>" +
                "<link rel=\"icon\" sizes=\"16x16\" href=\"tiny.png\">" +
                "<link rel=\"apple-touch-icon\" href=\"touch.png\">" +
                "</head>"
        ),
        listOf("touch.png", "tiny.png")
    )
    check(
        "stylesheet ignored",
        icons("<head><link rel=\"stylesheet\" href=\"app.css\"><link rel=\"icon\" href=\"a.png\"></head>"),
        listOf("a.png")
    )
    check(
        "unquoted and uppercase",
        icons("<HEAD><LINK REL=ICON HREF=a.png></HEAD>"),
        listOf("a.png")
    )
    check(
        "single quotes",
        icons("<head><link rel='icon' href='a b.png'></head>"),
        listOf("a b.png")
    )
    check(
        "entity in href",
        icons("<head><link rel=\"icon\" href=\"i.png?a=1&amp;b=2\"></head>"),
        listOf("i.png?a=1&b=2")
    )
    check(
        "duplicates collapsed",
        icons("<head><link rel=\"icon\" href=\"a.png\"><link rel=\"apple-touch-icon\" href=\"a.png\"></head>"),
        listOf("a.png")
    )
    check(
        "href missing skipped",
        icons("<head><link rel=\"icon\"><link rel=\"icon\" href=\"\"></head>"),
        emptyList<String>()
    )
    check(
        "data uri passes through",
        icons("<head><link rel=\"icon\" href=\"data:image/png;base64,AAA\"></head>"),
        listOf("data:image/png;base64,AAA")
    )
    check(
        "body links ignored",
        icons("<head></head><body><link rel=\"icon\" href=\"late.png\">"),
        emptyList<String>()
    )
    check(
        "sizes=any treated as unsized",
        icons(
            "<head>" +
                "<link rel=\"icon\" sizes=\"any\" href=\"vec.png\">" +
                "<link rel=\"icon\" sizes=\"48x48\" href=\"raster.png\">" +
                "</head>"
        ),
        listOf("raster.png", "vec.png")
    )

    println()
    println("pass=$pass fail=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
