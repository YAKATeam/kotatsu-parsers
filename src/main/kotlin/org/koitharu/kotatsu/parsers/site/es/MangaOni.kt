package org.koitharu.kotatsu.parsers.site.es

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAONI", "MangaOni", "es")
internal class MangaOni(context: MangaLoaderContext) :
	PagedMangaParser(context, source = MangaParserSource.MANGAONI, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("manga-oni.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)


	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = getAvailableTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.ONE_SHOT,
				ContentType.OTHER,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return if (!filter.query.isNullOrEmpty()) {
			// Search results
			val url = buildSearchUrl(page, filter.query!!)
			val doc = webClient.httpGet(url).parseHtml()
			parseSearchResults(doc)
		} else {
			// Directory results with NSFW detection
			getDirectoryWithNsfwDetection(page, order, filter)
		}
	}

	private suspend fun getDirectoryWithNsfwDetection(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Get all manga (including NSFW)
		val allUrl = buildDirectoryUrl(page, order, filter, includeNsfw = true)
		val allDoc = webClient.httpGet(allUrl).parseHtml()
		val allManga = parseDirectoryResults(allDoc)

		// Get safe manga only (no NSFW)
		val safeUrl = buildDirectoryUrl(page, order, filter, includeNsfw = false)
		val safeDoc = webClient.httpGet(safeUrl).parseHtml()
		val safeManga = parseDirectoryResults(safeDoc)
		val safeMangaUrls = safeManga.map { it.url }.toSet()

		// Mark NSFW content by comparing results
		return allManga.map { manga ->
			manga.copy(
				contentRating = if (safeMangaUrls.contains(manga.url)) {
					ContentRating.SAFE
				} else {
					ContentRating.ADULT
				}
			)
		}
	}

	private fun buildSearchUrl(page: Int, query: String): String {
		return "https://$domain/buscar".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("p", page.toString())
			.build()
			.toString()
	}

	private fun buildDirectoryUrl(page: Int, order: SortOrder, filter: MangaListFilter, includeNsfw: Boolean = true): String {
		return "https://$domain/directorio".toHttpUrl().newBuilder().apply {
			addQueryParameter("genero", getGenreParam(filter))
			addQueryParameter("estado", getStateParam(filter))
			addQueryParameter("filtro", getSortParam(order))
			addQueryParameter("tipo", getTypeParam(filter))
			addQueryParameter("adulto", if (includeNsfw) "false" else "0")
			addQueryParameter("orden", "desc")
			addQueryParameter("p", page.toString())
		}.build().toString()
	}

	private fun getGenreParam(filter: MangaListFilter): String {
		return filter.tags.firstOrNull()?.key ?: "false"
	}

	private fun getStateParam(filter: MangaListFilter): String {
		return when (filter.states.firstOrNull()) {
			MangaState.ONGOING -> "1"
			MangaState.FINISHED -> "0"
			else -> "false"
		}
	}

	private fun getSortParam(order: SortOrder): String {
		return when (order) {
			SortOrder.POPULARITY -> "visitas"
			SortOrder.UPDATED -> "id"
			SortOrder.ALPHABETICAL -> "nombre"
			else -> "id" // Default to recent updates
		}
	}

	private fun getTypeParam(filter: MangaListFilter): String {
		return when (filter.types.firstOrNull()) {
			ContentType.MANGA -> "0"
			ContentType.MANHWA -> "1"
			ContentType.MANHUA -> "3"
			ContentType.ONE_SHOT -> "2"
			ContentType.OTHER -> "4"  // Novelas
			else -> "false"
		}
	}

	private fun parseDirectoryResults(doc: Document): List<Manga> {
		return doc.select("#article-div a").mapNotNull { element ->
			val href = element.attr("href")
			Manga(
				id = generateUid(href),
				title = element.select("div:eq(1)").text().trim(),
				altTitles = emptySet(),
				url = href.toRelativeUrl(domain),
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE, // Will be properly set in getDetails()
				coverUrl = element.select("img").attr("data-src"),
				largeCoverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	private fun parseSearchResults(doc: Document): List<Manga> {
		return doc.select("div._2NNxg").mapNotNull { element ->
			val linkElement = element.selectFirst("a") ?: return@mapNotNull null
			val href = linkElement.attr("href")

			Manga(
				id = generateUid(href),
				title = linkElement.text().trim(),
				altTitles = emptySet(),
				url = href.toRelativeUrl(domain),
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE, // Will be properly set in getDetails()
				coverUrl = element.select("img").attr("src"),
				largeCoverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
	}


	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val description = doc.select("div#sinopsis").lastOrNull()?.ownText()
		val author = doc.select("div#info-i").text().let {
			if (it.contains("Autor", true)) {
				it.substringAfter("Autor:").substringBefore("Fecha:").trim()
			} else {
				null
			}
		}
		val genres = doc.select("div#categ a").mapNotNull { element ->
			MangaTag(
				key = element.attr("href").substringAfterLast("="),
				title = element.text(),
				source = source,
			)
		}.toSet()

		val state = when (doc.select("strong:contains(Estado) + span").firstOrNull()?.text()) {
			"En desarrollo" -> MangaState.ONGOING
			"Finalizado" -> MangaState.FINISHED
			else -> null
		}

		val chapters = parseChapterList(doc)

		return manga.copy(
			title = doc.select("h1").firstOrNull()?.text() ?: manga.title,
			description = description,
			coverUrl = doc.select("img[src*=cover]").attr("abs:src").ifEmpty { manga.coverUrl },
			authors = setOfNotNull(author),
			tags = genres,
			state = state,
			chapters = chapters,
			// Keep existing contentRating from list page (determined by dual search)
		)
	}

	private fun parseChapterList(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

		return doc.select("div#c_list a").mapIndexed { index, element ->
			val href = element.attr("href")
			MangaChapter(
				id = generateUid(href),
				title = element.text().trim(),
				number = element.select("span").attr("data-num").toFloatOrNull() ?: (index + 1).toFloat(),
				volume = 0,
				url = href.toRelativeUrl(domain),
				scanlator = null,
				uploadDate = dateFormat.parseSafe(element.select("span").attr("datetime")),
				branch = null,
				source = source,
			)
		}.asReversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val scriptData = doc.select("script:containsData(unicap)").firstOrNull()
			?.data()?.substringAfter("'")?.substringBefore("'")
			?: throw Exception("unicap not found")

		val drop = scriptData.length % 4
		val cleanedData = scriptData.dropLast(drop)

		// Decode base64
		val decodedBytes = java.util.Base64.getDecoder().decode(cleanedData)
		val decoded = String(decodedBytes, Charsets.UTF_8)

		val path = decoded.substringBefore("||")
		val fileList = decoded.substringAfter("[").substringBefore("]")
			.split(",")
			.map { it.removeSurrounding("\"") }

		return fileList.mapIndexed { index, fileName ->
			MangaPage(
				id = generateUid("$path$fileName"),
				url = "$path$fileName",
				preview = null,
				source = source,
			)
		}
	}

	private fun getAvailableTags(): Set<MangaTag> {
		return setOf(
			MangaTag("Comedia", "1", source),
			MangaTag("Drama", "2", source),
			MangaTag("Acción", "3", source),
			MangaTag("Escolar", "4", source),
			MangaTag("Romance", "5", source),
			MangaTag("Ecchi", "6", source),
			MangaTag("Aventura", "7", source),
			MangaTag("Shōnen", "8", source),
			MangaTag("Shōjo", "9", source),
			MangaTag("Deportes", "10", source),
			MangaTag("Psicológico", "11", source),
			MangaTag("Fantasía", "12", source),
			MangaTag("Mecha", "13", source),
			MangaTag("Gore", "14", source),
			MangaTag("Yaoi", "15", source),
			MangaTag("Yuri", "16", source),
			MangaTag("Misterio", "17", source),
			MangaTag("Sobrenatural", "18", source),
			MangaTag("Seinen", "19", source),
			MangaTag("Ficción", "20", source),
			MangaTag("Harem", "21", source),
			MangaTag("Webtoon", "25", source),
			MangaTag("Histórico", "27", source),
			MangaTag("Musical", "30", source),
			MangaTag("Ciencia ficción", "31", source),
			MangaTag("Shōjo-ai", "32", source),
			MangaTag("Josei", "33", source),
			MangaTag("Magia", "34", source),
			MangaTag("Artes Marciales", "35", source),
			MangaTag("Horror", "36", source),
			MangaTag("Demonios", "37", source),
			MangaTag("Supervivencia", "38", source),
			MangaTag("Recuentos de la vida", "39", source),
			MangaTag("Shōnen ai", "40", source),
			MangaTag("Militar", "41", source),
			MangaTag("Eroge", "42", source),
			MangaTag("Isekai", "43", source),
		)
	}
}
