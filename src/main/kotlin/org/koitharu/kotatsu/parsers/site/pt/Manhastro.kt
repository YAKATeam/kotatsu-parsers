package org.koitharu.kotatsu.parsers.site.pt

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import okhttp3.Response
import okhttp3.Interceptor

@MangaSourceParser("MANHASTRO", "Manhastro", "pt")
internal class Manhastro(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.MANHASTRO) {

    private val apiUrl = "https://api2.manhastro.net"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    override val configKeyDomain = ConfigKey.Domain("manhastro.net")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val mangas = fetchAllMangas()
        val genres = mangas.flatMap { it.genres }.distinct().sorted()
        return MangaListFilterOptions(
            availableTags = genres.map { MangaTag(key = it, title = it, source = source) }.toSet(),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT)
        )
    }

    override suspend fun getList(
        offset: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {
        val query = filter.query.orEmpty().trim().lowercase().normalize()
        val selectedTags = filter.tags.map { it.key }.toSet()
        
        var mangas = fetchAllMangas()

        if (query.isNotEmpty()) {
            mangas = mangas.filter { 
                it.title.lowercase().normalize().contains(query) || 
                it.titleBrasil?.lowercase()?.normalize()?.contains(query) == true
            }
        }

        if (selectedTags.isNotEmpty()) {
            mangas = mangas.filter { it.genres.containsAll(selectedTags) }
        }

        mangas = when (order) {
            SortOrder.UPDATED -> {
                val latestIds = fetchLatestIds()
                val latestMap = mangas.associateBy { it.id }
                latestIds.mapNotNull { latestMap[it] }
            }
            SortOrder.POPULARITY -> mangas.sortedByDescending { it.views }
            SortOrder.NEWEST -> mangas.sortedByDescending { it.id }
            SortOrder.ALPHABETICAL -> mangas.sortedBy { it.displayTitle.lowercase() }
            else -> mangas
        }

        val end = (offset + 24).coerceAtMost(mangas.size)
        if (offset >= mangas.size) return emptyList()
        
        return mangas.subList(offset, end).map { dto ->
            Manga(
                id = generateUid(dto.id.toString()),
                title = dto.displayTitle,
                altTitles = emptySet(),
                url = "/manga/${dto.id}",
                publicUrl = "$domain/manga/${dto.id}",
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = dto.thumbnailUrl,
                tags = dto.genres.map { MangaTag(key = it, title = it, source = source) }.toSet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/").toIntOrNull() 
            ?: throw Exception("Invalid Manga ID")
            
        val allMangas = fetchAllMangas()
        val dto = allMangas.find { it.id == id } ?: throw Exception("Manga not found")
        
        val chapters = fetchChapters(dto.id)

        return manga.copy(
            title = dto.displayTitle,
            description = dto.displayDescription,
            tags = dto.genres.map { MangaTag(key = it, title = it, source = source) }.toSet(),
            coverUrl = dto.thumbnailUrl,
            chapters = chapters
        )
    }

    private suspend fun fetchChapters(mangaId: Int): List<MangaChapter> {
        val response = webClient.httpGet("$apiUrl/dados/$mangaId")
        val json = response.parseJsonUtf8()
        
        val data = json.optJSONArray("data") ?: return emptyList()
        val chapters = ArrayList<MangaChapter>(data.length())

        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val chapterId = obj.getInt("capitulo_id")
            val name = obj.getString("capitulo_nome")
            val dateStr = obj.optString("capitulo_data")
            
            val number = extractChapterNumber(name)
            val date = runCatching { if (dateStr.isNotEmpty()) dateFormat.parse(dateStr)?.time ?: 0L else 0L }.getOrDefault(0L)

            chapters.add(
                MangaChapter(
                    id = generateUid(chapterId.toString()),
                    url = "/capitulo/$chapterId",
                    title = name,
                    number = number,
                    volume = 0,
                    uploadDate = date,
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }
        
        return chapters.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfterLast("/")
        val response = webClient.httpGet("$apiUrl/paginas/$id")
        val json = response.parseJsonUtf8()
        val data = json.optJSONObject("data") ?: return emptyList()
        
        if (data.optBoolean("text", false)) {
            return emptyList()
        }
        
        val chData = data.optJSONObject("chapter") ?: return emptyList()
        val baseUrl = chData.optString("baseUrl")
        val hash = chData.optString("hash")
        val files = chData.optJSONArray("data") ?: return emptyList()
        
        val pages = ArrayList<MangaPage>(files.length())
        for (i in 0 until files.length()) {
            val file = files.getString(i)
            val url = "$baseUrl/$hash/$file"
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Referer") == null) {
            val newRequest = request.newBuilder()
                .addHeader("Referer", "https://manhastro.net/")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }

    private suspend fun fetchAllMangas(): List<MangaDto> {
        val currentTime = System.currentTimeMillis()
        synchronized(lock) {
            if (cachedMangas != null && currentTime - lastCacheTime < CACHE_DURATION) {
                return cachedMangas!!
            }
        }

        val response = webClient.httpGet("$apiUrl/dados")
        val json = response.parseJsonUtf8()
        val array = json.getJSONArray("data")
        val list = ArrayList<MangaDto>(array.length())
        for (i in 0 until array.length()) {
            list.add(MangaDto.fromJson(array.getJSONObject(i)))
        }

        synchronized(lock) {
            cachedMangas = list
            lastCacheTime = currentTime
        }
        return list
    }

    private suspend fun fetchLatestIds(): List<Int> {
        val currentTime = System.currentTimeMillis()
        synchronized(lock) {
             if (cachedLatestIds != null && currentTime - lastLatestIdsTime < CACHE_DURATION) {
                 return cachedLatestIds!!
             }
        }

        val response = webClient.httpGet("$apiUrl/lancamentos")
        val json = response.parseJsonUtf8()
        val array = json.getJSONArray("data")
        val list = ArrayList<Int>(array.length())
        for (i in 0 until array.length()) {
            list.add(array.getJSONObject(i).getInt("manga_id"))
        }
        
        synchronized(lock) {
            cachedLatestIds = list
            lastLatestIdsTime = currentTime
        }

        return list
    }

    private fun Response.parseJsonUtf8(): JSONObject {
        return this.use { response ->
            val source = response.body.source()
            val str = source.readString(java.nio.charset.StandardCharsets.UTF_8)
            JSONObject(str)
        }
    }

    private fun extractChapterNumber(name: String): Float {
        val regex = Regex("""(\d+(?:\.\d+)?)""")
        val match = regex.find(name)
        return match?.value?.toFloatOrNull() ?: -1f
    }
    
    private fun String.normalize(): String {
        return java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
            .replace(Regex("""[\p{InCombiningDiacriticalMarks}]"""), "")
    }

    companion object {
        private var cachedMangas: List<MangaDto>? = null
        private var lastCacheTime: Long = 0
        private var cachedLatestIds: List<Int>? = null
        private var lastLatestIdsTime: Long = 0
        private const val CACHE_DURATION = 10 * 60 * 1000 // 10 minutes
        private val lock = Any()
        
        private val BROKEN_GENRES = mapOf(
            "A??o" to "Ação",
            "Adapta??o" to "Adaptação",
            "Atualiza??o" to "Atualização",
            "Com?dia" to "Comédia",
            "Culin?ria" to "Culinária",
            "Dem?nios" to "Demônios",
            "Fic??o Cient?fica" to "Ficção Científica",
            "Filos?fico" to "Filosófico",
            "Gen?rico" to "Genérico",
            "Har?m" to "Harém",
            "Hist?rico" to "Histórico",
            "M?dico" to "Médico",
            "M?sica" to "Música",
            "Mist?rio" to "Mistério",
            "Psicol?gico" to "Psicológico",
            "Reencarna??o" to "Reencarnação",
            "Regress?o" to "Regressão",
            "Sobreviv?ncia" to "Sobrevivência",
            "Super-her?i" to "Super-herói",
            "Trag?dia" to "Tragédia",
            "Vingan?a" to "Vingança"
        )
    }

    private data class MangaDto(
        val id: Int,
        val title: String,
        val titleBrasil: String?,
        val description: String?,
        val descriptionBrasil: String?,
        val image: String?,
        val cover: String?,
        val genres: List<String>,
        val views: Int
    ) {
        val displayTitle: String get() = if (!titleBrasil.isNullOrBlank()) titleBrasil else title
        val displayDescription: String? get() = if (!descriptionBrasil.isNullOrBlank()) descriptionBrasil else description
        val thumbnailUrl: String? get() {
            val url = if (!image.isNullOrBlank()) image else cover
            return url?.let { if (it.startsWith("http")) it else "https://$it" }
        }

        companion object {
            fun fromJson(json: JSONObject): MangaDto {
                val genresStr = json.optString("generos")
                val genresList = when {
                    genresStr.startsWith("[") -> runCatching {
                        val arr = JSONArray(genresStr)
                        (0 until arr.length()).map { arr.getString(it) }
                    }.getOrDefault(emptyList())
                    genresStr.contains(",") -> genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    genresStr.isNotBlank() -> listOf(genresStr.trim())
                    else -> emptyList()
                }

                val fixedGenres = genresList.map { BROKEN_GENRES[it] ?: it }

                return MangaDto(
                    id = json.getInt("manga_id"),
                    title = json.optString("titulo"),
                    titleBrasil = json.optString("titulo_brasil").takeIf { it != "null" },
                    description = json.optString("descricao").takeIf { it != "null" },
                    descriptionBrasil = json.optString("descricao_brasil").takeIf { it != "null" },
                    image = json.optString("imagem").takeIf { it != "null" },
                    cover = json.optString("capa").takeIf { it != "null" },
                    genres = fixedGenres,
                    views = json.optString("views_mes").toIntOrNull() ?: 0
                )
            }
        }
    }
}