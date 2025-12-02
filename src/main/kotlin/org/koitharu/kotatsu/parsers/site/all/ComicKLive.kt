package org.koitharu.kotatsu.parsers.site.all

import androidx.collection.ArraySet
import androidx.collection.SparseArrayCompat
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup.parseBodyFragment
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("COMICKLIVE", "ComicK (Unofficial)")
internal class ComicKLive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMICKLIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain(
        "comick.live",
        "comick.art"
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.RATING,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearRangeSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.OTHER,
        ),
        availableDemographics = EnumSet.of(
            Demographic.SHOUNEN,
            Demographic.SHOUJO,
            Demographic.SEINEN,
            Demographic.JOSEI,
            Demographic.NONE,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = urlBuilder()
            .host(domain)
            .addPathSegment("api")
            .addPathSegment("search")
            .addQueryParameter("type", "comic")
            .addQueryParameter("tachiyomi", "true")
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("page", page.toString())

        filter.query?.let {
            url.addQueryParameter("q", filter.query)
        }

        filter.tags.forEach {
            url.addQueryParameter("genres", it.key)
        }

        filter.tagsExclude.forEach {
            url.addQueryParameter("excludes", it.key)
        }

        url.addQueryParameter(
            "sort",
            when (order) {
                SortOrder.NEWEST -> "created_at"
                SortOrder.POPULARITY -> "view"
                SortOrder.RATING -> "rating"
                SortOrder.UPDATED -> "uploaded"
                else -> "uploaded"
            },
        )

        filter.states.oneOrThrowIfMany()?.let {
            url.addQueryParameter(
                "status",
                when (it) {
                    MangaState.ONGOING -> "1"
                    MangaState.FINISHED -> "2"
                    MangaState.ABANDONED -> "3"
                    MangaState.PAUSED -> "4"
                    else -> ""
                },
            )
        }

        if (filter.yearFrom != YEAR_UNKNOWN) {
            url.addQueryParameter("from", filter.yearFrom.toString())
        }

        if (filter.yearTo != YEAR_UNKNOWN) {
            url.addQueryParameter("to", filter.yearTo.toString())
        }

        filter.types.forEach {
            url.addQueryParameter(
                "country",
                when (it) {
                    ContentType.MANGA -> "jp"
                    ContentType.MANHWA -> "kr"
                    ContentType.MANHUA -> "cn"
                    ContentType.OTHER -> "others"
                    else -> ""
                },
            )
        }

        filter.demographics.forEach {
            url.addQueryParameter(
                "demographic",
                when (it) {
                    Demographic.SHOUNEN -> "1"
                    Demographic.SHOUJO -> "2"
                    Demographic.SEINEN -> "3"
                    Demographic.JOSEI -> "4"
                    Demographic.NONE -> "5"
                    else -> ""
                },
            )
        }

        val ja = webClient.httpGet(url.build()).parseJson().getJSONArray("data")
        val tagsMap = tagsArray.get()
        return ja.mapJSON { jo ->
            val slug = jo.getString("slug")
            Manga(
                id = generateUid(slug),
                title = jo.optString("title", "No title available"),
                altTitles = emptySet(),
                url = slug,
                publicUrl = "https://$domain/comic/$slug",
                rating = RATING_UNKNOWN,
                contentRating = when (jo.optString("content_rating")) {
                    "suggestive" -> ContentRating.SUGGESTIVE
                    "erotica" -> ContentRating.ADULT
                    else -> ContentRating.SAFE
                },
                coverUrl = jo.getStringOrNull("default_thumbnail"),
                tags = jo.selectGenres(tagsMap),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$domain/comic/${manga.url}"
        val htmlPage = webClient.httpGet(url).parseHtml()

        // get (pre-loaded) embedded <script> from html :p?
        val comicDataJS = htmlPage.selectFirst("#comic-data")?.data()
            ?: throw ParseException("Comic data not found", url)
        val jo = JSONObject(comicDataJS)

        val altTitles = jo.getJSONArray("titles")
            ?.asTypedList<JSONObject>()
            ?.mapNotNullToSet { it.getStringOrNull("title") }
            ?: emptySet()

        val authors = jo.getJSONArray("authors")
            ?.mapJSONNotNullToSet { it.getStringOrNull("name") }
            ?: emptySet()

        return manga.copy(
            title = jo.optString("title", manga.title),
            altTitles = altTitles,
            contentRating = when (jo.getStringOrNull("content_rating")) {
                "suggestive" -> ContentRating.SUGGESTIVE
                "erotica" -> ContentRating.ADULT
                else -> ContentRating.SAFE
            },
            description = jo.getStringOrNull("desc")?.let { desc ->
                parseBodyFragment(desc).wholeText()
            },
            tags = jo.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val tag = arr.getJSONObject(i).getJSONObject("genres")
                    MangaTag(
                        title = tag.getString("name"),
                        key = tag.getString("slug"),
                        source = source
                    )
                }.toSet()
            } ?: emptySet(),
            authors = authors,
            state = when (jo.getInt("status")) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                3 -> MangaState.ABANDONED
                4 -> MangaState.PAUSED
                else -> null
            },
            chapters = getChapters(manga.url),
        )
    }

    private suspend fun getChapters(slug: String): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")

        // get all chapters from API
        val firstPg = webClient.httpGet("https://$domain/api/comics/$slug/chapter-list")
            .parseJson()
            .getJSONObject("pagination")
        val lastPg = findLastPage(firstPg)

        val allChaps = (1..lastPg).flatMap { page ->
            val res = webClient.httpGet("https://$domain/api/comics/$slug/chapter-list?page=$page")
                .parseJson()
            res.getJSONArray("data").asTypedList<JSONObject>()
        }

        return allChaps.reversed().mapChapters { _, jo ->
            val vol = jo.getIntOrDefault("vol", 0)
            val chap = jo.getFloat("chap")
            val locale = Locale.forLanguageTag(jo.getString("lang"))
            val group = jo.optJSONArray("group_name")?.joinToString(", ")
            val branch = buildString {
                append(locale.getDisplayName(locale).toTitleCase(locale))
                if (!group.isNullOrEmpty()) {
                    append(" (")
                    append(group)
                    append(')')
                }
            }
            MangaChapter(
                id = generateUid(jo.getLong("id")),
                title = jo.getStringOrNull("title"),
                number = chap,
                volume = vol,
                url = "$slug/${jo.getString("hid")}-chapter-$chap-${jo.getString("lang")}",
                scanlator = jo.optJSONArray("group_name")?.asTypedList<String>()?.joinToString()
                    ?.takeUnless { it.isBlank() },
                uploadDate = dateFormat.parseSafe(jo.getString("created_at").substringBefore('T')),
                branch = branch,
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Again :p?
        val jo = webClient.httpGet("https://${domain}/${chapter.url}")
            .parseHtml().selectFirst("#sv-data")?.data()
            ?: throw IllegalArgumentException("Page data not found")

        val imgs = JSONObject(jo)
            .getJSONObject("chapter")

        return imgs.getJSONArray("images").mapJSON {
            val url = it.getString("url")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val slug = link.pathSegments.lastOrNull() ?: return null
        return resolver.resolveManga(this, url = slug, id = generateUid(slug))
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val sparseArray = tagsArray.get()
        val set = ArraySet<MangaTag>(sparseArray.size())
        for (i in 0 until sparseArray.size()) {
            set.add(sparseArray.valueAt(i))
        }
        return set
    }

    private suspend fun loadTags(): SparseArrayCompat<MangaTag> {
        val response = webClient.httpGet("https://$domain/api/metadata").parseJson()
        val genres = response.getJSONArray("genres")
        val tags = SparseArrayCompat<MangaTag>(genres.length())

        for (i in 0 until genres.length()) {
            val jo = genres.getJSONObject(i)
            tags.append(
                jo.getInt("id"),
                MangaTag(
                    title = jo.getString("name").toTitleCase(Locale.ENGLISH),
                    key = jo.getString("slug"),
                    source = source,
                ),
            )
        }
        return tags
    }

    private fun findTagBySlug(tags: SparseArrayCompat<MangaTag>, slug: String): MangaTag? {
        // Same problem
        for (i in 0 until tags.size()) {
            val tag = tags.valueAt(i)
            if (tag.key == slug) {
                return tag
            }
        }
        return null
    }

    private fun findLastPage(pagination: JSONObject): Int {
        // should return 1 as default if cant find last pageNum
        val links = pagination.optJSONArray("links") ?: return 1
        return (0 until links.length())
            .mapNotNull { i ->
                val label = links.getJSONObject(i).optString("label")
                label.toIntOrNull()
            }
            .maxOrNull() ?: 1
    }

    private fun JSONObject.selectGenres(tags: SparseArrayCompat<MangaTag>): Set<MangaTag> {
        // Ref from Redo, like a mess
        // Need to debug that website to refactor it, leave it later
        val array = optJSONArray("genres") ?: return emptySet()
        val res = ArraySet<MangaTag>(array.length())
        for (i in 0 until array.length()) {
            val element = array.opt(i)
            val tag = when (element) {
                is Int -> tags[element]
                is JSONObject -> {
                    val slug = element.optString("slug")
                    if (slug.isNotEmpty()) {
                        findTagBySlug(tags, slug)
                    } else {
                        null
                    }
                }
                else -> null
            }
            tag?.let { res.add(it) }
        }
        return res
    }

    private fun JSONArray.joinToString(separator: String): String {
        return (0 until length()).joinToString(separator) { i -> getString(i) }
    }

    private val tagsArray = suspendLazy(initializer = ::loadTags)
}
