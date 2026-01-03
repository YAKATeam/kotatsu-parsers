package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.math.BigDecimal
import java.math.RoundingMode

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.COMIX, 28) {

	override val configKeyDomain = ConfigKey.Domain("comix.to")
	private val apiBase = "api/v2"
	private val apiBaseUrl get() = "https://$domain/$apiBase"

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.build()

	private val nsfwGenreIds = listOf("87264", "8", "87265", "13", "87266", "87268")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(
		SortOrder.RELEVANCE,
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags()
	)

	// -------------------------
	// List / Search
	// -------------------------
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val builder = "$apiBaseUrl/manga".toHttpUrl().newBuilder().apply {
			if (!filter.query.isNullOrBlank()) {
				addQueryParameter("keyword", filter.query)
			}

			val (param, dir) = when (order) {
				SortOrder.RELEVANCE -> "relevance" to "desc"
				SortOrder.UPDATED -> "chapter_updated_at" to "desc"
				SortOrder.POPULARITY -> "views_30d" to "desc"
				SortOrder.NEWEST -> "created_at" to "desc"
				SortOrder.ALPHABETICAL -> "title" to "asc"
				else -> "chapter_updated_at" to "desc"
			}
			addQueryParameter("order[$param]", dir)

			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach { addQueryParameter("genres[]", it.key) }
			}
			if (filter.tagsExclude.isNotEmpty()) {
				filter.tagsExclude.forEach { addQueryParameter("genres[]", "-${it.key}") }
			}

			if (filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) {
				nsfwGenreIds.forEach { addQueryParameter("genres[]", "-$it") }
			}

			addQueryParameter("limit", pageSize.toString())
			addQueryParameter("page", page.toString())
		}

		val response = webClient.httpGet(builder.build()).parseJson()
		val items = response.optJSONObject("result")?.optJSONArray("items") ?: return emptyList()
		val list = ArrayList<Manga>(items.length())

		for (i in 0 until items.length()) {
			val it = items.optJSONObject(i) ?: continue
			list.add(parseMangaFromJson(it))
		}
		return list
	}

	private fun parseMangaFromJson(json: JSONObject): Manga {
		val hashId = json.optString("hash_id", "").nullIfEmpty()
		val slug = json.optString("slug", "").nullIfEmpty()
		val title = json.optString("title", "Unknown")
		val description = json.optString("synopsis", "").nullIfEmpty()

		val poster = json.optJSONObject("poster")
		val coverUrl = poster?.optString("medium", "")?.nullIfEmpty()
			?: poster?.optString("large", "")?.nullIfEmpty()
			?: poster?.optString("small", "")?.nullIfEmpty()
			?: ""

		val state = when (json.optString("status", "").lowercase()) {
			"finished" -> MangaState.FINISHED
			"releasing" -> MangaState.ONGOING
			"on_hiatus" -> MangaState.PAUSED
			"discontinued" -> MangaState.ABANDONED
			else -> null
		}

		val ratedAvg = json.optDouble("rated_avg", 0.0)
		val rating = if (ratedAvg > 0.0) (ratedAvg / 20.0).toFloat() else RATING_UNKNOWN

		val resolvedHash = hashId ?: UUID.randomUUID().toString()
		val urlSlug = if (slug != null) "$resolvedHash-$slug" else resolvedHash

		return Manga(
			id = generateUid(resolvedHash),
			url = "/title/$urlSlug",
			publicUrl = "https://$domain/title/$urlSlug",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			description = description,
			rating = rating,
			tags = emptySet(),
			authors = emptySet(),
			state = state,
			source = source,
			contentRating = if (json.optBoolean("is_nsfw", false)) ContentRating.ADULT else ContentRating.SAFE
		)
	}

	// -------------------------
	// Details
	// -------------------------
	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val hash = manga.url.substringAfter("/title/").substringBefore("-").nullIfEmpty()
			?: throw ParseException("Invalid manga URL", manga.url)

		val detailsUrl = "$apiBaseUrl/manga/$hash".toHttpUrl().newBuilder()
			.addQueryParameter("includes[]", "author")
			.addQueryParameter("includes[]", "artist")
			.addQueryParameter("includes[]", "genre")
			.addQueryParameter("includes[]", "theme")
			.addQueryParameter("includes[]", "demographic")
			.build()
		val detailsDeferred = async { webClient.httpGet(detailsUrl).parseJson() }
		val chaptersDeferred = async { getChapters(hash) }

		val response = runCatching { detailsDeferred.await() }.getOrDefault(JSONObject())
		val chapters = runCatching { chaptersDeferred.await() }.getOrDefault(emptyList())

		val result = response.optJSONObject("result")
		if (result != null) {
			val updated = parseMangaFromJson(result)

			val authors = LinkedHashSet<String>()
			result.optJSONArray("author")?.let { arr ->
				for (i in 0 until arr.length()) {
					arr.optJSONObject(i)?.optString("title")?.nullIfEmpty()?.let { authors.add(it) }
				}
			}
			result.optJSONArray("artist")?.let { arr ->
				for (i in 0 until arr.length()) {
					arr.optJSONObject(i)?.optString("title")?.nullIfEmpty()?.let { authors.add(it) }
				}
			}

			val tags = mutableSetOf<MangaTag>()
			fun addTags(field: String) {
				result.optJSONArray(field)?.let { arr ->
					for (i in 0 until arr.length()) {
						val o = arr.optJSONObject(i) ?: continue
						val name = o.optString("title", "").nullIfEmpty() ?: continue
						val id = o.optInt("term_id", 0).takeIf { it != 0 }?.toString() ?: continue
						tags.add(MangaTag(title = name, key = id, source = source))
					}
				}
			}
			addTags("genre"); addTags("theme"); addTags("demographic")

			val ratedAvg = result.optDouble("rated_avg", 0.0)
			val synopsis = result.optString("synopsis", "")
			val altTitles = result.optJSONArray("alt_titles")?.let { arr ->
				(0 until arr.length()).map { arr.getString(it) }
			} ?: emptyList()

			val newDesc = buildString {
				val fancyScore = generateFancyScore(ratedAvg)
				if (fancyScore.isNotEmpty()) {
					append(fancyScore).append("\n\n")
				}
				append(synopsis)
				if (altTitles.isNotEmpty()) {
					append("\n\nAlternative Names:\n")
					append(altTitles.joinToString("\n"))
				}
			}

			return@coroutineScope updated.copy(
				chapters = chapters,
				authors = authors,
				tags = tags,
				description = newDesc,
				altTitles = altTitles.toSet()
			)
		}

		return@coroutineScope manga.copy(chapters = chapters)
	}

	private fun generateFancyScore(ratedAvg: Double): String {
		if (ratedAvg == 0.0) return ""
		val score = BigDecimal(ratedAvg).setScale(1, RoundingMode.HALF_UP)
		val stars = score.divide(BigDecimal(20), 0, RoundingMode.HALF_UP).toInt()

		return buildString {
			append("★".repeat(stars))
			if (stars < 5) append("☆".repeat(5 - stars))
			append(" ").append(score.toPlainString())
		}
	}

	// -------------------------
	// Chapters
	// -------------------------
	private suspend fun getChapters(hashId: String): List<MangaChapter> = coroutineScope {
		val firstPageUrl = "$apiBaseUrl/manga/$hashId/chapters".toHttpUrl().newBuilder()
			.addQueryParameter("order[number]", "desc")
			.addQueryParameter("limit", "100")
			.addQueryParameter("page", "1")
			.build()

		val firstResp = runCatching { webClient.httpGet(firstPageUrl).parseJson() }.getOrNull()
			?: return@coroutineScope emptyList()

		val result = firstResp.optJSONObject("result") ?: return@coroutineScope emptyList()
		val firstItems = result.optJSONArray("items") ?: JSONArray()
		val pagination = result.optJSONObject("pagination")
		val totalPages = pagination?.optInt("last_page", 1) ?: 1

		val allItems = mutableListOf<JSONObject>()
		for (i in 0 until firstItems.length()) {
			firstItems.optJSONObject(i)?.let { allItems.add(it) }
		}

		if (totalPages > 1) {
			val deferreds = (2..totalPages).map {
				async {
					val url = "$apiBaseUrl/manga/$hashId/chapters".toHttpUrl().newBuilder()
						.addQueryParameter("order[number]", "desc")
						.addQueryParameter("limit", "100")
						.addQueryParameter("page", it.toString())
						.build()
					runCatching {
							webClient.httpGet(url).parseJson().optJSONObject("result")?.optJSONArray("items")
						}.getOrNull()
				}
			}
			deferreds.awaitAll().filterNotNull().forEach {
				for (i in 0 until it.length()) {
					it.optJSONObject(i)?.let { allItems.add(it) }
				}
			}
		}

		return@coroutineScope allItems.mapNotNull {
			val num = it.optDouble("number", Double.NaN)
			if (num.isNaN()) return@mapNotNull null

			val chapterId = it.optLong("chapter_id", 0L)
			val number = num.toFloat()
			val name = it.optString("name", "").nullIfEmpty()
			val createdAt = it.optLong("created_at", 0L)

			val scanlationGroup = it.optJSONObject("scanlation_group")
			var scanlatorName = scanlationGroup?.optString("name", null)?.nullIfEmpty()

			if (scanlatorName == null && it.optInt("is_official", 0) == 1) {
				scanlatorName = "Official"
			}

			val groupId = it.optInt("scanlation_group_id", 0)

			val title = buildString {
				if (it.optString("volume", "0") != "0") append("Vol. ").append(it.optString("volume")).append(" ")
				append("Ch. ").append(if (number == number.toLong().toFloat()) number.toLong() else number)
				if (name != null) append(" - ").append(name)
				if (scanlatorName != null) append(" [").append(scanlatorName).append("]")
			}.trim()

			val uid = if (chapterId != 0L) "$chapterId-$groupId" else UUID.randomUUID().toString()

			MangaChapter(
				id = generateUid(uid),
				title = title.ifBlank { "Chapter $number" },
				number = number,
				volume = it.optString("volume", "0").toIntOrNull() ?: 0,
				url = "/title/$hashId/dummy-slug/$chapterId-chapter-${number.toInt()}",
				uploadDate = createdAt * 1000L,
				source = source,
				scanlator = scanlatorName,
				branch = scanlatorName
			)
		}.sortedBy { it.number }
	}

	// -------------------------
	// Pages
	// -------------------------
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
		val response = webClient.httpGet("$apiBaseUrl/chapters/$chapterId").parseJson()

		val images = response.optJSONObject("result")?.optJSONArray("images") ?: return emptyList()
		val pages = ArrayList<MangaPage>(images.length())

		for (i in 0 until images.length()) {
			val imgObj = images.optJSONObject(i) ?: continue
			val url = imgObj.optString("url", "").nullIfEmpty() ?: continue

			pages.add(MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source
			))
		}

		return pages
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val currentTime = System.currentTimeMillis()
		synchronized(lock) {
			if (cachedTags != null && currentTime - lastCacheTime < CACHE_DURATION) {
				return cachedTags!!
			}
		}

		val tags = mutableSetOf<MangaTag>()

		suspend fun fetch(type: String, page: Int = 1) {
			val url = "$apiBaseUrl/terms".toHttpUrl().newBuilder()
				.addQueryParameter("type", type)
				.addQueryParameter("page", page.toString())
				.build()
			val resp = runCatching { webClient.httpGet(url).parseJson() }.getOrNull() ?: return
			val result = resp.optJSONObject("result") ?: return
			val items = result.optJSONArray("items") ?: return
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				val id = item.optInt("term_id", 0).takeIf { it != 0 }?.toString() ?: continue
				val title = item.optString("title", "").nullIfEmpty() ?: continue
				tags.add(MangaTag(title = title, key = id, source = source))
			}
			val pagination = result.optJSONObject("pagination")
			val lastPage = pagination?.optInt("last_page", page) ?: page
			if (page < lastPage && page < 5) {
				fetch(type, page + 1)
			}
		}

		fetch("genre")
		fetch("theme")
		fetch("demographic")

		val resultSet = tags.toSet()
		synchronized(lock) {
			cachedTags = resultSet
			lastCacheTime = currentTime
		}
		return resultSet
	}

	companion object {
		private var cachedTags: Set<MangaTag>? = null
		private var lastCacheTime: Long = 0
		private const val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours
		private val lock = Any()
	}
}
