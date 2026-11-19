package cc.monomer.control.pages

import cc.monomer.control.ControlProductAuthorityProperties
import cc.monomer.control.ControlProductPostgresProperties
import cc.monomer.control.ControlProductTransactionProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PagesPostgresPropertiesTest {
    @Test
    fun `notification listener keeps the configured TLS parameters of the product JDBC URL`() {
        val jdbcUrl = "jdbc:postgresql://db.internal:5432/monomer?sslmode=verify-full&sslrootcert=/opt/monomer/rds-ca.pem"
        val properties = PagesApplicationComposition().pagesPostgresProperties(
            ControlProductAuthorityProperties(
                ControlProductPostgresProperties(
                    jdbcUrl, "monomer", "fixture-password", 2,
                    Duration.ofSeconds(1), Duration.ofMillis(250),
                ),
                ControlProductTransactionProperties(
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(100),
                ),
            ),
        )

        assertEquals(jdbcUrl, properties.jdbcUrl)
        assertFalse(properties.toString().contains("fixture-password"))
    }
}
