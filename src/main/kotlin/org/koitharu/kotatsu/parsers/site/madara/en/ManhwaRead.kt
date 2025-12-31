package org.koitharu.kotatsu.parsers.site.madara.en

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("MANHWAREAD", "ManhwaRead", "en", ContentType.HENTAI)
internal class ManhwaRead(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANHWAREAD, "manhwaread.com", 30) {

    override val tagPrefix = "genre/"
    override val datePattern = "dd/MM/yyyy"
    override val listUrl = "manhwa/"
    override val withoutAjax = true

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/genre-index/").parseHtml()
        return doc.select("#mainTermsList li a").mapToSet { a ->
            MangaTag(
                key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
                title = a.selectFirst("span")?.text()?.toTitleCase().orEmpty(),
                source = source
            )
        }
    }

    override val selectTestAsync = "#chaptersList"
    override val selectDesc = "div.manga-desc__content"
    override val selectGenre = "div.manga-genres a"

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.manga-item").map { div ->
            val link = div.selectFirstOrThrow("a.manga-item__link")
            val href = link.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = div.selectFirst("img")?.src(),
                title = link.text().trim(),
                altTitles = emptySet(),
                rating = div.selectFirst(".manga-item__rating span")?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = when (div.selectFirst(".manga-status__label")?.text()?.lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                },
                source = source,
                contentRating = ContentRating.ADULT
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val desc = doc.select(selectDesc).textOrNull()
        val genres = doc.select(selectGenre).mapNotNullToSet {
            MangaTag(
                key = it.attr("href").removeSuffix("/").substringAfterLast('/'),
                title = it.text().toTitleCase(),
                source = source
            )
        }

        val statusText = doc.selectFirst(".manga-status__label")
			?.text()?.lowercase()
			.orEmpty()

        val status = when {
            statusText.contains("ongoing") -> MangaState.ONGOING
            statusText.contains("completed") -> MangaState.FINISHED
            statusText.contains("hiatus") -> MangaState.PAUSED
            statusText.contains("dropped") -> MangaState.ABANDONED
            else -> null
        }

        val authors = doc.select("a[href*='/author/'], a[href*='/artist/']").mapNotNullToSet {
            it.text()
        }

        return manga.copy(
            description = desc,
            tags = genres,
            state = status,
            authors = authors,
            chapters = getChapters(manga, doc)
        )
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        return doc.select("#chaptersList a.chapter-item").mapChapters(reversed = true) { i, a ->
            val href = a.attrAsRelativeUrl("href")
            val name = a.selectFirst(".chapter-item__name")?.text()
				?: a.text()
            val dateText = a.selectFirst(".chapter-item__date")?.text()

            MangaChapter(
                id = generateUid(href),
                title = name,
                number = i + 1f,
                volume = 0,
                url = href,
                uploadDate = parseChapterDate(dateFormat, dateText),
                source = source,
                scanlator = null,
                branch = null
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val script = doc.selectFirst("script#single-chapter-js-extra")?.data()
            ?: throw ParseException("Chapter data not found", fullUrl)

        val jsonStr = script.substringAfter("var chapterData = ").substringBeforeLast(";")
        val jsonObj = JSONObject(jsonStr)
        val dataEncoded = jsonObj.getString("data")
        val jsonArray = JSONArray(String(context.decodeBase64(dataEncoded)))
        val base = jsonObj.optString("base", "").removeSuffix("/")
		return List(jsonArray.length()) { i ->
			val item = jsonArray.getJSONObject(i)
			val src = item.getString("src").removePrefix("/")
			val url = if (base.isNotEmpty()) "$base/$src" else src
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source
			)
		}
    }
}
