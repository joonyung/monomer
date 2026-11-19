package cc.monomer.identity.adapter.outbound

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class IdentitySchemaContractTest {
    private val moduleRoot = File(
        System.getProperty("control.service.root"),
        "modules/identity-access",
    )

    @Test
    fun `owner DDL is the identity runtime authority`() {
        val ddl = requireNotNull(
            javaClass.classLoader.getResource("db/identity-runtime/schema.sql"),
        ).readText()

        assertThat(ddl)
            .contains("CREATE SCHEMA IF NOT EXISTS identity_runtime")
            .contains("identity_runtime.principals")
            .contains("identity_runtime.sessions")
            .contains("identity_runtime.agent_oauth_grants")
            .contains("identity_runtime.agent_oauth_access_tokens")
            .doesNotContain("identity_runtime.workspace_group_roles")
            .doesNotContain("identity_runtime.resource_access_decisions")
            .doesNotContain("all_relations_allowed")
            .contains("public_pages BOOLEAN NOT NULL DEFAULT false")
            .contains("reporting_time_zone TEXT NOT NULL DEFAULT 'UTC'")
            .doesNotContain("workspace_relation_access_assignments")
            .doesNotContain("authoring_capability_assignments")
            .doesNotContain("resource_access_decisions")
            .doesNotContain("identity_runtime.bindings")
            .doesNotContain("resource_bindings")
            .doesNotContain("service_accounts")
            .doesNotContain("execution_authorization")
            .doesNotContain("data.explore")
            .doesNotContain("data.raw_sql")
            .doesNotContain("data.export")
            .contains("'VIEWER','EXPLORER','EDITOR','OWNER'")
            .contains("identity_runtime.organization_members")
            .contains("identity_runtime.workspace_members")
            .doesNotContain("auth.")
            .doesNotContain("governance.")
            .doesNotContainIgnoringCase("semantic_governance")
            .doesNotContainIgnoringCase("semantic_publication")
            .doesNotContain("raw_credential")
            .doesNotContain("bearer_token")

        assertThat(moduleRoot.resolve("src/main/resources/db/auth/schema.sql")).doesNotExist()
    }

    @Test
    fun `Postgres gate owns one pinned disposable database without external fallback`() {
        val buildFile = moduleRoot.resolve("build.gradle.kts").readText()
        val integrationSources = moduleRoot.resolve("src/integrationTest")
            .walkTopDown()
            .filter(File::isFile)
            .joinToString("\n", transform = File::readText)

        assertThat(buildFile)
            .contains("org.testcontainers:testcontainers-postgresql:2.0.5")
            .doesNotContain("target-environment-descriptor")
            .doesNotContain("MONOMER_TARGET_POSTGRES_PASSWORD")
        assertThat(integrationSources)
            .contains("postgres:17.10-alpine3.24@")
            .contains("sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193")
            .contains("@RegisterExtension")
            .contains("BeforeAllCallback")
            .contains("AfterAllCallback")
            .contains("postgres.start()")
            .contains("runCatching { postgres.stop() }")
            .contains("postgres.stop()")
            .contains("applyCanonicalSchemaTwice")
            .contains("repeat(2)")
            .contains("identity_runtime_gate_ephemeral")
            .doesNotContain("System.getProperty")
            .doesNotContain("System.getenv")
            .doesNotContain("MONOMER_TARGET_POSTGRES_PASSWORD")
            .doesNotContain("target-environment-descriptor")
            .doesNotContainIgnoringCase("TRUNCATE ")
        val rollbackFixtureConstraintCleanup =
            "ALTER TABLE identity_runtime.workspace_members DROP CONSTRAINT test_reject_initial_owner_role"
        assertThat(integrationSources.replace(rollbackFixtureConstraintCleanup, ""))
            .doesNotContainIgnoringCase("DROP ")
        assertThat(integrationSources)
            .contains("DELETE FROM identity_runtime.workspace_members")
            .doesNotContainIgnoringCase("DELETE FROM identity_runtime.organizations")
            .doesNotContainIgnoringCase("DELETE FROM identity_runtime.workspaces")
    }

    @Test
    fun `canonical admission reads credential and workspace facts through one snapshot`() {
        val adapterSource = moduleRoot.resolve(
            "src/main/kotlin/cc/monomer/identity/outbound/postgres/" +
                "PostgresCanonicalIdentityAdmissionSnapshot.kt",
        ).readText()

        assertThat(adapterSource)
            .contains("identity_runtime.sessions")
            .contains("identity_runtime.agent_oauth_access_tokens")
            .contains("transactionRunner.readOnly")
            .contains("PostgresWorkspaceAuthorizationReader")
    }

    @Test
    fun `semantic publication and governance binding are absent from the identity module`() {
        val runtimeDdl = requireNotNull(
            javaClass.classLoader.getResource("db/identity-runtime/schema.sql"),
        ).readText()
        val mainSources = moduleRoot.resolve("src/main").walkTopDown()
            .filter(File::isFile)
            .joinToString("\n", transform = File::readText)

        assertThat(runtimeDdl)
            .doesNotContainIgnoringCase("semantic_governance")
            .doesNotContainIgnoringCase("semantic_publication")
            .doesNotContainIgnoringCase("semantic_revision")
            .doesNotContainIgnoringCase("active_graph_digest")
            .doesNotContain("agent.semantic.governance")
        assertThat(mainSources)
            .doesNotContain("SemanticGovernance")
            .doesNotContain("SemanticPublication")
            .doesNotContain("semantic_governance")
            .doesNotContain("semantic_publication")
            .doesNotContain("semanticRevision")
            .doesNotContain("activeGraphDigest")
    }
}
