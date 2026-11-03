package com.bonsaponsi.baptv

import java.text.Normalizer

/**
 * Channel categories – the general DVB content categories (EN 300 468, table 29), the same as the programme
 * genres of the TV guide (`R.array.genres`, `Context.genreLabel`), so one set serves antenna, HbbTV and internet
 * channels. Guessed in this order: the user's choice ([ChannelStore.customCategory]), the playlist group of an
 * internet channel (`group-title`), known Polish channel names, keywords in the name, and finally the genre
 * that fills most of the channel's TV guide.
 */
object ChannelCategory {
    const val NONE = 0
    const val FILM = 1
    const val NEWS = 2
    const val ENTERTAINMENT = 3
    const val SPORT = 4
    const val KIDS = 5
    const val MUSIC = 6
    const val ARTS = 7
    const val SOCIETY = 8
    const val EDUCATION = 9
    const val LEISURE = 10

    /** Categories in display order (filter, phone remote). */
    val ALL = listOf(NEWS, ENTERTAINMENT, FILM, SPORT, KIDS, MUSIC, EDUCATION, ARTS, LEISURE, SOCIETY)

    /** Polish terrestrial channels ([ServiceCatalog]) whose names say nothing about their content. */
    private val known: Map<String, Int> = buildMap {
        listOf("tvp1", "tvp2", "polsat", "tvn", "tv4", "tv puls", "tv6", "super polsat", "ttv", "antena", "tvp polonia",
            "tvp rozrywka", "nowa tv", "wp", "tvt", "polsat hbbtv", "stars tv").forEach { put(it, ENTERTAINMENT) }
        listOf("tvn 7", "stopklatka tv", "puls 2", "tvp vod", "cda premium", "mango").forEach { put(it, FILM) }
        listOf("tvp3", "tvp info", "tvp world", "tvn 24", "polsat news", "polsat news polityka", "republika", "wpolsce24",
            "wydarzenia 24", "newsmax polska", "belsat tv").forEach { put(it, NEWS) }
        listOf("tvp abc", "tvp abc 2", "alfa tvp", "4fun kids", "4 fun kids").forEach { put(it, KIDS) }
        listOf("eska tv", "polo tv", "4fun tv", "4 fun tv", "4fun dance", "4 fun dance", "emitv").forEach { put(it, MUSIC) }
        listOf("fokus tv", "tvp historia", "tvp historia 2", "tvp nauka", "tvp dokument").forEach { put(it, EDUCATION) }
        listOf("tvp kultura", "tvp kultura 2", "tv trwam", "radio jasna gora").forEach { put(it, ARTS) }
        listOf("tvn turbo", "tvn style", "tvp kobieta", "sklep kapitan pl", "tv okazje").forEach { put(it, LEISURE) }
        listOf("tok fm", "radio bezpieczna podroz").forEach { put(it, NEWS) }
        // Names a keyword rule below would get wrong ("vox", "radio", "crime").
        put("vox", ENTERTAINMENT)
        put("polskie radio 24", NEWS)
        listOf("crime+investigation", "crime + investigation", "crime investigation").forEach { put(it, EDUCATION) }
    }

    /** Keyword rules for names and playlist groups, checked in order (the first match wins). */
    private val rules: List<Pair<Regex, Int>> = listOf(
        "sport|eleven|\\bfight|\\bgol\\b|football|pilka|\\bnba\\b|\\bnfl\\b|\\bmma\\b|kickbox|boxing|\\bracer?\\b|racing|motorsport|monster jam" to SPORT,
        "news|\\binfo|wiadomosci|informac|wydarzenia|nachrichten|\\bnoticias|actualit|\\bpolityk|\\bbiznes|business" to NEWS,
        "kids|\\bkid\\b|junior|dzieci|dziecie|bajk|cartoon|disney|nick|minimini|kinder|enfant|infantil|\\bbaby|jimjam" to KIDS,
        "music|muzy|musik|musica|\\bhits?\\b|dance|\\bdisco\\b|\\bmtv\\b|\\bvh1\\b|\\bvox\\b|\\bradio\\b|\\bfm\\b|rock|\\bjazz|\\brmf\\b|antyradio|przeboje|\\beska\\b|\\bzet\\b|\\b[5-9]0s\\b" to MUSIC,
        "movie|\\bfilm|\\bkino|cinema|cine\\b|serial|series|\\bhbo\\b|canal\\+|stopklatka|\\baxn\\b|crime" to FILM,
        "docu|dokument|discovery|nat ?geo|national geographic|histor|nauk|science|planete|edukac|education|wiedz|\\bnatur" to EDUCATION,
        "kultur|culture|\\barte?\\b|opera|religi|trwam|\\bkatol|\\bewtn\\b" to ARTS,
        "travel|podroz|kuchni|kulinar|food|cook|style|lifestyle|\\bhome\\b|\\bdom\\b|turbo|motor|auto|hobby|\\bshop|sklep|okazje|fashion|moda" to LEISURE,
        "rozryw|entertain|comedy|komedi|general|ogoln|\\bshow\\b|reality" to ENTERTAINMENT,
        "\\bspolecz|society|\\bparlament" to SOCIETY,
    ).map { (pattern, category) -> Regex(pattern) to category }

    /**
     * Category from what the channel says about itself; [NONE] when nothing matches.
     * @param group playlist group of an internet channel (`group-title`), checked before the name.
     */
    fun guess(name: String, group: String? = null): Int {
        val key = normalize(name)
        known[key]?.let { return it }
        known[stripRegional(key)]?.let { return it }
        group?.let { g -> g.split(';', '|', ',', '/').forEach { part -> match(normalize(part)).takeIf { it != NONE }?.let { return it } } }
        return match(key)
    }

    /**
     * The genre that fills at least half of the guide's time (at least [MIN_PROGRAMMES] programmes with a genre);
     * [NONE] otherwise. Used when the name does not tell.
     */
    fun fromProgrammes(programmes: List<Programme>): Int {
        val withGenre = programmes.filter { it.genre in 1..LEISURE && it.endMs > it.startMs }
        if (withGenre.size < MIN_PROGRAMMES) return NONE
        val total = programmes.filter { it.endMs > it.startMs }.sumOf { it.endMs - it.startMs }
        val (genre, time) = withGenre.groupBy { it.genre }.mapValues { (_, list) -> list.sumOf { it.endMs - it.startMs } }
            .maxByOrNull { it.value } ?: return NONE
        return if (time * 2 >= total) genre else NONE
    }

    private fun match(text: String): Int = rules.firstOrNull { it.first.containsMatchIn(text) }?.second ?: NONE

    /** "TVP3 Gdańsk" → "tvp3"; "TVN 24 HD" is cut at "hd" by [normalize]. */
    private fun stripRegional(key: String) = if (key.startsWith("tvp3")) "tvp3" else key

    /**
     * Lower case without diacritics, punctuation, quality suffixes ("HD", "FHD", "4K") and bracketed notes of
     * playlists ("Puls 2 (1080p) [Geo-blocked]" → "puls 2").
     */
    fun normalize(text: String): String {
        val bare = text.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        val plain = Normalizer.normalize(bare.lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.replace(Regex("[^a-z0-9+]+"), " ").replace(Regex("\\b(u?hd|fhd|sd|4k|uhd|hevc)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private const val MIN_PROGRAMMES = 4
}
