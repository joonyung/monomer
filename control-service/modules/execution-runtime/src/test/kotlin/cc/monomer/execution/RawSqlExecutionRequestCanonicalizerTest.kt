package cc.monomer.execution

import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.RawSqlExecutionRequest
import cc.monomer.execution.api.RawSqlExecutionRequestCanonicalizer
import cc.monomer.execution.api.QueryParameterValue
import cc.monomer.execution.api.NullQueryParameterType
import cc.monomer.execution.api.Sha256Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RawSqlExecutionRequestCanonicalizerTest {
    @Test
    fun newTypedBindsAndExecutionChecksChangeDigest() {
        val base = requestWithDigest(Sha256Digest("0".repeat(64)))
        val variants = listOf(
            base,
            base.copy(validateRawSqlBeforeRun = true),
            base.copy(expectedColumnNames = listOf("amount")),
            base.copy(expectedColumnNames = listOf("amount", "date")),
            base.copy(expectedColumnNames = listOf("date", "amount")),
            base.copy(parameters = listOf(QueryParameterValue.Decimal("1.23", name = ""))),
            base.copy(parameters = listOf(QueryParameterValue.Date(java.time.LocalDate.parse("2026-10-06"), name = ""))),
            base.copy(parameters = listOf(QueryParameterValue.Timestamp(java.time.Instant.parse("2026-10-06T01:02:03.123456Z"), name = ""))),
            base.copy(parameters = listOf(QueryParameterValue.Null(NullQueryParameterType.DECIMAL, name = ""))),
            base.copy(parameters = listOf(QueryParameterValue.Null(NullQueryParameterType.DATE, name = ""))),
            base.copy(parameters = listOf(QueryParameterValue.Null(NullQueryParameterType.TIMESTAMP, name = ""))),
        )
        assertEquals(variants.size, variants.map { request -> RawSqlExecutionRequestCanonicalizer.canonicalDigest(request, OBJECT_KEY) }.distinct().size)
        assertFailsWith<IllegalArgumentException> { QueryParameterValue.Decimal("1.230", name = "") }
        assertFailsWith<IllegalArgumentException> { QueryParameterValue.Decimal("0.1234567890123456789", name = "") }
        assertFailsWith<IllegalArgumentException> { QueryParameterValue.Decimal("123456789012345678901234567890123456789", name = "") }
        assertFailsWith<IllegalArgumentException> { QueryParameterValue.Timestamp(java.time.Instant.parse("2026-10-06T01:02:03.123456789Z"), name = "") }
    }

    @Test
    fun `rejects artifact byte limits above the execution contract`() {
        assertFailsWith<IllegalArgumentException> {
            requestWithDigest(Sha256Digest("0".repeat(64))).copy(
                maximumArtifactBytes = RawSqlExecutionRequest.MAXIMUM_ARTIFACT_BYTES + 1uL,
            )
        }
    }

    @Test
    fun `logical request digest remains deterministic`() {
        val request = requestWithDigest(Sha256Digest("0".repeat(64)))

        assertEquals(
            "3c63c5a803327f011e553cb9268d36ac0700ab9bf99c99b0b9a5024e5d39acc7",
            RawSqlExecutionRequestCanonicalizer.canonicalDigest(request, OBJECT_KEY).value,
        )
    }

    @Test
    fun `canonical check rejects SQL parameter and row limit tampering`() {
        val digest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(
            requestWithDigest(Sha256Digest("0".repeat(64))),
            OBJECT_KEY,
        )
        val request = requestWithDigest(digest)

        assertTrue(RawSqlExecutionRequestCanonicalizer.isCanonical(request, OBJECT_KEY))
        assertFalse(
            RawSqlExecutionRequestCanonicalizer.isCanonical(
                request.copy(sql = "SELECT 1"),
                OBJECT_KEY,
            ),
        )
        assertFalse(
            RawSqlExecutionRequestCanonicalizer.isCanonical(
                request.copy(parameters = request.parameters.dropLast(1)),
                OBJECT_KEY,
            ),
        )
        assertFalse(
            RawSqlExecutionRequestCanonicalizer.isCanonical(
                request.copy(maximumResultRows = 43u),
                OBJECT_KEY,
            ),
        )
        assertFalse(
            RawSqlExecutionRequestCanonicalizer.isCanonical(
                request.copy(maximumArtifactBytes = 1_073_741_823u),
                OBJECT_KEY,
            ),
        )
    }

    @Test
    fun `named parameters use a self delimiting tail and preserve unnamed v1`() {
        val base = requestWithDigest(Sha256Digest("0".repeat(64)))
        val names = listOf("p1", "p2", "p3", "p4", "p5", "p6")
        val parameters = listOf(
            QueryParameterValue.Text("café", names[0]), QueryParameterValue.Integer64(-7, names[1]),
            QueryParameterValue.Float64(-0.0, names[2]), QueryParameterValue.BooleanValue(true, names[3]),
            QueryParameterValue.Bytes(BinaryValue.copyOf(byteArrayOf(0, 127, -1)), names[4]),
            QueryParameterValue.Null(NullQueryParameterType.BYTES, names[5]),
        )
        val variants = listOf(
            base.copy(parameters = parameters),
            base.copy(parameters = parameters, validateRawSqlBeforeRun = true),
            base.copy(parameters = parameters, expectedColumnNames = listOf("amount")),
            base.copy(parameters = parameters, validateRawSqlBeforeRun = true, expectedColumnNames = listOf("amount")),
            base.copy(parameters = listOf(
                QueryParameterValue.Text("café", "p6"), QueryParameterValue.Integer64(-7, "p5"),
                QueryParameterValue.Float64(-0.0, "p4"), QueryParameterValue.BooleanValue(true, "p3"),
                QueryParameterValue.Bytes(BinaryValue.copyOf(byteArrayOf(0, 127, -1)), "p2"),
                QueryParameterValue.Null(NullQueryParameterType.BYTES, "p1"),
            )),
            base.copy(parameters = listOf(QueryParameterValue.Text("café", "p1")) + base.parameters.drop(1)),
        )
        assertEquals(listOf(
            "2015e4a15dfb0d3468460b45ce4a5f16f8006ae9c800c1d318266afd72647fe6",
            "5acc766438fd2e4e4b2783154008302787d0814d4c105757b7991913bf480bd9",
            "85f970b41739c1d77408f00eed462804584cd1dd6afc2ad29f506dcae692a217",
            "f94231cd64e76e34e06f5c47b86edd29ecccc82e945ad49eb098b4985bab9a7b",
            "bd6668da53b191390fbd0b4e0c60c4254e8a3327d8034e2949a02ffde3fe9a15",
            "d76250ca19e0d81446c3e5599e7e48dc8b00f858a10018a91e4606ddb50a2baf",
        ), variants.map { RawSqlExecutionRequestCanonicalizer.canonicalDigest(it, OBJECT_KEY).value })
    }

    private fun requestWithDigest(digest: Sha256Digest) =
        RawSqlExecutionRequest(
            validateRawSqlBeforeRun = false,
            expectedColumnNames = emptyList(),
            schemaVersion = RawSqlExecutionRequest.SCHEMA_VERSION,
            canonicalRequestDigest = digest,
            sql = "SELECT * FROM \"orders\" WHERE total > ? AND active = ?",
            parameters =
                listOf(
                    QueryParameterValue.Text("café", name = ""),
                    QueryParameterValue.Integer64(-7, name = ""),
                    QueryParameterValue.Float64(-0.0, name = ""),
                    QueryParameterValue.BooleanValue(true, name = ""),
                    QueryParameterValue.Bytes(BinaryValue.copyOf(byteArrayOf(0, 127, -1)), name = ""),
                    QueryParameterValue.Null(NullQueryParameterType.BYTES, name = ""),
            ),
            maximumResultRows = 42u,
            maximumArtifactBytes = 1_073_741_824u,
            maximumRecordBatchRows = 1024,
            maximumRecordBatchBytes = 8192,
            maximumCellBytes = 4096,
            maximumMultipartPartBytes = 8192,
        )

    private companion object {
        const val OBJECT_KEY =
            "artifacts/workspaces/019e0000-0000-7000-8000-0000000000a1/explore/" +
                "runs/019e0000-0000-7000-8000-0000000000b1/result.arrow"
    }
}
