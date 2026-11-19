package cc.monomer.semantic

import cc.monomer.semantic.application.partitionExpressionIdentifiers
import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticPartitionFactsTest {
    @Test
    fun `bare identifiers are lowercased ascii words and function names are skipped`() = assertIdentifiers(
        "" to emptyList(),
        "ordered_at" to listOf("ordered_at"),
        "ORDERED_AT" to listOf("ordered_at"),
        "a + b_1 * _c" to listOf("a", "b_1", "_c"),
        "a a" to listOf("a", "a"),
        "1abc 2 x9" to listOf("abc", "x9"),
        "orders.ordered_at" to listOf("orders", "ordered_at"),
        "café Ünit_x" to listOf("caf", "nit_x"),
        "DATE(created_at)" to listOf("created_at"),
        "DATE  (created_at)" to listOf("created_at"),
        "DATE\n\t(created_at)" to listOf("created_at"),
        "DATE (created_at)" to listOf("created_at"),
        "DATE /* call */ (created_at)" to listOf("date", "created_at"),
        "f(g(h))" to listOf("h"),
        "x'y'" to listOf("x"),
        "'abc(" to emptyList(),
    )

    @Test
    fun `backtick identifiers keep their content split on dots and are never function names`() = assertIdentifiers(
        "`Orders`.`Ordered_At`" to listOf("orders", "ordered_at"),
        "`proj.dataset.Col`" to listOf("proj", "dataset", "col"),
        "`DATE`(x)" to listOf("date", "x"),
        "`x`(" to listOf("x"),
        "`a b`" to listOf("a b"),
        "`a``b`" to listOf("a", "b"),
        "`'x'` y" to listOf("'x'", "y"),
        "``" to emptyList(),
        "`` x" to listOf("x"),
        "`abc" to listOf("abc"),
    )

    @Test
    fun `single quoted literals are skipped including doubled and unterminated quotes`() = assertIdentifiers(
        "'abc'" to emptyList(),
        "'abc' x" to listOf("x"),
        "''" to emptyList(),
        "'' x" to listOf("x"),
        "'''" to emptyList(),
        "''''" to emptyList(),
        "'it''s' x" to listOf("x"),
        "'a' 'b' c" to listOf("c"),
        "'a\"b' c" to listOf("c"),
        "'a\nb' c" to listOf("c"),
        "'a'(b)" to listOf("b"),
        "'😀' x" to listOf("x"),
        "'abc" to listOf("abc"),
        "'abc''" to emptyList(),
        "'abc''' x" to listOf("x"),
        "'a''b" to listOf("b"),
        "'x'' y''z" to listOf("z"),
        "'/* x */' y" to listOf("y"),
        "'-- x' y" to listOf("y"),
    )

    @Test
    fun `double quoted literals follow the same rules as single quoted literals`() = assertIdentifiers(
        "\"Ordered_At\"" to emptyList(),
        "\"abc\" x" to listOf("x"),
        "\"\" x" to listOf("x"),
        "\"\"\"" to emptyList(),
        "\"a\"\"b\" x" to listOf("x"),
        "\"it's\" x" to listOf("x"),
        "'it\"s' \"x'y\" z" to listOf("z"),
        "\"abc" to listOf("abc"),
        "\"abc\"\"" to emptyList(),
        "\"a\"\"b" to listOf("b"),
        "\"`x`\" y" to listOf("y"),
    )

    @Test
    fun `line and block comments are skipped and unterminated block comments are not`() = assertIdentifiers(
        "-- ordered_at" to emptyList(),
        "a -- b\nc" to listOf("a", "c"),
        "a -- b\r\nc" to listOf("a", "c"),
        "a -- b\rc" to listOf("a", "c"),
        "a - b" to listOf("a", "b"),
        "a--b" to listOf("a"),
        "-- 'unterminated\nx" to listOf("x"),
        "/* a */ b" to listOf("b"),
        "/* a \n b */ c" to listOf("c"),
        "/**/ x" to listOf("x"),
        "/*/ a */ b" to listOf("b"),
        "/* a */*/ b" to listOf("b"),
        "/* 'x */ y" to listOf("y"),
        "/* a" to listOf("a"),
        "a/b" to listOf("a", "b"),
    )

    @Test
    fun `mixed tokens keep only modeled identifiers`() = assertIdentifiers(
        "'ordered_at' /* ordered_at */ -- ordered_at\nstatus" to listOf("status"),
        "'a' -- 'b\n/* 'c */ `D`.e \"f\" g(h)" to listOf("d", "e", "h"),
        "CASE WHEN `Region` = 'it''s' THEN \"Col\" ELSE amount END" to listOf("case", "when", "region", "then", "else", "amount", "end"),
    )

    @Test
    fun `multi megabyte quoted literals are scanned without exhausting the stack`() {
        val body = "a".repeat(4_000_000)
        mapOf(
            "single quoted" to ("'$body' x" to listOf("x")),
            "double quoted" to ("\"$body\" x" to listOf("x")),
            "doubled quotes" to ("'${"a''".repeat(1_500_000)}' x" to listOf("x")),
            "unterminated after a doubled quote" to ("'$body''" to emptyList()),
            "unterminated" to ("'$body" to listOf(body)),
        ).forEach { (label, case) -> assertEquals(case.second, partitionExpressionIdentifiers(case.first), label) }
    }

    private fun assertIdentifiers(vararg cases: Pair<String, List<String>>) {
        cases.forEach { (expression, expected) -> assertEquals(expected, partitionExpressionIdentifiers(expression), expression) }
    }
}
