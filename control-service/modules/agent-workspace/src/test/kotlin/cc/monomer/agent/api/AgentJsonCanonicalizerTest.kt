package cc.monomer.agent.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentJsonCanonicalizerTest {
    @Test
    fun `RFC 8785 number vectors use ECMAScript serialization`() {
        val vectors =
            linkedMapOf(
                "-0" to "0",
                "5e-324" to "5e-324",
                "1.7976931348623157e308" to "1.7976931348623157e+308",
                "9007199254740992" to "9007199254740992",
                "295147905179352830000" to "295147905179352830000",
                "1e21" to "1e+21",
                "9.999999999999997e-7" to "9.999999999999997e-7",
                "0.000001" to "0.000001",
                "333333333.33333329" to "333333333.3333333",
            )

        vectors.forEach { (input, expected) ->
            assertEquals(expected, AgentJsonCanonicalizer.canonicalNumber(input), input)
        }
    }

    @Test
    fun `strict number admission rejects JCS value changes while accepting equivalent forms`() {
        assertEquals("9007199254740991", AgentJsonCanonicalizer.canonicalNumberPreservingValue("9007199254740991"))
        assertEquals("60", AgentJsonCanonicalizer.canonicalNumberPreservingValue("6e1"))

        listOf("9007199254740993", "0.10000000000000001", "1e-400").forEach { number ->
            assertFailsWith<IllegalArgumentException> {
                AgentJsonCanonicalizer.canonicalNumberPreservingValue(number)
            }
        }
    }

    @Test
    fun `RFC 8785 property sorting follows UTF-16 code units`() {
        val input =
            """{"€":"Euro Sign","\r":"Carriage Return","דּ":"Hebrew Letter Dalet With Dagesh","1":"One","😀":"Emoji Grinning Face","":"Control","ö":"Latin Small Letter O With Diaeresis"}"""
        val expected =
            """{"\r":"Carriage Return","1":"One","":"Control","ö":"Latin Small Letter O With Diaeresis","€":"Euro Sign","😀":"Emoji Grinning Face","דּ":"Hebrew Letter Dalet With Dagesh"}"""

        assertEquals(expected, AgentJsonCanonicalizer.canonicalBytes(input).decodeToString())
    }

    @Test
    fun `Unicode scalar validation accepts pairs and rejects lone surrogates`() {
        assertTrue(AgentJsonCanonicalizer.hasOnlyUnicodeScalars("한글😀"))
        assertFalse(AgentJsonCanonicalizer.hasOnlyUnicodeScalars("\uD800"))
        assertFalse(AgentJsonCanonicalizer.hasOnlyUnicodeScalars("\uDC00"))
    }
}
