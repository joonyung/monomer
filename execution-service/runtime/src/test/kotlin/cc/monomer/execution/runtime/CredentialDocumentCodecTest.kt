package cc.monomer.execution.runtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialDocumentCodecTest {
    @Test
    fun exactSixtyKibibyteMcr1DocumentIsDecodedAndItsSecretsAreZeroizedOnClose() {
        val document =
            credentialDocument(
                "first" to "a".repeat(30_702),
                "second" to "b".repeat(30_703),
            )
        assertEquals(MAXIMUM_DOCUMENT_BYTES, document.size)
        val workingCopy = document.copyOf()
        val credential =
            CredentialDocumentCodec.decode(
                document = workingCopy,
                maximumFields = MAXIMUM_FIELDS,
                maximumFieldCharacters = MAXIMUM_FIELD_CHARACTERS,
            )
        try {
            assertEquals(setOf("first", "second"), credential.fieldNames())
            assertFalse("a".repeat(30_702) in credential.toString())
            assertFalse("b".repeat(30_703) in credential.toString())
        } finally {
            credential.close()
            workingCopy.fill(0)
        }
        assertTrue(credential.isZeroized())
    }

    @Test
    fun invalidMagicFailsClosedWithoutLeakingDecodedSecrets() {
        val document = credentialDocument("token" to "super-secret-token")
        document[0] = 0
        assertFailsWith<IllegalArgumentException> {
            CredentialDocumentCodec.decode(
                document = document,
                maximumFields = MAXIMUM_FIELDS,
                maximumFieldCharacters = MAXIMUM_FIELD_CHARACTERS,
            )
        }
    }

    @Test
    fun decodedCredentialToStringDoesNotContainFieldValues() {
        val document = credentialDocument("password" to "sentinel-secret-xyz")
        val credential =
            CredentialDocumentCodec.decode(
                document = document,
                maximumFields = MAXIMUM_FIELDS,
                maximumFieldCharacters = MAXIMUM_FIELD_CHARACTERS,
            )
        try {
            val rendered = credential.toString()
            assertTrue("password" in rendered)
            assertFalse("sentinel-secret-xyz" in rendered)
        } finally {
            credential.close()
        }
    }

    private fun credentialDocument(vararg fields: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4d435231)
                data.writeInt(fields.size)
                fields.forEach { (name, value) ->
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    data.writeInt(nameBytes.size)
                    data.write(nameBytes)
                    val valueBytes = value.toByteArray(Charsets.UTF_8)
                    data.writeInt(valueBytes.size)
                    data.write(valueBytes)
                }
            }
            output.toByteArray()
        }

    private companion object {
        const val MAXIMUM_DOCUMENT_BYTES = 60 * 1_024
        const val MAXIMUM_FIELDS = 32
        const val MAXIMUM_FIELD_CHARACTERS = 32 * 1_024
    }
}
