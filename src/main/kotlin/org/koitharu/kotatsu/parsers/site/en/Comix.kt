package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.arraySetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@Broken("Need to refactor & implement more ContentType")
@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

	private val apiSuffix = "api/v2/manga"
    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
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

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = urlBuilder()
			.host(domain)
			.addPathSegments(apiSuffix)

            if (!filter.query.isNullOrEmpty()) {
                url.addQueryParameter("keyword", filter.query)
            }

            val orderParam = when (order) {
                SortOrder.RELEVANCE -> "relevance"
                SortOrder.UPDATED -> "chapter_updated_at"
                SortOrder.POPULARITY -> "views_30d"
                SortOrder.NEWEST -> "created_at"
                SortOrder.ALPHABETICAL -> "title"
                else -> "chapter_updated_at"
            }

            val direction = if (order == SortOrder.ALPHABETICAL) "asc" else "desc"
            url.addQueryParameter("order[$orderParam]", direction)

            if (filter.tags.isNotEmpty()) {
                for (tag in filter.tags) {
                    url.addQueryParameter("genres[]", tag.key)
                }
            } else {
                // Default exclusions (Adult/Hentai/Smut/Ecchi) matching Lua/Web behavior
                listOf("87264", "87266", "87268", "87265").forEach {
                    url.addQueryParameter("genres[]", "-$it")
                }
            }

            url.addQueryParameter("limit", pageSize.toString())
            url.addQueryParameter("page", page.toString())

        val response = webClient.httpGet(url.build()).parseJson()
        val result = response.getJSONObject("result")
        val items = result.getJSONArray("items")

        return (0 until items.length()).map { i ->
            parseMangaFromJson(items.getJSONObject(i))
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.getString("hash_id")
        val title = json.getString("title")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        val coverUrl = poster?.optString("medium", "")?.nullIfEmpty() // Lua uses 'medium'
        val status = json.optString("status", "")
        val rating = json.optDouble("rated_avg", 0.0)

        val state = when (status.lowercase()) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://${domain}/title/$hashId",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = if (rating > 0) (rating / 10.0f).toFloat() else RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        // Lua: /manga/HASH-SLUG -> we extract just the hash
        val hashId = manga.url.substringAfter("/title/").substringBefore("-")

        // Fetch details from API (Lua: includes[]=author&includes[]=artist...)
		val detailsUrl = urlBuilder()
			.host(domain)
			.addPathSegments(apiSuffix)
			.addPathSegment(hashId)
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .addQueryParameter("includes[]", "genre")
            .addQueryParameter("includes[]", "theme")
            .addQueryParameter("includes[]", "demographic")
            .build()

        val detailsDeferred = async { webClient.httpGet(detailsUrl).parseJson() }
        val chaptersDeferred = async { getChapters(hashId) }

        val response = detailsDeferred.await()
        val chapters = chaptersDeferred.await()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            // Extract Authors/Artists/Genres similar to Lua logic
            val authors = result.optJSONArray("author")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("title").nullIfEmpty() }
            }?.toSet() ?: emptySet()

            val genres = buildSet {
                result.optJSONArray("genre")?.let { arr ->
                    (0 until arr.length()).forEach { add(arr.getJSONObject(it).getString("title")) }
                }
                result.optJSONArray("theme")?.let { arr ->
                    (0 until arr.length()).forEach { add(arr.getJSONObject(it).getString("title")) }
                }
            }

            // Map strings to MangaTags
            val mappedTags = genres.mapNotNull { name ->
                // We don't have IDs here easily without map, creating tags with name as key is safe for display
                MangaTag(name, name, source)
            }.toSet()

            return@coroutineScope updatedManga.copy(
                chapters = chapters,
                authors = authors,
                tags = mappedTags
            )
        }

        return@coroutineScope manga.copy(chapters = chapters)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private suspend fun getChapters(hashId: String): List<MangaChapter> {
        val allChapters = ArrayList<JSONObject>()
        var page = 1
        var lastPage = 1

        // 1. Fetch all pages of chapters
        do {
            val chaptersUrl = urlBuilder()
				.host(domain)
				.addPathSegments(apiSuffix)
				.addPathSegment(hashId)
				.addPathSegment("chapters")
                .addQueryParameter("order[number]", "asc") // Lua uses asc, we can sort later
                .addQueryParameter("limit", "100")
                .addQueryParameter("page", page.toString())
                .build()

            val response = webClient.httpGet(chaptersUrl).parseJson()
            val result = response.getJSONObject("result")
            val items = result.getJSONArray("items")

            for (i in 0 until items.length()) {
                allChapters.add(items.getJSONObject(i))
            }

            val pagination = result.optJSONObject("pagination")
            if (pagination != null) {
                lastPage = pagination.optInt("last_page", 1)
            }
            page++
        } while (page <= lastPage)

        // 2. Deduplication Logic (Ported from Lua)
        // Prefer Official (Group ID 9275), then Highest Votes, then Latest Update
        val chapterMap = HashMap<String, JSONObject>()
        val chapterOrder = ArrayList<String>() // To keep order

        for (item in allChapters) {
            val numberStr = item.optString("number")
            val current = chapterMap[numberStr]

            val scanGroupId = item.optInt("scanlation_group_id", 0)
            val votes = item.optInt("votes", 0)
            val updatedAt = item.optLong("updated_at", 0)

            if (current == null) {
                chapterMap[numberStr] = item
                chapterOrder.add(numberStr)
            } else {
                val currentGroupId = current.optInt("scanlation_group_id", 0)
                val currentVotes = current.optInt("votes", 0)
                val currentUpdatedAt = current.optLong("updated_at", 0)

                val officialNew = (scanGroupId == 9275)
                val officialCurrent = (currentGroupId == 9275)
                var better = false

                if (officialNew && !officialCurrent) {
                    better = true
                } else if (!officialNew && officialCurrent) {
                    better = false
                } else {
                    if (votes > currentVotes) {
                        better = true
                    } else if (votes < currentVotes) {
                        better = false
                    } else if (updatedAt > currentUpdatedAt) {
                        better = true
                    }
                }

                if (better) {
                    chapterMap[numberStr] = item
                }
            }
        }

        // 3. Convert to MangaChapter objects
        // Lua loop iterates chapterOrder to maintain sort
        val finalList = chapterOrder.mapNotNull { numberStr ->
            val item = chapterMap[numberStr] ?: return@mapNotNull null

            val chapterId = item.getLong("chapter_id")
            val number = item.optDouble("number", 0.0).toFloat()
            val volume = item.optString("volume", "0")
            val name = item.optString("name", "").nullIfEmpty()
            val createdAt = item.optLong("created_at")
            val scanlationGroup = item.optJSONObject("scanlation_group")
            val scanlatorName = scanlationGroup?.optString("name", null)

            // Construct title: Vol. X Ch. Y - Name [Group]
            val volStr = if (volume != "0") "Vol. $volume " else ""
            val chStr = if (numberStr.isNotEmpty()) "Ch. ${number.niceString()}" else ""
            val titleStr = if (name != null) " - $name" else ""

            val fullTitle = "$volStr$chStr$titleStr".trim()

            MangaChapter(
                id = generateUid(chapterId.toString()),
                title = fullTitle.ifEmpty { "Chapter ${number.niceString()}" },
                number = number,
                volume = volume.toIntOrNull() ?: 0,
                url = "chapters/$chapterId", // API endpoint suffix for pages
                uploadDate = createdAt * 1000L,
                source = source,
                scanlator = scanlatorName,
                branch = null,
            )
        }

        // Reverse because list was fetched ASC (oldest first), Kotatsu usually expects newest first in list?
        // Actually, mapChapters(reversed=true) handles list ordering in UI usually, but let's provide desc.
        return finalList.reversed()
    }

    private fun Float.niceString(): String {
        return if (this == this.toLong().toFloat()) {
            this.toLong().toString()
        } else {
            this.toString()
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Lua: API_URL .. '/chapters' .. URL
        // Chapter.url was set to "/chapters/12345"
		val apiUrl = urlBuilder().host(domain)
			.addPathSegments(apiSuffix)
			.removePathSegment(2)
			.addPathSegments(chapter.url)
			.build()
        val response = webClient.httpGet(apiUrl).parseJson()
        val result = response.getJSONObject("result")
        val images = result.getJSONArray("images")

        return (0 until images.length()).map { i ->
            val imageUrl = images.getString(i)
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun fetchAvailableTags() = arraySetOf(
        // Genres
        MangaTag("Action", "6", source),
        MangaTag("Adult", "87264", source),
        MangaTag("Adventure", "7", source),
        MangaTag("Boys Love", "8", source),
        MangaTag("Comedy", "9", source),
        MangaTag("Crime", "10", source),
        MangaTag("Drama", "11", source),
        MangaTag("Ecchi", "87265", source),
        MangaTag("Fantasy", "12", source),
        MangaTag("Girls Love", "13", source),
        MangaTag("Hentai", "87266", source),
        MangaTag("Historical", "14", source),
        MangaTag("Horror", "15", source),
        MangaTag("Isekai", "16", source),
        MangaTag("Magical Girls", "17", source),
        MangaTag("Mature", "87267", source),
        MangaTag("Mecha", "18", source),
        MangaTag("Medical", "19", source),
        MangaTag("Mystery", "20", source),
        MangaTag("Philosophical", "21", source),
        MangaTag("Psychological", "22", source),
        MangaTag("Romance", "23", source),
        MangaTag("Sci-Fi", "24", source),
        MangaTag("Slice of Life", "25", source),
        MangaTag("Smut", "87268", source),
        MangaTag("Sports", "26", source),
        MangaTag("Superhero", "27", source),
        MangaTag("Thriller", "28", source),
        MangaTag("Tragedy", "29", source),
        MangaTag("Wuxia", "30", source),

        // Themes
        MangaTag("Aliens", "31", source),
        MangaTag("Animals", "32", source),
        MangaTag("Cooking", "33", source),
        MangaTag("Crossdressing", "34", source),
        MangaTag("Delinquents", "35", source),
        MangaTag("Demons", "36", source),
        MangaTag("Genderswap", "37", source),
        MangaTag("Ghosts", "38", source),
        MangaTag("Gyaru", "39", source),
        MangaTag("Harem", "40", source),
        MangaTag("Incest", "41", source),
        MangaTag("Loli", "42", source),
        MangaTag("Mafia", "43", source),
        MangaTag("Magic", "44", source),
        MangaTag("Martial Arts", "45", source),
        MangaTag("Military", "46", source),
        MangaTag("Monster Girls", "47", source),
        MangaTag("Monsters", "48", source),
        MangaTag("Music", "49", source),
        MangaTag("Ninja", "50", source),
        MangaTag("Office Workers", "51", source),
        MangaTag("Police", "52", source),
        MangaTag("Post-Apocalyptic", "53", source),
        MangaTag("Reincarnation", "54", source),
        MangaTag("Reverse Harem", "55", source),
        MangaTag("Samurai", "56", source),
        MangaTag("School Life", "57", source),
        MangaTag("Shota", "58", source),
        MangaTag("Supernatural", "59", source),
        MangaTag("Survival", "60", source),
        MangaTag("Time Travel", "61", source),
        MangaTag("Traditional Games", "62", source),
        MangaTag("Vampires", "63", source),
        MangaTag("Video Games", "64", source),
        MangaTag("Villainess", "65", source),
        MangaTag("Virtual Reality", "66", source),
        MangaTag("Zombies", "67", source),
    )
}
