package org.koitharu.kotatsu.parsers.site.vi

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
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NHENTAIWORLD", "Nhentai World", "vi", ContentType.HENTAI)
internal class NhentaiWorld(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NHENTAIWORLD, 24) {

    private val apiDomain = "nhentaiclub.cyou"
    private val cdnDomain = "i1.nhentaiclub.shop"
	override val configKeyDomain = ConfigKey.Domain("nhentaiclub.icu")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("origin", "https://$domain")
		.add("referer", "https://$domain")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
            isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val urlBuilder = urlBuilder()
            urlBuilder.host(apiDomain)
            urlBuilder.addPathSegment("comic")
            urlBuilder.addPathSegment("gets")

        // Sort
		urlBuilder.addQueryParameter(
			"sort",
			when (order) {
				SortOrder.UPDATED -> "recent-update"
				SortOrder.POPULARITY -> "view"
				else -> "recent-update"
			},
		)

        // Tag (once)
        if (filter.tags.isNotEmpty()) {
            val key = filter.tags.oneOrThrowIfMany()
                ?.key
                ?.splitByWhitespace()
                ?.joinToString("+") { it }
            urlBuilder.addQueryParameter("genre", key)
        }

        // Search
		if (!filter.query.isNullOrEmpty()) {
			urlBuilder.addQueryParameter(
                "search",
                filter.query.splitByWhitespace().joinToString("+") { it }
            )
		}

        // State
        if (filter.states.isNotEmpty()) {
            filter.states.oneOrThrowIfMany()?.let {
                urlBuilder.addQueryParameter(
                    "status",
                    when (it) {
                        MangaState.FINISHED -> "completed"
                        else -> "progress"
                    }
                )
            }
        }

        // Author
        if (!filter.author.isNullOrEmpty()) {
            urlBuilder.addQueryParameter(
                "author",
                filter.author.splitByWhitespace().joinToString("+") { it }
            )
        }

        // Paging
		urlBuilder.addQueryParameter("page", page.toString())

		val res = webClient.httpGet(urlBuilder.build()).parseJson()
		return res.getJSONArray("data").mapJSONNotNull { ja ->
			val id = ja.getLong("id")
            val thumbnail = "$cdnDomain/$id/thumbnail.jpg"
			Manga(
				id = generateUid(id),
				title = ja.getString("name"),
				altTitles = emptySet(),
				url = id.toString(),
				publicUrl = "/g/$id".toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = thumbnail,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val root = doc.selectFirst("div.flex-1.bg-neutral-900") ?: return manga
		val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("GMT+7")
		}

		val tags = root.select("div.flex.flex-wrap.gap-2 button").mapNotNullToSet { button ->
			val tagName = button.text().toTitleCase(sourceLocale)
			val tagUrl = button.parent()?.attrOrNull("href")?.substringAfterLast('/')
			if (tagUrl != null) {
				MangaTag(title = tagName, key = tagUrl, source = source)
			} else {
				null
			}
		}

		val state = when {
			root.selectFirst("a[href*='status=completed']") != null -> MangaState.FINISHED
			root.selectFirst("a[href*='status=progress']") != null -> MangaState.ONGOING
			else -> null
		}

		val description = root.selectFirst("div#introduction-wrap p.font-light")?.html()?.nullIfEmpty()

		val altTitles = description?.split("\n")?.mapNotNullToSet { line ->
			when {
				line.startsWith("Tên tiếng anh:", ignoreCase = true) ->
					line.substringAfter(':').substringBefore("Tên gốc:").trim()

				line.startsWith("Tên gốc:", ignoreCase = true) ->
					line.substringAfter(':').trim().substringBefore(' ')

				else -> null
			}
		}

		val scriptTag = doc.select("script").firstOrNull { script ->
			val data = script.data()
			data.contains("data") && data.contains("chapterListEn")
		}?.data()
		val chapters = parseChapterList(scriptTag, manga, chapterDateFormat)

		return manga.copy(
			title = doc.selectFirst("h1")!!.text(),
			tags = tags,
			state = state,
			description = description,
			altTitles = altTitles.orEmpty(),
			chapters = chapters.reversed(),
		)
	}

	private fun parseChapterList(scriptTag: String?, manga: Manga, chapterDateFormat: SimpleDateFormat): List<MangaChapter> {
		val idManga = manga.url.substringAfter("detail/").toIntOrNull() ?: return emptyList()

		val chapters = ArrayList<MangaChapter>()
		if (scriptTag.isNullOrEmpty()) return chapters

		val cleanedScript = scriptTag.replace("\\", "")

		val cutScript = "null,{\"data\""
		val needScript = cleanedScript.indexOf(cutScript)
		if (needScript == -1) return chapters
		val finalScript = cleanedScript.substring(needScript)

		val vnPrefix = "null,{\"data\":"
		val vnStart = finalScript.indexOf(vnPrefix)
		if (vnStart == -1) return chapters
		val beforeEn = ",\"chapterListEn\""
		val vnEnd = finalScript.indexOf(beforeEn, vnStart)
		if (vnEnd == -1) return chapters
		val vnChapterStr = finalScript.substring(vnStart + vnPrefix.length, vnEnd)

		val vnArray = try {
			JSONArray(vnChapterStr)
		} catch (_: Exception) {
			JSONArray()
		}

		for (i in 0 until vnArray.length()) {
			val chapter = vnArray.getJSONObject(i)
			val name = chapter.optString("name", null) ?: continue
			val uploadDateStr = chapter.optString("createdAt", null)
			val uploadDate = chapterDateFormat.parseSafe(uploadDateStr)
			val href = "${idManga}/${name}?lang=VI"
			chapters.add(
				MangaChapter(
					id = generateUid(href),
					title = if (name.toFloatOrNull() != null) "Chapter $name" else name,
					number = name.toFloatOrNull() ?: (i + 1).toFloat(),
					url = "/read/${href}",
					scanlator = null,
					uploadDate = uploadDate,
					branch = "Tiếng Việt",
					source = source,
					volume = 0
				)
			)
		}

		// Copy + Paste from VI
		val enPrefix = ",\"chapterListEn\":"
		val enStart = finalScript.indexOf(enPrefix)
		if (enStart == -1) return chapters
		val beforeId = ",\"id\""
		val enEnd = finalScript.indexOf(beforeId, enStart)
		if (enEnd == -1) return chapters
		val enChapterStr = finalScript.substring(enStart + enPrefix.length, enEnd)

		val enArray = try {
			JSONArray(enChapterStr)
		} catch (_: Exception) {
			JSONArray()
		}

		for (i in 0 until enArray.length()) {
			val chapter = enArray.getJSONObject(i)
			val name = chapter.optString("name", null) ?: continue
			val uploadDateStr = chapter.optString("createdAt", null)
			val uploadDate = chapterDateFormat.parseSafe(uploadDateStr)
			val href = "${idManga}/${name}?lang=EN"
			chapters.add(
				MangaChapter(
					id = generateUid(href),
					title = if (name.toFloatOrNull() != null) "Chapter $name" else name,
					number = name.toFloatOrNull() ?: (i + 1).toFloat(),
					url = "/read/${href}",
					scanlator = null,
					uploadDate = uploadDate,
					branch = "English",
					source = source,
					volume = 0
				)
			)
		}

		return chapters
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()
		val root = doc.select("img.m-auto.read-image.w-auto.h-auto.md\\:min-h-\\[800px\\].min-h-\\[300px\\]")

		if (root.isEmpty()) { // for Debug #1604
			throw ParseException("Root not found!", url)
		}

		return root.map { img ->
			val imgUrl = img.requireSrc()
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		}
	}

    private suspend fun fetchTags(): Set<MangaTag> {
        val url = "https://$domain/_next/static/chunks/130-5a83ead201ba7c6a.js"
        val response = webClient.httpGet(url).parseRaw()

        val subString = response
            .substringAfter("{genres:")
            .substringBefore(",status")

        val ja = JSONObject("{\"genres\":$subString}")

        return ja.getJSONArray("genres").mapJSONToSet { jo ->
            val label = jo.getString("label")
            MangaTag(label, label, source)
        }
    }
}
