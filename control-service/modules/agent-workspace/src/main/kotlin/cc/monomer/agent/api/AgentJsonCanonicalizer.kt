package cc.monomer.agent.api

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import org.erdtman.jcs.JsonCanonicalizer

internal object AgentJsonCanonicalizer {
    fun canonicalBytes(document: String): ByteArray =
        try {
            JsonCanonicalizer(document).encodedUTF8
        } catch (exception: Exception) {
            throw IllegalArgumentException("The JSON document is not RFC 8785 canonicalizable.", exception)
        }

    fun canonicalNumber(number: String): String {
        val canonicalArray = canonicalBytes("[$number]").toString(StandardCharsets.UTF_8)
        check(canonicalArray.startsWith('[') && canonicalArray.endsWith(']'))
        return canonicalArray.substring(1, canonicalArray.lastIndex)
    }

    fun canonicalNumberPreservingValue(number: String): String {
        val canonicalNumber = canonicalNumber(number)
        require(BigDecimal(number).compareTo(BigDecimal(canonicalNumber)) == 0) {
            "The JSON number changes value during RFC 8785 canonicalization."
        }
        return canonicalNumber
    }

    fun hasOnlyUnicodeScalars(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        return false
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> return false
                else -> index += 1
            }
        }
        return true
    }
}
