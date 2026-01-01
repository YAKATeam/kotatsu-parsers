package org.koitharu.kotatsu.parsers.site.all

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import java.text.SimpleDateFormat
import java.util.*

private const val SERVER_DATA = "512"
private const val SERVER_DATA_SAVER = "256"

@Broken("TODO: Handle all tags, fix getDetails, getPages")
@MangaSourceParser("WEEBDEX", "WeebDex")
internal class WeebDex(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WEEBDEX, 42) {

	private val cdnDomain = "srv.notdelta.xyz"
	override val configKeyDomain = ConfigKey.Domain("weebdex.org")

	private val preferredServerKey = ConfigKey.PreferredImageServer(
		presetValues = mapOf(
			SERVER_DATA to "High quality cover",
			SERVER_DATA_SAVER to "Compressed quality cover",
		),
		defaultValue = SERVER_DATA,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(preferredServerKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ADDED, // no args
		SortOrder.ADDED_ASC, // no args with asc order
		SortOrder.RELEVANCE, // relevance
		SortOrder.UPDATED, // updatedAt
		SortOrder.NEWEST, // createdAt
		SortOrder.NEWEST_ASC, // createdAt with asc
		SortOrder.ALPHABETICAL, // title with asc
		SortOrder.ALPHABETICAL_DESC, // title
		SortOrder.RATING, // rating
		SortOrder.RATING_ASC, // rating with asc
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isYearRangeSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentRating = EnumSet.allOf(ContentRating::class.java),
			availableLocales = setOf(
				Locale.ENGLISH,
				Locale("af"), // Afrikaans
				Locale("sq"), // Albanian
				Locale("ar"), // Arabic
				Locale("az"), // Azerbaijani
				Locale("eu"), // Basque
				Locale("be"), // Belarusian
				Locale("bn"), // Bengali
				Locale("bg"), // Bulgarian
				Locale("my"), // Burmese
				Locale("ca"), // Catalan
				Locale.CHINESE,
				Locale("zh", "HK"), // Chinese (Traditional)
				Locale("cv"), // Chuvash
				Locale("hr"), // Croatian
				Locale("cs"), // Czech
				Locale("da"), // Danish
				Locale("nl"), // Dutch
				Locale("eo"), // Esperanto
				Locale("et"), // Estonian
				Locale("tl"), // Filipino
				Locale("fi"), // Finnish
				Locale.FRENCH,
				Locale("ka"), // Georgian
				Locale.GERMAN,
				Locale("el"), // Greek
				Locale("he"), // Hebrew
				Locale("hi"), // Hindi
				Locale("hu"), // Hungarian
				Locale("id"), // Indonesian
				Locale("jv"), // Javanese
				Locale("ga"), // Irish
				Locale.ITALIAN,
				Locale.JAPANESE,
				Locale("kk"), // Kazakh
				Locale.KOREAN,
				Locale("la"), // Latin
				Locale("lt"), // Lithuanian
				Locale("ms"), // Malay
				Locale("mn"), // Mongolian
				Locale("ne"), // Nepali
				Locale("no"), // Norwegian
				Locale("fa"), // Persian (Farsi)
				Locale("pl"), // Polish
				Locale("pt"), // Portuguese
				Locale("pt", "BR"), // Portuguese (Brazil)
				Locale("ro"), // Romanian
				Locale("ru"), // Russian
				Locale("sr"), // Serbian
				Locale("sk"), // Slovak
				Locale("sl"), // Slovenian
				Locale("es"), // Spanish
				Locale("es", "MX"), // Spanish (LATAM)
				Locale("sv"), // Swedish
				Locale("tam"), // Tamil
				Locale("te"), // Telugu
				Locale("th"), // Thai
				Locale("tr"), // Turkish
				Locale("uk"), // Ukrainian
				Locale("ur"), // Urdu
				Locale("uz"), // Uzbek
				Locale("vi"), // Vietnamese
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()
			.host("api.$domain")
			.addPathSegment("manga")

		// Paging
		url.addQueryParameter("limit", pageSize.toString())
		url.addQueryParameter("page", page.toString())

		// SortOrder mapping
		when (order) {
			SortOrder.ADDED_ASC -> url.addQueryParameter("order", "asc")
			SortOrder.RELEVANCE -> url.addQueryParameter("sort", "relevance")
			SortOrder.UPDATED -> url.addQueryParameter("sort", "updatedAt")
			SortOrder.NEWEST -> url.addQueryParameter("sort", "createdAt")
			SortOrder.NEWEST_ASC -> {
				url.addQueryParameter("sort", "createdAt")
				url.addQueryParameter("order", "asc")
			}
			SortOrder.ALPHABETICAL -> {
				url.addQueryParameter("sort", "title")
				url.addQueryParameter("order", "asc")
			}
			SortOrder.ALPHABETICAL_DESC -> {
				url.addQueryParameter("sort", "title")
				url.addQueryParameter("order", "desc")
			}
			SortOrder.RATING -> {
				url.addQueryParameter("sort", "followedCount")
				url.addQueryParameter("order", "desc")
			}
			SortOrder.RATING_ASC -> {
				url.addQueryParameter("sort", "followedCount")
				url.addQueryParameter("order", "asc")
			}
			else -> {} // ADDED
		}

		// Keyword
		if (!filter.query.isNullOrEmpty()) {
			url.addQueryParameter("title", filter.query.urlEncoded())
		}

		// Content rating
		if (!filter.contentRating.isEmpty()) {
			filter.contentRating.forEach {
				when (it) {
					ContentRating.SAFE -> url.addQueryParameter("contentRating", "safe")
					ContentRating.SUGGESTIVE -> url.addQueryParameter("contentRating", "suggestive")
					ContentRating.ADULT -> {
						url.addQueryParameter("contentRating", "erotica")
						url.addQueryParameter("contentRating", "pornographic")
					}
				}
			}
		}

		// States
		filter.states.forEach { state ->
			when (state) {
				MangaState.ONGOING -> url.addQueryParameter("status", "ongoing")
				MangaState.FINISHED -> url.addQueryParameter("status", "completed")
				MangaState.PAUSED -> url.addQueryParameter("status", "hiatus")
				MangaState.ABANDONED -> url.addQueryParameter("status", "cancelled")
				else -> {}
			}
		}

		// Tags (Genres)
		if (!filter.tags.isEmpty()) {
			filter.tags.forEach {
				url.addQueryParameter("tag", it.key)
			}
		}

		// Exclude tags (Genres)
		if (!filter.tagsExclude.isEmpty()) {
			filter.tagsExclude.forEach {
				url.addQueryParameter("tagx", it.key)
			}
		}

		// Search by language (Translated languages)
		filter.locale?.let {
			val langCode = when {
				it.language.equals("pt", true) && it.country.equals("BR", true) -> "pt-br"
				it.language.equals("es", true) && it.country.equals("MX", true) -> "es-la"
				it.language.equals("zh", true) && it.country.equals("HK", true) -> "zh-hk"
				else -> it.language
			}
			url.addQueryParameter("hasChapters", "true")
			url.addQueryParameter("availableTranslatedLang", langCode)
		}

		// Search by Year (From - To)
		if (filter.yearFrom != YEAR_UNKNOWN) {
			url.addQueryParameter("yearFrom", filter.yearFrom.toString())
		}

		if (filter.yearTo != YEAR_UNKNOWN) {
			url.addQueryParameter("yearTo", filter.yearTo.toString())
		}

		// Author search
		if (!filter.author.isNullOrBlank()) {
			url.addQueryParameter("authorOrArtist", filter.author)
		}

		val js = webClient.httpGet(url.build()).parseJson()
		// prevent throw Exception for empty result
		val response = js.optJSONArray("data") ?: return emptyList()
		return response.mapJSON { jo ->
			val id = jo.getString("id")
			val title = jo.getString("title")
			val relationships = jo.getJSONObject("relationships")
			val coverId = relationships.getJSONObject("cover").getString("id")
			val quality = config[preferredServerKey] ?: SERVER_DATA
			val tags = relationships.optJSONArray("tags")?.mapJSONNotNullToSet {
				if (it.getString("group") != "genre")
					return@mapJSONNotNullToSet null
				MangaTag(
					key = it.getString("id"),
					title = it.getString("name"),
					source = source,
				)
			} ?: emptySet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = emptySet(),
				url = id,
				publicUrl = "https://$domain/title/$id/"
					+ title.splitByWhitespace().joinToString("-") { it },
				coverUrl = "https://$cdnDomain/covers/$id/$coverId.$quality.webp",
				largeCoverUrl = "https://$cdnDomain/covers/$id/$coverId.webp",
				contentRating = when (jo.getString("content_rating")) {
					"safe" -> ContentRating.SAFE
					"suggestive" -> ContentRating.SUGGESTIVE
					"erotica", "pornographic" -> ContentRating.ADULT
					else -> null
				},
				tags = tags,
				state = when (jo.getString("status")) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					"cancelled" -> MangaState.ABANDONED
					else -> null
				},
				description = jo.getStringOrNull("description"),
				authors = emptySet(),
				rating = RATING_UNKNOWN,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		return manga.copy()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/")
		val url = "api.$domain/chapter/$chapterId"

		val response = webClient.httpGet(url).parseJson()
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

	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://api.$domain/manga/tag"
		val response = webClient.httpGet(url).parseJson()
		val data = response.getJSONArray("data")
		return data.mapJSONNotNullToSet {
			if (it.getString("group") != "genre")
				return@mapJSONNotNullToSet null
			MangaTag(
				key = it.getString("id"),
				title = it.getString("name"),
				source = source,
			)
		}
	}
}
