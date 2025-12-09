package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    private val apiBase = "api/v2"

    // Dynamic API URL based on user settings
    private val apiBaseUrl get() = "https://$domain/$apiBase"

    private val pageLimitPerRequest = 100
    private val DEFAULT_MAX_PAGES = 200
    private val DEFAULT_MAX_UNIQUE = 1500 // Increased safety cap

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true // FIX 1: Enabled Exclusion
        )

    override val availableSortOrders: Set<SortOrder> = linkedSetOf(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags()
    )

    // -------------------------
    // Helpers
    // -------------------------
    private fun buildApiUrl(path: String): HttpUrl.Builder =
        "$apiBaseUrl/$path".toHttpUrl().newBuilder()

    private fun safeBuildMangaId(hashId: String?): String =
        hashId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    private fun extractHashFromUrl(url: String?): String? =
        url?.substringAfter("/title/")?.substringBefore("/")?.nullIfEmpty()

    // -------------------------
    // List / Search
    // -------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val builder = buildApiUrl("manga").apply {
            if (!filter.query.isNullOrBlank()) addQueryParameter("keyword", filter.query)
            val (param, dir) = when (order) {
                SortOrder.RELEVANCE -> "relevance" to "desc"
                SortOrder.UPDATED -> "chapter_updated_at" to "desc"
                SortOrder.POPULARITY -> "views_30d" to "desc"
                SortOrder.NEWEST -> "created_at" to "desc"
                SortOrder.ALPHABETICAL -> "title" to "asc"
                else -> "chapter_updated_at" to "desc"
            }
            addQueryParameter("order[$param]", dir)

            // FIX 1: Handle inclusions AND exclusions
            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach { addQueryParameter("genres[]", it.key) }
            }
            
            if (filter.tagsExclude.isNotEmpty()) {
                filter.tagsExclude.forEach { addQueryParameter("genres[]", "-${it.key}") }
            }

            // Default exclusions only if no filters are applied (matches web behavior)
            if (filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) {
                listOf("87264", "87266", "87268", "87265").forEach { addQueryParameter("genres[]", "-$it") }
            }

            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("page", page.toString())
        }

        val response = webClient.httpGet(builder.build()).parseJson()

        val items = response.optJSONObject("result")?.optJSONArray("items") ?: return emptyList()
        val list = ArrayList<Manga>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            list.add(parseMangaFromJson(it))
        }
        return list
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.optString("hash_id", "").nullIfEmpty()
        val title = json.optString("title", "Unknown")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        val coverUrl = poster?.optString("medium", "")?.nullIfEmpty()
            ?: poster?.optString("large", "")?.nullIfEmpty()
            ?: ""

        val state = when (json.optString("status", "").lowercase()) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        val ratedAvg = json.optDouble("rated_avg", 0.0)
        val rating = if (ratedAvg > 0.0) (ratedAvg / 10.0f).toFloat() else RATING_UNKNOWN

        val resolvedHash = safeBuildMangaId(hashId)
        return Manga(
            id = generateUid(resolvedHash),
            url = "/title/${hashId ?: resolvedHash}",
            publicUrl = "https://$domain/title/${hashId ?: resolvedHash}",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = rating,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = if (json.optBoolean("is_nsfw", false)) ContentRating.ADULT else ContentRating.SAFE
        )
    }

    // -------------------------
    // Details
    // -------------------------
    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val hash = extractHashFromUrl(manga.url) ?: extractHashFromUrl(manga.publicUrl)
        ?: throw ParseException("Invalid manga URL for $domain", manga.url)
        
        val detailsUrl = buildApiUrl("manga/$hash").build()
        val detailsDeferred = async { webClient.httpGet(detailsUrl).parseJson() }
        val chaptersDeferred = async { getChapters(manga, DEFAULT_MAX_PAGES, DEFAULT_MAX_UNIQUE) }

        val response = try {
            detailsDeferred.await()
        } catch (e: Exception) {
            JSONObject()
        }
        val chapters = try {
            chaptersDeferred.await()
        } catch (e: Exception) {
            emptyList<MangaChapter>()
        }

        val result = response.optJSONObject("result")
        if (result != null) {
            val updated = parseMangaFromJson(result)
            val authors = result.optJSONArray("author")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title")?.nullIfEmpty() }.toSet()
            } ?: emptySet()

            val tags = mutableSetOf<MangaTag>()
            fun addTags(field: String) {
                result.optJSONArray(field)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("title", "").nullIfEmpty() ?: continue
                        val id = o.optInt("term_id", 0).takeIf { it != 0 }?.toString() ?: continue
                        tags.add(MangaTag(key = id, title = name, source = source))
                    }
                }
            }
            addTags("genre"); addTags("theme"); addTags("demographic")
            result.optString("type", "").nullIfEmpty()?.let {
                tags.add(MangaTag(key = it, title = it.replaceFirstChar { ch -> ch.titlecase() }, source = source))
            }

            return@coroutineScope updated.copy(chapters = chapters, authors = authors, tags = tags)
        }

        return@coroutineScope manga.copy(chapters = chapters)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    // -------------------------
    // Efficient chapter streaming (FIX 2: SHOW ALL SCANLATORS)
    // -------------------------
    private suspend fun getChapters(
        manga: Manga,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxUnique: Int = DEFAULT_MAX_UNIQUE
    ): List<MangaChapter> {
        val hashId = extractHashFromUrl(manga.url) ?: throw ParseException("Invalid manga URL for $domain", manga.url)

        val allItems = ArrayList<JSONObject>()

        var page = 1
        var lastPage = Int.MAX_VALUE
        // Use a loop to fetch all pages, but do NOT deduplicate
        while (page <= lastPage && page <= maxPages && allItems.size < maxUnique) {
            val url = buildApiUrl("manga/$hashId/chapters").apply {
                addQueryParameter("order[number]", "desc")
                addQueryParameter("limit", pageLimitPerRequest.toString())
                addQueryParameter("page", page.toString())
            }.build()

            val resp = try {
                webClient.httpGet(url).parseJson()
            } catch (e: Exception) {
                break
            }
            
            val items = resp.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                allItems.add(item)
            }

            val pagination = resp.optJSONObject("result")?.optJSONObject("pagination")
            if (pagination != null) {
                lastPage = pagination.optInt("last_page", page)
            } else {
                if (items.length() < pageLimitPerRequest) lastPage = page
            }
            page++
        }

        // Map everything to chapters. We DO NOT use a map to overwrite keys.
        val chapters = allItems.mapNotNull { item ->
            val num = item.optDouble("number", Double.NaN)
            if (num.isNaN()) return@mapNotNull null

            val chapterId = item.optLong("chapter_id", 0L)
            val number = num.toFloat()
            val name = item.optString("name", "").nullIfEmpty()
            val createdAt = item.optLong("created_at", 0L)
            val scanlationGroup = item.optJSONObject("scanlation_group")
            val scanlatorName = scanlationGroup?.optString("name", null)?.nullIfEmpty()
            val groupId = item.optInt("scanlation_group_id", 0)

            val title = buildString {
                if (item.optString("volume", "0") != "0") append("Vol. ${item.optString("volume")} ")
                append("Ch. ${if (number == number.toLong().toFloat()) number.toLong() else number}")
                if (name != null) append(" - ").append(name)
                // Add scanlator to title so user can distinguish them in the list
                if (scanlatorName != null) append(" [").append(scanlatorName).append("]")
            }.trim()

            // FIX 2: Unique ID based on ChapterID + GroupID allows duplicates in list
            val uid = if (chapterId != 0L) "$chapterId-$groupId" else UUID.randomUUID().toString()
            val branchVal = if (groupId != 0) groupId.toString() else null
            
            MangaChapter(
                id = generateUid(uid),
                title = if (title.isNotBlank()) title else "Chapter ${if (number == number.toLong().toFloat()) number.toLong() else number}",
                number = number,
                volume = item.optString("volume", "0").toIntOrNull() ?: 0,
                url = "/title/$hashId/$chapterId-chapter-${number.toInt()}",
                uploadDate = createdAt * 1000L,
                source = source,
                scanlator = scanlatorName,
                branch = branchVal
            )
        }

        // Return standard list (usually reversed if source sends DESC, but we are just passing through)
        return chapters
    }

    // -------------------------
    // Pages
    // -------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-").nullIfEmpty()
            ?: throw ParseException("Invalid chapter URL for $domain: ${chapter.url}", chapter.url)

        // Try API first
        try {
            val apiPages = getPagesFromApi(chapterId)
            if (apiPages.isNotEmpty()) return apiPages
        } catch (e: Exception) {
            // Fallback
        }

        // HTML Scraping Fallback
        val chapterUrl = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(chapterUrl).parseHtml()

        val scripts = doc.select("script")
        val imagesArrayPattern = Regex("""["']?images["']?\s*:\s*(\[[^\]]*(?:\](?!\s*[,\]}])|[^\]]*)\])""", RegexOption.DOT_MATCHES_ALL)

        var imagesJson: JSONArray? = null
        for (script in scripts) {
            val txt = script.html()
            val m = imagesArrayPattern.find(txt) ?: continue
            val rawArray = m.groupValues.getOrNull(1) ?: continue
            val clean = rawArray.replace("\\\"", "\"").replace("\\\\/", "/")
            try {
                imagesJson = JSONArray(clean)
                break
            } catch (e: Exception) {
                // Manual JSON string extraction logic...
                try {
                    val start = txt.indexOf("[", m.range.first)
                    if (start >= 0) {
                        var depth = 1
                        var end = -1
                        var i = start + 1
                        var inString = false
                        var escape = false
                        while (i < txt.length) {
                            val ch = txt[i]
                            if (escape) { escape = false; i++; continue }
                            when (ch) {
                                '\\' -> escape = true
                                '"' -> inString = !inString
                                '[' -> if (!inString) depth++
                                ']' -> if (!inString) {
                                    depth--
                                    if (depth == 0) { end = i; break }
                                }
                            }
                            i++
                        }
                        if (end > start) {
                            val candidate = txt.substring(start, end + 1).replace("\\\"", "\"").replace("\\\\/", "/")
                            imagesJson = JSONArray(candidate)
                            break
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        if (imagesJson == null) {
            throw ParseException("Unable to find chapter images", chapterUrl)
        }

        val pages = ArrayList<MangaPage>(imagesJson.length())
        for (i in 0 until imagesJson.length()) {
            val img = imagesJson.optString(i, "").nullIfEmpty() ?: continue
            pages.add(MangaPage(id = generateUid("${chapterId}-$i"), url = img, preview = null, source = source))
        }
        return pages
    }

    private suspend fun getPagesFromApi(chapterId: String): List<MangaPage> {
        val apiUrl = buildApiUrl("chapters/$chapterId").build()
        val resp = webClient.httpGet(apiUrl).parseJson()
        val images = resp.optJSONObject("result")?.optJSONArray("images") ?: return emptyList()
        return (0 until images.length()).map { i ->
            MangaPage(id = generateUid("$chapterId-$i"), url = images.optString(i, ""), preview = null, source = source)
        }
    }

    // -------------------------
    // Tags
    // -------------------------
    private fun fetchAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("6", "Action", source),
        MangaTag("87264", "Adult", source),
        MangaTag("7", "Adventure", source),
        MangaTag("8", "Boys Love", source),
        MangaTag("9", "Comedy", source),
        MangaTag("10", "Crime", source),
        MangaTag("11", "Drama", source),
        MangaTag("87265", "Ecchi", source),
        MangaTag("12", "Fantasy", source),
        MangaTag("13", "Girls Love", source),
        MangaTag("87266", "Hentai", source),
        MangaTag("14", "Historical", source),
        MangaTag("15", "Horror", source),
        MangaTag("16", "Isekai", source),
        MangaTag("17", "Magical Girls", source),
        MangaTag("87267", "Mature", source),
        MangaTag("18", "Mecha", source),
        MangaTag("19", "Medical", source),
        MangaTag("20", "Mystery", source),
        MangaTag("21", "Philosophical", source),
        MangaTag("22", "Psychological", source),
        MangaTag("23", "Romance", source),
        MangaTag("24", "Sci-Fi", source),
        MangaTag("25", "Slice of Life", source),
        MangaTag("87268", "Smut", source),
        MangaTag("26", "Sports", source),
        MangaTag("27", "Superhero", source),
        MangaTag("28", "Thriller", source),
        MangaTag("29", "Tragedy", source),
        MangaTag("30", "Wuxia", source),
        MangaTag("31", "Aliens", source),
        MangaTag("32", "Animals", source),
        MangaTag("33", "Cooking", source),
        MangaTag("34", "Crossdressing", source),
        MangaTag("35", "Delinquents", source),
        MangaTag("36", "Demons", source),
        MangaTag("37", "Genderswap", source),
        MangaTag("38", "Ghosts", source),
        MangaTag("39", "Gyaru", source),
        MangaTag("40", "Harem", source),
        MangaTag("41", "Incest", source),
        MangaTag("42", "Loli", source),
        MangaTag("43", "Mafia", source),
        MangaTag("44", "Magic", source),
        MangaTag("45", "Martial Arts", source),
        MangaTag("46", "Military", source),
        MangaTag("47", "Monster Girls", source),
        MangaTag("48", "Monsters", source),
        MangaTag("49", "Music", source),
        MangaTag("50", "Ninja", source),
        MangaTag("51", "Office Workers", source),
        MangaTag("52", "Police", source),
        MangaTag("53", "Post-Apocalyptic", source),
        MangaTag("54", "Reincarnation", source),
        MangaTag("55", "Reverse Harem", source),
        MangaTag("56", "Samurai", source),
        MangaTag("57", "School Life", source),
        MangaTag("58", "Shota", source),
        MangaTag("59", "Supernatural", source),
        MangaTag("60", "Survival", source),
        MangaTag("61", "Time Travel", source),
        MangaTag("62", "Traditional Games", source),
        MangaTag("63", "Vampires", source),
        MangaTag("64", "Video Games", source),
        MangaTag("65", "Villainess", source),
        MangaTag("66", "Virtual Reality", source),
        MangaTag("67", "Zombies", source)
    )
}
