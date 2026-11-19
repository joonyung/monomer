package cc.monomer.execution.driver.redshift

import kotlin.test.Test
import kotlin.test.assertFalse

class RedshiftCredentialRedactionTest {
    @Test
    fun `IAM and temporary credentials never print secrets`() {
        val iam = RedshiftIamCredential("AKIAEXAMPLEKEY", "aws-secret-access-key", "us-east-1", "analytics-cluster", "reader")
        val temporary = RedshiftTemporaryCredential("IAM:reader", "temporary-password")

        assertFalse("AKIAEXAMPLEKEY" in iam.toString())
        assertFalse("aws-secret-access-key" in iam.toString())
        assertFalse("temporary-password" in temporary.toString())
    }
}
