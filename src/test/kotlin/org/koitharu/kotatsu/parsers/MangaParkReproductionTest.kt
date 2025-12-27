package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import kotlin.time.Duration.Companion.minutes

internal class MangaParkReproductionTest {

    private val context = MangaLoaderContextMock
    private val timeout = 2.minutes
    private val source = MangaParserSource.MANGAPARK

    @Test
    fun list() = runTest(timeout = timeout) {
        val parser = context.newParserInstance(source)
        val list = parser.getList(MangaSearchQuery.Builder().build())
        
        assert(list.isNotEmpty()) { "Manga list is empty" }
        assert(list.all { it.source == source })
        
        // Also check if we can get details for one of them
        val manga = list.first()
        println("Checking details for: ${manga.title}")
        val details = parser.getDetails(manga)
        assert(details.chapters?.isNotEmpty() == true) { "Chapters are empty for ${manga.title}" }
    }
}
