package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANGAPARK", "MangaPark", "en")
internal class MangaPark(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAPARK, 24) {

	override val configKeyDomain = ConfigKey.Domain("mangapark.io")

	private val apiUrl = "https://$domain/apo/"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val tags = listOf(
			"action", "adventure", "comedy", "drama", "fantasy", "horror", "mystery",
			"psychological", "romance", "sci_fi", "slice_of_life", "sports", "supernatural",
			"thriller", "tragedy"
		).map {
			MangaTag(it.replace("_", " ").toCamelCase(), it, source)
		}.toSet()
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = $$"""
            query($select: SearchComic_Select) {
              get_searchComic(select: $select) {
                items {
                  data {
                    id
                    name
                    altNames
                    urlPath
                    urlCoverOri
                  }
                }
              }
            }
        """

		val variables = JSONObject().apply {
			put("select", JSONObject().apply {
				put("page", page)
				put("size", 24)
				put("word", filter.query ?: "")
				if (filter.tags.isNotEmpty()) {
					put("incGenres", JSONArray(filter.tags.map { it.key }))
				}
			})
		}

		val json = graphqlRequest(query, variables)
		val items = json.optJSONObject("data")
			?.optJSONObject("get_searchComic")
			?.optJSONArray("items") ?: return emptyList()

		val mangaList = mutableListOf<Manga>()
		for (i in 0 until items.length()) {
			val item = items.getJSONObject(i).optJSONObject("data") ?: continue
			val id = item.optString("id")
			val relativePath = item.optString("urlPath")

			// FIX 2: Added missing required fields (tags, authors, contentRating)
			mangaList.add(
				Manga(
					id = generateUid(id),
					url = relativePath,
					publicUrl = buildUrl(relativePath),
					coverUrl = buildUrl(item.optString("urlCoverOri")),
					title = item.optString("name"),
					altTitles = item.optJSONArray("altNames")?.let { arr ->
						(0 until arr.length()).map { arr.getString(it) }.toSet()
					} ?: emptySet(),
					rating = RATING_UNKNOWN,
					source = source,
					state = null,
					tags = emptySet(),      // Required
					authors = emptySet(),   // Required
					contentRating = null    // Required
				)
			)
		}
		return mangaList
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val comicId = Regex("\\d+").find(manga.url)?.value
			?: throw ParseException("Could not find Comic ID in URL", manga.url)

		val query = $$"""
            query($id: ID!) {
              get_comicNode(id: $id) {
                data {
                  name
                  altNames
                  urlCoverOri
                  authors
                  artists
                  genres
                  originalStatus
                  uploadStatus
                  summary
                }
              }
              get_comicChapterList(comicId: $id) {
                data {
                  id
                  dname
                  title
                  dateCreate
                  dateModify
                  urlPath
                  srcTitle
                  userNode {
                    data {
                      name
                    }
                  }
                }
              }
            }
        """

		val json = graphqlRequest(query, JSONObject().put("id", comicId))
		val data = json.optJSONObject("data")
			?: throw ParseException("No data returned from GraphQL", manga.url)

		val comicNode = data.optJSONObject("get_comicNode")?.optJSONObject("data")
		val chapterList = data.optJSONArray("get_comicChapterList")

		val authors = mutableSetOf<String>()
		comicNode?.optJSONArray("authors")?.let { arr ->
			for (i in 0 until arr.length()) authors.add(arr.getString(i))
		}
		comicNode?.optJSONArray("artists")?.let { arr ->
			for (i in 0 until arr.length()) authors.add(arr.getString(i))
		}

		val tags = mutableSetOf<MangaTag>()
		comicNode?.optJSONArray("genres")?.let { arr ->
			for (i in 0 until arr.length()) {
				val tag = arr.getString(i)
				val formatted = tag.replace("_", " ").toCamelCase()
				tags.add(MangaTag(formatted, tag, source))
			}
		}

		val uploadStatus = comicNode?.optString("uploadStatus")
		val originalStatus = comicNode?.optString("originalStatus")
		val statusStr = if (uploadStatus != "null" && !uploadStatus.isNullOrBlank()) uploadStatus else originalStatus

		val state = when (statusStr?.lowercase()) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"cancelled" -> MangaState.ABANDONED
			else -> null
		}

		val chapters = mutableListOf<MangaChapter>()
		if (chapterList != null) {
			for (i in 0 until chapterList.length()) {
				val chapData = chapterList.optJSONObject(i)?.optJSONObject("data") ?: continue
				val dname = chapData.optString("dname")
				val titlePart = chapData.optString("title")
				val fullTitle = if (titlePart != "null" && titlePart.isNotEmpty()) "$dname - $titlePart" else dname

				val dateTs = chapData.optLong("dateModify").takeIf { it > 0 } ?: chapData.optLong("dateCreate")

				chapters.add(
					MangaChapter(
						id = generateUid(chapData.optString("id")),
						title = fullTitle,
						number = parseChapterNumber(dname),
						volume = 0,
						url = chapData.optString("urlPath"),
						uploadDate = dateTs * 1000L,
						source = source,
						scanlator = chapData.optJSONObject("userNode")?.optJSONObject("data")?.optString("name")
							?: chapData.optString("srcTitle"),
						branch = null
					)
				)
			}
		}

		return manga.copy(
			title = comicNode?.optString("name") ?: manga.title,
			authors = authors,
			description = comicNode?.optString("summary"),
			state = state,
			chapters = chapters,
			tags = tags,
			contentRating = null
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast('/').substringBefore('-')

		val query = $$"""
            query($id: ID!) {
              get_chapterNode(id: $id) {
                data {
                  imageFile {
                    urlList
                  }
                }
              }
            }
        """

		val json = graphqlRequest(query, JSONObject().put("id", chapterId))
		val chapterNode = json.optJSONObject("data")?.optJSONObject("get_chapterNode")
		val urls = chapterNode?.optJSONObject("data")
			?.optJSONObject("imageFile")
			?.optJSONArray("urlList") ?: return emptyList()

		val pages = ArrayList<MangaPage>(urls.length())
		for (i in 0 until urls.length()) {
			var url = urls.getString(i)
			url = url.replace(Regex("s\\d\\d"), "s00")

			pages.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source
				)
			)
		}
		return pages
	}

	private suspend fun graphqlRequest(query: String, variables: JSONObject): JSONObject {
		val payload = JSONObject().apply {
			put("query", query)
			put("variables", variables)
		}

		// FIX 3: Converted URL string to HttpUrl and Map to Headers for correct overload
		val responseBody = webClient.httpPost(
			url = apiUrl.toHttpUrl(),
			body = payload,
			extraHeaders = getRequestHeaders().newBuilder()
				.add("Content-Type", "application/json")
				.add("Referer", "https://$domain/")
				.build()
		).parseJson()

		val errors = responseBody.optJSONArray("errors")
		if (errors != null && errors.length() > 0) {
			throw Exception("GraphQL Error: ${errors.getJSONObject(0).optString("message")}")
		}

		return responseBody
	}

	private fun buildUrl(path: String): String {
		return when {
			path.startsWith("http") -> path
			path.startsWith("/") -> "https://$domain$path"
			else -> "https://$domain/$path"
		}
	}

	private fun parseChapterNumber(dname: String): Float {
		val cleaned = dname.replace(Regex("^Vol\\.\\s*\\S+\\s+", RegexOption.IGNORE_CASE), "")
		if (cleaned.contains("Bonus", ignoreCase = true)) return -2f

		val match = Regex("(?:Ch\\.|Chapter)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(cleaned)
		return match?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
	}
}
