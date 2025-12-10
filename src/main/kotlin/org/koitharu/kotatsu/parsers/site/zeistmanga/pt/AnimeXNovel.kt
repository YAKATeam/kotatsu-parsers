package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ANIMEXNOVEL", "AnimeXNovel", "pt")
internal class AnimeXNovel(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ANIMEXNOVEL, 24) {

    override val configKeyDomain = ConfigKey.Domain("animexnovel.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

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
    // 1. List / Search (Hybrid: HTML for Popular, AJAX for Search)
    // ---------------------------------------------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return if (!filter.query.isNullOrEmpty()) {
            searchManga(filter.query)
        } else {
            // Popular/Latest from HTML
            val endpoint = if (order == SortOrder.POPULARITY) "/mangas" else "/"
            val url = "https://$domain$endpoint".toHttpUrl()
            
            // Selector differs based on page type
            val selector = if (order == SortOrder.POPULARITY) {
                ".eael-post-grid-container article"
            } else {
                "div:contains(Últimos Mangás) + div .manga-card"
            }

            val doc = webClient.httpGet(url).parseHtml()
            doc.select(selector).mapNotNull { element ->
                val link = element.selectFirst("a") ?: return@mapNotNull null
                val href = link.attrAsRelativeUrl("href")
                Manga(
                    id = generateUid(href),
                    title = element.selectFirst("h2, h3, .search-content")?.text() ?: "Unknown",
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = element.selectFirst("img")?.src(),
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            }
        }
    }

    private suspend fun searchManga(query: String): List<Manga> {
        val url = "https://$domain/wp-admin/admin-ajax.php".toHttpUrl()
        
        // Manual form body string construction for Kotatsu webClient
        val payload = "action=newscrunch_live_search&keyword=${query.urlEncoded()}"
        
        val doc = webClient.httpPost(
            url = url,
            payload = payload,
            extraHeaders = headers.newBuilder()
                .add("Content-Type", "application/x-www-form-urlencoded")
                .build()
        ).parseHtml()

        return doc.select(".search-wrapper").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            var href = link.attrAsRelativeUrl("href")
            
            // Fix URL if it points to a chapter (remove 'capitulo-X')
            if (href.contains("capitulo")) {
                href = href.substringBeforeLast("capitulo")
            }
            
            // Clean title based on regex (remove trailing dash or numbers)
            val rawTitle = element.selectFirst(".search-content")?.text() ?: "Unknown"
            val title = rawTitle.replace(Regex("""[-–][^-–]*$"""), "").trim()

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = element.selectFirst("img")?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // 2. Details
    // ---------------------------------------------------------------
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val author = doc.selectFirst("li:contains(Autor:)")?.text()?.substringAfter(":")?.trim()
        val artist = doc.selectFirst("li:contains(Arte:)")?.text()?.substringAfter(":")?.trim()
        val description = doc.selectFirst("meta[itemprop='description']")?.attr("content")
        
        // Extract Category ID required for the WordPress API call
        val categoryId = doc.selectFirst("#container-capitulos")?.attr("data-categoria")
            ?: throw ParseException("Could not find category ID for chapters", manga.url)

        val chapters = fetchChaptersApi(categoryId)

        return manga.copy(
            title = doc.selectFirst("h2.spnc-entry-title")?.text() ?: manga.title,
            authors = setOfNotNull(author, artist),
            description = description,
            chapters = chapters,
            tags = emptySet() 
        )
    }

    // ---------------------------------------------------------------
    // 3. Chapters (WordPress REST API)
    // ---------------------------------------------------------------
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    private suspend fun fetchChaptersApi(categoryId: String): List<MangaChapter> {
        val allChapters = ArrayList<MangaChapter>()
        var page = 1
        
        // Loop until no more chapters
        while (true) {
            val apiUrl = "https://$domain/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
                .addQueryParameter("categories", categoryId)
                .addQueryParameter("orderby", "date")
                .addQueryParameter("order", "desc")
                .addQueryParameter("per_page", "100")
                .addQueryParameter("page", page.toString())
                .build()

            val response = try {
                webClient.httpGet(apiUrl).parseJson()
            } catch (e: Exception) {
                break // Stop on 404/Error (End of list)
            }

            if (response !is JSONArray) break
            if (response.length() == 0) break

            for (i in 0 until response.length()) {
                val item = response.getJSONObject(i)
                val link = item.getString("link").toRelativeUrl(domain)
                
                // Tachiyomi filter: ensure it's a chapter link
                if (!link.contains("capitulo")) continue

                val titleObj = item.getJSONObject("title")
                val rawTitle = titleObj.getString("rendered")
                val cleanTitle = rawTitle.substringAfter(";").takeIf { it.isNotBlank() } ?: rawTitle
                
                val dateStr = item.optString("date").take(10)
                
                // Attempt to extract number from slug or title
                val slug = item.optString("slug")
                val number = Regex("""(\d+(\.\d+)?)""").findAll(slug).lastOrNull()?.value?.toFloatOrNull() ?: 0f

                allChapters.add(
                    MangaChapter(
                        id = generateUid(link),
                        title = cleanTitle,
                        number = number,
                        volume = 0,
                        url = link,
                        scanlator = null,
                        uploadDate = dateFormat.parseSafe(dateStr),
                        branch = null,
                        source = source
                    )
                )
            }
            page++
        }
        
        return allChapters
    }

    // ---------------------------------------------------------------
    // 4. Pages (HTML Scraping)
    // ---------------------------------------------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        // Selectors from Tachiyomi code
        val container = doc.selectFirst(".spice-block-img-gallery, .wp-block-gallery, .spnc-entry-content")
            ?: throw ParseException("Page container not found", chapter.url)

        return container.select("img").mapIndexed { i, img ->
            val url = img.src() ?: img.attr("data-src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
