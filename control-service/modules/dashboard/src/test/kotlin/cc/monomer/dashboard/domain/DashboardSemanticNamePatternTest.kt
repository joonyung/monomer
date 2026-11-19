package cc.monomer.dashboard.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DashboardSemanticNamePatternTest {
    @Test
    fun `semantic names keep the previous language without repeated groups`() {
        val previous = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
        words(listOf('a', '0', '_', 'A', '-', '\n', '\u0085'), 7).forEach { value ->
            assertEquals(value.isNotBlank() && previous.matches(value) && value.length <= 128, DashboardDefinition.isSemanticName(value), value)
        }
        assertFalse(DashboardDefinition.isSemanticName("a" + "_a".repeat(500_000)))
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
