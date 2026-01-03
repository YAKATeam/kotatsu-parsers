package org.koitharu.kotatsu.parsers.site.mangabox

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.FlexiblePagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.util.*
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

internal abstract class MangaboxParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	pageSize: Int = 24,
) : FlexiblePagedMangaParser(context, source, pageSize) {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities(
			SearchCapability(
				field = TAG,
				criteriaTypes = setOf(Include::class, Exclude::class),
				isMultiple = true,
			),
			SearchCapability(
				field = TITLE_NAME,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
			),
			SearchCapability(
				field = STATE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = AUTHOR,
				criteriaTypes = setOf(Include::class),
				isMultiple = false,
				isExclusive = true,
			),
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	init {
		paginator.firstPage = 1
		searchPaginator.firstPage = 1
	}

	@JvmField
	protected val ongoing: Set<String> = setOf(
		"ongoing",
	)

	@JvmField
	protected val finished: Set<String> = setOf(
		"completed",
	)

	protected val cdnSet: MangaBoxLinkedCdnSet = MangaBoxLinkedCdnSet()

	protected open val listUrl = "/advanced_search"
	protected open val authorUrl = "/search/author"
	protected open val searchUrl = "/search/story/"
	protected open val datePattern = "MMM dd,yy"

	private fun SearchableField.toParamName(): String = when (this) {
		TITLE_NAME, AUTHOR -> "keyw"
		TAG -> "g_i"
		STATE -> "sts"
		else -> ""
	}

	private fun Any?.toQueryParam(): String = when (this) {
		is String -> normalizeSearchQuery(this)
		is MangaTag -> key
		is MangaState -> when (this) {
			MangaState.ONGOING -> "ongoing"
			MangaState.FINISHED -> "completed"
			else -> ""
		}

		is SortOrder -> when (this) {
			SortOrder.ALPHABETICAL -> "az"
			SortOrder.NEWEST -> "newest"
			SortOrder.POPULARITY -> "topview"
			else -> ""
		}

		else -> this.toString().replace(" ", "_").urlEncoded()
	}

	protected fun normalizeSearchQuery(query: String): String {
		var str = query.lowercase()
		str = str.replace(Regex("[àáạảãâầấậẩẫăằắặẳẵ]"), "a")
		str = str.replace(Regex("[èéẹẻẽêềếệểễ]"), "e")
		str = str.replace(Regex("[ìíịỉĩ]"), "i")
		str = str.replace(Regex("[òóọỏõôồốộổỗơờớợởỡ]"), "o")
		str = str.replace(Regex("[ùúụủũưừứựửữ]"), "u")
		str = str.replace(Regex("[ỳýỵỷỹ]"), "y")
		str = str.replace(Regex("đ"), "d")
		str = str.replace(
			Regex("""!|@|%|\^|\*|\(|\)|\+|=|<|>|\?|/|,|\.|:|;|'| |"|&|#|\[|]|~|-|$|_"""),
			"_",
		)
		str = str.replace(Regex("_+_"), "_")
		str = str.replace(Regex("^_+|_+$"), "")
		return str
	}

	private fun StringBuilder.appendCriterion(field: SearchableField, value: Any?, paramName: String? = null) {
		val param = paramName ?: field.toParamName()
		if (param.isNotBlank()) {
			append("&$param=")
			append(value.toQueryParam())
		}
	}

	private fun HttpUrl.getBaseUrl(): String =
		"${scheme}://${host}${
			when (port) {
				80, 443 -> ""
				else -> ":${port}"
			}
		}"

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (cdnSet.isEmpty()) {
			return chain.proceed(request)
		}

		val requestUrl = request.url.toString()
		val isTargetingCdn = cdnSet.any { cdn -> requestUrl.startsWith(cdn) }

		if (!isTargetingCdn) {
			return chain.proceed(request)
		}

		val originalResponse: Response? = try {
			chain.proceed(request)
		} catch (e: IOException) {
			null
		}

		if (originalResponse?.isSuccessful == true) {
			cdnSet.moveItemToFirst(request.url.getBaseUrl())
			return originalResponse
		}

		originalResponse?.close()

		for (cdnUrl in cdnSet) {
			var tryResponse: Response? = null
			try {
				val newUrl = cdnUrl.toHttpUrl().newBuilder()
					.encodedPath(request.url.encodedPath)
					.fragment(request.url.fragment)
					.build()

				val newRequest = request.newBuilder()
					.url(newUrl)
					.build()

				tryResponse = chain.proceed(newRequest)

				if (tryResponse.isSuccessful) {
					cdnSet.moveItemToFirst(newRequest.url.getBaseUrl())
					return tryResponse
				}
				tryResponse.close()
			} catch (_: IOException) {
				tryResponse?.close()
			}
		}

		throw IOException("All CDN attempts failed for $requestUrl")
	}

	override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
		var authorSearchUrl: String? = null
		val url = buildString {
			val pageQueryParameter = "page=$page"
			append("https://${domain}${listUrl}/?s=all")

			query.criteria.forEach { criterion ->
				when (criterion) {
					is Include<*> -> {
						if (criterion.field == AUTHOR) {
							criterion.values.firstOrNull()?.toQueryParam()?.takeIf { it.isNotBlank() }
								?.let { authorKey ->
									authorSearchUrl = "https://${domain}${authorUrl}/${authorKey}/?$pageQueryParameter"
								}
						}

						criterion.field.toParamName().takeIf { it.isNotBlank() }?.let { param ->
							append("&$param=${criterion.values.joinToString("_") { it.toQueryParam() }}")
						}
					}

					is Exclude<*> -> {
						append("&g_e=${criterion.values.joinToString("_") { it.toQueryParam() }}")
					}

					is Match<*> -> {
						appendCriterion(criterion.field, criterion.value)
					}

					else -> {
						// Not supported
					}
				}
			}

			append("&${pageQueryParameter}")
			append("&orby=${(query.order ?: defaultSortOrder).toQueryParam()}")
		}

				val doc = webClient.httpGet(authorSearchUrl ?: url).parseHtml()

		

				return parseMangaList(doc)

			}

		

			protected open fun parseMangaList(doc: Document): List<Manga> {

				return doc.select("div.content-genres-item, div.list-story-item, div.story_item_right, div.truyen-list > div.list-truyen-item-wrap, div.comic-list > .list-comic-item-wrap").ifEmpty {

					doc.select("div.search-story-item")

				}.map {

					val href = it.selectFirstOrThrow("a").attrAsRelativeUrl("href")

					Manga(

						id = generateUid(href),

						url = href,

						publicUrl = href.toAbsoluteUrl(it.host ?: domain),

						coverUrl = it.selectFirst("img")?.src(),

						title = it.selectFirst("h3")?.text().orEmpty(),

						altTitles = emptySet(),

						rating = RATING_UNKNOWN,

						tags = emptySet(),

						authors = emptySet(),

						state = null,

						source = source,

						contentRating = sourceContentRating,

					)

				}

			}

		

			protected open val selectTagMap = "div.panel-genres-list a:not(.genres-select)"

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
		val tags = doc.select(selectTagMap).drop(1) // remove all tags
		return tags.mapToSet { a ->
			val key = a.attr("href").removeSuffix('/').substringAfterLast('/')
			val name = a.attr("title").replace(" Manga", "")
			MangaTag(
				key = key,
				title = name,
				source = source,
			)
		}
	}

	protected open val selectDesc = "div#noidungm, div#panel-story-info-description, div#contentBox"
	protected open val selectState = "li:contains(status), td:containsOwn(status) + td"
	protected open val selectAlt = ".story-alternative, tr:has(.info-alternative) h2"
	protected open val selectAut = "li:contains(author) a, td:contains(author) + td a"
	protected open val selectTag = "div.manga-info-top li:contains(genres) a , td:containsOwn(genres) + td a"

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		checkForRedirectMessage(doc)

		val chaptersDeferred = async { getChapters(doc) }
		val desc = doc.selectFirst(selectDesc)?.html()
		val stateDiv = doc.select(selectState).text()
		val state = stateDiv.let {
			when (it.lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				else -> null
			}
		}
		val alt = doc.body().select(selectAlt).text().replace("Alternative : ", "").nullIfEmpty()
		val authors = doc.body().select(selectAut).mapToSet { it.text() }

		manga.copy(
			tags = doc.body().select(selectTag).mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast("category=").substringBefore("&"),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = desc,
			altTitles = setOfNotNull(alt),
			authors = authors,
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}

	protected fun checkForRedirectMessage(document: Document) {
		if (document.select("body").text().startsWith("REDIRECT :")) {
			throw org.koitharu.kotatsu.parsers.exception.NotFoundException("Source URL has changed", "")
		}
	}

	protected open val selectDate = "span"
	protected open val selectChapter = "div.chapter-list div.row, ul.row-content-chapter li"

	protected open suspend fun getChapters(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val chapters = doc.body().select(selectChapter)
		if (chapters.isEmpty()) checkForRedirectMessage(doc)

		return chapters.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val dateText = li.select(selectDate).last()?.text()

			MangaChapter(
				id = generateUid(href),
				title = a.text(),
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	protected open val selectPage = "div#vungdoc img, div.container-chapter-reader img"

	protected open val otherDomain = ""

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val content = doc.select("script:containsData(cdns =)").joinToString("\n") { it.data() }
		if (content.isNotEmpty()) {
			val cdns = extractArray(content, "cdns") + extractArray(content, "backupImage")
			val chapterImages = extractArray(content, "chapterImages")

			if (cdns.isNotEmpty()) {
				cdnSet.addAll(cdns)
			}

			if (cdns.isNotEmpty() && chapterImages.isNotEmpty()) {
				val cdn = cdns.first().let { if (it.startsWith("//")) "https:$it" else it }
				return chapterImages.map { imagePath ->
					val url = "${cdn.removeSuffix("/")}/${imagePath.replace("//", "/").removePrefix("/")}"
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		}

		if (doc.select(selectPage).isEmpty()) {
			val fullUrl2 = chapter.url.toAbsoluteUrl(domain).replace(domain, otherDomain)
			val doc2 = webClient.httpGet(fullUrl2).parseHtml()

			return doc2.select(selectPage).map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)

				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		} else {
			return doc.select(selectPage).map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)

				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

	}

	protected fun extractArray(scriptContent: String, arrayName: String): List<String> {
		val pattern = Pattern.compile("$arrayName\\s*=\\s*\\[([^]]+)]")
		val matcher = pattern.matcher(scriptContent)
		val arrayValues = mutableListOf<String>()

		if (matcher.find()) {
			val arrayContent = matcher.group(1)
			val values = arrayContent?.split(",")
			if (values != null) {
				for (value in values) {
					arrayValues.add(
						value.trim()
							.removeSurrounding("\"")
							.replace("\\/", "/")
							.removeSuffix("/"),
					)
				}
			}
		}

		return arrayValues
	}

	protected fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			WordSet(" ago", " h", " d").endsWith(d) -> {
				parseRelativeDate(d)
			}

			WordSet("today").startsWith(d) -> {
				Calendar.getInstance().apply {
					set(Calendar.HOUR_OF_DAY, 0)
					set(Calendar.MINUTE, 0)
					set(Calendar.SECOND, 0)
					set(Calendar.MILLISECOND, 0)
				}.timeInMillis
			}

			date.contains(Regex("""\d(st|nd|rd|th)""")) -> date.split(" ").map {
				if (it.contains(Regex("""\d\D\D"""))) {
					it.replace(Regex("""\D"""), "")
				} else {
					it
				}
			}.let { dateFormat.parseSafe(it.joinToString(" ")) }

			else -> dateFormat.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		val cal = Calendar.getInstance()
		return when {
			WordSet("second")
				.anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis

			WordSet("min", "minute", "minutes")
				.anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis

			WordSet("hour", "hours", "h")
				.anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis

			WordSet("day", "days")
				.anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis

			WordSet("month", "months")
				.anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis

			WordSet("year")
				.anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis

			else -> 0
		}
	}

}
