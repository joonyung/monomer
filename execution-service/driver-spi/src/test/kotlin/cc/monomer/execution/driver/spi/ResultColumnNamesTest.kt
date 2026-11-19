package cc.monomer.execution.driver.spi

import kotlin.test.Test
import kotlin.test.assertEquals

class ResultColumnNamesTest {
    @Test
    fun namesAcceptedByResultColumnStayUnchanged() {
        val names = listOf("id", "Revenue", "주문 수", "a b", "order.total", "x".repeat(504))
        names.forEachIndexed { index, name -> ResultColumn(index + 1, name, ResultLogicalType.TEXT, true) }

        assertEquals(names, names.map(ResultColumn::normalizedName))
        assertEquals(names, ResultColumn.acceptedNames(names))
    }

    @Test
    fun normalizesComposedFormControlCharactersSurroundingSpaceAndLength() {
        assertEquals("café", ResultColumn.normalizedName("café"))
        assertEquals("ab c", ResultColumn.normalizedName("a\u0000b\t c\n"))
        assertEquals("padded", ResultColumn.normalizedName("  padded  "))
        assertEquals("x".repeat(504), ResultColumn.normalizedName("x".repeat(600)))
        assertEquals("x".repeat(503), ResultColumn.normalizedName("x".repeat(503) + " y"))
        assertEquals(null, ResultColumn.normalizedName(" \u0007 "))
    }

    @Test
    fun removesControlCharactersBeforeComposingSoTheResultIsAcceptedAndStable() {
        val composed = "\u00e9"
        assertEquals(composed, ResultColumn.normalizedName("e\u0000\u0301"))
        assertEquals(listOf(composed), ResultColumn.acceptedNames(listOf("e\u0000\u0301")))
        ResultColumn(1, composed, ResultLogicalType.TEXT, true)

        listOf(
            "e\u0000\u0301",
            "cafe\u0301",
            "\u0007 \u0301x ",
            "a\u0000b\t c\n",
            "\u0958" + "x".repeat(510),
            "x".repeat(503) + " \u0301",
            "\u1100\u0000\u1161",
        ).forEach { declaredName ->
            val normalized = ResultColumn.normalizedName(declaredName)
            assertEquals(normalized, normalized?.let(ResultColumn::normalizedName), declaredName)
            normalized?.let { ResultColumn(1, it, ResultLogicalType.TEXT, true) }
        }
    }

    @Test
    fun fillsMissingNamesAndDeduplicatesWithinTheColumnNameLimit() {
        val longName = "x".repeat(600)
        val accepted =
            ResultColumn.acceptedNames(listOf("a", "a", "a_2", null, "", "column_4", "\u0000", longName, longName))

        assertEquals(
            listOf("a", "a_2", "a_2_2", "column_4", "column_5", "column_4_2", "column_7", "x".repeat(504), "x".repeat(504) + "_2"),
            accepted,
        )
        accepted.forEachIndexed { index, name -> ResultColumn(index + 1, name, ResultLogicalType.TEXT, true) }
    }
}
