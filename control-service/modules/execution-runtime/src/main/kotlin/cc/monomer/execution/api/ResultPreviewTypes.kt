package cc.monomer.execution.api

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.time.Instant

enum class ResultLogicalType {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    FLOAT,
    TEXT,
    DATE,
    TIME,
    DATETIME,
    TIMESTAMP,
    BINARY,
    JSON,
}

/**
 * Preview values are a projection of the immutable Arrow result object.  They
 * are deliberately not a persisted execution stream or a second result
 * authority.
 */
data class ResultColumn(
    val ordinal: Int,
    val name: String,
    val logicalType: ResultLogicalType,
    val nullable: Boolean,
) {
    init {
        require(ordinal > 0)
        require(name.isNotBlank())
        require(name == name.trim())
        require(Normalizer.isNormalized(name, Normalizer.Form.NFC))
        require(name.none(Char::isISOControl))
    }

}

enum class ResultPreviewCellKind {
    NULL,
    BOOLEAN,
    TEXT,
    NUMBER,
    LOCAL_DATE,
    LOCAL_DATETIME,
    INSTANT,
}

sealed interface ResultPreviewCell {
    val kind: ResultPreviewCellKind

    data object Null : ResultPreviewCell {
        override val kind = ResultPreviewCellKind.NULL
    }

    data class BooleanValue(val value: Boolean) : ResultPreviewCell {
        override val kind = ResultPreviewCellKind.BOOLEAN
    }

    data class Text(val value: String) : ResultPreviewCell {
        override val kind = ResultPreviewCellKind.TEXT

        init {
            require(value.none { character -> character.code == 0 })
            require(canonicalUtf8(value).size <= MAXIMUM_CANONICAL_TEXT_UTF8_BYTES)
        }
    }

    data class Number(val canonicalValue: String) : ResultPreviewCell {
        override val kind = ResultPreviewCellKind.NUMBER

        init {
            require(canonicalValue.matches(CANONICAL_NUMBER))
        }
    }

    data class LocalDateValue(val canonicalValue: String) : ResultPreviewCell {
        override val kind = ResultPreviewCellKind.LOCAL_DATE

        init {
            require(canonicalValue.matches(CANONICAL_LOCAL_DATE))
            require(runCatching { java.time.LocalDate.parse(canonicalValue).toString() }.getOrNull() == canonicalValue)
        }
    }

    data class LocalDateTimeValue(val canonicalValue: String) : ResultPreviewCell {
        override val kind = ResultPreviewCellKind.LOCAL_DATETIME

        init {
            require(canonicalValue.matches(CANONICAL_LOCAL_DATETIME))
            require(runCatching { java.time.LocalDateTime.parse(canonicalValue) }.isSuccess)
        }
    }

    data class InstantValue(val canonicalValue: String) : ResultPreviewCell {
        override val kind = ResultPreviewCellKind.INSTANT

        init {
            require(canonicalValue.matches(CANONICAL_INSTANT))
            require(runCatching { Instant.parse(canonicalValue) }.isSuccess)
        }
    }

    companion object {
        const val MAXIMUM_CANONICAL_TEXT_UTF8_BYTES = 65_536

        private val CANONICAL_NUMBER = Regex("""^(NaN|-?Infinity|(0|-?[1-9][0-9]*|-0(?=\.))(\.[0-9]+)?)$""")
        private val CANONICAL_LOCAL_DATE = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2}$""")
        private val CANONICAL_LOCAL_DATETIME = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}$""")
        private val CANONICAL_INSTANT = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$""")
    }
}

enum class ResultPreviewStopReason {
    END_OF_RESULT,
    ROW_LIMIT,
    BYTE_LIMIT,
    FIRST_BATCH_BOUNDARY,
}

object ResultPreviewCanonicalizer {
    fun requireCellMatchesColumn(column: ResultColumn, cell: ResultPreviewCell) {
        if (cell.kind == ResultPreviewCellKind.NULL) {
            require(column.nullable)
            return
        }
        require(cell.kind == permittedCellKind(column.logicalType))
    }

    fun permittedCellKind(logicalType: ResultLogicalType): ResultPreviewCellKind =
        when (logicalType) {
            ResultLogicalType.BOOLEAN -> ResultPreviewCellKind.BOOLEAN
            ResultLogicalType.INTEGER, ResultLogicalType.DECIMAL, ResultLogicalType.FLOAT -> ResultPreviewCellKind.NUMBER
            ResultLogicalType.DATE -> ResultPreviewCellKind.LOCAL_DATE
            ResultLogicalType.DATETIME -> ResultPreviewCellKind.LOCAL_DATETIME
            ResultLogicalType.TIMESTAMP -> ResultPreviewCellKind.INSTANT
            ResultLogicalType.TEXT, ResultLogicalType.TIME, ResultLogicalType.BINARY, ResultLogicalType.JSON -> ResultPreviewCellKind.TEXT
        }

    fun canonicalText(cell: ResultPreviewCell): String? =
        when (cell) {
            ResultPreviewCell.Null -> null
            is ResultPreviewCell.BooleanValue -> if (cell.value) "true" else "false"
            is ResultPreviewCell.Text -> cell.value
            is ResultPreviewCell.Number -> cell.canonicalValue
            is ResultPreviewCell.LocalDateValue -> cell.canonicalValue
            is ResultPreviewCell.LocalDateTimeValue -> cell.canonicalValue
            is ResultPreviewCell.InstantValue -> cell.canonicalValue
        }
}

private fun canonicalUtf8(value: String): ByteArray {
    val encoded = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    return ByteArray(encoded.remaining()).also(encoded::get)
}
