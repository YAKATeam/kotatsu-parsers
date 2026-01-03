package org.koitharu.kotatsu.parsers.site.mangabox

internal class MangaBoxLinkedCdnSet : LinkedHashSet<String>() {
	fun moveItemToFirst(item: String) {
		synchronized(this) {
			if (contains(item) && first() != item) {
				remove(item)
				val newItems = mutableListOf(item)
				newItems.addAll(this)
				clear()
				addAll(newItems)
			}
		}
	}
}
