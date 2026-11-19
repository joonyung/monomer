package cc.monomer.execution.driver.spi

import java.text.Normalizer

enum class ResultLogicalType {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    FLOAT,
    TEXT,
    DATE,
    TIME,
    TIMESTAMP,
    BINARY,
    JSON,
}

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
        require(name.length <= MAXIMUM_COLUMN_NAME_CHARS)
        require(Normalizer.isNormalized(name, Normalizer.Form.NFC))
        require(name.none(Char::isISOControl))
    }

    companion object {
        const val MAXIMUM_COLUMN_NAME_CHARS = 512
        private const val DEDUPLICATION_SUFFIX_HEADROOM_CHARS = 8

        fun normalizedName(declaredName: String): String? =
            Normalizer.normalize(declaredName.filterNot(Char::isISOControl), Normalizer.Form.NFC)
                .trim()
                .take(MAXIMUM_COLUMN_NAME_CHARS - DEDUPLICATION_SUFFIX_HEADROOM_CHARS)
                .trim()
                .takeIf(String::isNotEmpty)

        fun acceptedNames(declaredNames: List<String?>): List<String> {
            val usedNames = mutableSetOf<String>()
            return declaredNames.mapIndexed { index, declaredName ->
                val baseName = declaredName?.let(::normalizedName) ?: "column_${index + 1}"
                var acceptedName = baseName
                var suffix = 2
                while (!usedNames.add(acceptedName)) {
                    acceptedName = "${baseName}_${suffix++}"
                }
                acceptedName
            }
        }
    }
}
