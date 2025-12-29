package org.koitharu.kotatsu.parsers.site.manhwaz

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectOrThrow
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

internal abstract class ManhwaZ(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableTags = fetchTags(),
        )
	}

	protected open val searchPath = "/search"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			if (!filter.query.isNullOrEmpty()) {
				append("$searchPath?s=")
				append(filter.query.urlEncoded())
				append("&page=")
				append(page.toString())
			} else {
				if (filter.tags.isNotEmpty()) {
					append("/genre/")
					append(filter.tags.first().key)
					append("/")
				} else {
					append("/")
				}
				append("?page=")
				append(page.toString())
				val sortQuery = getSortOrderQuery(order, filter.tags.isNotEmpty())
				if (sortQuery.isNotEmpty()) {
					append("&")
					append(sortQuery)
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".page-item-detail").mapNotNull { element ->
			val href = element.selectFirst(".item-summary a")?.attrAsRelativeUrl("href") ?: return@mapNotNull null
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = element.selectFirst(".item-summary a")?.text().orEmpty(),
                coverUrl = element.selectFirst(".item-thumb img")?.src().orEmpty(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
		}
	}

	protected open val selectAuthor =
		"div.summary-heading:contains(Tác giả) + div.summary-content"
	protected open val selectState =
		"div.summary-heading:contains(Trạng thái) + div.summary-content"

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val author = doc.selectFirst(selectAuthor)?.textOrNull()
		return manga.copy(
			altTitles = setOfNotNull(doc.selectFirst("h2.other-name")?.textOrNull()),
			authors = setOfNotNull(author),
			tags = doc.select("div.genres-content a[rel=tag]").mapToSet { a ->
                MangaTag(
                    key = a.attr("href").substringAfterLast('/'),
                    title = a.text().toTitleCase(sourceLocale),
                    source = source,
                )
			},
			description = doc.selectFirst("div.summary__content")?.html(),
			state = when (doc.selectFirst(selectState)?.text()?.lowercase()) {
				"đang ra" -> MangaState.ONGOING
				"hoàn thành" -> MangaState.FINISHED
				else -> null
			},
			chapters = doc.select("li.wp-manga-chapter").mapChapters(reversed = true) { i, element ->
				val a = element.selectFirst("a") ?: return@mapChapters null
                MangaChapter(
                    id = generateUid(a.attrAsRelativeUrl("href")),
                    title = a.text(),
                    number = i + 1f,
                    url = a.attrAsRelativeUrl("href"),
                    uploadDate = parseChapterDate(
                        element.selectFirst("span.chapter-release-date")?.text()
                    ),
                    branch = null,
                    scanlator = null,
                    source = source,
                    volume = 0,
                )
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.selectOrThrow("div.page-break img").mapIndexed { _, img ->
			val url = img.src().orEmpty()
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
		}
	}

	@JvmField
	protected val secondAgo: Set<String> = setOf(
		"giây trước",
		"second ago",
		"seconds ago"
	)

	@JvmField
	protected val minuteAgo: Set<String> = setOf(
		"phút trước",
		"minute ago",
		"minutes ago"
	)

	@JvmField
	protected val hourAgo: Set<String> = setOf(
		"giờ trước",
		"hour ago",
		"hours ago"
	)

	@JvmField
	protected val dayAgo: Set<String> = setOf(
		"ngày trước",
		"day ago",
		"days ago"
	)

	@JvmField
	protected val weekAgo: Set<String> = setOf(
		"tuần trước",
		"week ago",
		"weeks ago"
	)

	@JvmField
	protected val monthAgo: Set<String> = setOf(
		"tháng trước",
		"month ago",
		"months ago"
	)

	@JvmField
	protected val yearAgo: Set<String> = setOf(
		"năm trước",
		"year ago",
		"years ago"
	)

	private fun parseChapterDate(date: String?): Long {
		if (date == null) return 0L

		return when {
			secondAgo.any { date.contains(it) } ->
				System.currentTimeMillis() - date.removeSuffixes(secondAgo).toLong() * 1000

			minuteAgo.any { date.contains(it) } ->
				System.currentTimeMillis() - date.removeSuffixes(minuteAgo).toLong() * 60 * 1000

			hourAgo.any { date.contains(it) } ->
				System.currentTimeMillis() - date.removeSuffixes(hourAgo).toLong() * 60 * 60 * 1000

			dayAgo.any { date.contains(it) } ->
				System.currentTimeMillis() - date.removeSuffixes(dayAgo).toLong() * 24 * 60 * 60 * 1000

			weekAgo.any { date.contains(it) } ->
				System.currentTimeMillis() - date.removeSuffixes(weekAgo).toLong() * 7 * 24 * 60 * 60 * 1000

			monthAgo.any { date.contains(it) } ->
				System.currentTimeMillis() - date.removeSuffixes(monthAgo).toLong() * 30 * 24 * 60 * 60 * 1000

			yearAgo.any { date.contains(it) } ->
				System.currentTimeMillis() - date.removeSuffixes(yearAgo).toLong() * 365 * 24 * 60 * 60 * 1000

			else ->
				SimpleDateFormat("dd/MM/yyyy", Locale.US).parseSafe(date)
		}
	}

	private fun String.removeSuffixes(suffixes: Set<String>): String =
		suffixes.fold(this) { text, suffix ->
			text.replace(suffix, "")
		}.trim()

	private fun getSortOrderQuery(order: SortOrder, hasTags: Boolean): String {
		if (!hasTags) return ""
		return when (order) {
			SortOrder.UPDATED -> "m_orderby=latest"
			SortOrder.POPULARITY -> "m_orderby=views"
			SortOrder.ALPHABETICAL -> "m_orderby=alphabet"
			SortOrder.RATING -> "m_orderby=rating"
			else -> "m_orderby=latest"
		}
	}

	protected open val tagPath = "genre"

	private suspend fun fetchTags(): Set<MangaTag> = webClient.httpGet("https://$domain/$tagPath").parseHtml()
		.select("ul.page-genres li a")
		.mapToSet { a ->
			val title = a.ownText().toTitleCase(sourceLocale)
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = title,
                source = source,
            )
		}
}
