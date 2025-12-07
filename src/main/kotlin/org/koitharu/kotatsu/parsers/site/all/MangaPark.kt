package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANGAPARK", "MangaPark", "en")
internal class MangaPark(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAPARK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("mangapark.io")

    // FIX 1: Removed 'private val domain' to avoid compilation error.
    // We use the 'domain' provided by PagedMangaParser directly.

    private val apiUrl: String
        get() = "https://$domain/apo/"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = emptySet(),
            availableContentRating = emptySet(),
            availableLocales = emptySet()
        )
    }

    // --- GraphQL Queries ---
    private val SEARCH_QUERY = """
        query(${"$"}select: SearchComic_Select) {
          get_searchComic(select: ${"$"}select) {
            items {
              data {
                id
                name
                altNames
                urlPath
                urlCoverOri
                authors
              }
            }
          }
        }
    """

    private val DETAILS_QUERY = """
        query(${"$"}id: ID!) {
          get_comicNode(id: ${"$"}id) {
            data {
              id
              name
              altNames
              authors
              genres
              summary
              originalStatus
              urlPath
              urlCoverOri
            }
          }
        }
    """

    private val CHAPTERS_QUERY = """
        query(${"$"}id: ID!) {
          get_comicChapterList(comicId: ${"$"}id) {
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
    """

    private val PAGES_QUERY = """
        query(${"$"}id: ID!) {
          get_chapterNode(id: ${"$"}id) {
            data {
              imageFile {
                urlList
              }
            }
          }
        }
    """

    // --- Implementation ---

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortValue = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.NEWEST -> "create"
            SortOrder.RATING -> "rating"
            else -> "update"
        }

        val select = JSONObject().apply {
            put("page", page)
            put("size", pageSize)
            put("word", filter.query ?: "")
            put("sort", sortValue)
        }
        val variables = JSONObject().apply {
            put("select", select)
        }

        val json = try {
            graphqlRequest(SEARCH_QUERY, variables)
        } catch (e: Exception) {
            return emptyList()
        }

        val items = json.optJSONObject("data")
            ?.optJSONObject("get_searchComic")
            ?.optJSONArray("items") ?: return emptyList()

        val result = mutableListOf<Manga>()

        for (i in 0 until items.length()) {
            val wrapper = items.optJSONObject(i) ?: continue
            val data = wrapper.optJSONObject("data") ?: continue

            val urlPath = data.optString("urlPath")
            val uid = generateUid(urlPath)

            result.add(
                Manga(
                    id = uid,
                    url = urlPath,
                    publicUrl = buildAbsoluteUrl(urlPath),
                    coverUrl = buildAbsoluteUrl(data.optString("urlCoverOri")),
                    title = data.optString("name"),
                    altTitles = parseStringSet(data.optJSONArray("altNames")),
                    rating = RATING_UNKNOWN,
                    source = source,
                    state = null,
                    tags = emptySet(),
                    authors = parseStringSet(data.optJSONArray("authors")),
                    contentRating = null
                )
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val comicId = extractNumericId(manga.url)
            ?: throw Exception("Could not parse comic ID from URL: ${manga.url}")

        // Fetch Metadata
        val detailVars = JSONObject()
        detailVars.put("id", comicId)
        
        val detailsJson = graphqlRequest(DETAILS_QUERY, detailVars)
        val data = detailsJson.optJSONObject("data")
            ?.optJSONObject("get_comicNode")
            ?.optJSONObject("data")
            ?: throw Exception("Manga details not found")

        val statusText = data.optString("originalStatus").lowercase()
        val state = when {
            statusText.contains("ongoing") -> MangaState.ONGOING
            statusText.contains("completed") -> MangaState.FINISHED
            statusText.contains("hiatus") -> MangaState.PAUSED
            statusText.contains("cancelled") -> MangaState.ABANDONED
            else -> null
        }

        // Fetch Chapters
        val chapterVars = JSONObject()
        chapterVars.put("id", comicId)
        
        val chaptersJson = graphqlRequest(CHAPTERS_QUERY, chapterVars)
        val chapterList = chaptersJson.optJSONObject("data")
            ?.optJSONObject("get_comicChapterList")
            ?.optJSONArray("data")

        val chapters = mutableListOf<MangaChapter>()

        if (chapterList != null) {
            for (i in 0 until chapterList.length()) {
                val chData = chapterList.getJSONObject(i)
                
                val dname = chData.optString("dname")
                val titlePart = chData.optString("title")
                val fullTitle = if (titlePart.isNotBlank()) "$dname - $titlePart" else dname
                
                val dateTs = chData.optLong("dateModify").takeIf { it > 0 } 
                    ?: chData.optLong("dateCreate")

                val scanlator = chData.optJSONObject("userNode")?.optJSONObject("data")?.optString("name")
                    ?: chData.optString("srcTitle")

                val chapterNumericId = chData.optString("id")
                val urlPath = chData.optString("urlPath")

                chapters.add(
                    MangaChapter(
                        id = generateUid(chapterNumericId),
                        title = fullTitle,
                        number = parseChapterNumber(dname),
                        volume = 0,
                        // FIX 2: Store ID in URL fragment to keep UI clean
                        url = "$urlPath#$chapterNumericId",
                        uploadDate = dateTs * 1000L,
                        source = source,
                        scanlator = scanlator.ifBlank { null },
                        branch = null // Clean UI (no random numbers)
                    )
                )
            }
        }
        
        chapters.reverse()

        return manga.copy(
            description = data.optString("summary"),
            authors = parseStringSet(data.optJSONArray("authors")),
            tags = parseStringSet(data.optJSONArray("genres")),
            state = state,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // FIX 2: Extract ID from URL fragment
        val chapterId = chapter.url.substringAfterLast('#')
            .takeIf { it != chapter.url } // ensure fragment existed
            ?: extractNumericId(chapter.url)
            ?: throw Exception("Could not determine Chapter ID")

        val variables = JSONObject()
        variables.put("id", chapterId)

        val json = graphqlRequest(PAGES_QUERY, variables)

        val urls = json.optJSONObject("data")
            ?.optJSONObject("get_chapterNode")
            ?.optJSONObject("data")
            ?.optJSONObject("imageFile")
            ?.optJSONArray("urlList")
            ?: return emptyList()

        val pages = ArrayList<MangaPage>(urls.length())
        for (i in 0 until urls.length()) {
            val url = urls.getString(i)
            pages.add(
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source
                )
            )
        }
        return pages
    }

    // --- Helpers ---

    private suspend fun graphqlRequest(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }

        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()

        val responseString = webClient.httpPost(
            url = apiUrl.toHttpUrl(),
            payload = payload.toString(),
            extraHeaders = headers
        ).parseJson()

        val errors = responseString.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val msg = errors.optJSONObject(0)?.optString("message") ?: "Unknown GraphQL Error"
            throw Exception("API Error: $msg")
        }

        return responseString
    }

    private fun buildAbsoluteUrl(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return if (path.startsWith("http")) path else "https://$domain$path"
    }

    private fun extractNumericId(url: String): String? {
        val match = Regex("""/comic/(\d+)""").find(url)
            ?: Regex("""/chapter/(\d+)""").find(url)
        return match?.groupValues?.get(1)
    }

    private fun parseStringSet(jsonArray: org.json.JSONArray?): Set<String> {
        if (jsonArray == null) return emptySet()
        val set = mutableSetOf<String>()
        for (i in 0 until jsonArray.length()) {
            set.add(jsonArray.getString(i))
        }
        return set
    }

    private fun parseChapterNumber(name: String): Float {
        if (name.contains("bonus", true) || name.contains("special", true)) return -2f
        if (name.contains("oneshot", true)) return -3f

        val cleanName = name.replace(Regex("""Vol\.\s*\d+""", RegexOption.IGNORE_CASE), "")
        val match = Regex("""(?:ch|chapter)?\.?\s*(\d+(\.\d+)?)""", RegexOption.IGNORE_CASE).find(cleanName)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
