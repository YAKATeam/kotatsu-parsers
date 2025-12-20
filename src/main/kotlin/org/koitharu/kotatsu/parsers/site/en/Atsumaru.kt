package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
@MangaSourceParser("ATSUMARU", "Atsumaru", "en")
internal class Atsumaru(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMARU, 24) {

    override val configKeyDomain = ConfigKey.Domain("atsu.moe")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        // The source code implies generic types (Manga, Manwha, etc) are always fetched.
        // We can expose them as tags if needed, but for now we keep it simple to match the original.
        return MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = emptySet(),
            availableContentRating = emptySet(),
            availableLocales = emptySet()
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Case 1: Search (Uses a different API endpoint)
        if (!filter.query.isNullOrEmpty()) {
            return getSearchPage(page, filter.query)
        }

        // Case 2: Popular / Latest (Uses the infinite API)
        val endpoint = when (order) {
            SortOrder.UPDATED -> "recentlyUpdated"
            else -> "trending" // Default to POPULARITY
        }

        val url = "https://$domain/api/infinite/$endpoint?page=${page - 1}&types=Manga,Manwha,Manhua,OEL"
        val response = webClient.httpGet(url).parseJson()

        val items = response.optJSONArray("items") ?: return emptyList()

        return (0 until items.length()).map { i ->
            parseMangaDto(items.getJSONObject(i))
        }
    }

    private suspend fun getSearchPage(page: Int, query: String): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/collections/manga/documents/search")
            append("?q=${query.urlEncoded()}")
            append("&query_by=title,englishTitle,otherNames")
            append("&limit=$pageSize")
            append("&page=$page")
            append("&query_by_weights=3,2,1")
            append("&include_fields=id,title,englishTitle,poster")
            append("&num_typos=4,3,2")
        }

        val response = webClient.httpGet(url).parseJson()
        val hits = response.optJSONArray("hits") ?: return emptyList()

        return (0 until hits.length()).map { i ->
            // In search results, the actual data is inside a "document" object
            val document = hits.getJSONObject(i).getJSONObject("document")
            parseMangaDto(document)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // 1. Fetch Details
        // The ID in Kotatsu might be generated, so we rely on the URL or the ID if it's the raw slug
        val slug = manga.url // In parseMangaDto, we stored the raw ID/slug as url

        val detailsUrl = "https://$domain/api/manga/page?id=$slug"
        val detailsResponse = webClient.httpGet(detailsUrl).parseJson()
        val mangaPage = detailsResponse.getJSONObject("mangaPage")

        val baseManga = parseMangaDto(mangaPage)

        // 2. Fetch Chapters (Paginated)
        val allChapters = ArrayList<MangaChapter>()
        var page = 0

        while (true) {
            val chaptersUrl = "https://$domain/api/manga/chapters?id=$slug&filter=all&sort=desc&page=$page"
            val response = webClient.httpGet(chaptersUrl).parseJson()
            val chapters = response.optJSONArray("chapters") ?: break

            if (chapters.length() == 0) break

            for (i in 0 until chapters.length()) {
                val ch = chapters.getJSONObject(i)
                val chId = ch.getString("id")
                val number = ch.optDouble("number", 0.0).toFloat()
                val title = ch.optString("title")
                val dateStr = ch.optString("createdAt")

                // Format: slug/chapterId
                val chapterUrl = "$slug/$chId"

                allChapters.add(
                    MangaChapter(
                        id = generateUid(chapterUrl), // Generate unique ID from the composite URL
                        title = title,
                        number = number,
                        volume = 0,
                        url = chapterUrl,
                        uploadDate = parseDate(dateStr),
                        source = source,
                        scanlator = null,
                        branch = null
                    )
                )
            }

            val totalPages = response.optInt("pages", 0)
            val currentPage = response.optInt("page", 0)

            // Check if we reached the last page
            if (currentPage + 1 >= totalPages) break
            page++
        }

        return baseManga.copy(
            chapters = allChapters,
            state = baseManga.state // Preserve the state parsed from details
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (slug, chapterId) = chapter.url.split("/")

        val url = "https://$domain/api/read/chapter?mangaId=$slug&chapterId=$chapterId"
        val response = webClient.httpGet(url).parseJson()

        val pagesArray = response.getJSONObject("readChapter").getJSONArray("pages")

        return (0 until pagesArray.length()).map { i ->
            val page = pagesArray.getJSONObject(i)
            val imagePath = page.getString("image")
            val imageUrl = "https://$domain$imagePath"

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    // ================= Helpers =================

    private fun parseMangaDto(json: JSONObject): Manga {
        val id = json.getString("id")
        val title = json.getString("title")

        // Image path handling (supports object or string in original DTO)
        val imagePathRaw = json.opt("poster") ?: json.opt("image")
        val imagePath = when (imagePathRaw) {
            is JSONObject -> imagePathRaw.optString("image")
            is String -> imagePathRaw
            else -> null
        }?.removePrefix("/")?.removePrefix("static/")

        val coverUrl = if (imagePath != null) "https://$domain/static/$imagePath" else null

        val synopsis = json.optString("synopsis").nullIfEmpty()

        // Status mapping
        val statusStr = json.optString("status")
        val state = when (statusStr.lowercase().trim()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "canceled", "cancelled" -> MangaState.ABANDONED
            else -> null
        }

        // Tags
        val tagsArray = json.optJSONArray("tags")
        val tags = mutableSetOf<MangaTag>()

        // Add "Type" as a tag if present (Manhwa, Manga etc)
        val type = json.optString("type")
        if (type.isNotEmpty()) tags.add(MangaTag(key = type, title = type, source = source))

        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val t = tagsArray.getJSONObject(i)
                val name = t.getString("name")
                tags.add(MangaTag(key = name, title = name, source = source))
            }
        }

        // Authors
        val authorsArray = json.optJSONArray("authors")
        val authors = mutableSetOf<String>()
        if (authorsArray != null) {
            for (i in 0 until authorsArray.length()) {
                val a = authorsArray.getJSONObject(i)
                authors.add(a.getString("name"))
            }
        }

        return Manga(
            id = generateUid(id), // Kotatsu internal UID
            url = id,             // We store the raw slug/ID here for API usage
            publicUrl = "https://$domain/manga/$id",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(), // Could parse englishTitle if needed
            rating = RATING_UNKNOWN,
            tags = tags,
            authors = authors,
            state = state,
            source = source,
            description = synopsis,
            contentRating = ContentRating.SAFE
        )
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
