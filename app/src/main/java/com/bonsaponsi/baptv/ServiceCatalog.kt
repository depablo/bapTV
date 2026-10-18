package com.bonsaponsi.baptv

/**
 * Names and LCN numbers of Polish terrestrial services by service number (service_id).
 *
 * Needed because SDT names are unavailable: dtvinput keeps them in a hidden database (searchable=0),
 * and a stream recording ([TsProbe]) contains no SDT. The service number comes from the recording's PMT.
 * Service numbers on the national multiplexes in Poland are unique, so Poland's ONID and the SID are enough.
 *
 * Source: dvbt2wpolsce.pl/parametry-sid-i-lcn (as of 09.10.2026), checked against recordings of
 * MUX-3 and MUX-4 (TVP AIT, EPG). Local multiplexes omitted – their numbers repeat.
 */
object ServiceCatalog {

    /** ONID of the Polish terrestrial network (0x2268 = 8808, country code 616). */
    const val ONID_POLAND = 0x2268

    /**
     * @param virtual "HbbTV-only" channel: a still card over the air, the actual video comes over the internet
     *   from the broadcaster's HbbTV app (e.g. TVP ABC 2, Historia 2, Kultura 2).
     */
    data class Service(val name: String, val lcn: Int, val mux: String, val radio: Boolean = false, val virtual: Boolean = false)

    private fun tv(name: String, lcn: Int, mux: String) = Service(name, lcn, mux)
    private fun virtual(name: String, lcn: Int, mux: String) = Service(name, lcn, mux, virtual = true)
    private fun radio(name: String, lcn: Int) = Service(name, lcn, "MUX-4", radio = true)

    private val services: Map<Int, Service> = buildMap {
        // MUX-1
        put(27, tv("ESKA TV", 12, "MUX-1")); put(28, tv("TTV", 13, "MUX-1")); put(29, tv("Polo TV", 14, "MUX-1"))
        put(30, tv("Antena HD", 15, "MUX-1")); put(50, tv("TV Trwam", 16, "MUX-1")); put(52, tv("Stopklatka TV", 17, "MUX-1"))
        put(53, tv("Fokus TV", 18, "MUX-1")); put(70, tv("Wydarzenia 24", 49, "MUX-1"))
        // MUX-2
        put(3, tv("Polsat", 4, "MUX-2")); put(4, tv("TVN", 5, "MUX-2")); put(5, tv("TV4", 6, "MUX-2")); put(6, tv("TV Puls", 7, "MUX-2"))
        put(23, tv("TVN 7", 8, "MUX-2")); put(24, tv("PULS 2", 9, "MUX-2")); put(25, tv("TV6", 10, "MUX-2")); put(26, tv("Super Polsat", 11, "MUX-2"))
        // MUX-3 (TVP)
        put(1, tv("TVP1 HD", 1, "MUX-3")); put(2, tv("TVP2 HD", 2, "MUX-3"))
        listOf("Białystok", "Bydgoszcz", "Gdańsk", "Gorzów", "Katowice", "Kielce", "Kraków", "Lublin", "Łódź",
            "Olsztyn", "Opole", "Poznań", "Rzeszów", "Szczecin", "Warszawa", "Wrocław")
            .forEachIndexed { i, city -> put(7 + i, tv("TVP3 $city", 3, "MUX-3")) }
        put(51, tv("TVP ABC", 29, "MUX-3")); put(31, tv("TVP Kultura", 30, "MUX-3")); put(32, tv("TVP Historia", 31, "MUX-3"))
        put(33, tv("TVP Sport", 32, "MUX-3")); put(35, tv("TVP Info", 34, "MUX-3")); put(58, virtual("TVP VOD", 88, "MUX-3"))
        // On air (MUX-3, 10.2026) TVP VOD is broadcast as service 88, not 58 as in the listing.
        put(88, virtual("TVP VOD", 88, "MUX-3"))
        put(91, virtual("TVP World", 91, "MUX-3")); put(92, virtual("TVP ABC 2", 92, "MUX-3")); put(93, virtual("TVP Historia 2", 93, "MUX-3"))
        put(94, virtual("TVP Kultura 2", 94, "MUX-3"))
        // MUX-4 (pay TV – scrambled TV channels) and radio
        put(110, tv("Polsat Sport 1", 110, "MUX-4")); put(111, tv("Polsat Sport 2", 111, "MUX-4"))
        put(112, tv("Eurosport 1", 112, "MUX-4")); put(113, tv("Eurosport 2", 113, "MUX-4"))
        put(114, tv("Eleven Sports 1", 114, "MUX-4")); put(115, tv("Eleven Sports 2", 115, "MUX-4"))
        put(116, tv("TVN Turbo HD", 116, "MUX-4")); put(117, tv("TVN Style", 117, "MUX-4"))
        put(118, tv("Polsat News Polityka", 118, "MUX-4")); put(119, tv("Polsat News HD", 119, "MUX-4"))
        put(120, tv("TVN 24 HD", 120, "MUX-4")); put(8189, virtual("Polsat HbbTV", 333, "MUX-4"))
        listOf("RMF FM", "RMF MAXX", "Antyradio", "Norda FM", "Radio ZET", "Złote Przeboje", "Radio Plus", "VOX FM",
            "TOK FM", "Rock Radio", "Eska Rock", "Radio Wielkopolska", "Radio Bezpieczna Podróż", "Radio Rekord FM",
            "Radio Kolor 103FM", "Radio Kaszebe FM", "Radio Jasna Góra")
            .forEachIndexed { i, name -> put(4096 + i, radio(name, 900 + i)) }
        // MUX-6
        put(42, tv("TVP Polonia", 19, "MUX-6")); put(66, tv("TVP Nauka", 20, "MUX-6")); put(61, tv("TVP Dokument", 24, "MUX-6"))
        put(62, tv("TVP Rozrywka", 27, "MUX-6")); put(40, tv("Alfa TVP", 28, "MUX-6")); put(83, tv("TVP World", 33, "MUX-6"))
        put(54, tv("TVP Kobieta", 35, "MUX-6")); put(55, tv("Belsat TV", 36, "MUX-6"))
        // MUX-8
        put(59, tv("Nowa TV", 40, "MUX-8")); put(60, tv("WP", 41, "MUX-8")); put(72, tv("Republika", 51, "MUX-8"))
        put(73, tv("wPolsce24", 52, "MUX-8")); put(86, tv("CDA Premium", 86, "MUX-8")); put(87, tv("Sklep Kapitan.pl", 87, "MUX-8"))
        put(89, tv("Newsmax Polska", 89, "MUX-8")); put(98, Service("EmiRadio", 98, "MUX-8", radio = true)); put(99, tv("EmiTV", 99, "MUX-8"))
        // MUX BCAST (Gdańsk, Warsaw, Wrocław) – LCN numbers for Gdańsk and Warsaw
        put(171, tv("Polsat HD", 21, "BCAST")); put(172, tv("Nowa TV HD", 22, "BCAST")); put(173, tv("TV Okazje HD", 23, "BCAST"))
        put(174, tv("Stars.TV HD", 25, "BCAST")); put(176, tv("TVT HD", 26, "BCAST")); put(177, tv("Mango HD", 59, "BCAST"))
        put(178, tv("4Fun.TV", 42, "BCAST")); put(179, tv("4Fun Kids", 43, "BCAST")); put(180, tv("4Fun Dance", 44, "BCAST"))
    }

    /** Service with the given number in the Polish network; null for another network or an unknown number. */
    fun lookup(onid: Int?, sid: Int?): Service? {
        if (sid == null || (onid != null && onid != ONID_POLAND)) return null
        // Without ONID (no SDT or EIT in the recording) the service number says little – catalog used only in Poland.
        if (onid == null && Region.country().let { it.isNotEmpty() && !it.equals("PL", ignoreCase = true) }) return null
        return services[sid]
    }
}
