package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

@MangaSourceParser("WEEBDEX", "WeebDex")
internal class WeebDex(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WEEBDEX, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("weebdex.org")
	private val apiUrl = "https://api.weebdex.org"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isOriginalLocaleSupported = true
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchTags(),
			availableStates = EnumSet.allOf(MangaState::class.java),
			availableContentRating = EnumSet.of(
				ContentRating.SAFE,
				ContentRating.SUGGESTIVE,
				ContentRating.ADULT // Fixed: Combined Erotica/Pornographic into ADULT
			),
			availableLocales = setOf(
				Locale.ENGLISH,
				Locale.JAPANESE,
				Locale.CHINESE,
				Locale.KOREAN,
				Locale("id") // Indonesian
			)
		)
	}

	override fun getRequestHeaders(): Headers =
		super.getRequestHeaders().newBuilder()
			.add("Origin", "https://$domain")
			.add("Referer", "https://$domain/")
			.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("$apiUrl/manga?limit=$pageSize&page=$page")

			if (!filter.query.isNullOrEmpty()) {
				append("&title=${filter.query.urlEncoded()}")
			}

			// Sort mapping
			val sortOrder = when (order) {
				SortOrder.UPDATED -> "updatedAt"
				SortOrder.NEWEST -> "createdAt"
				SortOrder.ALPHABETICAL -> "title"
				SortOrder.RATING -> "followedCount"
				else -> "updatedAt"
			}
			append("&sort=$sortOrder")
			val direction = if (order == SortOrder.ALPHABETICAL) "asc" else "desc"
			append("&order=$direction")

			// Filters
			if (filter.contentRating.isNotEmpty()) {
				filter.contentRating.forEach { rating ->
					when (rating) {
						ContentRating.SAFE -> append("&contentRating=safe")
						ContentRating.SUGGESTIVE -> append("&contentRating=suggestive")
						else -> append("&contentRating=erotica&contentRating=pornographic")
					}
				}
			} else {
				append("&contentRating=safe&contentRating=suggestive")
			}

			if (filter.states.isNotEmpty()) {
				filter.states.forEach { state ->
					val statusParam = when (state) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						MangaState.ABANDONED -> "cancelled"
						else -> null
					}
					if (statusParam != null) append("&status=$statusParam")
				}
			}

			// Tags (Genres)
			filter.tags.forEach { tag ->
				append("&includedTags[]=${tag.key}")
			}

			// Apply Locale Filter if selected in search
			filter.originalLocale?.let { locale ->
				append("&originalLanguage[]=${locale.language}")
			}
		}

		val response = webClient.httpGet(url, getRequestHeaders()).parseJson()
		val data = response.optJSONArray("data") ?: return emptyList()

		return (0 until data.length()).mapNotNull { i ->
			val json = data.getJSONObject(i)
			if (json.optString("last_chapter").isEmpty() && json.optString("last_volume").isEmpty()) {
				val tagsArr = json.optJSONObject("relationships")?.optJSONArray("tags")
				var isOneshot = false
				if (tagsArr != null) {
					for (k in 0 until tagsArr.length()) {
						if (tagsArr.getJSONObject(k).getString("name") == "Oneshot") {
							isOneshot = true
							break
						}
					}
				}
				if (!isOneshot) return@mapNotNull null
			}
			parseMangaJson(json)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val originalId = manga.url.substringAfterLast("/")
		val mangaUrl = "$apiUrl/manga/$originalId"
		val response = webClient.httpGet(mangaUrl, getRequestHeaders()).parseJson()
		val baseManga = parseMangaJson(response)

		val allChapters = ArrayList<MangaChapter>()
		var page = 1
		val langParam = ""

		while (true) {
			val chaptersUrl = "$mangaUrl/chapters?limit=100&page=$page&order=desc$langParam"
			val chResponse = webClient.httpGet(chaptersUrl, getRequestHeaders()).parseJson()
			val data = chResponse.optJSONArray("data") ?: break

			if (data.length() == 0) break

			for (i in 0 until data.length()) {
				val ch = data.getJSONObject(i)
				val id = ch.getString("id")

				val vol = ch.optString("volume")
				val chapNum = ch.optString("chapter")
				val title = ch.optString("title")
				val lang = ch.optString("language")

				// Groups
				val groups = mutableListOf<String>()
				val rels = ch.optJSONObject("relationships")
				val groupArr = rels?.optJSONArray("groups")
				if (groupArr != null) {
					for (k in 0 until groupArr.length()) {
						groups.add(groupArr.getJSONObject(k).getString("name"))
					}
				}
				val scanlator = if (groups.isNotEmpty()) groups.joinToString(", ") else null

				// Title Formatting
				val volStr = if (vol.isNotEmpty() && vol != "null") "Vol. $vol " else ""
				val chStr = if (chapNum.isNotEmpty() && chapNum != "null") "Ch. $chapNum" else ""
				val titleStr = if (title.isNotEmpty() && title != "null") " - $title" else ""

				var fullTitle = "$volStr$chStr$titleStr".trim()
				if (fullTitle.isEmpty()) fullTitle = "Oneshot"

				if (lang.isNotEmpty()) fullTitle += " [$lang]"

				val numFloat = chapNum.toFloatOrNull() ?: -1f
				val dateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
					.parseSafe(ch.optString("updatedAt"))

				allChapters.add(
					MangaChapter(
						id = generateUid(id),
						title = fullTitle,
						number = numFloat,
						volume = vol.toIntOrNull() ?: 0,
						url = "/chapter/$id",
						uploadDate = dateStr,
						source = source,
						scanlator = scanlator,
						branch = null
					)
				)
			}

			val total = chResponse.optInt("total", 0)
			val limit = chResponse.optInt("limit", 100)
			val totalPages = ceil(total.toDouble() / limit).toInt()

			if (page >= totalPages) break
			page++
		}

		return baseManga.copy(chapters = allChapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/")
		val url = "$apiUrl/chapter/$chapterId"

		val response = webClient.httpGet(url, getRequestHeaders()).parseJson()
		val node = response.getString("node")
		val data = response.getJSONArray("data")

		return (0 until data.length()).map { i ->
			val pageObj = data.getJSONObject(i)
			val filename = pageObj.getString("name")
			val imageUrl = "$node/data/$chapterId/$filename"

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source
			)
		}
	}

	private fun parseMangaJson(json: JSONObject): Manga {
		val id = json.getString("id")
		val title = json.getString("title")
		val desc = json.optString("description")
		val statusStr = json.optString("status")

		val relationships = json.optJSONObject("relationships")

		var coverUrl: String? = null
		val coverObj = relationships?.optJSONObject("cover")
		if (coverObj != null) {
			val coverId = coverObj.getString("id")
			coverUrl = "https://srv.notdelta.xyz/covers/$id/$coverId.256.webp"
		}

		val authors = mutableSetOf<String>()
		val authorArr = relationships?.optJSONArray("authors")
		if (authorArr != null) {
			for (i in 0 until authorArr.length()) {
				authors.add(authorArr.getJSONObject(i).getString("name"))
			}
		}

		val tags = mutableSetOf<MangaTag>()
		val tagsArr = relationships?.optJSONArray("tags")
		if (tagsArr != null) {
			for (i in 0 until tagsArr.length()) {
				val t = tagsArr.getJSONObject(i)
				val tId = t.getString("id")
				val tName = t.getString("name")
				tags.add(MangaTag(key = tId, title = tName, source = source))
			}
		}

		val demo = json.optString("demographic")
		if (demo.isNotEmpty() && demo != "null") {
			tags.add(MangaTag(key = demo, title = demo.replaceFirstChar { it.uppercase() }, source = source))
		}

		val state = when (statusStr) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"cancelled" -> MangaState.ABANDONED
			else -> null
		}

		val ratingStr = json.optString("contentRating")
		val contentRating = when(ratingStr) {
			"erotica", "pornographic" -> ContentRating.ADULT
			"suggestive" -> ContentRating.SUGGESTIVE
			else -> ContentRating.SAFE
		}

		return Manga(
			id = generateUid(id),
			url = "/manga/$id",
			publicUrl = "https://$domain/title/$id",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			tags = tags,
			authors = authors,
			state = state,
			source = source,
			description = desc,
			contentRating = contentRating
		)
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val response = webClient.httpGet("$apiUrl/manga/tag").parseJson()
		val data = response.optJSONArray("data") ?: return emptySet()
		val tags = mutableSetOf<MangaTag>()
		for (i in 0 until data.length()) {
			val t = data.getJSONObject(i)
			tags.add(MangaTag(key = t.getString("id"), title = t.getString("name"), source = source))
		}
		return tags
	}
}
