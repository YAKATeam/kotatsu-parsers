package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.GraphQLException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*
import java.util.concurrent.TimeUnit

@MangaSourceParser("BATOTOV4", "Bato.To v4")
internal class BatoToV4Parser(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.BATOTOV4,
	pageSize = 36,
) {

	override val configKeyDomain = ConfigKey.Domain("bato.si", "battwo.com", "bato.to", "bato.ing")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isOriginalLocaleSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = GENRE_OPTIONS.mapToSet { (title, key) -> MangaTag(title, key, source) },
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED),
			availableLocales = LANGUAGES.values.toSet(),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val variables = JSONObject().apply {
			val select = JSONObject().apply {
				put("page", page)
				put("size", pageSize)
				put("word", filter.query ?: "")
				put("sortby", when (order) {
					SortOrder.POPULARITY -> "field_score"
					SortOrder.RATING -> "field_score"
					SortOrder.UPDATED -> "field_upload"
					SortOrder.NEWEST -> "field_public"
					SortOrder.ALPHABETICAL -> "field_name"
					else -> "field_score"
				})
				put("where", if (filter.query.isNullOrEmpty()) "browse" else "search")
				put("incGenres", JSONArray(filter.tags.map { it.key }))
				put("excGenres", JSONArray(filter.tagsExclude.map { it.key }))
				put("incOLangs", JSONArray())
				put("incTLangs", JSONArray(filter.locale?.let { listOf(it.language) } ?: emptyList<String>()))
				put("origStatus", filter.states.firstOrNull()?.let {
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						MangaState.ABANDONED -> "cancelled"
						else -> ""
					}
				} ?: "")
				put("siteStatus", "")
				put("chapCount", "")
			}
			put("select", select)
		}

		val response = graphQLQuery("https://$domain/ap2/", COMIC_SEARCH_QUERY, variables)
		val data = response.getJSONObject("data").getJSONObject("get_comic_browse")
		val items = data.getJSONArray("items")

		return items.mapJSON { item ->
			parseManga(item.getJSONObject("data"))
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val variables = JSONObject().apply {
			put("id", manga.url)
		}
		val response = graphQLQuery("https://$domain/ap2/", COMIC_NODE_QUERY, variables)
		val comic = response.getJSONObject("data").getJSONObject("get_comicNode").getJSONObject("data")

		val authors = comic.optJSONArray("authors")?.asTypedList<String>()?.toSet() ?: emptySet()
		val genres = comic.optJSONArray("genres")?.asTypedList<String>()?.mapToSet { key ->
			val title = GENRE_OPTIONS.find { it.second == key }?.first ?: key.toTitleCase()
			MangaTag(title, key, source)
		} ?: emptySet()

		return manga.copy(
			authors = authors,
			tags = genres,
			description = comic.optString("summary"),
			state = parseStatus(comic.optString("originalStatus", "")),
			largeCoverUrl = comic.optString("urlCoverOri")?.let { "https://$domain$it" },
			chapters = getChapters(manga.url)
		)
	}

	private suspend fun getChapters(comicId: String): List<MangaChapter> {
		val variables = JSONObject().apply {
			put("comicId", comicId)
			put("start", -1)
		}
		val response = graphQLQuery("https://$domain/ap2/", CHAPTER_LIST_QUERY, variables)
		val data = response.getJSONObject("data").getJSONArray("get_comic_chapterList")

		return data.mapJSON { item ->
			val chapter = item.getJSONObject("data")
			val id = chapter.getString("id")
			val name = chapter.optString("dname").takeIf { it.isNotBlank() && it != "null" }
			val title = chapter.optString("title").takeIf { it.isNotBlank() && it != "null" }
			val serial = chapter.getDouble("serial")

			val groups = chapter.optJSONObject("groupNodes")?.optJSONArray("data")
				?.asTypedList<JSONObject>()
				?.mapNotNull { it.optString("name").takeIf { s -> s.isNotBlank() && s != "null" } }
				?.joinToString()
				.takeIf { !it.isNullOrBlank() }
				?: chapter.optJSONObject("userNode")?.optJSONObject("data")?.optString("name")
					?.takeIf { it != "null" }

			MangaChapter(
				id = generateUid(id),
				title = when {
					name != null && title != null -> "$name: $title"
					name != null -> name
					title != null -> title
					else -> null
				},
				number = serial.toFloat(),
				volume = 0,
				url = "$comicId/$id",
				uploadDate = chapter.optLong("dateModify", chapter.optLong("dateCreate", 0)),
				source = source,
				scanlator = groups,
				branch = groups
			)
		}
		// .asReversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/")
		val variables = JSONObject().apply {
			put("id", chapterId)
		}
		val response = graphQLQuery("https://$domain/ap2/", CHAPTER_NODE_QUERY, variables)
		val data = response.getJSONObject("data").getJSONObject("get_chapterNode").getJSONObject("data")
		val urls = data.getJSONObject("imageFile").getJSONArray("urlList")

		return (0 until urls.length()).map { i ->
			val urlString = urls.getString(i)
			MangaPage(
				id = generateUid(urlString),
				url = "$urlString#page",
				preview = null,
				source = source
			)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)

		if (request.url.fragment != "page" || response.isSuccessful) {
			return response
		}

		response.close()

		val urlString = request.url.toString()
		if (SERVER_PATTERN.containsMatchIn(urlString)) {
			for (server in SERVERS) {
				val newUrl = urlString.replace(SERVER_PATTERN, "https://$server")
				val newRequest = request.newBuilder()
					.url(newUrl)
					.build()

				try {
					val newResponse = chain
						.withConnectTimeout(5, TimeUnit.SECONDS)
						.withReadTimeout(10, TimeUnit.SECONDS)
						.proceed(newRequest)

					if (newResponse.isSuccessful) {
						return newResponse
					}
					newResponse.close()
				} catch (_: Exception) {
					// Ignore and try next server
				}
			}
		}

		return chain.proceed(request)
	}

	private suspend fun graphQLQuery(endpoint: String, query: String, variables: JSONObject): JSONObject {
		val payload = JSONObject().apply {
			put("query", query)
			put("variables", variables)
		}
		val response = webClient.httpPost(endpoint.toHttpUrl(), payload, getRequestHeaders())
		val json = response.parseJson()
		json.optJSONArray("errors")?.let {
			if (it.length() != 0) {
				throw GraphQLException(it)
			}
		}
		return json
	}

	private fun parseManga(json: JSONObject): Manga {
		val id = json.getString("id")
		val name = json.getString("name")
		val cover = json.optString("urlCoverOri")
		return Manga(
			id = generateUid(id),
			title = name,
			altTitles = emptySet(),
			url = id,
			publicUrl = "https://$domain/title/$id",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = if (cover.isNullOrEmpty()) null else "https://$domain$cover",
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source
		)
	}

	private fun parseStatus(status: String): MangaState? = when {
		status.contains("ongoing", true) -> MangaState.ONGOING
		status.contains("completed", true) -> MangaState.FINISHED
		status.contains("hiatus", true) -> MangaState.PAUSED
		status.contains("cancelled", true) -> MangaState.ABANDONED
		else -> null
	}

	companion object {
		private val SERVER_PATTERN = Regex("https://[a-zA-Z]\\d{2}")
		private val SERVERS = listOf(
			"n03", "n00", "n01", "n02", "n04", "n05", "n06", "n07", "n08", "n09", "n10",
			"k03", "k06", "k07", "k00", "k01", "k02", "k04", "k05", "k08", "k09"
		)

		private val GENRE_OPTIONS = listOf(
			"Artbook" to "artbook",
			"Cartoon" to "cartoon",
			"Comic" to "comic",
			"Doujinshi" to "doujinshi",
			"Imageset" to "imageset",
			"Manga" to "manga",
			"Manhua" to "manhua",
			"Manhwa" to "manhwa",
			"Webtoon" to "webtoon",
			"Western" to "western",
			"4-Koma" to "_4_koma",
			"Oneshot" to "oneshot",
			"Shoujo(G)" to "shoujo",
			"Shounen(B)" to "shounen",
			"Josei(W)" to "josei",
			"Seinen(M)" to "seinen",
			"Yuri(GL)" to "yuri",
			"Yaoi(BL)" to "yaoi",
			"Futa(WL)" to "futa",
			"Bara(ML)" to "bara",
			"Kodomo(Kid)" to "kodomo",
			"Silver & Golden" to "old_people",
			"Shoujo Ai" to "shoujo_ai",
			"Shounen Ai" to "shounen_ai",
			"Non-human" to "non_human",
			"Gore" to "gore",
			"Bloody" to "bloody",
			"Violence" to "violence",
			"Ecchi" to "ecchi",
			"Adult" to "adult",
			"Mature" to "mature",
			"Smut" to "smut",
			"Hentai" to "hentai",
			"Action" to "action",
			"Adaptation" to "adaptation",
			"Adventure" to "adventure",
			"Age Gap" to "age_gap",
			"Aliens" to "aliens",
			"Animals" to "animals",
			"Anthology" to "anthology",
			"Beasts" to "beasts",
			"Bodyswap" to "bodyswap",
			"Boys" to "boys",
			"Cars" to "cars",
			"Cheating/Infidelity" to "cheating_infidelity",
			"Childhood Friends" to "childhood_friends",
			"College Life" to "college_life",
			"Comedy" to "comedy",
			"Contest Winning" to "contest_winning",
			"Cooking" to "cooking",
			"Crime" to "crime",
			"Crossdressing" to "crossdressing",
			"Delinquents" to "delinquents",
			"Dementia" to "dementia",
			"Demons" to "demons",
			"Drama" to "drama",
			"Dungeons" to "dungeons",
			"Emperor's Daughter" to "emperor_daughte",
			"Fantasy" to "fantasy",
			"Fan-Colored" to "fan_colored",
			"Fetish" to "fetish",
			"Full Color" to "full_color",
			"Game" to "game",
			"Gender Bender" to "gender_bender",
			"Genderswap" to "genderswap",
			"Girls" to "girls",
			"Ghosts" to "ghosts",
			"Gyaru" to "gyaru",
			"Harem" to "harem",
			"Harlequin" to "harlequin",
			"Historical" to "historical",
			"Horror" to "horror",
			"Incest" to "incest",
			"Isekai" to "isekai",
			"Kids" to "kids",
			"Magic" to "magic",
			"Magical Girls" to "magical_girls",
			"Martial Arts" to "martial_arts",
			"Mecha" to "mecha",
			"Medical" to "medical",
			"Military" to "military",
			"Monster Girls" to "monster_girls",
			"Monsters" to "monsters",
			"Music" to "music",
			"Mystery" to "mystery",
			"Netori" to "netori",
			"Netorare/NTR" to "netorare",
			"Ninja" to "ninja",
			"Office Workers" to "office_workers",
			"Omegaverse" to "omegaverse",
			"Parody" to "parody",
			"Philosophical" to "philosophical",
			"Police" to "police",
			"Post-Apocalyptic" to "post_apocalyptic",
			"Psychological" to "psychological",
			"Regression" to "regression",
			"Reincarnation" to "reincarnation",
			"Reverse Harem" to "reverse_harem",
			"Revenge" to "revenge",
			"Reverse Isekai" to "reverse_isekai",
			"Romance" to "romance",
			"Royal Family" to "royal_family",
			"Royalty" to "royalty",
			"Samurai" to "samurai",
			"School Life" to "school_life",
			"Sci-Fi" to "sci_fi",
			"Shota" to "shota",
			"Showbiz" to "showbiz",
			"Slice of Life" to "slice_of_life",
			"SM/BDSM/SUB-DOM" to "sm_bdsm",
			"Space" to "space",
			"Sports" to "sports",
			"Super Power" to "super_power",
			"Superhero" to "superhero",
			"Supernatural" to "supernatural",
			"Survival" to "survival",
			"Thriller" to "thriller",
			"Time Travel" to "time_travel",
			"Tower Climbing" to "tower_climbing",
			"Traditional Games" to "traditional_games",
			"Tragedy" to "tragedy",
			"Transmigration" to "transmigration",
			"Vampires" to "vampires",
			"Villainess" to "villainess",
			"Video Games" to "video_games",
			"Virtual Reality" to "virtual_reality",
			"Wuxia" to "wuxia",
			"Xianxia" to "xianxia",
			"Xuanhuan" to "xuanhuan",
			"Yakuzas" to "yakuzas",
			"Zombies" to "zombies"
		)

		private val LANGUAGES = mapOf(
			"English" to Locale.ENGLISH,
			"Chinese" to Locale.CHINESE,
			"Japanese" to Locale.JAPANESE,
			"Korean" to Locale.KOREAN,
			"Afrikaans" to Locale("af"),
			"Albanian" to Locale("sq"),
			"Amharic" to Locale("am"),
			"Arabic" to Locale("ar"),
			"Armenian" to Locale("hy"),
			"Azerbaijani" to Locale("az"),
			"Belarusian" to Locale("be"),
			"Bengali" to Locale("bn"),
			"Bosnian" to Locale("bs"),
			"Bulgarian" to Locale("bg"),
			"Burmese" to Locale("my"),
			"Cambodian" to Locale("km"),
			"Catalan" to Locale("ca"),
			"Cebuano" to Locale("ceb"),
			"Chinese (Cantonese)" to Locale("zh_hk"),
			"Chinese (Traditional)" to Locale("zh_tw"),
			"Croatian" to Locale("hr"),
			"Czech" to Locale("cs"),
			"Danish" to Locale("da"),
			"Dutch" to Locale("nl"),
			"Estonian" to Locale("et"),
			"Faroese" to Locale("fo"),
			"Filipino" to Locale("fil"),
			"Finnish" to Locale("fi"),
			"French" to Locale.FRENCH,
			"Georgian" to Locale("ka"),
			"German" to Locale.GERMAN,
			"Greek" to Locale("el"),
			"Guarani" to Locale("gn"),
			"Gujarati" to Locale("gu"),
			"Haitian Creole" to Locale("ht"),
			"Hausa" to Locale("ha"),
			"Hebrew" to Locale("he"),
			"Hindi" to Locale("hi"),
			"Hungarian" to Locale("hu"),
			"Icelandic" to Locale("is"),
			"Igbo" to Locale("ig"),
			"Indonesian" to Locale("id"),
			"Irish" to Locale("ga"),
			"Italian" to Locale.ITALIAN,
			"Javanese" to Locale("jv"),
			"Kannada" to Locale("kn"),
			"Kazakh" to Locale("kk"),
			"Kurdish" to Locale("ku"),
			"Kyrgyz" to Locale("ky"),
			"Laothian" to Locale("lo"),
			"Latvian" to Locale("lv"),
			"Lithuanian" to Locale("lt"),
			"Luxembourgish" to Locale("lb"),
			"Macedonian" to Locale("mk"),
			"Malagasy" to Locale("mg"),
			"Malay" to Locale("ms"),
			"Malayalam" to Locale("ml"),
			"Maltese" to Locale("mt"),
			"Maori" to Locale("mi"),
			"Marathi" to Locale("mr"),
			"Moldavian" to Locale("mo"),
			"Mongolian" to Locale("mn"),
			"Nepali" to Locale("ne"),
			"Norwegian" to Locale("no"),
			"Nyanja" to Locale("ny"),
			"Pashto" to Locale("ps"),
			"Persian" to Locale("fa"),
			"Polish" to Locale("pl"),
			"Portuguese" to Locale("pt"),
			"Portuguese (Brazil)" to Locale("pt", "BR"),
			"Romanian" to Locale("ro"),
			"Romansh" to Locale("rm"),
			"Russian" to Locale("ru"),
			"Samoan" to Locale("sm"),
			"Serbian" to Locale("sr"),
			"Serbo-Croatian" to Locale("sh"),
			"Sesotho" to Locale("st"),
			"Shona" to Locale("sn"),
			"Sindhi" to Locale("sd"),
			"Sinhalese" to Locale("si"),
			"Slovak" to Locale("sk"),
			"Slovenian" to Locale("sl"),
			"Somali" to Locale("so"),
			"Spanish" to Locale("es"),
			"Spanish (Latin America)" to Locale("es", "419"),
			"Swahili" to Locale("sw"),
			"Swedish" to Locale("sv"),
			"Tajik" to Locale("tg"),
			"Tamil" to Locale("ta"),
			"Telugu" to Locale("te"),
			"Thai" to Locale("th"),
			"Tigrinya" to Locale("ti"),
			"Tonga" to Locale("to"),
			"Turkish" to Locale("tr"),
			"Turkmen" to Locale("tk"),
			"Ukrainian" to Locale("uk"),
			"Urdu" to Locale("ur"),
			"Uzbek" to Locale("uz"),
			"Vietnamese" to Locale("vi"),
			"Yoruba" to Locale("yo"),
			"Zulu" to Locale("zu")
		)

		private const val COMIC_NODE = """
			data {
				id
				name
				altNames
				authors
				artists
				originalStatus
				uploadStatus
				genres
				summary
				extraInfo
				urlPath
				urlCoverOri
			}
		"""

		val COMIC_SEARCH_QUERY = $$"""
			query ($select: Comic_Browse_Select) {
				get_comic_browse(select: $select) {
					paging {
						next
					}
					items {
						$$COMIC_NODE
					}
				}
			}
		""".trimIndent()

		val COMIC_NODE_QUERY = $$"""
			query get_comicNode($id: ID!) {
				get_comicNode(id: $id) {
					$$COMIC_NODE
				}
			}
		""".trimIndent()

		val CHAPTER_LIST_QUERY = $$"""
			query get_comic_chapterList($comicId: ID!, $start: Int) {
				get_comic_chapterList(comicId: $comicId, start: $start) {
					data {
						comicId
						id
						serial
						dname
						title
						dateCreate
						dateModify
						userNode {
							data {
								name
							}
						}
						groupNodes {
							data {
								name
							}
						}
					}
				}
			}
		""".trimIndent()

		val CHAPTER_NODE_QUERY = $$"""
			query get_chapterNode($id: ID!) {
				get_chapterNode(id: $id) {
					data {
						id
						comicId
						imageFile {
							urlList
						}
					}
				}
			}
		""".trimIndent()
	}
}
