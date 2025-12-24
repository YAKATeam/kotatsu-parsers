package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
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

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.RELEVANCE,
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL
        )
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return setOf(
            // Genres
            MangaTag(key = "6", title = "Action", source = source),
            MangaTag(key = "87264", title = "Adult", source = source),
            MangaTag(key = "7", title = "Adventure", source = source),
            MangaTag(key = "8", title = "Boys Love", source = source),
            MangaTag(key = "9", title = "Comedy", source = source),
            MangaTag(key = "10", title = "Crime", source = source),
            MangaTag(key = "11", title = "Drama", source = source),
            MangaTag(key = "87265", title = "Ecchi", source = source),
            MangaTag(key = "12", title = "Fantasy", source = source),
            MangaTag(key = "13", title = "Girls Love", source = source),
            MangaTag(key = "87266", title = "Hentai", source = source),
            MangaTag(key = "14", title = "Historical", source = source),
            MangaTag(key = "15", title = "Horror", source = source),
            MangaTag(key = "16", title = "Isekai", source = source),
            MangaTag(key = "17", title = "Magical Girls", source = source),
            MangaTag(key = "87267", title = "Mature", source = source),
            MangaTag(key = "18", title = "Mecha", source = source),
            MangaTag(key = "19", title = "Medical", source = source),
            MangaTag(key = "20", title = "Mystery", source = source),
            MangaTag(key = "21", title = "Philosophical", source = source),
            MangaTag(key = "22", title = "Psychological", source = source),
            MangaTag(key = "23", title = "Romance", source = source),
            MangaTag(key = "24", title = "Sci-Fi", source = source),
            MangaTag(key = "25", title = "Slice of Life", source = source),
            MangaTag(key = "87268", title = "Smut", source = source),
            MangaTag(key = "26", title = "Sports", source = source),
            MangaTag(key = "27", title = "Superhero", source = source),
            MangaTag(key = "28", title = "Thriller", source = source),
            MangaTag(key = "29", title = "Tragedy", source = source),
            MangaTag(key = "30", title = "Wuxia", source = source),
            // Themes
            MangaTag(key = "31", title = "Aliens", source = source),
            MangaTag(key = "32", title = "Animals", source = source),
            MangaTag(key = "33", title = "Cooking", source = source),
            MangaTag(key = "34", title = "Crossdressing", source = source),
            MangaTag(key = "35", title = "Delinquents", source = source),
            MangaTag(key = "36", title = "Demons", source = source),
            MangaTag(key = "37", title = "Genderswap", source = source),
            MangaTag(key = "38", title = "Ghosts", source = source),
            MangaTag(key = "39", title = "Gyaru", source = source),
            MangaTag(key = "40", title = "Harem", source = source),
            MangaTag(key = "41", title = "Incest", source = source),
            MangaTag(key = "42", title = "Loli", source = source),
            MangaTag(key = "43", title = "Mafia", source = source),
            MangaTag(key = "44", title = "Magic", source = source),
            MangaTag(key = "45", title = "Martial Arts", source = source),
            MangaTag(key = "46", title = "Military", source = source),
            MangaTag(key = "47", title = "Monster Girls", source = source),
            MangaTag(key = "48", title = "Monsters", source = source),
            MangaTag(key = "49", title = "Music", source = source),
            MangaTag(key = "50", title = "Ninja", source = source),
            MangaTag(key = "51", title = "Office Workers", source = source),
            MangaTag(key = "52", title = "Police", source = source),
            MangaTag(key = "53", title = "Post-Apocalyptic", source = source),
            MangaTag(key = "54", title = "Reincarnation", source = source),
            MangaTag(key = "55", title = "Reverse Harem", source = source),
            MangaTag(key = "56", title = "Samurai", source = source),
            MangaTag(key = "57", title = "School Life", source = source),
            MangaTag(key = "58", title = "Shota", source = source),
            MangaTag(key = "59", title = "Supernatural", source = source),
            MangaTag(key = "60", title = "Survival", source = source),
            MangaTag(key = "61", title = "Time Travel", source = source),
            MangaTag(key = "62", title = "Traditional Games", source = source),
            MangaTag(key = "63", title = "Vampires", source = source),
            MangaTag(key = "64", title = "Video Games", source = source),
            MangaTag(key = "65", title = "Villainess", source = source),
            MangaTag(key = "66", title = "Virtual Reality", source = source),
            MangaTag(key = "67", title = "Zombies", source = source),
        )
    }

    // kotlin
    // kotlin
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://comix.to/api/v2/manga?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            // Search keyword if provided
            if (!filter.query.isNullOrEmpty()) {
                addParam("keyword=${filter.query.urlEncoded()}")
            }

            // Use the provided sort order directly
            when (order) {
                SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
                SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                SortOrder.NEWEST -> addParam("order[created_at]=desc")
                SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                else -> addParam("order[chapter_updated_at]=desc")
            }

            // Handle genre filtering
            if (filter.tags.isNotEmpty()) {
                for (tag in filter.tags) {
                    addParam("genres[]=${tag.key}")
                }
            }

            // Default exclude adult content
            addParam("genres[]=-87264") // Adult
            addParam("genres[]=-87266") // Hentai
            addParam("genres[]=-87268") // Smut
            addParam("genres[]=-87265") // Ecchi
            addParam("limit=$pageSize")
            addParam("page=$page")
        }

        val response = webClient.httpGet(url).parseJson()
        val result = response.getJSONObject("result")
        val items = result.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseMangaFromJson(item)
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.getString("hash_id")
        val title = json.getString("title")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.getJSONObject("poster")
        val coverUrl = poster.optString("large", "").nullIfEmpty()
        val status = json.optString("status", "")
        val year = json.optInt("year", 0)
        val rating = json.optDouble("rated_avg", 0.0)

        val state = when (status) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            else -> null
        }

        return Manga(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://comix.to/title/$hashId",
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
        val hashId = manga.url.substringAfter("/title/")
        val chaptersDeferred = async { getChapters(manga) }

        // Get detailed manga info
        val detailUrl = "https://comix.to/api/v2/manga/$hashId"
        val response = webClient.httpGet(detailUrl).parseJson()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            return@coroutineScope updatedManga.copy(
                chapters = chaptersDeferred.await(),
            )
        }

        return@coroutineScope manga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val chapterUrl = "https://comix.to${chapter.url}"

        // Get the chapter page HTML to extract images from the script
        val response = webClient.httpGet(chapterUrl).parseHtml()

        // Look for the images array in the JavaScript (with escaped quotes)
        val scripts = response.select("script")
        var images: JSONArray? = null

        for (script in scripts) {
            val scriptContent = script.html()

            // Look for the images array with escaped quotes in JSON
            if (scriptContent.contains("\\\"images\\\":[")) {
                try {
                    // Find the start of the images array (with escaped quotes)
                    val imagesStart = scriptContent.indexOf("\\\"images\\\":[")
                    val colonPos = scriptContent.indexOf(":", imagesStart)
                    val arrayStart = scriptContent.indexOf("[", colonPos)

                    // Find the matching closing bracket for the array
                    var bracketCount = 1 // Start with 1 since we're at the opening bracket
                    var arrayEnd = arrayStart + 1 // Start after the opening bracket
                    var inString = false
                    var escapeNext = false

                    for (i in (arrayStart + 1) until scriptContent.length) {
                        val char = scriptContent[i]

                        if (escapeNext) {
                            escapeNext = false
                            continue
                        }

                        when (char) {
                            '\\' -> escapeNext = true
                            '"' -> inString = !inString
                            '[' -> if (!inString) bracketCount++
                            ']' -> if (!inString) {
                                bracketCount--
                                if (bracketCount == 0) {
                                    arrayEnd = i + 1
                                    break
                                }
                            }
                        }
                    }

                    val imagesJsonString = scriptContent.substring(arrayStart, arrayEnd)
                    // Parse the JSON array, handling escaped quotes
                    images = JSONArray(imagesJsonString.replace("\\\"", "\""))
                    break
                } catch (e: Exception) {
                    // Continue to next script if parsing fails
                    continue
                }
            }
        }

        if (images == null) {
            throw ParseException("Unable to find chapter images", chapterUrl)
        }

        return (0 until images.length()).map { i ->
            val imageItem = images.get(i)
            val imageUrl = when (imageItem) {
                is String -> imageItem
                is JSONObject -> imageItem.getString("url")
                else -> throw ParseException("Unexpected image format", chapterUrl)
            }
            MangaPage(
                id = generateUid("$chapterId-$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val allChapters = mutableListOf<JSONObject>()
        var page = 1

        // Fetch all chapters with pagination
        while (true) {
            val chaptersUrl = "https://comix.to/api/v2/manga/$hashId/chapters?order[number]=desc&limit=100&page=$page"
            val response = webClient.httpGet(chaptersUrl).parseJson()
            val result = response.getJSONObject("result")
            val items = result.getJSONArray("items")

            if (items.length() == 0) break

            for (i in 0 until items.length()) {
                allChapters.add(items.getJSONObject(i))
            }

            // Check pagination info to see if we have more pages
            val pagination = result.optJSONObject("pagination")
            if (pagination != null) {
                val currentPage = pagination.getInt("current_page")
                val lastPage = pagination.getInt("last_page")
                if (currentPage >= lastPage) break
            }

            page++
        }

        // Group chapters by number and pick one translation per chapter (preferring latest)
        val uniqueChapters = allChapters
            .groupBy { it.getDouble("number") }
            .mapValues { (_, chapters) ->
                // Sort by creation date descending and take the first (most recent)
                chapters.maxByOrNull { it.getLong("created_at") }!!
            }
            .values
            .sortedByDescending { it.getDouble("number") } // Sort by chapter number descending

        return uniqueChapters.mapIndexedNotNull { index, item ->
            val chapterId = item.getLong("chapter_id")
            val number = item.getDouble("number").toFloat()
            val name = item.optString("name", "").nullIfEmpty()
            val createdAt = item.getLong("created_at")
            val scanlationGroup = item.optJSONObject("scanlation_group")
            val scanlatorName = scanlationGroup?.optString("name", null)

            val title = if (name != null) {
                "Chapter $number: $name"
            } else {
                "Chapter $number"
            }

            MangaChapter(
                id = generateUid(chapterId.toString()),
                title = title,
                number = number,
                volume = 0,
                url = "/title/$hashId/$chapterId-chapter-${number.toInt()}",
                uploadDate = createdAt * 1000L, // Convert to milliseconds
                source = source,
                scanlator = scanlatorName,
                branch = null,
            )
        }.reversed() // Reverse to have ascending order
    }
}