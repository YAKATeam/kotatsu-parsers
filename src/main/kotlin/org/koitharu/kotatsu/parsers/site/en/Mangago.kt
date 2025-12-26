package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

@MangaSourceParser("MANGAGO", "MangaGo", "en")
internal class MangaGo(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, 24), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("www.mangago.me")

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Cookie", "adult_confirmed=1; stay=1")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchGenres(),
            availableStates = EnumSet.allOf(MangaState::class.java),
        )
    }

    // ---------------------------------------------------------------
    // 1. List / Search
    // ---------------------------------------------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.UPDATED -> "s=1"
            SortOrder.POPULARITY -> "s=9"
            SortOrder.NEWEST -> "s=2"
            SortOrder.ALPHABETICAL -> "s=3"
            else -> "s=1"
        }

        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/r/l_search/?name=${filter.query.urlEncoded()}&page=$page"
        } else {
            val genre = filter.tags.firstOrNull()?.key ?: "All"
            "https://$domain/genre/$genre/$page/?$sortParam"
        }

        val response = webClient.httpGet(url)
        val doc = response.parseHtml()
        val items = doc.select("ul#search_list > li").takeIf { it.isNotEmpty() }
            ?: doc.select("div.listitem").takeIf { it.isNotEmpty() }
            ?: doc.select("div.row")

        return items.mapNotNull { element ->
            val linkEl = element.selectFirst("a.thm-effect") 
                ?: element.selectFirst("h2 a")
                ?: element.selectFirst("h3 a")
                ?: element.selectFirst("a[href*=/read-manga/]")
            
            if (linkEl == null) return@mapNotNull null
            
            val title = linkEl.attr("title").takeIf { it.isNotEmpty() } ?: linkEl.text()
            val href = linkEl.attr("href")
            val relativeUrl = href.toRelativeUrl(domain)
            val absoluteUrl = href.toAbsoluteUrl(domain)
            
            val imgEl = element.selectFirst("img")
            val img = imgEl?.src()

            Manga(
                id = generateUid(relativeUrl),
                url = relativeUrl,
                publicUrl = absoluteUrl,
                coverUrl = img,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    // ---------------------------------------------------------------
    // 2. Details
    // ---------------------------------------------------------------
    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url).parseHtml()

        val infoArea = doc.selectFirst("div.manga_right")
        val title = doc.selectFirst("h1")?.text() ?: manga.title
        val authors = infoArea?.select("td:contains(Author) a")?.map { it.text() }?.toSet() ?: emptySet()
        val genres = infoArea?.select("td:contains(Genre) a")?.map { it.text() }?.toSet() ?: emptySet()
        val statusText = infoArea?.select("td:contains(Status) span")?.text()?.lowercase()
        val summary = doc.select("div.manga_summary").text().removePrefix("Summary:")

        val state = when {
            statusText?.contains("completed") == true -> MangaState.FINISHED
            statusText?.contains("ongoing") == true -> MangaState.ONGOING
            else -> null
        }

        val chapters = doc.select("table#chapter_table tr").mapIndexedNotNull { i, tr ->
            val a = tr.selectFirst("a.chico") ?: return@mapIndexedNotNull null
            val href = a.attr("href")
            val chapterTitle = a.text()
            val dateText = tr.select("td:last-child").text()

            MangaChapter(
                id = generateUid(href),
                title = chapterTitle,
                number = (i + 1).toFloat(),
                volume = 0,
                url = href.toRelativeUrl(domain),
                uploadDate = parseDate(dateText),
                source = source,
                scanlator = null,
                branch = null
            )
        }.reversed()

        val imgEl = doc.selectFirst("div.manga_left img")
        val coverUrl = imgEl?.src()

        return manga.copy(
            title = title,
            authors = authors,
            tags = genres.map { MangaTag(it, it, source) }.toSet(),
            description = summary,
            state = state,
            chapters = chapters,
            coverUrl = coverUrl ?: manga.coverUrl
        )
    }

    // ---------------------------------------------------------------
    // 3. Pages (Ported Logic)
    // ---------------------------------------------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        // 1. Extract base64 encrypted image string
        val scriptContent = doc.select("script:containsData(imgsrcs)").html()
        val imgsrcsBase64 = Regex("""var imgsrcs\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw Exception("Could not find imgsrcs")

        // 2. Get chapter.js URL
        val jsUrl = doc.select("script[src*='chapter.js']").attr("abs:src")
        if (jsUrl.isEmpty()) throw Exception("Chapter JS not found")

        // 3. Fetch and Deobfuscate JS (SoJson v4)
        val rawJsContent = webClient.httpGet(jsUrl).body.string()
        val jsContent = SoJsonV4Deobfuscator.decode(rawJsContent)

        // 4. Extract Keys & IVs
        val keyHex = findHexEncodedVariable(jsContent, "key")
        val ivHex = findHexEncodedVariable(jsContent, "iv")
        
        // 5. AES Decrypt
        var imageListString = decryptAes(imgsrcsBase64, keyHex, ivHex)
        
        // 6. Unscramble String (Character shuffling)
        imageListString = unescrambleImageList(imageListString, jsContent)
        
        // 7. Extract Cols (for image tiles)
        val cols = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""").find(jsContent)?.groupValues?.get(1) ?: "1"

        // 8. Prepare logic for 'cspiclink' images
        val replacePosScript = """
            function replacePos(strObj, pos, replacetext) {
                var str = strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                return str;
            }
        """.trimIndent()

        val imgKeysBody = extractImgKeysBody(jsContent)

        return imageListString.split(",").map { url ->
            var finalUrl = url
            // If image is protected (cspiclink), generate the desckey
            if (url.contains("cspiclink")) {
                try {
                    val script = """
                        $replacePosScript
                        function getDescramblingKey(url) { 
                            var key = '';
                            $imgKeysBody
                            return key; 
                        }
                        getDescramblingKey('$url');
                    """.trimIndent()
                    
                    val key = context.evaluateJs("https://$domain", script, 30000L)
                    if (!key.isNullOrBlank()) {
                        finalUrl = "$url#desckey=$key&cols=$cols"
                    }
                } catch (e: Exception) {
                    // Fallback to original url
                }
            }

            MangaPage(
                id = generateUid(finalUrl),
                url = finalUrl,
                preview = null,
                source = source
            )
        }
    }

    // ---------------------------------------------------------------
    // 4. Image Interceptor (Bitmap Descrambling)
    // ---------------------------------------------------------------
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        
        if (url.contains("mangapicgallery.com")) {
            val newRequest = request.newBuilder()
                .header("Referer", "https://$domain/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            return chain.proceed(newRequest)
        }

        val response = chain.proceed(request)
        val frag = request.url.fragment ?: return response

        // Trigger redraw only if desckey is present
        if (frag.contains("desckey=")) {
            return context.redrawImageResponse(response) { bitmap ->
                val desckey = frag.substringAfter("desckey=").substringBefore("&")
                val cols = frag.substringAfter("cols=").toIntOrNull() ?: 1
                descramble(bitmap, desckey, cols)
            }
        }
        return response
    }

    private fun descramble(bitmap: Bitmap, desckey: String, cols: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = context.createBitmap(width, height)

        val unitWidth = width / cols
        val unitHeight = height / cols
        
        val keyArray = desckey.split("a")

        for (idx in 0 until (cols * cols)) {
            val keyVal = keyArray.getOrNull(idx)?.ifEmpty { "0" }?.toIntOrNull() ?: 0

            // Logic ported from Tachiyomi 'unscrambleImage'
            val heightY = keyVal.floorDiv(cols)
            val dy = heightY * unitHeight
            val dx = (keyVal - heightY * cols) * unitWidth

            val widthY = idx.floorDiv(cols)
            val sy = widthY * unitHeight
            val sx = (idx - widthY * cols) * unitWidth
            
            val srcRect = Rect(sx, sy, sx + unitWidth, sy + unitHeight)
            val dstRect = Rect(dx, dy, dx + unitWidth, dy + unitHeight)

            result.drawBitmap(bitmap, srcRect, dstRect)
        }
        return result
    }

    // ---------------------------------------------------------------
    // Helpers & Deobfuscation Logic
    // ---------------------------------------------------------------

    object SoJsonV4Deobfuscator {
        private val splitRegex: Regex = Regex("""[a-zA-Z]+""")

        fun decode(jsf: String): String {
            if (!jsf.startsWith("['sojson.v4']")) {
                 return jsf // Not obfuscated or unknown format
            }
            // Tachiyomi uses strict substrings. Kotlin ranges are 0-based.
            // Tachiyomi: substring(240, jsf.length - 59)
            val end = jsf.length - 59
            if (end <= 240) return jsf

            val args = jsf.substring(240, end).split(splitRegex)
            return args.mapNotNull { it.toIntOrNull()?.toChar() }.joinToString("")
        }
    }

    private fun findHexEncodedVariable(input: String, variable: String): String {
        val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
        return regex.find(input)?.groupValues?.get(1) ?: ""
    }

    private fun extractImgKeysBody(deobfChapterJs: String): String {
        val startMarker = "var renImg = function(img,width,height,id){"
        val endMarker = "key = key.split("
        
        val body = deobfChapterJs
            .substringAfter(startMarker, "")
            .substringBefore(endMarker, "")
            
        if (body.isEmpty()) return ""

        // Tachiyomi filters these lines out to make it pure logic
        val jsFilters = listOf("jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height")
        
        return body.lineSequence()
            .filter { line -> jsFilters.none { line.contains(it) } }
            .joinToString("\n")
            .replace("img.src", "url")
    }

    private fun decryptAes(b64: String, hexKey: String, hexIv: String): String {
        try {
            val keyBytes = hexStringToByteArray(hexKey)
            val ivBytes = hexStringToByteArray(hexIv)
            val inputBytes = Base64.getDecoder().decode(b64)

            // Tachiyomi uses AES/CBC/ZEROBYTEPADDING via BouncyCastle usually.
            // Standard Java 'NoPadding' works if data is aligned, or 'PKCS5Padding' if standard.
            // Since we receive a clean string, NoPadding usually suffices for this source.
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(inputBytes)

            return String(decryptedBytes, Charsets.UTF_8).trim { it <= '\u0020' }
        } catch (e: Exception) {
            throw Exception("Failed to decrypt image list: ${e.message}")
        }
    }

    private fun unescrambleImageList(imageList: String, js: String): String {
        var imgList = imageList
        val keyLocationRegex = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
        try {
            val keyLocations = keyLocationRegex.findAll(js).map {
                it.groupValues[1].toInt()
            }.distinct().toList()

            val unscrambleKey = keyLocations.map {
                imgList[it].toString().toInt()
            }.toList()

            keyLocations.forEachIndexed { idx, it ->
                imgList = imgList.removeRange(it - idx..it - idx)
            }

            imgList = unscrambleString(imgList, unscrambleKey)
        } catch (e: Exception) {
            // ignore
        }
        return imgList.trim { it <= '\u0020' }
    }

    private fun unscrambleString(str: String, keys: List<Int>): String {
        val sb = StringBuilder(str)
        keys.reversed().forEach { key ->
            for (i in sb.length - 1 downTo key) {
                if (i % 2 != 0) {
                    val idxA = i - key
                    val idxB = i
                    val temp = sb[idxA]
                    sb.setCharAt(idxA, sb[idxB])
                    sb.setCharAt(idxB, temp)
                }
            }
        }
        return sb.toString()
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun parseDate(dateStr: String): Long {
        val format = if (dateStr.contains(",")) "MMM d, yyyy" else "MMM d yyyy"
        return SimpleDateFormat(format, Locale.ENGLISH).parseSafe(dateStr)
    }

    private fun fetchGenres(): Set<MangaTag> {
        val genres = listOf(
            "Action", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi", "Fantasy",
            "Gender Bender", "Harem", "Historical", "Horror", "Josei", "Martial Arts",
            "Mature", "Mecha", "Mystery", "One Shot", "Psychological", "Romance",
            "School Life", "Sci-fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen",
            "Shounen Ai", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy",
            "Webtoons", "Yaoi", "Yuri"
        )
        return genres.map { MangaTag(it, it, source) }.toSet()
    }
}
