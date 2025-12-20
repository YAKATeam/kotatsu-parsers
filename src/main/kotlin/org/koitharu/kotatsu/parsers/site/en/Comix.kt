package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.Response
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
	PagedMangaParser(context, MangaParserSource.COMIX, 28), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("comix.to")
	private val apiBase = "api/v2"
	private val apiBaseUrl get() = "https://$domain/$apiBase"

	// FIX: Removed 'override val webClient'.
	// The base class automatically wires up the Interceptor when the interface is implemented.

	// Interceptor to add Referer to ALL requests
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request().newBuilder()
			.addHeader("Referer", "https://$domain/")
			.addHeader("Origin", "https://$domain")
			.build()
		return chain.proceed(request)
	}

	private val nsfwGenreIds = listOf("87264", "8", "87265", "13", "87266", "87268")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true
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

		val resolvedHash = hashId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
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

		val detailsUrl = "$apiBaseUrl/manga/$hash".toHttpUrl().newBuilder().build()
		val detailsDeferred = async { webClient.httpGet(detailsUrl).parseJson() }
		val chaptersDeferred = async { getChapters(hash) }

		val response = try {
			detailsDeferred.await()
		} catch (e: Exception) {
			JSONObject()
		}
		val chapters = try {
			chaptersDeferred.await()
		} catch (e: Exception) {
			emptyList()
		}

		val result = response.optJSONObject("result")
		if (result != null) {
			val updated = parseMangaFromJson(result)

			val authors = result.optJSONArray("author")?.let { arr ->
				(0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title")?.nullIfEmpty() }.toSet()
			} ?: emptySet()

			val tags = mutableSetOf<MangaTag>()
			fun addTags(field: String) {
				result.optJSONArray(field)?.let { arr ->
					for (i in 0 until arr.length()) {
						val o = arr.optJSONObject(i) ?: continue
						val name = o.optString("title", "").nullIfEmpty() ?: continue
						val id = o.optInt("term_id", 0).takeIf { it != 0 }?.toString() ?: continue
						tags.add(MangaTag(key = id, title = name, source = source))
					}
				}
			}
			addTags("genre"); addTags("theme"); addTags("demographic")

			val ratedAvg = result.optDouble("rated_avg", 0.0)
			val fancyScore = generateFancyScore(ratedAvg)
			val synopsis = result.optString("synopsis", "")
			val altTitles = result.optJSONArray("alt_titles")?.let { arr ->
				(0 until arr.length()).map { arr.getString(it) }
			} ?: emptyList()

			val newDesc = buildString {
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
			append(" ${score.toPlainString()}")
		}
	}

	// -------------------------
	// Chapters
	// -------------------------
	private suspend fun getChapters(hashId: String): List<MangaChapter> {
		val allItems = ArrayList<JSONObject>()
		var page = 1
		var lastPage = Int.MAX_VALUE

		while (page <= lastPage && page <= 200) {
			val url = "$apiBaseUrl/manga/$hashId/chapters".toHttpUrl().newBuilder().apply {
				addQueryParameter("order[number]", "desc")
				addQueryParameter("limit", "100")
				addQueryParameter("page", page.toString())
			}.build()

			val resp = try {
				webClient.httpGet(url).parseJson()
			} catch (e: Exception) {
				break
			}

			val items = resp.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				allItems.add(item)
			}

			val pagination = resp.optJSONObject("result")?.optJSONObject("pagination")
			if (pagination != null) {
				lastPage = pagination.optInt("last_page", page)
			} else {
				if (items.length() < 100) lastPage = page
			}
			page++
		}

		return allItems.mapNotNull { item ->
			val num = item.optDouble("number", Double.NaN)
			if (num.isNaN()) return@mapNotNull null

			val chapterId = item.optLong("chapter_id", 0L)
			val number = num.toFloat()
			val name = item.optString("name", "").nullIfEmpty()
			val createdAt = item.optLong("created_at", 0L)

			val scanlationGroup = item.optJSONObject("scanlation_group")
			var scanlatorName = scanlationGroup?.optString("name", null)?.nullIfEmpty()

			if (scanlatorName == null && item.optInt("is_official", 0) == 1) {
				scanlatorName = "Official"
			}

			val groupId = item.optInt("scanlation_group_id", 0)

			val title = buildString {
				if (item.optString("volume", "0") != "0") append("Vol. ${item.optString("volume")} ")
				append("Ch. ${if (number == number.toLong().toFloat()) number.toLong() else number}")
				if (name != null) append(" - ").append(name)
				if (scanlatorName != null) append(" [$scanlatorName]")
			}.trim()

			val uid = if (chapterId != 0L) "$chapterId-$groupId" else UUID.randomUUID().toString()

			MangaChapter(
				id = generateUid(uid),
				title = title.ifBlank { "Chapter $number" },
				number = number,
				volume = item.optString("volume", "0").toIntOrNull() ?: 0,
				url = "/title/$hashId/dummy-slug/$chapterId-chapter-${number.toInt()}",
				uploadDate = createdAt * 1000L,
				source = source,
				scanlator = scanlatorName,
				branch = null
			)
		}
	}

	// -------------------------
	// Pages
	// -------------------------
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = "https://$domain${chapter.url}"

		val response = try {
			webClient.httpGet(chapterUrl)
		} catch (e: Exception) {
			throw ParseException("Failed to load chapter page", chapterUrl)
		}

		val body = response.body?.string()
			?: throw ParseException("Empty response body", chapterUrl)

		val regex = Regex("""["\\]*images["\\]*\s*:\s*(\[[^\]]*(?:\](?!\s*[,\]}])|[^\]]*)\])""", RegexOption.DOT_MATCHES_ALL)

		val match = regex.find(body)
		if (match == null || match.groupValues.size < 2) {
			throw ParseException("Images regex NOT matched", chapterUrl)
		}

		val jsonStringRaw = match.groupValues[1]
		val jsonStringClean = jsonStringRaw.replace("\\\"", "\"")

		val imagesJson = try {
			JSONArray(jsonStringClean)
		} catch (e: Exception) {
			throw ParseException("Failed to parse images JSON", chapterUrl)
		}

		val pages = ArrayList<MangaPage>(imagesJson.length())
		for (i in 0 until imagesJson.length()) {
			val imgObj = imagesJson.optJSONObject(i) ?: continue
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

	private fun fetchAvailableTags(): Set<MangaTag> = setOf(
		MangaTag("6", "Action", source),
		MangaTag("87264", "Adult", source),
		MangaTag("7", "Adventure", source),
		MangaTag("8", "Boys Love", source),
		MangaTag("9", "Comedy", source),
		MangaTag("10", "Crime", source),
		MangaTag("11", "Drama", source),
		MangaTag("87265", "Ecchi", source),
		MangaTag("12", "Fantasy", source),
		MangaTag("13", "Girls Love", source),
		MangaTag("87266", "Hentai", source),
		MangaTag("14", "Historical", source),
		MangaTag("15", "Horror", source),
		MangaTag("16", "Isekai", source),
		MangaTag("17", "Magical Girls", source),
		MangaTag("87267", "Mature", source),
		MangaTag("18", "Mecha", source),
		MangaTag("19", "Medical", source),
		MangaTag("20", "Mystery", source),
		MangaTag("21", "Philosophical", source),
		MangaTag("22", "Psychological", source),
		MangaTag("23", "Romance", source),
		MangaTag("24", "Sci-Fi", source),
		MangaTag("25", "Slice of Life", source),
		MangaTag("87268", "Smut", source),
		MangaTag("26", "Sports", source),
		MangaTag("27", "Superhero", source),
		MangaTag("28", "Thriller", source),
		MangaTag("29", "Tragedy", source),
		MangaTag("30", "Wuxia", source),
		MangaTag("31", "Aliens", source),
		MangaTag("32", "Animals", source),
		MangaTag("33", "Cooking", source),
		MangaTag("34", "Crossdressing", source),
		MangaTag("35", "Delinquents", source),
		MangaTag("36", "Demons", source),
		MangaTag("37", "Genderswap", source),
		MangaTag("38", "Ghosts", source),
		MangaTag("39", "Gyaru", source),
		MangaTag("40", "Harem", source),
		MangaTag("41", "Incest", source),
		MangaTag("42", "Loli", source),
		MangaTag("43", "Mafia", source),
		MangaTag("44", "Magic", source),
		MangaTag("45", "Martial Arts", source),
		MangaTag("46", "Military", source),
		MangaTag("47", "Monster Girls", source),
		MangaTag("48", "Monsters", source),
		MangaTag("49", "Music", source),
		MangaTag("50", "Ninja", source),
		MangaTag("51", "Office Workers", source),
		MangaTag("52", "Police", source),
		MangaTag("53", "Post-Apocalyptic", source),
		MangaTag("54", "Reincarnation", source),
		MangaTag("55", "Reverse Harem", source),
		MangaTag("56", "Samurai", source),
		MangaTag("57", "School Life", source),
		MangaTag("58", "Shota", source),
		MangaTag("59", "Supernatural", source),
		MangaTag("60", "Survival", source),
		MangaTag("61", "Time Travel", source),
		MangaTag("62", "Traditional Games", source),
		MangaTag("63", "Vampires", source),
		MangaTag("64", "Video Games", source),
		MangaTag("65", "Villainess", source),
		MangaTag("66", "Virtual Reality", source),
		MangaTag("67", "Zombies", source)
	)
}
