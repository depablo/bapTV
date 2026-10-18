package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** English (values) and Polish (values-pl) must have the same strings and the same format placeholders. */
class StringsTest {

    private fun load(dir: String): Map<String, String> {
        val file = listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map(::File).first { it.exists() }
        return Regex("""<(string|string-array) name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText()).associate { it.groupValues[2] to it.groupValues[3] }
    }

    private fun placeholders(text: String) = Regex("""%\d\$[ds]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun languagesHaveSameKeysAndPlaceholders() {
        val en = load("values")
        val pl = load("values-pl")
        assertEquals(en.keys.sorted(), pl.keys.sorted())
        en.forEach { (key, text) -> assertEquals("placeholders in $key", placeholders(text), placeholders(pl.getValue(key))) }
    }

    @Test
    fun genreListsHaveSameLength() {
        fun count(dir: String) = Regex("<item>").findAll(load(dir).getValue("genres")).count()
        assertEquals(count("values"), count("values-pl"))
    }
}
