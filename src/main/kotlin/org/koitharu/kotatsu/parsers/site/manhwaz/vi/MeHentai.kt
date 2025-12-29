package org.koitharu.kotatsu.parsers.site.manhwaz.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.manhwaz.ManhwaZ

@MangaSourceParser("MEHENTAI", "MeHentai", "vi", ContentType.HENTAI)
internal class MeHentai(context: MangaLoaderContext) :
	ManhwaZ(context, MangaParserSource.MEHENTAI, "mehentai.tv") {
	override val searchPath = "tim-kiem"
}
