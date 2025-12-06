package org.koitharu.kotatsu.parsers.site.all

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
    private val domain = "mangapark.io"
    private val baseUrl = "https://$domain"
    private val apiUrl = "$baseUrl/apo/"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = emptySet(),
            availableContentRating = emptySet(),
            availableLocales = emptySet()
        )
    }

    // GraphQL query strings
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
              }
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

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val variables = JSONObject().apply {
            put("select", JSONObject().apply {
                put("page", page)
                put("size", pageSize)
                put("word", filter.query ?: "")
            })
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
            val data = items.getJSONObject(i).optJSONObject("data") ?: continue
            val originalId = data.optString("id")
            val urlPath = data.optString("urlPath")
            val cover = buildUrl(data.optString("urlCoverOri"))
            val altTitles = data.optJSONArray("altNames")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } ?: emptySet()

            result.add(
                Manga(
                    id = generateUid(originalId),
                    url = urlPath,
                    publicUrl = buildUrl(urlPath) ?: "",
                    coverUrl = cover ?: "",
                    title = data.optString("name"),
                    altTitles = altTitles,
                    rating = RATING_UNKNOWN,
                    source = source,
                    state = null,
                    tags = emptySet(),
                    authors = emptySet(),
                    contentRating = null
                )
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // Try scraping page for author/description/status as a best-effort fallback.
        val (scrapedAuthor, scrapedDescription, scrapedState) = try {
            val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
            val author = doc.select("div:has(span:containsOwn(Authors)) a, div:has(span:containsOwn(Author)) a")
                .map { it.text() }
                .firstOrNull()
            val description = doc.select("div:has(h3:containsOwn(Summary)) + div").textOrNull()
                ?: doc.select("div:has(h3:containsOwn(Description)) + div").textOrNull()
            val statusText = doc.select("div:has(span:containsOwn(Status))").textOrNull()?.lowercase()
            val state = when {
                statusText?.contains("ongoing") == true -> MangaState.ONGOING
                statusText?.contains("completed") == true -> MangaState.FINISHED
                statusText?.contains("hiatus") == true -> MangaState.PAUSED
                statusText?.contains("cancelled") == true -> MangaState.ABANDONED
                else -> null
            }
            Triple(author, description, state)
        } catch (e: Exception) {
            Triple(null, null, null)
        }

        // Extract original comic id robustly: prefer publicUrl (absolute), then url, then id.
        val comicId = extractFirstDigits(manga.publicUrl)
            ?: extractFirstDigits(manga.url)
            ?: extractFirstDigits(manga.id)
            ?: throw Exception("Could not find comic numeric ID for ${manga.title}")

        val chaptersJson = try {
            graphqlRequest(CHAPTERS_QUERY, JSONObject().put("id", comicId))
        } catch (e: Exception) {
            // Return best-effort metadata without chapters
            return manga.copy(
                authors = setOfNotNull(scrapedAuthor),
                description = scrapedDescription,
                state = scrapedState,
                chapters = emptyList(),
                tags = emptySet(),
                contentRating = null
            )
        }

        val chapterArray = chaptersJson.optJSONObject("data")
            ?.optJSONObject("get_comicChapterList")
            ?.optJSONArray("data")
            ?: chaptersJson.optJSONObject("data")?.optJSONArray("get_comicChapterList")

        val chapters = mutableListOf<MangaChapter>()
        if (chapterArray != null) {
            for (i in 0 until chapterArray.length()) {
                val element = chapterArray.getJSONObject(i)
                val data = element.optJSONObject("data") ?: continue
                val originalChapterId = data.optString("id")
                val dname = data.optString("dname")
                val titlePart = data.optString("title")
                val fullTitle = if (titlePart.isNotEmpty()) "$dname - $titlePart" else dname
                val dateTs = data.optLong("dateModify").takeIf { it > 0 } ?: data.optLong("dateCreate")

                chapters.add(
                    MangaChapter(
                        id = generateUid(originalChapterId),
                        title = fullTitle,
                        number = parseChapterNumber(dname),
                        volume = 0,
                        url = data.optString("urlPath"),
                        uploadDate = dateTs.takeIf { it > 0 }?.let { it * 1000L } ?: 0L,
                        source = source,
                        scanlator = data.optJSONObject("userNode")?.optJSONObject("data")?.optString("name")
                            ?: data.optString("srcTitle"),
                        branch = originalChapterId // preserve original numeric id for getPages
                    )
                )
            }
        }

        return manga.copy(
            authors = setOfNotNull(scrapedAuthor),
            description = scrapedDescription,
            state = scrapedState,
            chapters = chapters,
            tags = emptySet(),
            contentRating = null
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Use stored original chapter id (branch). Fallback to digits from url/id.
        val chapterId = chapter.branch?.takeIf { it.isNotBlank() }
            ?: extractFirstDigits(chapter.url)
            ?: extractFirstDigits(chapter.id)
            ?: throw Exception("Could not find original chapter ID for chapter ${chapter.title}")

        val json = try {
            graphqlRequest(PAGES_QUERY, JSONObject().put("id", chapterId))
        } catch (e: Exception) {
            return emptyList()
        }

        val urls = json.optJSONObject("data")
            ?.optJSONObject("get_chapterNode")
            ?.optJSONObject("data")
            ?.optJSONObject("imageFile")
            ?.optJSONArray("urlList") ?: return emptyList()

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

    // Send GraphQL POST with JSON payload and required headers.
    private suspend fun graphqlRequest(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }

        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add("Referer", baseUrl)
            .build()

        val responseBody = try {
            webClient.httpPost(
                url = apiUrl,
                payload = payload.toString(),
                extraHeaders = headers
            ).parseJson()
        } catch (e: Exception) {
            throw Exception("Network error during GraphQL request: ${e.message}", e)
        }

        val errors = responseBody.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw Exception("GraphQL Error: ${errors.optJSONObject(0)?.optString("message") ?: "Unknown error"}")
        }

        return responseBody
    }

    // Build absolute URL — return null if empty input.
    private fun buildUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            path.startsWith("http", ignoreCase = true) -> path
            path.startsWith("/") -> "$baseUrl$path"
            else -> "$baseUrl/$path"
        }
    }

    // Parse chapter number to Float. If not parseable, fallback to -1f (unknown) or special negative values for bonus.
    private fun parseChapterNumber(dname: String): Float {
        val cleaned = dname.replace(Regex("^Vol\\.\\s*\\S+\\s+", RegexOption.IGNORE_CASE), "")
        if (cleaned.contains("Bonus", ignoreCase = true)) return -2f
        val match = Regex("(?:Ch\\.|Chapter)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(cleaned)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: run {
            Regex("(\\d+(?:\\.\\d+)?)").find(cleaned)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        }
    }

    // Extract first contiguous digits from input or null if none.
    private fun extractFirstDigits(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val m = Regex("(\\d+)").find(input)
        return m?.groupValues?.get(1)
    }
}
