package org.koitharu.kotatsu.parsers.site.vi

import androidx.collection.arraySetOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import java.util.*
import kotlin.collections.map

@MangaSourceParser("GOCTRUYENTRANHVUI", "Góc Truyện Tranh Vui", "vi")
internal class GocTruyenTranhVui(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.GOCTRUYENTRANHVUI, 50), MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("goctruyentranhvui17.com")
	override val userAgentKey = ConfigKey.UserAgent(
		"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.46 Mobile Safari/537.36",
	)
    private val apiUrl by lazy { "https://$domain/api/v2" }

    private val requestMutex = Mutex()
    private var lastRequestTime = 0L
	private var userToken: String = ""

	private fun apiHeaders(): Headers = Headers.Builder()
		.add("Authorization", userToken)
		.add("Referer", "https://$domain/")
		.add("X-Requested-With", "XMLHttpRequest")
		.build()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
    )

	override val authUrl: String
		get() = domain

	override suspend fun isAuthorized(): Boolean {
		val token = loadAuthToken()
		if (!token.isNullOrBlank()) {
			userToken = token
			return true
		}
		return false
	}

	override suspend fun getUsername(): String {
		val raw = WebViewHelper(context)
			.getLocalStorageValue(domain, "user_info")
			?.removeSurrounding('"')
			?.trim()

		if (raw.isNullOrBlank()) {
			throw AuthRequiredException(
				source,
				IllegalStateException("user_info not found in Local Storage")
			)
		}

		val localStorage = try {
			JSONObject(raw)
		} catch (e: Exception) {
			throw AuthRequiredException(
				source,
				IllegalStateException("Invalid user_info JSON", e)
			)
		}

		val name = localStorage.optString("name")
		if (name.isBlank()) {
			throw AuthRequiredException(
				source,
				IllegalStateException("Username not found")
			)
		}

		return name
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        enforceRateLimit()
        val url = buildString {
            append(apiUrl)
            append("/search?p=${page - 1}")
            if (!filter.query.isNullOrBlank()) {
                append("&searchValue=${filter.query.urlEncoded()}")
            }

            val sortValue = when (order) {
                SortOrder.POPULARITY -> "viewCount"
                SortOrder.NEWEST -> "createdAt"
                SortOrder.RATING -> "evaluationScore"
                else -> "recentDate" // UPDATED
            }
            append("&orders%5B%5D=$sortValue")

            filter.tags.forEach { append("&categories%5B%5D=${it.key}") }

            filter.states.forEach {
                val statusKey = when (it) {
                    MangaState.ONGOING -> "PRG"
                    MangaState.FINISHED -> "END"
                    else -> null
                }
                if (statusKey != null) append("&status%5B%5D=$statusKey")
            }
        }

        val json = webClient.httpGet(url, extraHeaders = apiHeaders()).parseJson()
        val result = json.optJSONObject("result") ?: return emptyList()
        val data = result.optJSONArray("data") ?: return emptyList()

        return List(data.length()) { i ->
            val item = data.getJSONObject(i)
            val comicId = item.getString("id")
            val slug = item.getString("nameEn")
            val mangaUrl = "/truyen/$slug"
            val tags = item.optJSONArray("category")?.let { arr ->
                (0 until arr.length()).mapNotNullTo(mutableSetOf()) { index ->
                    val tagName = arr.getString(index)
                    availableTags().find { it.title.equals(tagName, ignoreCase = true) }?.let { genrePair ->
                        MangaTag(key = genrePair.key, title = genrePair.title, source = source)
                    }
                }
            } ?: emptySet()

            Manga(
                id = generateUid(comicId),
                title = item.getString("name"),
                altTitles = item.optString("otherName", "").split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet(),
                url = "$comicId:$slug", // Store both id and slug, separated by ':'
                publicUrl = "https://$domain$mangaUrl",
                rating = item.optDouble("evaluationScore", 0.0).toFloat(),
                contentRating = null,
                coverUrl = "https://$domain${item.getString("photo")}",
                tags = tags,
                state = when (item.optString("statusCode")) {
                    "PRG" -> MangaState.ONGOING
                    "END" -> MangaState.FINISHED
                    else -> null
                },
                authors = setOf(item.optString("author", "Updating")),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val comicId = manga.url.substringBefore(':')
        val slug = manga.url.substringAfter(':')

        val chapters = try {
            enforceRateLimit()
            val chapterApiUrl = "https://$domain/api/comic/$comicId/chapter?limit=-1"

			// Auth before send request for chapters
			if (userToken.isBlank()) {
				throw AuthRequiredException(
					source,
					IllegalStateException("No username found, please login")
				)
			}

            val chapterJson = webClient.httpGet(chapterApiUrl, extraHeaders = apiHeaders()).parseJson()
            val chaptersData = chapterJson.getJSONObject("result").getJSONArray("chapters")

            List(chaptersData.length()) { i ->
                val item = chaptersData.getJSONObject(i)
                val number = item.getString("numberChapter")
                val name = item.getString("name")
                val chapterUrl = "/truyen/$slug/chuong-$number" // keep for generateUid
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = if (name != "N/A" && name.isNotBlank()) name else "Chapter $number",
                    number = number.toFloatOrNull() ?: -1f,
                    volume = 0,
                    url = "${item.getLong("id")}:$number/$slug",
                    scanlator = null,
                    uploadDate = item.optLong("updateTime", 0L),
                    branch = null,
                    source = source
                )
            }
        } catch (_: Exception) {
            emptyList()
        }.reversed()

        enforceRateLimit()
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()

        val detailTags = doc.select(".group-content > .v-chip-link").mapNotNullTo(mutableSetOf()) { el ->
            availableTags().find { it.title.equals(el.text(), ignoreCase = true) }?.let {
                MangaTag(key = it.key, title = it.title, source = source)
            }
        }

        return manga.copy(
            title = doc.selectFirst(".v-card-title")?.text().orEmpty(),
            tags = manga.tags + detailTags,
            coverUrl = doc.selectFirst("img.image")?.absUrl("src"),
            state = when (doc.selectFirst(".mb-1:contains(Trạng thái:) span")?.text()) {
                "Đang thực hiện" -> MangaState.ONGOING
                "Hoàn thành" -> MangaState.FINISHED
                else -> manga.state
            },
            authors = setOfNotNull(doc.selectFirst(".mb-1:contains(Tác giả:) span")?.text()),
            description = doc.selectFirst(".v-card-text")?.text(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val payload =
			"comicId=${chapter.url.substringBefore(":")}" +
				"&chapterNumber=${chapter.url.substringAfter(":").substringBefore("/")}" +
				"&nameEn=${chapter.url.substringAfter("/")}"

		val res = webClient.httpPost(
			"https://$domain/api/chapter/loadAll".toHttpUrl(),
			payload,
			apiHeaders()
		).parseJson()

		val data = res.getJSONObject("result").getJSONArray("data")

		return data.asTypedList<String>().map {
			MangaPage(
				id = generateUid(it),
				url = it,
				preview = null,
				source = source,
			)
		}
	}

    private suspend fun enforceRateLimit() {
        requestMutex.withLock {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - lastRequestTime
            if (timeSinceLastRequest < REQUEST_DELAY_MS) { // Vẫn truy cập được REQUEST_DELAY_MS
                delay(REQUEST_DELAY_MS - timeSinceLastRequest)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }

	private suspend fun loadAuthToken(): String? {
		return WebViewHelper(context)
			.getLocalStorageValue(domain, "Authorization")
			?.removeSurrounding('"')
			?.trim()
			?.takeIf { it.startsWith("Bearer ") }
	}

	private fun availableTags() = arraySetOf(
        MangaTag("Anime", "ANI", source),
        MangaTag("Drama", "DRA", source),
        MangaTag("Josei", "JOS", source),
        MangaTag("Manhwa", "MAW", source),
        MangaTag("One Shot", "OSH", source),
        MangaTag("Shounen", "SHO", source),
        MangaTag("Webtoons", "WEB", source),
        MangaTag("Shoujo", "SHJ", source),
        MangaTag("Harem", "HAR", source),
        MangaTag("Ecchi", "ECC", source),
        MangaTag("Mature", "MAT", source),
        MangaTag("Slice of life", "SOL", source),
        MangaTag("Isekai", "ISE", source),
        MangaTag("Manga", "MAG", source),
        MangaTag("Manhua", "MAU", source),
        MangaTag("Hành Động", "ACT", source),
        MangaTag("Phiêu Lưu", "ADV", source),
        MangaTag("Hài Hước", "COM", source),
        MangaTag("Võ Thuật", "MAA", source),
        MangaTag("Huyền Bí", "MYS", source),
        MangaTag("Lãng Mạn", "ROM", source),
        MangaTag("Thể Thao", "SPO", source),
        MangaTag("Học Đường", "SCL", source),
        MangaTag("Lịch Sử", "HIS", source),
        MangaTag("Kinh Dị", "HOR", source),
        MangaTag("Siêu Nhiên", "SUN", source),
        MangaTag("Bi Kịch", "TRA", source),
        MangaTag("Trùng Sinh", "RED", source),
        MangaTag("Game", "GAM", source),
        MangaTag("Viễn Tưởng", "FTS", source),
        MangaTag("Khoa Học", "SCF", source),
        MangaTag("Truyện Màu", "COI", source),
        MangaTag("Người Lớn", "ADU", source),
        MangaTag("BoyLove", "BBL", source),
        MangaTag("Hầm Ngục", "DUN", source),
        MangaTag("Săn Bắn", "HUNT", source),
        MangaTag("Ngôn Từ Nhạy Cảm", "NTNC", source),
        MangaTag("Doujinshi", "DOU", source),
        MangaTag("Bạo Lực", "BLM", source),
        MangaTag("Ngôn Tình", "NTT", source),
        MangaTag("Nữ Cường", "NCT", source),
        MangaTag("Gender Bender", "GDB", source),
        MangaTag("Murim", "MRR", source),
        MangaTag("Leo Tháp", "LTT", source),
        MangaTag("Nấu Ăn", "COO", source)
    )

    companion object {
        private const val REQUEST_DELAY_MS = 350L
    }
}
