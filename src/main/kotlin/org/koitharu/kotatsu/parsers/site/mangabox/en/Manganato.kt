package org.koitharu.kotatsu.parsers.site.mangabox.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@MangaSourceParser("MANGANATO", "Manganato", "en")
internal class Manganato(context: MangaLoaderContext) :
	MangaboxParser(context, MangaParserSource.MANGANATO) {
	override val configKeyDomain = ConfigKey.Domain(
		"www.natomanga.com",
		"www.nelomanga.com",
		"www.manganato.gg",
	)
	override val otherDomain = "www.nelomanga.com"

	override val authorUrl = "/author/story"
	override val selectPage = ".container-chapter-reader > img"
	override val listUrl = "/genre/all"

	override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
		val titleMatch = query.criteria.filterIsInstance<Match<*>>().find { it.field == TITLE_NAME }
		val authorInclude = query.criteria.filterIsInstance<Include<*>>().find { it.field == AUTHOR }

		val url = if (titleMatch != null) {
			val text = titleMatch.value.toString()
			val normalized = normalizeSearchQuery(text)
			"https://$domain/search/story/$normalized?page=$page"
		} else if (authorInclude != null) {
			val author = authorInclude.values.first().toString()
			val normalized = normalizeSearchQuery(author)
			"https://$domain$authorUrl/$normalized?page=$page"
		} else {
			val genre = query.criteria.filterIsInstance<Include<*>>().find { it.field == TAG }
				?.values?.firstOrNull()?.let { (it as MangaTag).key } ?: "all"

			val state = query.criteria.filterIsInstance<Include<*>>().find { it.field == STATE }
				?.values?.firstOrNull()?.let {
					when (it as MangaState) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						else -> "all"
					}
				} ?: "all"

			val type = when (query.order) {
				SortOrder.NEWEST -> "newest"
				SortOrder.POPULARITY -> "topview"
				SortOrder.ALPHABETICAL -> "az"
				else -> "latest"
			}

			"https://$domain/genre/$genre?page=$page&type=$type&state=$state"
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val scriptContent = doc.select("script:containsData(cdns =)").joinToString("\n") { it.data() }
		if (scriptContent.isNotEmpty()) {
			val cdns = extractArray(scriptContent, "cdns")
			val chapterImages = extractArray(scriptContent, "chapterImages")

			if (cdns.isNotEmpty()) {
				cdnSet.addAll(cdns)
			}

			if (cdns.isNotEmpty() && chapterImages.isNotEmpty()) {
				val cdn = cdns.first()
				return chapterImages.map { imagePath ->
					val url = (cdn + "/" + imagePath).replace(Regex("(?<!:)/{2,}"), "/")
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		}

		return super.getPages(chapter)
	}
}
