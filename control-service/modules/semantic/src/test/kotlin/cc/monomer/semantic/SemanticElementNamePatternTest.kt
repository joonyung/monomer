package cc.monomer.semantic

import cc.monomer.semantic.api.SemanticElementName
import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticElementNamePatternTest {
    @Test
    fun `element names keep the previous lower snake case language without repeated groups`() {
        val previous = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
        words(listOf('a', '0', '_', 'A', '-', '\n', '\u0085'), 7).forEach { value ->
            assertEquals(previous.matches(value), accepted(value), value)
        }
        val megabyteName = "a" + "_a".repeat(500_000)
        assertEquals(megabyteName, SemanticElementName(megabyteName).value)
        assertEquals(false, accepted(megabyteName + "_"))
    }

    private fun accepted(value: String): Boolean =
        try {
            SemanticElementName(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun words(alphabet: List<Char>, maximumLength: Int): List<String> {
        val values = mutableListOf<String>()
        var layer = listOf("")
        repeat(maximumLength + 1) { length ->
            values += layer
            if (length < maximumLength) layer = layer.flatMap { word -> alphabet.map { word + it } }
        }
        return values
    }
}
