package org.koitharu.kotatsu.parsers.site.natsu

import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

/**
 * Base parser for NatsuId WordPress theme
 * Theme: https://themesinfo.com/natsu_id-theme-wordpress-c8x1c
 * Author: Dzul Qurnain
 */
internal abstract class NatsuParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    pageSize: Int = 24,
) : PagedMangaParser(context, source, pageSize, pageSize) {

    override val sourceLocale: Locale = Locale.ENGLISH

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
            ContentType.NOVEL,
        ),
    )

    private var nonce: String? = null

    private suspend fun getNonce(): String {
        if (nonce == null) {
            val json =
                webClient.httpGet("https://${domain}/wp-admin/admin-ajax.php?type=search_form&action=get_nonce")
            val html = json.parseHtml()
            val nonceValue = html.select("input[name=search_nonce]").attr("value")
            nonce = nonceValue
        }
        return nonce!!
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://${domain}/wp-admin/admin-ajax.php?action=advanced_search"

        val formParts = mutableMapOf<String, String>()
        formParts["nonce"] = getNonce()

        formParts["inclusion"] = "OR"
        if (filter.tags.isNotEmpty()) {
            val genreArray = JSONArray(filter.tags.map { it.key })
            formParts["genre"] = genreArray.toString()
        } else formParts["genre"] = "[]"

        formParts["exclusion"] = "OR"
        if (filter.tagsExclude.isNotEmpty()) {
            val exGenreArray = JSONArray(filter.tagsExclude.map { it.key })
            formParts["genre_exclude"] = exGenreArray.toString()
        } else formParts["genre_exclude"] = "[]"

        formParts["page"] = page.toString()

        if (!filter.author.isNullOrEmpty()) {
            val authorArray = JSONArray(filter.author)
            formParts["author"] = authorArray.toString()
        } else formParts["author"] = "[]"

        formParts["artist"] = "[]"
        formParts["project"] = "0"

        if (filter.types.isNotEmpty()) {
            val typeArray = JSONArray()
            filter.types.forEach { type ->
                when (type) {
                    ContentType.MANGA -> typeArray.put("manga")
                    ContentType.MANHWA -> typeArray.put("manhwa")
                    ContentType.MANHUA -> typeArray.put("manhua")
                    ContentType.COMICS -> typeArray.put("comic")
                    ContentType.NOVEL -> typeArray.put("novel")
                    else -> {}
                }
            }
            formParts["type"] = typeArray.toString()
        } else {
            formParts["type"] = "[]"
        }

        if (filter.states.isNotEmpty()) {
            val statusArray = JSONArray()
            filter.states.forEach { state ->
                when (state) {
                    MangaState.ONGOING -> statusArray.put("ongoing")
                    MangaState.FINISHED -> statusArray.put("completed")
                    MangaState.PAUSED -> statusArray.put("on-hiatus")
                    else -> {}
                }
            }
            formParts["status"] = statusArray.toString()
        } else {
            formParts["status"] = "[]"
        }

        formParts["order"] = "desc"
        formParts["orderby"] = when (order) {
            SortOrder.UPDATED -> "updated"
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.RATING -> "rating"
            else -> "popular"
        }

        if (!filter.query.isNullOrEmpty()) {
            formParts["query"] = filter.query
        }

        val html = httpPost(url, formParts)
        return parseMangaList(html)
    }

    protected open fun parseMangaList(doc: Document): List<Manga> {
        val mangaList = mutableListOf<Manga>()

        doc.select("body > div").forEach { divElement ->
            val mainLink = divElement.selectFirst("a[href*='/manga/']") ?: return@forEach
            val href = mainLink.attrAsRelativeUrl("href")

            if (href.contains("/chapter-")) return@forEach

            val title = divElement.selectFirst("a.text-base, a.text-white, h1")?.text()?.trim()
                ?: mainLink.attr("title").ifEmpty { mainLink.text() }

            val coverUrl = divElement.selectFirst("img")?.src()

            val ratingText = divElement.selectFirst(".numscore, span.text-yellow-400")?.text()
            val rating = ratingText?.toFloatOrNull()?.let {
                if (it > 5) it / 10f else it / 5f
            } ?: RATING_UNKNOWN

            val stateText =
                divElement.selectFirst("span.bg-accent, p:contains(Ongoing), p:contains(Completed)")
                    ?.text()?.lowercase()
            val state = when {
                stateText?.contains("ongoing") == true -> MangaState.ONGOING
                stateText?.contains("completed") == true -> MangaState.FINISHED
                stateText?.contains("hiatus") == true -> MangaState.PAUSED
                else -> null
            }

            mangaList.add(
                Manga(
                    id = generateUid(href),
                    url = href,
                    title = title,
                    altTitles = emptySet(),
                    publicUrl = mainLink.attrAsAbsoluteUrl("href"),
                    rating = rating,
                    contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                    coverUrl = coverUrl,
                    tags = emptySet(),
                    state = state,
                    authors = emptySet(),
                    source = source,
                ),
            )
        }

        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        // Manga ID for chapter loading
        val mangaId = doc.selectFirst("[hx-get*='manga_id=']")
            ?.attr("hx-get")
            ?.substringAfter("manga_id=")
            ?.substringBefore("&")
            ?.trim()
            ?: doc.selectFirst("input#manga_id, [data-manga-id]")
                ?.let { it.attr("value").ifEmpty { it.attr("data-manga-id") } }
            ?: manga.url.substringAfterLast("/manga/").substringBefore("/")

        val titleElement = doc.selectFirst("h1[itemprop=name]")
        val title = titleElement?.text() ?: manga.title

        val altTitles = titleElement?.nextElementSibling()?.text()
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            ?.toSet()
            ?: emptySet()

        val description = doc.select("div[itemprop=description]")
            .joinToString("\n\n") { it.text() }
            .trim()
            .takeIf { it.isNotBlank() }

        val coverUrl = doc.selectFirst("div[itemprop=image] > img")?.src()
            ?: manga.coverUrl

        val tags = doc.select("a[itemprop=genre]").mapNotNullToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast("/genre/").removeSuffix("/"),
                title = a.text().toTitleCase(),
                source = source,
            )
        }

        fun findInfoText(key: String): String? {
            return doc.select("div.space-y-2 > .flex:has(h4)")
                .find { it.selectFirst("h4")?.text()?.contains(key, ignoreCase = true) == true }
                ?.selectFirst("p.font-normal")?.text()
        }

        val stateText = findInfoText("Status")?.lowercase()
        val state = when {
            stateText?.contains("ongoing") == true -> MangaState.ONGOING
            stateText?.contains("completed") == true -> MangaState.FINISHED
            stateText?.contains("hiatus") == true -> MangaState.PAUSED
            else -> manga.state
        }

        val authors = findInfoText("Author")
            ?.split(",")
            ?.map { it.trim() }
            ?.toSet() ?: emptySet()

        val chapters = loadChapters(mangaId, manga.url.toAbsoluteUrl(domain))

        return manga.copy(
            title = title,
            altTitles = altTitles,
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            state = state,
            authors = authors,
            chapters = chapters,
        )
    }

    protected open suspend fun loadChapters(
        mangaId: String,
        mangaAbsoluteUrl: String,
    ): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1

        val headers = Headers.Companion.headersOf(
            "hx-request", "true",
            "hx-target", "chapter-list",
            "hx-trigger", "getChapterList",
            "Referer", mangaAbsoluteUrl,
        )

        while (true) {
            val url = "https://${domain}/wp-admin/admin-ajax.php?manga_id=$mangaId&page=$page&action=chapter_list"
            val doc = try {
                webClient.httpGet(url, headers).parseHtml()
            } catch (e: Exception) {
                break
            }

            val chapterElements = doc.select("div#chapter-list > div[data-chapter-number]")
            if (chapterElements.isEmpty()) break

            chapterElements.forEach { element ->
                val a = element.selectFirst("a") ?: return@forEach
                val href = a.attrAsRelativeUrl("href")
                if (href.isBlank()) return@forEach

                val chapterTitle = element.selectFirst("div.font-medium span")?.text()?.trim() ?: ""
                val dateText = element.selectFirst("time")?.text()
                val number = element.attr("data-chapter-number").toFloatOrNull() ?: -1f

                chapters.add(
                    MangaChapter(
                        id = generateUid(href),
                        title = chapterTitle,
                        url = href,
                        number = number,
                        volume = 0,
                        scanlator = null,
                        uploadDate = parseDate(dateText),
                        branch = null,
                        source = source,
                    ),
                )
            }
            page++
            if (page > 100) break
        }
        return chapters.distinctBy { it.url }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("main section section > img").map { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
        return try {
            // Try to fetch from WP JSON API first (more reliable)
            val response = webClient.httpGet("https://${domain}/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc")
            val jsonText = response.body.use { it?.string() } ?: return emptySet()
            val jsonArray = org.json.JSONArray(jsonText)
            val tags = mutableSetOf<MangaTag>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue

                tags += MangaTag(
                    title = name.toTitleCase(),
                    key = slug,
                    source = source
                )
            }
            tags
        } catch (e: Exception) {
            // Fallback to advanced-search page method
            try {
                val doc = webClient.httpGet("https://${domain}/advanced-search/").parseHtml()
                val scriptContent = doc.select("script")
                    .firstOrNull { it.data().contains("var searchTerms") }
                    ?.data()
                    ?: return emptySet()

                val jsonString = scriptContent
                    .substringAfter("var searchTerms =")
                    .substringBeforeLast(";")
                    .trim()

                val json = org.json.JSONObject(jsonString)
                val genreObject = json.optJSONObject("genre") ?: return emptySet()
                val tags = mutableSetOf<MangaTag>()

                for (key in genreObject.keys()) {
                    val item = genreObject.optJSONObject(key) ?: continue
                    val taxonomy = item.optString("taxonomy")
                    if (taxonomy != "genre") continue
                    val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue

                    tags += MangaTag(
                        title = name.toTitleCase(),
                        key = slug,
                        source = source
                    )
                }
                tags
            } catch (e2: Exception) {
                emptySet()
            }
        }
    }

    protected open fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0

        return try {
            when {
                dateStr.contains("ago") -> {
                    val number = Regex("""(\d+)""").find(dateStr)?.value?.toIntOrNull() ?: return 0
                    val cal = Calendar.getInstance()
                    when {
                        dateStr.contains("min") -> cal.apply { add(Calendar.MINUTE, -number) }
                        dateStr.contains("hour") -> cal.apply { add(Calendar.HOUR, -number) }
                        dateStr.contains("day") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }
                        dateStr.contains("week") -> cal.apply {
                            add(
                                Calendar.WEEK_OF_YEAR,
                                -number,
                            )
                        }

                        dateStr.contains("month") -> cal.apply { add(Calendar.MONTH, -number) }
                        dateStr.contains("year") -> cal.apply { add(Calendar.YEAR, -number) }
                        else -> cal
                    }.timeInMillis
                }

                else -> {
                    SimpleDateFormat("MMM dd, yyyy", sourceLocale).parse(dateStr)?.time ?: 0
                }
            }
        } catch (_: Exception) {
            0
        }
    }

    // Utils
    private val multipartHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    protected open suspend fun httpPost(url: String, form: Map<String, String>, extraHeaders: Headers? = null): Document {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        form.forEach { (k, v) -> body.addFormDataPart(k, v) }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.build())
            .addHeader("Referer", "https://${domain}/advanced-search/")
            .addHeader("Origin", "https://${domain}")

        if (extraHeaders != null) {
            for (name in extraHeaders.names()) {
                if (!name.equals("Content-Type", ignoreCase = true)) {
                    val value = extraHeaders.get(name) ?: continue
                    requestBuilder.addHeader(name, value)
                }
            }
        }

        val request = requestBuilder.build()
        val response = multipartHttpClient.newCall(request).await()
        return response.parseHtml()
    }
}
