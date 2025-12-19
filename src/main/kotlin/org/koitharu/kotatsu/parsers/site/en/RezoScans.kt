package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException // FIX: Added missing import
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("REZOSCANS", "Rezo Scans", "en")
internal class RezoScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.REZOSCANS, 18) {

    override val configKeyDomain = ConfigKey.Domain("rezoscan.org")
    
    private val apiUrl = "https://api.rezoscan.org/api"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = emptySet(),
        availableContentTypes = emptySet()
    )

    // ---------------------------------------------------------------
    // 1. List / Search
    // ---------------------------------------------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val urlBuilder = "$apiUrl/posts".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", pageSize.toString())
            .addQueryParameter("isNovel", "false")

        if (!filter.query.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("query", filter.query)
        } else {
            val tag = when (order) {
                SortOrder.POPULARITY -> "hot"
                SortOrder.NEWEST -> "new"
                else -> "new" // Default to "new" for UPDATED as well
            }
            urlBuilder.addQueryParameter("tag", tag)
        }

        val response = webClient.httpGet(urlBuilder.build()).parseJson()
        val posts = response.optJSONArray("posts") ?: return emptyList()

        val mangaList = ArrayList<Manga>(posts.length())
        for (i in 0 until posts.length()) {
            val item = posts.getJSONObject(i)
            mangaList.add(parseManga(item))
        }
        return mangaList
    }

    private fun parseManga(json: JSONObject): Manga {
        val slug = json.getString("slug")
        val url = "/series/$slug"
        
        val coverPath = json.optString("cover").takeIf { it.isNotEmpty() }
            ?: json.optString("thumbnail")
        val coverUrl = if (coverPath.startsWith("http")) coverPath else "https://api.rezoscan.org$coverPath"

        return Manga(
            id = generateUid(slug),
            title = json.optString("title", "Unknown"),
            url = url,
            publicUrl = "https://$domain$url",
            coverUrl = coverUrl,
            rating = RATING_UNKNOWN,
            source = source,
            altTitles = emptySet(),
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            contentRating = null
        )
    }

    // ---------------------------------------------------------------
    // 2. Details
    // ---------------------------------------------------------------
    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfterLast("/")
        
        val url = "$apiUrl/posts/slug/$slug"
        val json = webClient.httpGet(url.toHttpUrl()).parseJson()
        
        val post = json.optJSONObject("post") ?: json
        
        val description = post.optString("description")
        
        val statusStr = post.optString("status", "").lowercase()
        val state = when {
            statusStr.contains("ongoing") -> MangaState.ONGOING
            statusStr.contains("completed") -> MangaState.FINISHED
            statusStr.contains("drop") -> MangaState.ABANDONED
            statusStr.contains("hiatus") -> MangaState.PAUSED
            else -> null
        }

        val chaptersArray = post.optJSONArray("chapters") ?: org.json.JSONArray()
        val chapters = ArrayList<MangaChapter>(chaptersArray.length())
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val chSlug = ch.getString("slug")
            val chNum = ch.optDouble("number", 0.0).toFloat()
            val chName = ch.optString("title", "")
            
            val dateStr = ch.optString("created_at")
            val date = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) { 0L }

            val title = if (chName.isNotBlank()) "Chapter $chNum: $chName" else "Chapter $chNum"

            chapters.add(
                MangaChapter(
                    id = generateUid(chSlug),
                    title = title,
                    number = chNum,
                    volume = 0,
                    url = "/series/$slug/$chSlug",
                    uploadDate = date,
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }
        
        chapters.reverse()

        return manga.copy(
            description = description,
            state = state,
            chapters = chapters
        )
    }

    // ---------------------------------------------------------------
    // 3. Pages
    // ---------------------------------------------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/").filter { it.isNotBlank() }
        val mangaSlug = parts.getOrNull(1) ?: throw ParseException("Invalid chapter URL", chapter.url)
        val chapterSlug = parts.getOrNull(2) ?: throw ParseException("Invalid chapter URL", chapter.url)

        val url = "$apiUrl/posts/slug/$mangaSlug/chapter/$chapterSlug"
        val json = webClient.httpGet(url.toHttpUrl()).parseJson()
        
        val chapterData = json.optJSONObject("chapter") ?: json
        val images = chapterData.optJSONArray("images") 
            ?: throw ParseException("No images found", chapter.url)

        val pages = ArrayList<MangaPage>(images.length())
        for (i in 0 until images.length()) {
            val imgPath = images.getString(i)
            val imgUrl = if (imgPath.startsWith("http")) imgPath else "https://api.rezoscan.org$imgPath"
            
            pages.add(
                MangaPage(
                    id = generateUid(imgUrl),
                    url = imgUrl,
                    preview = null,
                    source = source
                )
            )
        }
        
        return pages
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
