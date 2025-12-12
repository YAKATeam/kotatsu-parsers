package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.Locale

@MangaSourceParser("MADARADEX", "MadaraDex", "en", ContentType.HENTAI)
internal class MadaraDex(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MADARADEX, "madaradex.org") {

	init {
		context.cookieJar.insertCookies(domain, "wpmanga-adault=1")
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.set("User-Agent", UserAgents.CHROME_DESKTOP)
		.set("referer", "https://madaradex.org/")
		.build()

	override val authUrl: String
		get() = "https://${domain}"

	override suspend fun isAuthorized(): Boolean {
		return context.cookieJar.getCookies(domain).any {
			it.name.contains("cm_uaid")
		}
	}

	override val listUrl = "title/"
	override val tagPrefix = "genre/"
	override val postReq = true
	override val stylePage = ""

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = loadChapterDocument(fullUrl)

		val images = doc.select("div.page-break img")

		if (images.isEmpty()) {
			throw ParseException("No images found, try to log in", fullUrl)
		}

		return images.mapNotNull { img ->
			val rawUrl = img.attr("data-src").ifBlank { img.attr("src") }.trim()

			if (rawUrl.isEmpty()) {
				return@mapNotNull null
			}

			val cleanUrl = rawUrl.toRelativeUrl(domain).substringBefore('#')

			MangaPage(
				id = generateUid(cleanUrl),
				url = cleanUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun loadChapterDocument(url: String): Document {
		var doc = fetchChapterDocument(url)
		if (doc != null && !isCloudflareDocument(doc)) {
			return doc
		}

		context.requestBrowserAction(this, url)

		doc = fetchChapterDocument(url)
		val resolved = doc ?: throw ParseException(
			"Cloudflare verification is still required. Please open the chapter in the in-app browser and retry.",
			url,
		)
		if (isCloudflareDocument(resolved)) {
			throw ParseException(
				"Cloudflare verification is still required. Please open the chapter in the in-app browser and retry.",
				url,
			)
		}
		return resolved
	}

	private suspend fun fetchChapterDocument(url: String): Document? {
		val response = runCatching { webClient.httpGet(url) }.getOrElse { return null }
		return response.use { res -> runCatching { res.parseHtml() }.getOrNull() }
	}

	private fun isCloudflareDocument(doc: Document): Boolean {
		val title = (doc.title() ?: "").lowercase(Locale.ROOT)
		val hasBlockedTitle = title.contains("access denied")

		val hasActiveChallengeForm = doc.selectFirst("""form[action*="__cf_chl"]""") != null
		val hasChallengeScript = doc.selectFirst("""script[src*="challenge-platform"]""") != null

		// Only return blocked if we're absolutely certain
		return hasBlockedTitle || hasActiveChallengeForm || hasChallengeScript
	}
}
