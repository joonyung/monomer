package cc.monomer.search

import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTextTest {
    @Test fun `names preserve separators while search splits identifiers`() {
        assertEquals("customer_revenue", SearchText.normalizedName("customer_revenue"))
        assertEquals("customer_revenue customer revenue", SearchText.document(listOf("customer_revenue")))
        assertEquals("customerrevenue customer revenue", SearchText.document(listOf("customerRevenue")))
        assertEquals("('customer_revenue' | ('customer' & 'revenue'))", SearchText.query("customer_revenue", SearchMatch.WHOLE_TOKENS))
        assertEquals("'customer' & 'revenue'", SearchText.query("customer revenue", SearchMatch.WHOLE_TOKENS))
    }

    @Test fun `plain input has no stemming stopwords or query operators`() {
        assertEquals("'id' & 'in' & 'or' & '매출을' & '고객별매출'", SearchText.query("id in or 매출을 고객별매출", SearchMatch.WHOLE_TOKENS))
        assertEquals("'revenue' & 'sales'", SearchText.query("revenue | !sales:*", SearchMatch.WHOLE_TOKENS))
        assertFalse(SearchText.hasTokens("_ ! :*"))
        assertTrue(SearchText.hasTokens("0"))
    }

    @Test fun `unicode composition and acronym boundaries are shared`() {
        assertEquals(SearchText.query("café", SearchMatch.WHOLE_TOKENS), SearchText.query("cafe\u0301", SearchMatch.WHOLE_TOKENS))
        assertEquals("httpserver http server", SearchText.document(listOf("HTTPServer")))
        assertEquals("매출", SearchText.normalizedName("매출"))
    }
}
