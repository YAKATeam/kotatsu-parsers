package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Calendar
import java.util.EnumSet
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("MANGATARO", "Mangataro", "en", ContentType.MANGA)
internal class Mangataro(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGATARO, 48) {

	override val configKeyDomain = ConfigKey.Domain("mangataro.org")

	private val chapterNumberRegex = Regex("""(\d+(?:\.\d+)?)""")

	private val tokenFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH", Locale.US)

	private val genreTags by lazy {
		setOf(
			tag("7", "Action"),
			tag("19", "Adventure"),
			tag("41", "Award Winning"),
			tag("49", "Boys Love"),
			tag("62", "Comedy"),
			tag("79", "Drama"),
			tag("83", "Ecchi"),
			tag("87", "Erotica"),
			tag("91", "Fantasy"),
			tag("93", "Full Color"),
			tag("100", "Girls Love"),
			tag("104", "Harem"),
			tag("108", "Historical"),
			tag("109", "Horror"),
			tag("114", "Isekai"),
			tag("116", "Josei"),
			tag("123", "Long Strip"),
			tag("134", "Manga"),
			tag("136", "Manhua"),
			tag("137", "Manhwa"),
			tag("138", "Martial Arts"),
			tag("152", "Mystery"),
			tag("159", "One-shot"),
			tag("170", "Psychological"),
			tag("173", "Reincarnation"),
			tag("175", "Romance"),
			tag("178", "School"),
			tag("180", "Sci-Fi"),
			tag("181", "Seinen"),
			tag("184", "Shoujo"),
			tag("186", "Shounen"),
			tag("190", "Slice of Life"),
			tag("194", "Sports"),
			tag("198", "Supernatural"),
			tag("206", "Time Travel"),
			tag("220", "Web Comic"),
		)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = genreTags,
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val payload = JSONObject().apply {
			put("page", page)
			put("search", filter.query.orEmpty())
			put("years", "[]")
			put("genres", filter.tags.toGenrePayload())
			put("types", "[]")
			put("statuses", "[]")
			put("sort", order.toApiSort())
			put("genreMatchMode", "any")
		}
		val json = postJsonArray("https://$domain/wp-json/manga/v1/load", payload)
		return List(json.length()) { index ->
			val item = json.getJSONObject(index)
			item.toManga()
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailUrl = manga.url.urlWithoutFragment().toAbsoluteUrl(domain)
		val document = fetchDocument(detailUrl)
		val jsonLd = document.findSeriesJsonLd()
		val description = document.extractDescription(jsonLd)
		val altTitles = document.extractAltTitles(jsonLd)
		val tags = document.extractTags(jsonLd)
		val authors = document.extractAuthors(jsonLd)
		val state = document.extractStatus()
		val rating = document.extractRating(jsonLd)
		val mangaId = extractMangaId(manga.url)
			?: document.selectFirst("[data-manga-id]")?.attr("data-manga-id")
			?: jsonLd?.optString("identifier")?.nullIfEmpty()
			?: throw ParseException("Unable to determine manga id", detailUrl)
		val chapters = loadChapters(mangaId, detailUrl)
		return manga.copy(
			description = description,
			tags = if (tags.isEmpty()) manga.tags else tags,
			authors = if (authors.isEmpty()) manga.authors else authors,
			altTitles = if (altTitles.isEmpty()) manga.altTitles else altTitles,
			state = state,
			rating = rating,
			chapters = chapters,
		)
	}

	private suspend fun loadChapters(mangaId: String, referer: String): List<MangaChapter> {
		val headers = Headers.headersOf("Referer", referer)
		val result = ArrayList<MangaChapter>()
		val seenIds = HashSet<String>()
		var offset = 0
		val limit = 500
		var expectedTotal = Int.MAX_VALUE
		while (offset < expectedTotal) {
			val (token, timestamp) = generateChapterToken()
			val requestUrl = buildChapterUrl(mangaId, offset, limit, token, timestamp)
			val raw = webClient.httpGet(requestUrl, headers).parseRaw().trim()
			if (raw.isEmpty()) {
				break
			}
			val json = runCatching { JSONObject(raw) }.getOrNull() ?: break
			if (!json.optBoolean("success", false)) {
				break
			}
			expectedTotal = json.optInt("total", expectedTotal)
			val chaptersArray = json.optJSONArray("chapters") ?: break
			if (chaptersArray.length() == 0) {
				break
			}
			for (i in 0 until chaptersArray.length()) {
				val item = chaptersArray.getJSONObject(i)
				val key = item.optString("id").ifEmpty { item.optString("url") }
				if (!seenIds.add(key)) {
					continue
				}
				val fallbackNumber = (offset + i + 1).toFloat()
				result.add(item.toMangaChapter(fallbackNumber))
			}
			offset += chaptersArray.length()
			if (!json.optBoolean("has_more", false)) {
				break
			}
		}
		return result
	}

	private fun buildChapterUrl(
		mangaId: String,
		offset: Int,
		limit: Int,
		token: String,
		timestamp: String,
	): String {
		return HttpUrl.Builder()
			.scheme("https")
			.host(domain)
			.addPathSegment("auth")
			.addPathSegment("manga-chapters")
			.addQueryParameter("manga_id", mangaId)
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("limit", limit.toString())
			.addQueryParameter("order", "ASC")
			.addQueryParameter("_t", token)
			.addQueryParameter("_ts", timestamp)
			.build()
			.toString()
	}

	private fun generateChapterToken(): Pair<String, String> {
		val timestamp = (System.currentTimeMillis() / 1000L).toString()
		val hourKey = ZonedDateTime.now(ZoneOffset.UTC).format(tokenFormatter)
		val secret = "mng_ch_$hourKey"
		val hashInput = timestamp + secret
		val token = MessageDigest
			.getInstance("MD5")
			.digest(hashInput.toByteArray())
			.toHex()
			.substring(0, 16)
		return token to timestamp
	}

	private fun ByteArray.toHex(): String {
		if (isEmpty()) {
			return ""
		}
		val chars = CharArray(size * 2)
		var index = 0
		for (element in this) {
			val unsigned = element.toInt() and 0xFF
			chars[index++] = Character.forDigit((unsigned ushr 4) and 0xF, 16)
			chars[index++] = Character.forDigit(unsigned and 0xF, 16)
		}
		return String(chars)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val document = fetchDocument(chapter.url.toAbsoluteUrl(domain))
		val containers = document.select("div.comic-image-container img")
		val seen = HashSet<String>(containers.size)
		return containers.mapNotNull { element ->
			val imageUrl = element.resolveImageUrl()
				?.takeUnless { it.startsWith("data:") }
				?: return@mapNotNull null
			if (!seen.add(imageUrl)) {
				return@mapNotNull null
			}
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun JSONObject.toManga(): Manga {
		val id = optString("id")
		val rawUrl = optString("url")
		val relativeUrl = if (rawUrl.isBlank()) "/" else rawUrl.toRelativeUrl(domain)
		val internalUrl = relativeUrl.withMangaId(id)
		val publicUrl = rawUrl.nullIfEmpty() ?: relativeUrl.toAbsoluteUrl(domain)
		return Manga(
			id = generateUid(internalUrl),
			title = optString("title"),
			altTitles = emptySet(),
			url = internalUrl,
			publicUrl = publicUrl,
			rating = optString("score").toFloatOrNull() ?: RATING_UNKNOWN,
			contentRating = null,
			coverUrl = optString("cover").nullIfEmpty(),
			tags = emptySet(),
			state = optString("status").toMangaState(),
			authors = emptySet(),
			description = optString("description").nullIfEmpty(),
			source = source,
		)
	}

	private fun JSONObject.toMangaChapter(fallbackNumber: Float): MangaChapter {
		val chapterUrl = optString("url")
		val rawChapter = optString("chapter")
		val chapterLabel = rawChapter.nullIfEmpty() ?: fallbackNumber.toChapterLabel()
		val rawTitle = optString("title").trim()
		val title = when {
			rawTitle.equals("N/A", ignoreCase = true) -> "Chapter $chapterLabel"
			rawTitle.isEmpty() -> null
			else -> rawTitle
		}
		val number = chapterNumberRegex.find(rawChapter)?.value?.toFloatOrNull()
		return MangaChapter(
			id = generateUid(chapterUrl),
			title = title,
			number = number ?: fallbackNumber,
			volume = 0,
			url = chapterUrl.toRelativeUrl(domain),
			scanlator = null,
			uploadDate = parseChapterDate(optString("date")),
			branch = null,
			source = source,
		)
	}

	private fun Float.toChapterLabel(): String = if (this % 1f == 0f) {
		toInt().toString()
	} else {
		replaceTrailingZeros()
	}

	private fun Float.replaceTrailingZeros(): String {
		val text = toString()
		val trimmed = text.trimEnd('0')
		return if (trimmed.endsWith('.')) {
			trimmed.dropLast(1)
		} else {
			trimmed
		}
	}

	private fun String?.toMangaState(): MangaState? {
		val normalized = this?.lowercase(Locale.US)?.trim() ?: return null
		return when {
			normalized.contains("ongoing") -> MangaState.ONGOING
			normalized.contains("complete") || normalized.contains("completed") -> MangaState.FINISHED
			normalized.contains("hiatus") -> MangaState.PAUSED
			normalized.contains("canceled") || normalized.contains("cancelled") -> MangaState.ABANDONED
			normalized.contains("upcoming") || normalized.contains("tba") -> MangaState.UPCOMING
			else -> null
		}
	}

	private fun parseChapterDate(raw: String?): Long {
		val value = raw?.trim()?.lowercase(Locale.US)?.nullIfEmpty() ?: return 0L
		if (!value.endsWith("ago")) {
			return 0L
		}
		val amount = chapterNumberRegex.find(value)?.value?.toFloatOrNull() ?: return 0L
		val calendar = Calendar.getInstance()
		when {
			value.contains("second") -> calendar.add(Calendar.SECOND, -amount.toInt())
			value.contains("minute") -> calendar.add(Calendar.MINUTE, -amount.toInt())
			value.contains("hour") -> calendar.add(Calendar.HOUR_OF_DAY, -amount.toInt())
			value.contains("day") -> calendar.add(Calendar.DAY_OF_YEAR, -amount.toInt())
			value.contains("week") -> calendar.add(Calendar.WEEK_OF_YEAR, -amount.toInt())
			value.contains("month") -> calendar.add(Calendar.MONTH, -amount.toInt())
			value.contains("year") -> calendar.add(Calendar.YEAR, -amount.toInt())
			else -> return 0L
		}
		return calendar.timeInMillis
	}

	private fun SortOrder.toApiSort(): String = when (this) {
		SortOrder.POPULARITY -> "popular_desc"
		SortOrder.UPDATED -> "post_desc"
		SortOrder.NEWEST -> "release_desc"
		SortOrder.ALPHABETICAL -> "title_desc"
		else -> "post_desc"
	}

	private fun Document.extractDescription(jsonLd: JSONObject?): String? {
		val fromJson = jsonLd?.optString("description")?.nullIfEmpty()
		if (fromJson != null) {
			return fromJson
		}
		return selectFirst("div[data-description], div#description, div#synopsis, div:matchesOwn((?i)description)")
			?.nextElementSibling()
			?.text()
			?.nullIfEmpty()
			?: selectFirst("meta[name='description']")?.attr("content")?.nullIfEmpty()
			?: selectFirst("meta[property='og:description']")?.attr("content")?.nullIfEmpty()
	}

	private fun Document.extractAltTitles(jsonLd: JSONObject?): Set<String> {
		val alt = mutableSetOf<String>()
		jsonLd?.opt("alternateName")?.let { value ->
			when (value) {
				is JSONArray -> {
					repeat(value.length()) { index ->
						value.optString(index).nullIfEmpty()?.let(alt::add)
					}
				}
				is String -> value.nullIfEmpty()?.let(alt::add)
			}
		}
		if (alt.isEmpty()) {
			selectFirst("h1 + p")?.text()?.split(" / ")?.mapNotNull { it.nullIfEmpty() }?.let { alt.addAll(it) }
		}
		return alt
	}

	private fun Document.extractTags(jsonLd: JSONObject?): Set<MangaTag> {
		val tags = LinkedHashSet<MangaTag>()
		jsonLd?.opt("genre")?.let { genre ->
			when (genre) {
				is JSONArray -> repeat(genre.length()) { index ->
					genre.optString(index).nullIfEmpty()?.let { addTag(tags, it) }
				}
				is String -> genre.nullIfEmpty()?.let { addTag(tags, it) }
			}
		}
		if (tags.isEmpty()) {
			select("a[href*='/genre/']").mapNotNullToSet { anchor ->
				anchor.text().nullIfEmpty()?.let { title ->
					val href = anchor.attr("href").trimEnd('/')
					val slug = href.substringAfterLast('/').substringBefore('?').nullIfEmpty()
						?: return@let null
					MangaTag(
						key = slug,
						title = title,
						source = source,
					)
				}
			}
				.takeIf { it.isNotEmpty() }
				?.let(tags::addAll)
		}
		return tags
	}

	private fun addTag(target: MutableSet<MangaTag>, value: String) {
		val normalized = value.trim().nullIfEmpty() ?: return
		target.add(
			MangaTag(
				key = normalized.lowercase(Locale.US).replace(' ', '-'),
				title = normalized,
				source = source,
			),
		)
	}

	private fun Document.extractAuthors(jsonLd: JSONObject?): Set<String> {
		val authors = LinkedHashSet<String>()
		fun addName(value: Any?) {
			when (value) {
				is JSONArray -> repeat(value.length()) { index -> addName(value.opt(index)) }
				is JSONObject -> addName(value.opt("name"))
				is String -> value.nullIfEmpty()?.let(authors::add)
			}
		}
		addName(jsonLd?.opt("author"))
		addName(jsonLd?.opt("creator"))
		if (authors.isEmpty()) {
			select("a[href*='/author/'], a[href*='/artist/']").forEach { anchor ->
				anchor.text().nullIfEmpty()?.let(authors::add)
			}
		}
		return authors
	}

	private fun Document.extractStatus(): MangaState? {
		val label = select("*").firstOrNull { element ->
			element.ownText().trim().equals("Status", ignoreCase = true)
		}
		val statusText = label?.let { element ->
			element.findSiblingText()?.nullIfEmpty()
		}
			?: selectFirst("[data-status]")?.attr("data-status")?.nullIfEmpty()
			?: selectFirst("span.status, div.status")?.text()?.nullIfEmpty()
		return statusText.toMangaState()
	}

	private fun Document.extractRating(jsonLd: JSONObject?): Float {
		jsonLd?.optJSONObject("aggregateRating")?.optDouble("ratingValue")?.takeIf { !it.isNaN() }?.let {
			return it.toFloat()
		}
		return selectFirst("svg ~ span.font-semibold")?.text()?.toFloatOrNull() ?: RATING_UNKNOWN
	}

	private fun Document.findSeriesJsonLd(): JSONObject? {
		for (script in select("script[type=application/ld+json]")) {
			val raw = script.data().trim()
			if (raw.isEmpty()) {
				continue
			}
			val obj = raw.toJsonObjectOrNull()
			if (obj != null) {
				if (obj.matchesSeriesType()) {
					return obj
				}
				continue
			}
			val array = raw.toJsonArrayOrNull() ?: continue
			repeat(array.length()) { index ->
				val candidate = array.optJSONObject(index) ?: return@repeat
				if (candidate.matchesSeriesType()) {
					return candidate
				}
			}
		}
		return null
	}

	private fun String.toJsonObjectOrNull(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()
	private fun String.toJsonArrayOrNull(): JSONArray? = runCatching { JSONArray(this) }.getOrNull()
	private fun JSONObject.matchesSeriesType(): Boolean {
		val type = optString("@type")
		if (type.isNullOrEmpty()) {
			return false
		}
		return type.equals("ComicSeries", true) ||
			type.equals("ComicStory", true) ||
			type.equals("Book", true) ||
			type.equals("CreativeWorkSeries", true)
	}

	private fun String?.withMangaId(id: String?): String {
		val value = this?.nullIfEmpty() ?: return "/"
		val cleanId = id?.nullIfEmpty() ?: return value
		return if (value.contains('#')) {
			if (value.contains("mid=")) value else "$value&mid=$cleanId"
		} else {
			"$value#mid=$cleanId"
		}
	}

	private fun extractMangaId(rawUrl: String): String? {
		val fragment = rawUrl.substringAfter('#', "")
		if (fragment.isNotEmpty()) {
			fragment.split('&').firstOrNull { it.startsWith("mid=") }?.let { part ->
				return part.substringAfter("mid=").nullIfEmpty()
			}
		}
		val query = rawUrl.substringAfter('?', "")
		if (query.isNotEmpty()) {
			query.split('&').firstOrNull { it.startsWith("mid=") || it.startsWith("mangaId=") }?.let { part ->
				return part.substringAfter('=').nullIfEmpty()
			}
		}
		return null
	}

	private fun String.urlWithoutFragment(): String = substringBefore('#')

	private suspend fun postJsonArray(url: String, payload: JSONObject): JSONArray {
		val raw = webClient.httpPost(url, payload).parseRaw().trim()
		if (raw.isEmpty()) {
			return JSONArray()
		}
		return raw.toJsonArrayOrNull() ?: JSONArray()
	}

	private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

	private fun Collection<MangaTag>.toGenrePayload(): String {
		if (isEmpty()) {
			return "[]"
		}
		val ids = LinkedHashSet<String>(size)
		for (tag in this) {
			val numericKey = tag.key?.toIntOrNull()?.let { it.toString() }
				?: genreTags.firstOrNull { ref ->
					ref.title.equals(tag.title, ignoreCase = true)
				}?.key?.toIntOrNull()?.let { it.toString() }
			if (numericKey != null) {
				ids.add(numericKey)
			}
		}
		if (ids.isEmpty()) {
			return "[]"
		}
		return buildString(ids.size * 4) {
			append('[')
			ids.forEachIndexed { index, value ->
				if (index > 0) {
					append(',')
				}
				append(value)
			}
			append(']')
		}
	}

	private fun tag(id: String, title: String) = MangaTag(title = title, key = id, source = source)

	private fun Element.findSiblingText(): String? {
		nextElementSibling()?.text()?.nullIfEmpty()?.let { return it }
		val parent = parent() ?: return null
		val children = parent.children()
		val index = children.indexOf(this)
		if (index == -1) {
			return null
		}
		for (i in index + 1 until children.size) {
			val text = children[i].text().nullIfEmpty()
			if (text != null) {
				return text
			}
		}
		return null
	}

	private fun Element.resolveImageUrl(): String? {
		return attrAsAbsoluteUrlOrNull("data-src")
			?: attrAsAbsoluteUrlOrNull("data-lazy-src")
			?: attrAsAbsoluteUrlOrNull("src")
	}
}
