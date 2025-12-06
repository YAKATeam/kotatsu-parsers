package org.koitharu.kotatsu.parsers.site.all

import androidx.collection.ArrayMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

@MangaSourceParser("MANGAPARK", "MangaPark")
internal class MangaPark(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAPARK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("mangapark.io")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.RELEVANCE)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = emptySet(),
        availableContentRating = emptySet()
    )

    // GraphQL payloads
    private val querySearch = """
        query(${'$'}select: SearchComic_Select) {
          get_searchComic(select: ${'$'}select) {
            items {
              data {
                id
                name
                altNames
                urlPath
                urlCoverOri
                authors
                artists
                genres
                originalStatus
              }
            }
          }
        }
    """.trimIndent()

    private val queryDetailsAndChapters = """
        query(${'$'}id: ID!) {
          get_comicNode(id: ${'$'}id) {
            data {
              id
              name
              altNames
              authors
              artists
              genres
              summary
              originalStatus
              urlCoverOri
              urlPath
              scores {
                score
              }
            }
          }
          get_comicChapterList(comicId: ${'$'}id) {
            data {
              id
              dname
              title
              dateCreate
              dateModify
              urlPath
              srcTitle
              userNode {
                data {
                  name
                }
              }
            }
          }
        }
    """.trimIndent()

    private val queryPages = """
        query(${'$'}id: ID!) {
          get_chapterNode(id: ${'$'}id) {
            data {
              imageFile {
                urlList
              }
            }
          }
        }
    """.trimIndent()

    // -------------------------
    // Helpers: network + epoch guard
    // -------------------------
    private fun epochToMillis(epochSecondsOrMs: Long): Long =
        when {
            epochSecondsOrMs <= 0L -> 0L
            epochSecondsOrMs > 1_000_000_000_000L -> epochSecondsOrMs
            else -> epochSecondsOrMs * 1000L
        }

    private fun urlToString(url: HttpUrl) = url.toString()

    // suspended wrapper to POST GraphQL and return "data" JSONObject
    private suspend fun graphQlRequestRaw(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }
        val url = "https://$domain/apo/".toHttpUrl()
        
        // FIX 1: Use Headers.of() and pass JSONObject payload directly to match extension signature
        val headers = Headers.of(mapOf("Referer" to "https://$domain/"))

        val responseJson = try {
            webClient.httpPost(url, payload, headers).parseJson()
        } catch (e: Exception) {
            throw ParseException("GraphQL request failed for $url: ${e.message}", urlToString(url))
        }

        if (responseJson.has("errors")) {
            val err = responseJson.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            throw ParseException("GraphQL error: ${err ?: "unknown"}", urlToString(url))
        }
        return responseJson.optJSONObject("data") ?: JSONObject()
    }

    // retry wrapper for GraphQL (suspend)
    private suspend fun graphQlRequest(query: String, variables: JSONObject): JSONObject {
        var attempt = 0
        var delayMs = 800L
        val maxRetries = 2
        while (true) {
            try {
                return graphQlRequestRaw(query, variables)
            } catch (e: ParseException) {
                attempt++
                if (attempt > maxRetries) throw e
                delay(delayMs)
                delayMs *= 2
            }
        }
    }

    // -------------------------
    // List / Search
    // -------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val variables = JSONObject().apply {
            val select = JSONObject()
            select.put("page", page)
            select.put("size", pageSize)
            if (!filter.query.isNullOrEmpty()) select.put("word", filter.query)
            put("select", select)
        }

        val data = graphQlRequest(querySearch, variables)
        val wrapper = data.optJSONObject("get_searchComic")
        val items = wrapper?.optJSONArray("items") ?: JSONArray()

        return (0 until items.length()).mapNotNull { i ->
            val itemWrapper = items.optJSONObject(i) ?: return@mapNotNull null
            val item = itemWrapper.optJSONObject("data") ?: return@mapNotNull null

            val id = item.optString("id").nullIfEmpty() ?: return@mapNotNull null
            val urlPath = item.optString("urlPath", "/comic/$id")

            Manga(
                id = generateUid(id),
                url = urlPath,
                publicUrl = "https://$domain$urlPath",
                coverUrl = item.optString("urlCoverOri").nullIfEmpty(),
                title = item.optString("name").nullIfEmpty() ?: "Untitled",
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                state = when (item.optString("originalStatus")) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                },
                // FIX 2: Explicitly pass mandatory tags and authors parameters
                tags = emptySet(),
                authors = emptySet(),
                source = source
            )
        }
    }

    // -------------------------
    // Details + chapters
    // -------------------------
    override suspend fun getDetails(manga: Manga): Manga {
        // Extract numeric ID if present
        val mangaId = Regex("""/comic/(\d+)""").find(manga.url)?.groupValues?.get(1)
            ?: manga.url.substringAfterLast("/").nullIfEmpty()
            ?: throw ParseException("Unable to determine manga ID from url ${manga.url}", manga.url)

        val variables = JSONObject().put("id", mangaId)
        val data = graphQlRequest(queryDetailsAndChapters, variables)

        val comicNode = data.optJSONObject("get_comicNode")?.optJSONObject("data")
            ?: throw ParseException("Manga not found: $mangaId", mangaId)

        // Authors
        val authors = comicNode.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).nullIfEmpty() }.toSet()
        } ?: emptySet()

        // Genres -> MangaTag set
        val genres = comicNode.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.optString(it).nullIfEmpty()
            }.map { g -> MangaTag(g, g.lowercase(), source) }.toSet()
        } ?: emptySet()

        // rating - defensive handling
        val rating = comicNode.optJSONObject("scores")?.optDouble("score", Double.NaN)?.takeIf { !it.isNaN() }?.let {
            (it.toFloat() / 10f)
        } ?: RATING_UNKNOWN

        // Chapters
        val chapterList = data.optJSONArray("get_comicChapterList") ?: JSONArray()
        val chapters = ArrayList<MangaChapter>()

        for (i in 0 until chapterList.length()) {
            val chapterWrapper = chapterList.optJSONObject(i) ?: continue
            val cData = chapterWrapper.optJSONObject("data") ?: continue

            val cid = cData.optString("id").nullIfEmpty() ?: continue
            val dname = cData.optString("dname").nullIfEmpty() ?: ""
            val title = cData.optString("title").nullIfEmpty() ?: ""
            val dateModify = cData.optLong("dateModify", 0L)
            val dateCreate = cData.optLong("dateCreate", 0L)
            val uploadDate = epochToMillis(if (dateModify > 0L) dateModify else dateCreate)

            val userNode = cData.optJSONObject("userNode")?.optJSONObject("data")
            
            // FIX 3: Safe nullable handling for scanlator extraction
            val scanlator = userNode?.optString("name")?.nullIfEmpty()
                ?: cData.optString("srcTitle").nullIfEmpty()

            val number = parseChapterNumber(dname)

            val fullTitle = if (title.isNotEmpty()) "$dname - $title" else dname

            chapters.add(
                MangaChapter(
                    id = generateUid(cid),
                    url = cData.optString("urlPath", "/chapter/$cid"),
                    title = fullTitle.ifEmpty { "Chapter ${if (number >= 0f) number else cid}" },
                    number = number,
                    volume = 0,
                    uploadDate = uploadDate,
                    scanlator = scanlator,
                    source = source,
                    branch = null
                )
            )
        }

        return manga.copy(
            title = comicNode.optString("name").nullIfEmpty() ?: manga.title,
            description = comicNode.optString("summary").nullIfEmpty(),
            authors = authors,
            tags = genres,
            rating = rating,
            state = when (comicNode.optString("originalStatus")) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else -> null
            },
            coverUrl = comicNode.optString("urlCoverOri").nullIfEmpty(),
            chapters = chapters
        )
    }

    // parse numeric chapter number from human string (returns -1f if not found)
    private fun parseChapterNumber(dname: String): Float {
        if (dname.isBlank()) return -1f
        // Remove volume prefix (Vol. X)
        val cleaned = dname.replace(Regex("""(?i)^Vol\.\s*\S+\s+"""), "")

        // Try matches like "Ch. 12.5" or "Chapter 12" or "12.5"
        val patterns = listOf(
            Regex("""(?i)(?:Ch\.|Chapter)\s*(\d+(?:\.\d+)?)"""),
            Regex("""(?i)(\d+(?:\.\d+))""")
        )

        for (re in patterns) {
            val m = re.find(cleaned)
            if (m != null && m.groupValues.size > 1) {
                val num = m.groupValues[1].toFloatOrNull()
                if (num != null) return num
            }
        }
        return -1f
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = Regex("""/chapter/(\d+)""").find(chapter.url)?.groupValues?.get(1)
            ?: chapter.url.substringAfterLast("/").nullIfEmpty()
            ?: throw ParseException("Unable to determine chapter id from url ${chapter.url}", chapter.url)

        val variables = JSONObject().put("id", chapterId)
        val data = graphQlRequest(queryPages, variables)

        val urlList = data.optJSONObject("get_chapterNode")
            ?.optJSONObject("data")
            ?.optJSONObject("imageFile")
            ?.optJSONArray("urlList")
            ?: throw ParseException("No images found for chapter $chapterId", chapter.url)

        return (0 until urlList.length()).map { i ->
            val url = urlList.optString(i).nullIfEmpty() ?: ""
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
