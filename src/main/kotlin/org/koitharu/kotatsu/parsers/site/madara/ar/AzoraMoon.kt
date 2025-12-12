package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.delay
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.AZORAMOON, "azoramoon.com", pageSize = 10) {
	override val tagPrefix = "series-genre/"
	override val listUrl = "series/"

	// PERMANENT caching system - ONE request per action type EVER
	private val tagCache = ConcurrentHashMap<String, Set<MangaTag>>()
	private val filterOptionsCache = ConcurrentHashMap<String, MangaListFilterOptions>()
	private val singlePageCache = ConcurrentHashMap<String, List<Manga>>() // Only cache ONE page
	private var lastRequestTime = 0L
	private val minRequestInterval = 1200L // Increased to 1.2 seconds for heavy rate limiting

	override val withoutAjax = true

	// Rate limiting helper
	private suspend fun rateLimit() {
		val currentTime = System.currentTimeMillis()
		val timeSinceLastRequest = currentTime - lastRequestTime
		if (timeSinceLastRequest < minRequestInterval) {
			delay(minRequestInterval - timeSinceLastRequest)
		}
		lastRequestTime = System.currentTimeMillis()
	}

	// Helper function for PERMANENT caching - one request per operation EVER
	private suspend inline fun <T> withPermanentCache(
		cache: ConcurrentHashMap<String, T>,
		key: String,
		useRateLimit: Boolean = true,
		crossinline fetcher: suspend () -> T
	): T {
		// If we have ANY cached data, return it immediately - NEVER make another request
		val cached = cache[key]
		if (cached != null) {
			println("AzoraMoon: Returning permanent cache for $key")
			return cached
		}

		println("AzoraMoon: Making ONE-TIME request for $key")

		// Only make requests if we have NO cached data at all
		// Apply rate limiting only for restricted operations
		if (useRateLimit) {
			rateLimit()
		}

		try {
			val data = fetcher()
			cache[key] = data // Store permanently, no TTL
			println("AzoraMoon: Permanently cached $key")
			return data
		} catch (e: Exception) {
			println("AzoraMoon: Request failed for $key: ${e.message}")
			throw e
		}
	}

	// Override tag fetching with caching and rate limiting (HEAVILY RESTRICTED)
	override suspend fun fetchAvailableTags(): Set<MangaTag> = withPermanentCache(
		cache = tagCache,
		key = "tags",
		useRateLimit = true
	) {
		super.fetchAvailableTags()
	}

	// Override filter options with caching (HEAVILY RESTRICTED)
	override suspend fun getFilterOptions(): MangaListFilterOptions = withPermanentCache(
		cache = filterOptionsCache,
		key = "filter_options",
		useRateLimit = true
	) {
		MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// Generate stable cache key for list pages
	private fun generateListCacheKey(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val query = filter.query ?: ""
		val tags = filter.tags.sortedBy { it.key }.joinToString(",") { it.key }
		val states = filter.states.sorted().joinToString(",")
		val contentRating = filter.contentRating?.toString() ?: ""
		return "list_${page}_${order}_${query}_${tags}_${states}_${contentRating}"
	}

	// Override list page with VERY LIMITED caching - only allow basic browsing
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Only allow page 1 requests to avoid pagination spam
		if (page > 1) {
			println("AzoraMoon: Blocking page $page request - only page 1 allowed")
			return emptyList() // Return empty instead of making request
		}

		// Use simplified cache key for basic browsing only
		val simplifiedKey = if (filter.query.isNullOrEmpty() && filter.tags.isEmpty() && filter.states.isEmpty()) {
			"basic_list_${order}" // Basic browsing
		} else {
			"search_${filter.query ?: ""}_${order}" // Simple search
		}

		return withPermanentCache(
			cache = singlePageCache,
			key = simplifiedKey,
			useRateLimit = true
		) {
			super.getListPage(1, order, filter) // Always request page 1 only
		}
	}
}
