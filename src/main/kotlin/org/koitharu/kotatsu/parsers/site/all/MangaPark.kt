package org.koitharu.kotatsu.parsers.site.all

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAPARK", "MangaPark", "en")
internal class MangaPark(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAPARK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("mangapark.io")

    private val apiUrl = "https://mangapark.io/apo/"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    // Reduced filter capabilities as we only have the 'word' parameter in the provided API snippet
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = """
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

        val variables = JSONObject().apply {
            put("select", JSONObject().apply {
                put("page", page)
                put("size", 24)
                put("word", filter.query ?: "")
            })
        }

        val json = graphqlRequest(query, variables)
        val items = json.optJSONObject("data")
            ?.optJSONObject("get_searchComic")
            ?.optJSONArray("items") ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i).optJSONObject("data") ?: continue
            val id = item.optString("id")
            val relativePath = item.optString("urlPath")
            
            // We use the ID as the key part of the unique ID
            mangaList.add(
                Manga(
                    id = generateUid(id),
                    url = relativePath, // /comic/12345/title
                    publicUrl = buildUrl(relativePath),
                    coverUrl = buildUrl(item.optString("urlCoverOri")),
                    title = item.optString("name"),
                    altTitles = item.optJSONArray("altNames")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    } ?: emptySet(),
                    rating = RATING_UNKNOWN,
                    source = source,
                    state = null
                )
            )
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // 1. Fetch Metadata (Description, Author, Status) via HTML
        // The API snippet provided didn't have a details query, so we scrape the page.
        // We use robust selectors (Labels) instead of unstable keys.
        val details = try {
            val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
            val author = doc.select("div:has(span:containsOwn(Authors)) a, div:has(span:containsOwn(Author)) a").textOrNull()
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

        // 2. Fetch Chapters via GraphQL
        // Extract ID from URL if not present (URL format: /comic/12345/slug)
        val comicId = manga.url.split("/").find { it.all { char -> char.isDigit() } } 
            ?: throw Exception("Could not find Comic ID in URL")

        val query = """
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

        val json = graphqlRequest(query, JSONObject().put("id", comicId))
        val chapterList = json.optJSONObject("data")
            ?.optJSONObject("get_comicChapterList")
            ?.optJSONArray("data") // In the TS code it iterates get_comicChapterList directly, but GQL usually wraps lists? 
                                   // TS: result?.data?.get_comicChapterList || []
                                   // The TS interface implies get_comicChapterList IS the array. 
                                   // But typically GQL returns objects. Let's assume it might be a direct array based on TS code logic
            ?: json.optJSONObject("data")?.optJSONArray("get_comicChapterList") // Safety fallback

        val chapters = mutableListOf<MangaChapter>()
        
        if (chapterList != null) {
            for (i in 0 until chapterList.length()) {
                val data = chapterList.getJSONObject(i).optJSONObject("data") ?: continue
                val dname = data.optString("dname")
                val titlePart = data.optString("title")
                val fullTitle = if (titlePart.isNotEmpty()) "$dname - $titlePart" else dname
                val dateTs = data.optLong("dateModify").takeIf { it > 0 } ?: data.optLong("dateCreate")
                
                chapters.add(
                    MangaChapter(
                        id = generateUid(data.optString("id")), // API ID is crucial for getPages
                        title = fullTitle,
                        number = parseChapterNumber(dname),
                        volume = 0,
                        url = data.optString("urlPath"),
                        uploadDate = dateTs * 1000L,
                        source = source,
                        scanlator = data.optJSONObject("userNode")?.optJSONObject("data")?.optString("name")
                            ?: data.optString("srcTitle"),
                        branch = null
                    )
                )
            }
        }

        return manga.copy(
            authors = setOfNotNull(details.first),
            description = details.second,
            state = details.third,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.id // This was set to the API ID in getDetails
        
        val query = """
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

        val json = graphqlRequest(query, JSONObject().put("id", chapterId))
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

    private suspend fun graphqlRequest(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }
        
        val responseBody = webClient.httpPost(
            url = apiUrl,
            payload = payload.toString(),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Referer" to "https://$domain/"
            )
        ).parseJson()
        
        val errors = responseBody.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw Exception("GraphQL Error: ${errors.getJSONObject(0).optString("message")}")
        }
        
        return responseBody
    }

    private fun buildUrl(path: String): String {
        return when {
            path.startsWith("http") -> path
            path.startsWith("/") -> "https://$domain$path"
            else -> "https://$domain/$path"
        }
    }

    private fun parseChapterNumber(dname: String): Float {
        val cleaned = dname.replace(Regex("^Vol\\.\\s*\\S+\\s+", RegexOption.IGNORE_CASE), "")
        if (cleaned.contains("Bonus", ignoreCase = true)) return -2f // Arbitrary negative for bonus
        
        val match = Regex("(?:Ch\\.|Chapter)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(cleaned)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
