package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("MINITWOSCAN", "MiniTwoScan", "pt")
internal class MiniTwoScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MINITWOSCAN, "minitwoscan.com") {

	override val withoutAjax = true
	override val postReq = true
}
