package cc.monomer.execution.driver.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class QueryRequestCanonicalizerTest {
    @Test
    fun newTypedBindsAndExecutionChecksChangeDigest() {
        val base = unsignedRequest(QueryResultTarget.InlineStream(1024, 8192, 4096))
        val variants = listOf(
            base,
            base.copy(validateRawSqlBeforeRun = true),
            base.copy(expectedColumnNames = listOf("amount")),
            base.copy(expectedColumnNames = listOf("amount", "date")),
            base.copy(expectedColumnNames = listOf("date", "amount")),
            base.copy(parameters = listOf(QueryParameter.Decimal("1.23", name = ""))),
            base.copy(parameters = listOf(QueryParameter.Date(java.time.LocalDate.parse("2026-10-06"), name = ""))),
            base.copy(parameters = listOf(QueryParameter.Timestamp(java.time.Instant.parse("2026-10-06T01:02:03.123456Z"), name = ""))),
            base.copy(parameters = listOf(QueryParameter.Null(NullQueryParameterType.DECIMAL, name = ""))),
            base.copy(parameters = listOf(QueryParameter.Null(NullQueryParameterType.DATE, name = ""))),
            base.copy(parameters = listOf(QueryParameter.Null(NullQueryParameterType.TIMESTAMP, name = ""))),
        )
        assertEquals(variants.size, variants.map { request -> QueryRequestCanonicalizer.canonicalDigest(request) }.distinct().size)
        assertFailsWith<IllegalArgumentException> { QueryParameter.Decimal("1.230", name = "") }
        assertFailsWith<IllegalArgumentException> { QueryParameter.Decimal("0.1234567890123456789", name = "") }
        assertFailsWith<IllegalArgumentException> { QueryParameter.Decimal("123456789012345678901234567890123456789", name = "") }
        assertFailsWith<IllegalArgumentException> { QueryParameter.Timestamp(java.time.Instant.parse("2026-10-06T01:02:03.123456789Z"), name = "") }
    }

    @Test
    fun canonicalDigestIncludesTheSelectedDeliveryAndItsBounds() {
        val inline = unsignedRequest(QueryResultTarget.InlineStream(1024, 8192, 4096))
        val artifact = unsignedRequest(
            QueryResultTarget.ArtifactUpload(
                objectKey = "artifacts/workspaces/w/r/result.arrow",
                maximumArtifactRows = 1_000_000u,
                maximumArtifactBytes = 1_073_741_824u,
                maximumRecordBatchRows = 1024,
                maximumRecordBatchBytes = 8192,
                maximumCellBytes = 4096,
                maximumMultipartPartBytes = 8192,
            ),
        )

        assertNotEquals(
            QueryRequestCanonicalizer.canonicalDigest(inline),
            QueryRequestCanonicalizer.canonicalDigest(artifact),
        )
        assertEquals(
            QueryRequestCanonicalizer.canonicalDigest(inline),
            QueryRequestCanonicalizer.canonicalDigest(inline.copy()),
        )
    }

    @Test
    fun claimedDigestMismatchAndUnsupportedPayloadsFailClosed() {
        val unsigned = unsignedRequest(QueryResultTarget.InlineStream(1024, 8192, 4096))
        val canonical = unsigned.copy(
            canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned),
        )

        QueryRequestCanonicalizer.requireCanonical(canonical)
        assertFailsWith<IllegalArgumentException> {
            QueryRequestCanonicalizer.requireCanonical(
                canonical.copy(canonicalRequestDigest = Sha256Digest("0".repeat(64))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            QueryRequestCanonicalizer.requireCanonical(canonical.copy(schemaVersion = 2u))
        }
        assertFailsWith<IllegalArgumentException> {
            QueryRequestCanonicalizer.requireCanonical(canonical.copy(sql = "SELECT \u0000"))
        }
    }

    @Test
    fun `named parameters use a self delimiting tail and preserve unnamed v1`() {
        val base = unsignedRequest(QueryResultTarget.ArtifactUpload("artifacts/workspaces/019e0000-0000-7000-8000-0000000000a1/explore/runs/019e0000-0000-7000-8000-0000000000b1/result.arrow", 42uL, 1_073_741_824uL, 1024, 8192, 4096, 8192)).copy(sql = "SELECT * FROM \"orders\" WHERE total > ? AND active = ?")
        val names = listOf("p1", "p2", "p3", "p4", "p5", "p6")
        val parameters = listOf(
            QueryParameter.Text("café", names[0]), QueryParameter.Integer64(-7, names[1]),
            QueryParameter.Float64(-0.0, names[2]), QueryParameter.BooleanValue(true, names[3]),
            QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(0, 127, -1)), names[4]),
            QueryParameter.Null(NullQueryParameterType.BYTES, names[5]),
        )
        val variants = listOf(
            base.copy(parameters = parameters),
            base.copy(parameters = parameters, validateRawSqlBeforeRun = true),
            base.copy(parameters = parameters, expectedColumnNames = listOf("amount")),
            base.copy(parameters = parameters, validateRawSqlBeforeRun = true, expectedColumnNames = listOf("amount")),
            base.copy(parameters = listOf(
                QueryParameter.Text("café", "p6"), QueryParameter.Integer64(-7, "p5"),
                QueryParameter.Float64(-0.0, "p4"), QueryParameter.BooleanValue(true, "p3"),
                QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(0, 127, -1)), "p2"),
                QueryParameter.Null(NullQueryParameterType.BYTES, "p1"),
            )),
            base.copy(parameters = listOf(QueryParameter.Text("café", "p1")) + base.parameters.drop(1)),
        )
        assertEquals(listOf(
            "2015e4a15dfb0d3468460b45ce4a5f16f8006ae9c800c1d318266afd72647fe6",
            "5acc766438fd2e4e4b2783154008302787d0814d4c105757b7991913bf480bd9",
            "85f970b41739c1d77408f00eed462804584cd1dd6afc2ad29f506dcae692a217",
            "f94231cd64e76e34e06f5c47b86edd29ecccc82e945ad49eb098b4985bab9a7b",
            "bd6668da53b191390fbd0b4e0c60c4254e8a3327d8034e2949a02ffde3fe9a15",
            "d76250ca19e0d81446c3e5599e7e48dc8b00f858a10018a91e4606ddb50a2baf",
        ), variants.map { QueryRequestCanonicalizer.canonicalDigest(it).value })
    }

    private fun unsignedRequest(target: QueryResultTarget): QueryRequest =
        QueryRequest(
            validateRawSqlBeforeRun = false,
            expectedColumnNames = emptyList(),
            schemaVersion = 1u,
            canonicalRequestDigest = Sha256Digest("0".repeat(64)),
            sql = "SELECT * FROM orders WHERE total > ? AND active = ?",
            parameters =
                listOf(
                    QueryParameter.Text("café", name = ""),
                    QueryParameter.Integer64(-7, name = ""),
                    QueryParameter.Float64(-0.0, name = ""),
                    QueryParameter.BooleanValue(true, name = ""),
                    QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(0, 127, -1)), name = ""),
                    QueryParameter.Null(NullQueryParameterType.BYTES, name = ""),
                ),
            resultTarget = target,
        )
}
