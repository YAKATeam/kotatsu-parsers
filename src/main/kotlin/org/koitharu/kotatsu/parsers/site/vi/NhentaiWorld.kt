package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.mapJSONIndexed
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NHENTAIWORLD", "Nhentai World", "vi", ContentType.HENTAI)
internal class NhentaiWorld(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NHENTAIWORLD, 24) {

    private val apiDomain = "nhentaiclub.cyou"
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
			val url = id.toString()
            val cdn = getCDNDomain(url)
			Manga(
				id = generateUid(id),
				title = ja.getString("name"),
				altTitles = emptySet(),
				url = url,
				publicUrl = "/g/$id".toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = "https://$cdn/$id/thumbnail.jpg",
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$apiDomain/comic/get/${manga.url}"
        val jo = webClient.httpGet(url).parseJson()

        val description = jo.optString("introduction").ifBlank { null }
		val authors = jo.optString("author")
			?.takeIf { it.isNotBlank() }
			?.split(",")
			?.map { it.trim() }
			?.toSet()
			?: emptySet()

        val state = when (jo.optString("status")) {
            "completed" -> MangaState.FINISHED
            "progress" -> MangaState.ONGOING
            else -> null
        }

        val tags = jo.optJSONArray("genres")?.asTypedList<String>()?.mapToSet { tag ->
			MangaTag(tag, tag, source)
		} ?: emptySet()

        // List chapters (Vietnamese + English)
		val df = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
		val vi = jo.getJSONArray("chapterList").mapJSONIndexed { i, vi ->
			val locale = Locale.forLanguageTag("vi")
			val name = vi.getString("name")

			// Can contains "Oneshot"
			val number = parseChapterNumber(name, i)

			// Special URL
			val url = urlBuilder()
				url.addQueryParameter("name", name)
				url.addQueryParameter("language", "VI")
				url.addQueryParameter("pictures", vi.getInt("pictures").toString())
				url.addQueryParameter("mangaId", manga.url)
			.build()

			MangaChapter(
				id = generateUid(name),
				title = name,
				number = number,
				volume = 0,
				url = url.toString(),
				scanlator = null,
				uploadDate = df.parseSafe(vi.getString("createdAt")),
				branch = locale.getDisplayName(locale).toTitleCase(locale),
				source = source,
			)
		}

		val en = jo.getJSONArray("chapterListEn").mapJSONIndexed { i, en ->
			val locale = Locale.forLanguageTag("en")
			val name = en.getString("name")
			val number = parseChapterNumber(name, i)
			val url = urlBuilder()
				url.addQueryParameter("name", name)
				url.addQueryParameter("language", "EN")
				url.addQueryParameter("pictures", en.getInt("pictures").toString())
				url.addQueryParameter("mangaId", manga.url)
			.build()
			MangaChapter(
				id = generateUid(name),
				title = name,
				number = number,
				volume = 0,
				url = url.toString(),
				scanlator = null,
				uploadDate = df.parseSafe(en.getString("createdAt")),
				branch = locale.getDisplayName(locale).toTitleCase(locale),
				source = source,
			)
		}

        return manga.copy(
            tags = tags,
            state = state,
            authors = authors,
            description = description,
            chapters = (vi + en).sortedBy { it.number },
        )
    }

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val params = parseQueryParams(chapter.url)

		val name = URLDecoder.decode(params["name"], "UTF-8")
			?: chapter.url.substringAfter("name=").substringBefore("&language")
				.ifBlank { throw ParseException("Cant get chapter name", chapter.url) }

		val language = params["language"]
			?: chapter.url.substringAfter("language=").substringBefore("&pictures")
				.ifBlank { throw ParseException("Cant get chapter language", chapter.url) }

		val pictures = params["pictures"]?.toIntOrNull()
			?: chapter.url.substringAfter("pictures=").substringAfter("&mangaId")
				.ifBlank { throw ParseException("Cant get chapter images", chapter.url) }
				.toInt()

		val mangaId = params["mangaId"]
			?: chapter.url.substringAfter("mangaId=").ifBlank {
				throw ParseException("Cant get manga ID for images", chapter.url)
			}

		val imgDomain = getCDNDomain(mangaId)
		return (1..pictures).map { pageNumber ->
			val imgUrl = "https://$imgDomain/$mangaId/$language/$name/$pageNumber.jpg"
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

	private fun getCDNDomain(mangaId: String): String {
		val firstDigit = mangaId.firstOrNull()?.digitToIntOrNull() ?: 0
		return when {
			firstDigit < 4 -> "i7.nhentaiclub.shop"
			firstDigit < 8 -> "i3.nhentaiclub.shop"
			else -> "i1.nhentaiclub.shop"
		}
	}

	private fun parseChapterNumber(name: String, index: Int): Float {
		if (name.contains("oneshot", ignoreCase = true)) return 0f
		return Regex("""^\d+(\.\d+)?""").find(name)?.value?.toFloat()
			?: (index + 1).toFloat()
	}

	private fun parseQueryParams(url: String): Map<String, String> {
		return url.substringAfter("?", "")
			.split("&")
			.mapNotNull {
				val parts = it.split("=", limit = 2)
				if (parts.size == 2) parts[0] to parts[1] else null
			}
			.toMap()
	}
}
