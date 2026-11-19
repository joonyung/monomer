@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.port.WorkspaceMembersQueryResult
import cc.monomer.identity.domain.port.WorkspaceMemberCandidatesQueryResult
import cc.monomer.identity.domain.port.WorkspaceAuthorizationAssignmentsQueryResult
import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.grant.SetWorkspaceMemberRoleService
import cc.monomer.identity.application.grant.SetWorkspaceMemberRoleUseCase
import cc.monomer.identity.application.grant.InvalidWorkspaceRoleTargetException
import cc.monomer.identity.application.workspace.AddWorkspaceMemberService
import cc.monomer.identity.application.workspace.AddWorkspaceMemberUseCase
import cc.monomer.identity.application.workspace.OwnerWorkspaceMutationRunner
import cc.monomer.identity.application.workspace.RemoveWorkspaceMemberService
import cc.monomer.identity.application.workspace.RemoveWorkspaceMemberUseCase
import cc.monomer.identity.application.workspace.RenameWorkspaceService
import cc.monomer.identity.application.workspace.RenameWorkspaceUseCase
import cc.monomer.identity.application.workspace.SetWorkspacePublicPagesService
import cc.monomer.identity.application.workspace.SetWorkspacePublicPagesUseCase
import cc.monomer.identity.domain.port.WorkspaceAccessQueryResult
import cc.monomer.identity.domain.port.WorkspaceOwnerSummary
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceQuery
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceRepository
import cc.monomer.identity.outbound.postgres.PostgresAuthorizationAssignmentRepository
import cc.monomer.identity.outbound.postgres.PostgresAuditLogRepository
import cc.monomer.identity.outbound.postgres.PostgresOrganizationGovernanceOperations
import cc.monomer.identity.domain.port.WorkspaceOwnerCandidatesQueryResult
import cc.monomer.identity.domain.port.OrganizationMembersQueryResult
import java.time.Clock
import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignmentsService
import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignmentsUseCase
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.WorkspaceMembership
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.port.LastWorkspaceOwnerException
import cc.monomer.identity.domain.port.WorkspaceMemberInsertion
import cc.monomer.identity.domain.port.WorkspaceMemberRemoval
import cc.monomer.identity.outbound.postgres.AuthSchemaVerifier
import cc.monomer.identity.outbound.postgres.PostgresOrganizationRepository
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceAuthorizationOperations
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceMemberRepository
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class PostgresAuthorizationLifecycleIntegrationTest {
    private val dataSource get() = TargetIdentityPostgres.dataSource
    private val jdbc get() = NamedParameterJdbcTemplate(dataSource)
    private val now = Instant.parse("2026-08-21T00:00:00Z")
    private val organizationId = Uuid.generateV7().toJavaUuid()
    private val workspaceId = Uuid.generateV7().toJavaUuid()
    private val secondWorkspaceId = Uuid.generateV7().toJavaUuid()
    private val lastOwnerWorkspaceId = Uuid.generateV7().toJavaUuid()
    private val ownerUserId = Uuid.generateV7().toJavaUuid()
    private val targetUserId = Uuid.generateV7().toJavaUuid()
    private val relationId = Uuid.generateV7().toJavaUuid()
    private val secondRelationId = Uuid.generateV7().toJavaUuid()

    private lateinit var transactionRunner: SpringTransactionRunner
    private lateinit var writeTemplate: TransactionTemplate
    private lateinit var organizationRepository: PostgresOrganizationRepository
    private lateinit var workspaceMemberRepository: PostgresWorkspaceMemberRepository
    private lateinit var authorizationAssignmentsService: WorkspaceAuthorizationAssignmentsService

    @BeforeEach
    fun setUp() {
        TargetIdentityPostgres.dataSource.connection.use { connection ->
            TargetIdentityPostgres.applyCanonicalSchemaTwice(connection, javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(connection)
        }
        val transactionManager = DataSourceTransactionManager(dataSource)
        writeTemplate = TransactionTemplate(transactionManager)
        val readOnlyTemplate = TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
        transactionRunner = SpringTransactionRunner(writeTemplate, readOnlyTemplate, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        organizationRepository = PostgresOrganizationRepository(jdbc)
        workspaceMemberRepository = PostgresWorkspaceMemberRepository(jdbc)
        authorizationAssignmentsService = WorkspaceAuthorizationAssignmentsService(
            PostgresWorkspaceAuthorizationOperations(jdbc, transactionRunner),
        )
        insertFixture()
    }

    @Test
    fun `workspace membership removal cleans direct user authorization and re-add does not restore it`() {
        val workspaceRevisionBefore = authorizationRevision(workspaceId)
        val secondWorkspaceRevisionBefore = authorizationRevision(secondWorkspaceId)

        val removal = write {
            workspaceMemberRepository.delete(workspace(workspaceId), user(targetUserId))
        }

        assertThat(removal).isEqualTo(WorkspaceMemberRemoval.Removed)
        assertThat(workspaceMemberCount(workspaceId, targetUserId)).isZero()
        assertNoDirectOwner(workspaceId, targetUserId)
        assertDirectOwner(
            workspaceId = secondWorkspaceId,
            userId = targetUserId,
            ownerAssignments = 0,


        )
        assertThat(authorizationRevision(workspaceId)).isGreaterThan(workspaceRevisionBefore)
        assertThat(authorizationRevision(secondWorkspaceId)).isEqualTo(secondWorkspaceRevisionBefore)

        val inventory = runBlocking {
            authorizationAssignmentsService.read(
                WorkspaceAuthorizationAssignmentsUseCase.Command(
                    actorUserId = user(ownerUserId),
                    workspaceId = workspace(workspaceId),
                ),
            )
        }
        assertThat(inventory.members.map { it.user.userId.value.toJavaUuid() }).doesNotContain(targetUserId)

        val reAdd = write {
            workspaceMemberRepository.insert(
                WorkspaceMembership(
                    workspaceId = workspace(workspaceId),
                    userId = user(targetUserId),
                    invitedBy = user(ownerUserId),
                    invitedAt = now,
                    role = cc.monomer.identity.api.WorkspaceRole.VIEWER,
                ),
            )
        }
        assertThat(reAdd).isEqualTo(WorkspaceMemberInsertion.Inserted)
        assertThat(workspaceMemberCount(workspaceId, targetUserId)).isEqualTo(1)
        assertNoDirectOwner(workspaceId, targetUserId)
    }

    @Test
    fun `organization removal cleans direct user authorization in every workspace and rejoin does not restore it`() {
        val workspaceRevisionBefore = authorizationRevision(workspaceId)
        val secondWorkspaceRevisionBefore = authorizationRevision(secondWorkspaceId)

        val transition = write {
            organizationRepository.deleteMember(organization(organizationId), user(targetUserId))
        }

        assertThat(transition).isEqualTo(cc.monomer.identity.domain.port.OrganizationMemberRemoval.Removed)
        assertThat(organizationMembershipCount()).isZero()
        assertThat(workspaceMemberCount(workspaceId, targetUserId)).isZero()
        assertThat(workspaceMemberCount(secondWorkspaceId, targetUserId)).isZero()
        assertNoDirectOwner(workspaceId, targetUserId)
        assertNoDirectOwner(secondWorkspaceId, targetUserId)
        assertThat(authorizationRevision(workspaceId)).isGreaterThan(workspaceRevisionBefore)
        assertThat(authorizationRevision(secondWorkspaceId)).isGreaterThan(secondWorkspaceRevisionBefore)

        write {
            organizationRepository.insertMember(OrganizationMembership(organization(organizationId), user(targetUserId), OrganizationRole.MEMBER, now))
        }

        assertThat(workspaceMemberCount(workspaceId, targetUserId)).isZero()
        assertThat(workspaceMemberCount(secondWorkspaceId, targetUserId)).isZero()
        assertThat(organizationMembershipCount()).isEqualTo(1)
        assertNoDirectOwner(workspaceId, targetUserId)
        assertNoDirectOwner(secondWorkspaceId, targetUserId)
    }

    @Test
    fun `organization role and membership changes invalidate only that organizations workspace revisions`() {
        val otherOrganizationId = Uuid.generateV7().toJavaUuid()
        val otherWorkspaceId = Uuid.generateV7().toJavaUuid()
        write {
            val parameters = mapOf("organizationId" to otherOrganizationId, "workspaceId" to otherWorkspaceId,
                "ownerUserId" to ownerUserId, "targetUserId" to targetUserId, "createdAt" to Timestamp.from(now))
            jdbc.update("INSERT INTO identity_runtime.organizations(id, display_name, created_at) VALUES (:organizationId, 'Revision control organization', :createdAt)", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organizationId, :ownerUserId, 'ADMIN', :createdAt), (:organizationId, :targetUserId, 'MEMBER', :createdAt)", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, created_at) VALUES (:workspaceId, :organizationId, 'Revision control workspace', :createdAt)", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, role, invited_at) VALUES (:workspaceId, :ownerUserId, 'OWNER', :createdAt), (:workspaceId, :targetUserId, 'EXPLORER', :createdAt)", parameters)
        }
        val otherRevision = authorizationRevision(otherWorkspaceId)
        fun verifyScopedRevisionChange(change: () -> Unit) {
            val firstRevision = authorizationRevision(workspaceId)
            val secondRevision = authorizationRevision(secondWorkspaceId)
            write(change)
            assertThat(authorizationRevision(workspaceId)).isGreaterThan(firstRevision)
            assertThat(authorizationRevision(secondWorkspaceId)).isGreaterThan(secondRevision)
            assertThat(authorizationRevision(otherWorkspaceId)).isEqualTo(otherRevision)
            assertThat(workspaceMemberCount(otherWorkspaceId, targetUserId)).isEqualTo(1)
        }
        for (role in listOf(cc.monomer.identity.domain.model.OrganizationRole.ADMIN, cc.monomer.identity.domain.model.OrganizationRole.MEMBER)) {
            verifyScopedRevisionChange {
                organizationRepository.updateMemberRole(organization(organizationId), user(targetUserId), role)
            }
        }
        verifyScopedRevisionChange { organizationRepository.deleteMember(organization(organizationId), user(targetUserId)) }
        verifyScopedRevisionChange {
            organizationRepository.insertMember(OrganizationMembership(organization(organizationId), user(targetUserId), OrganizationRole.MEMBER, now))
        }
        assertThat(jdbc.queryForObject(
            "SELECT role FROM identity_runtime.organization_members WHERE organization_id = :organizationId AND user_id = :userId",
            mapOf("organizationId" to otherOrganizationId, "userId" to targetUserId), String::class.java,
        )).isEqualTo("MEMBER")
    }

    @Test
    fun `last owner rejection is atomic for organization removal and workspace membership removal`() {
        write { insertLastOwnerWorkspace() }

        val organizationRemovalRevisionBefore = authorizationRevision(workspaceId)
        assertThatThrownBy {
            write {
                organizationRepository.deleteMember(organization(organizationId), user(targetUserId))
            }
        }.isInstanceOf(LastWorkspaceOwnerException::class.java)

        assertThat(organizationMembershipCount()).isEqualTo(1)
        assertDirectOwner(
            workspaceId = workspaceId,
            userId = targetUserId,
            ownerAssignments = 1,


        )
        assertDirectOwner(
            workspaceId = lastOwnerWorkspaceId,
            userId = targetUserId,
            ownerAssignments = 1,


        )
        assertThat(authorizationRevision(workspaceId)).isEqualTo(organizationRemovalRevisionBefore)

        val membershipRemovalRevisionBefore = authorizationRevision(lastOwnerWorkspaceId)
        assertThatThrownBy {
            write {
                workspaceMemberRepository.delete(workspace(lastOwnerWorkspaceId), user(targetUserId))
            }
        }.isInstanceOf(LastWorkspaceOwnerException::class.java)

        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, targetUserId)).isEqualTo(1)
        assertThat(authorizationRevision(lastOwnerWorkspaceId)).isEqualTo(membershipRemovalRevisionBefore)
        assertDirectOwner(
            workspaceId = lastOwnerWorkspaceId,
            userId = targetUserId,
            ownerAssignments = 1,


        )
    }

    @Test
    fun `admin outside workspace assigns successor membership and Owner with an explicit direct role`(): Unit = runBlocking {
        write { insertLastOwnerWorkspace() }
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, ownerUserId)).isZero()
        addOwner(ownerUserId, lastOwnerWorkspaceId, ownerUserId)
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, ownerUserId)).isEqualTo(1)
        assertDirectOwner(lastOwnerWorkspaceId, ownerUserId, 1)
        removeOwner(ownerUserId, lastOwnerWorkspaceId, targetUserId)
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, targetUserId)).isEqualTo(1)
        assertDirectOwner(lastOwnerWorkspaceId, targetUserId, 0)
        assertThatThrownBy { runBlocking { removeOwner(ownerUserId, lastOwnerWorkspaceId, ownerUserId) } }
            .isInstanceOf(LastWorkspaceOwnerException::class.java)
        assertDirectOwner(lastOwnerWorkspaceId, ownerUserId, 1)
    }

    @Test
    fun `admin can set every existing member role and admit an organization nonmember with any role`(): Unit = runBlocking {
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        val service = SetWorkspaceMemberRoleService(OwnerWorkspaceMutationRunner(transactionRunner, repository), repository,
            PostgresAuditLogRepository(jdbc), AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)),
            workspaceMemberRepository, Clock.fixed(now, ZoneOffset.UTC))
        for (role in WorkspaceRole.entries) {
            service.set(SetWorkspaceMemberRoleUseCase.Command(user(ownerUserId), workspace(workspaceId), user(targetUserId), role, null))
            assertThat(jdbc.queryForObject("SELECT role FROM identity_runtime.workspace_members WHERE workspace_id=:workspace AND user_id=:user",
                mapOf("workspace" to workspaceId, "user" to targetUserId), String::class.java)).isEqualTo(role.name)
            val revision = authorizationRevision(workspaceId)
            assertThat(service.set(SetWorkspaceMemberRoleUseCase.Command(user(ownerUserId), workspace(workspaceId), user(targetUserId), role, null))).isFalse()
            assertThat(authorizationRevision(workspaceId)).isEqualTo(revision)
        }
        write { insertLastOwnerWorkspace() }
        for (role in WorkspaceRole.entries) {
            val revision = authorizationRevision(lastOwnerWorkspaceId)
            assertThat(service.set(SetWorkspaceMemberRoleUseCase.Command(user(ownerUserId), workspace(lastOwnerWorkspaceId), user(ownerUserId), role, null))).isTrue()
            assertThat(jdbc.queryForObject("SELECT role FROM identity_runtime.workspace_members WHERE workspace_id=:workspace AND user_id=:user",
                mapOf("workspace" to lastOwnerWorkspaceId, "user" to ownerUserId), String::class.java)).isEqualTo(role.name)
            assertThat(authorizationRevision(lastOwnerWorkspaceId)).isGreaterThan(revision)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id=:workspace AND resource_id=:user AND action='workspace.member.add' AND details->>'role'=:role",
                mapOf("workspace" to lastOwnerWorkspaceId, "user" to ownerUserId, "role" to role.name), Int::class.java,
            )).isEqualTo(1)
            assertThat(write { workspaceMemberRepository.delete(workspace(lastOwnerWorkspaceId), user(ownerUserId)) }).isEqualTo(WorkspaceMemberRemoval.Removed)
        }
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id=:workspace AND resource_id=:user AND action='workspace.member.role.set'",
            mapOf("workspace" to lastOwnerWorkspaceId, "user" to ownerUserId), Int::class.java,
        )).isZero()
    }

    @Test
    fun `admin cannot admit a user without organization membership and workspace owner cannot admit a nonmember`(): Unit = runBlocking {
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        val service = SetWorkspaceMemberRoleService(OwnerWorkspaceMutationRunner(transactionRunner, repository), repository,
            PostgresAuditLogRepository(jdbc), AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)),
            workspaceMemberRepository, Clock.fixed(now, ZoneOffset.UTC))
        val outsiderUserId = Uuid.generateV7().toJavaUuid()
        write {
            insertLastOwnerWorkspace()
            jdbc.update("INSERT INTO identity_runtime.principals(id, kind, display_name) VALUES (:id, 'user', 'Outsider')", mapOf("id" to outsiderUserId))
            jdbc.update("INSERT INTO identity_runtime.users(id, primary_email) VALUES (:id, :email)", mapOf("id" to outsiderUserId, "email" to "$outsiderUserId@example.com"))
        }
        for (role in WorkspaceRole.entries) {
            assertThatThrownBy { runBlocking {
                service.set(SetWorkspaceMemberRoleUseCase.Command(user(ownerUserId), workspace(lastOwnerWorkspaceId), user(outsiderUserId), role, null))
            } }.isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
            assertThatThrownBy { runBlocking {
                service.set(SetWorkspaceMemberRoleUseCase.Command(user(targetUserId), workspace(lastOwnerWorkspaceId), user(ownerUserId), role, null))
            } }.isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
        }
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, outsiderUserId)).isZero()
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, ownerUserId)).isZero()
    }

    @Test
    fun `owner member add rejects users outside the workspace organization as invalid targets without writes`(): Unit = runBlocking {
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = AddWorkspaceMemberService(OwnerWorkspaceMutationRunner(transactionRunner, repository), repository,
            workspaceMemberRepository, PostgresAuditLogRepository(jdbc), AuditEventFactory(clock), clock)
        val outsiderUserId = Uuid.generateV7().toJavaUuid()
        val unknownUserId = Uuid.generateV7().toJavaUuid()
        write {
            insertLastOwnerWorkspace()
            jdbc.update("INSERT INTO identity_runtime.principals(id, kind, display_name) VALUES (:id, 'user', 'Outsider')", mapOf("id" to outsiderUserId))
            jdbc.update("INSERT INTO identity_runtime.users(id, primary_email) VALUES (:id, :email)", mapOf("id" to outsiderUserId, "email" to "$outsiderUserId@example.com"))
        }
        for (ineligible in listOf(outsiderUserId, unknownUserId)) {
            assertThatThrownBy { runBlocking {
                service.add(AddWorkspaceMemberUseCase.Command(user(targetUserId), workspace(lastOwnerWorkspaceId), user(ineligible), null, WorkspaceRole.VIEWER))
            } }.isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
            assertThat(workspaceMemberCount(lastOwnerWorkspaceId, ineligible)).isZero()
        }
        assertThat(service.add(AddWorkspaceMemberUseCase.Command(user(targetUserId), workspace(lastOwnerWorkspaceId), user(ownerUserId), null, WorkspaceRole.VIEWER)).role)
            .isEqualTo(WorkspaceRole.VIEWER)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id = :workspace AND action = 'workspace.member.add'",
            mapOf("workspace" to lastOwnerWorkspaceId), Int::class.java,
        )).isEqualTo(1)
    }

    @Test
    fun `admin can govern ownerless workspace and cross organization targets and actors are rejected`(): Unit = runBlocking {
        write {
            // Only the disposable fixture bypasses the creation anchor to model an already ownerless Workspace.
            jdbc.jdbcTemplate.execute("ALTER TABLE identity_runtime.workspaces DISABLE TRIGGER trg_workspace_authorization_anchor_commit")
            jdbc.update("INSERT INTO identity_runtime.workspaces(id, organization_id, display_name) VALUES (:id, :organization, 'Ownerless')",
                mapOf("id" to lastOwnerWorkspaceId, "organization" to organizationId))
            jdbc.jdbcTemplate.execute("ALTER TABLE identity_runtime.workspaces ENABLE TRIGGER trg_workspace_authorization_anchor_commit")
        }
        assertThatThrownBy { runBlocking { addOwner(targetUserId, lastOwnerWorkspaceId, targetUserId) } }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { runBlocking { addOwner(ownerUserId, lastOwnerWorkspaceId, Uuid.generateV7().toJavaUuid()) } }
            .isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
        val foreignOrganization = Uuid.generateV7().toJavaUuid()
        val foreignUser = Uuid.generateV7().toJavaUuid()
        write {
            jdbc.update("INSERT INTO identity_runtime.organizations(id, display_name) VALUES (:id, 'Foreign')",
                mapOf("id" to foreignOrganization))
            jdbc.update("INSERT INTO identity_runtime.principals(id, kind, display_name) VALUES (:id, 'user', 'Foreign')", mapOf("id" to foreignUser))
            jdbc.update("INSERT INTO identity_runtime.users(id, primary_email) VALUES (:id, :email)", mapOf("id" to foreignUser, "email" to "$foreignUser@example.com"))
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id, user_id, role) VALUES (:organization, :user, 'ADMIN')", mapOf("organization" to foreignOrganization, "user" to foreignUser))
        }
        for (role in WorkspaceRole.entries) {
            assertThatThrownBy { runBlocking { setRole(ownerUserId, lastOwnerWorkspaceId, foreignUser, role) } }
                .isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
        }
        assertThatThrownBy { runBlocking { addOwner(foreignUser, lastOwnerWorkspaceId, targetUserId) } }
            .isInstanceOf(AccessDeniedException::class.java)
        addOwner(ownerUserId, lastOwnerWorkspaceId, targetUserId)
        assertDirectOwner(lastOwnerWorkspaceId, targetUserId, 1)
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, foreignUser)).isZero()
        assertThatThrownBy { runBlocking { addOwner(targetUserId, lastOwnerWorkspaceId, ownerUserId) } }
            .isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
    }

    @Test
    fun `organization admin removes members and renames workspaces of its own organization only`(): Unit = runBlocking {
        val explorerUserId = Uuid.generateV7().toJavaUuid()
        val foreignOrganization = Uuid.generateV7().toJavaUuid()
        val foreignAdmin = Uuid.generateV7().toJavaUuid()
        write {
            insertLastOwnerWorkspace()
            val parameters = mapOf("explorer" to explorerUserId, "foreignAdmin" to foreignAdmin, "foreignOrganization" to foreignOrganization,
                "organization" to organizationId, "workspace" to lastOwnerWorkspaceId)
            jdbc.update("INSERT INTO identity_runtime.principals(id, kind, display_name) VALUES (:explorer, 'user', 'Explorer'), (:foreignAdmin, 'user', 'Foreign admin')", parameters)
            jdbc.update("INSERT INTO identity_runtime.users(id, primary_email) VALUES (:explorer, :explorerEmail), (:foreignAdmin, :foreignAdminEmail)",
                parameters + mapOf("explorerEmail" to "$explorerUserId@example.com", "foreignAdminEmail" to "$foreignAdmin@example.com"))
            jdbc.update("INSERT INTO identity_runtime.organizations(id, display_name) VALUES (:foreignOrganization, 'Foreign')", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id, user_id, role) VALUES (:organization, :explorer, 'MEMBER'), (:foreignOrganization, :foreignAdmin, 'ADMIN')", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, role) VALUES (:workspace, :explorer, 'EXPLORER')", parameters)
        }
        val assignments = PostgresAuthorizationAssignmentRepository(jdbc)
        val runner = OwnerWorkspaceMutationRunner(transactionRunner, assignments)
        val audit = PostgresAuditLogRepository(jdbc)
        val auditEvents = AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC))
        val workspaces = PostgresWorkspaceRepository(jdbc)
        val workspaceQuery = PostgresWorkspaceQuery(jdbc, transactionRunner)
        val remove = RemoveWorkspaceMemberService(runner, workspaceMemberRepository, audit, auditEvents)
        val rename = RenameWorkspaceService(runner, workspaceQuery, workspaces, audit, auditEvents)
        val publicPages = SetWorkspacePublicPagesService(runner, workspaceQuery, workspaces, audit, auditEvents)
        val target = workspace(lastOwnerWorkspaceId)

        for (actor in listOf(foreignAdmin, explorerUserId)) {
            assertThatThrownBy { runBlocking { remove.remove(RemoveWorkspaceMemberUseCase.Command(user(actor), target, user(targetUserId), null)) } }
                .isInstanceOf(AccessDeniedException::class.java)
            assertThatThrownBy { runBlocking { rename.rename(RenameWorkspaceUseCase.Command(user(actor), target, "Rejected")) } }
                .isInstanceOf(AccessDeniedException::class.java)
        }
        assertThatThrownBy { runBlocking { publicPages.set(SetWorkspacePublicPagesUseCase.Command(user(ownerUserId), target, true)) } }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, ownerUserId)).isZero()

        assertThat(rename.rename(RenameWorkspaceUseCase.Command(user(ownerUserId), target, "Admin renamed")).displayName).isEqualTo("Admin renamed")
        assertThat(remove.remove(RemoveWorkspaceMemberUseCase.Command(user(ownerUserId), target, user(explorerUserId), null))).isTrue()
        assertThat(workspaceMemberCount(lastOwnerWorkspaceId, explorerUserId)).isZero()
        assertThatThrownBy { runBlocking { remove.remove(RemoveWorkspaceMemberUseCase.Command(user(ownerUserId), target, user(targetUserId), null)) } }
            .isInstanceOf(LastWorkspaceOwnerException::class.java)
        assertDirectOwner(lastOwnerWorkspaceId, targetUserId, 1)
        assertThat(rename.rename(RenameWorkspaceUseCase.Command(user(targetUserId), target, "Owner renamed")).displayName).isEqualTo("Owner renamed")
        assertThat(jdbc.queryForObject("SELECT display_name FROM identity_runtime.workspaces WHERE id = :id",
            mapOf("id" to lastOwnerWorkspaceId), String::class.java)).isEqualTo("Owner renamed")
        assertThat(jdbc.queryForList(
            "SELECT action FROM identity_runtime.audit_log WHERE workspace_id = :workspace AND actor_id = :actor ORDER BY action",
            mapOf("workspace" to lastOwnerWorkspaceId, "actor" to ownerUserId), String::class.java,
        )).containsExactly("workspace.member.remove", "workspace.rename")
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id = :workspace AND actor_id IN (:actors)",
            mapOf("workspace" to lastOwnerWorkspaceId, "actors" to listOf(foreignAdmin, explorerUserId)), Int::class.java,
        )).isZero()
    }

    @Test
    fun `workspace access lists owners by display name for any granted member and denies non members`(): Unit = runBlocking {
        val queries = PostgresWorkspaceAuthorizationOperations(jdbc, transactionRunner)
        val explorerAccess = queries.readWorkspaceAccess(user(targetUserId), workspace(secondWorkspaceId))
        explorerAccess as WorkspaceAccessQueryResult.Authorized
        assertThat(explorerAccess.projection.role).isEqualTo(WorkspaceRole.EXPLORER)
        assertThat(explorerAccess.projection.owners).containsExactly(WorkspaceOwnerSummary(user(ownerUserId), "Lifecycle owner"))
        write {
            jdbc.update("UPDATE identity_runtime.principals SET display_name = 'Aardvark target' WHERE id = :id", mapOf("id" to targetUserId))
        }
        val ownerAccess = queries.readWorkspaceAccess(user(ownerUserId), workspace(workspaceId)) as WorkspaceAccessQueryResult.Authorized
        assertThat(ownerAccess.projection.owners).containsExactly(
            WorkspaceOwnerSummary(user(targetUserId), "Aardvark target"),
            WorkspaceOwnerSummary(user(ownerUserId), "Lifecycle owner"),
        )
        write { insertLastOwnerWorkspace() }
        assertThat(queries.readWorkspaceAccess(user(ownerUserId), workspace(lastOwnerWorkspaceId)))
            .isInstanceOf(WorkspaceAccessQueryResult.Denied::class.java)
    }

    @Test
    fun `admin owner inventory includes organization nonmembers and scoped participation impact`(): Unit = runBlocking {
        write { insertLastOwnerWorkspace() }
        val queries = PostgresWorkspaceAuthorizationOperations(jdbc, transactionRunner)
        assertManagementReadsDenied(queries, ownerUserId, lastOwnerWorkspaceId)
        val members = queries.readWorkspaceMembers(user(ownerUserId), workspace(lastOwnerWorkspaceId))
        assertThat((members as WorkspaceMembersQueryResult.Authorized).authorization.role).isNull()
        assertThat(members.members.filter { it.role == cc.monomer.identity.api.WorkspaceRole.OWNER }.map { it.user.userId }).containsExactly(user(targetUserId))
        val candidates = queries.readWorkspaceOwnerCandidates(user(ownerUserId), organization(organizationId), workspace(lastOwnerWorkspaceId))
        assertThat((candidates as WorkspaceOwnerCandidatesQueryResult.Authorized).authorization.role).isNull()
        assertThat(candidates.users.map { it.userId }).containsExactly(user(ownerUserId))
        val roster = PostgresOrganizationGovernanceOperations(jdbc, transactionRunner).readOrganizationMembers(user(ownerUserId), organization(organizationId))
        val target = (roster as OrganizationMembersQueryResult.Authorized).members.single { it.user.userId == user(targetUserId) }
        val impact = target.workspaces.single { it.id == workspace(lastOwnerWorkspaceId) }
        assertThat(impact.role).isEqualTo(WorkspaceRole.OWNER)
        assertThat(impact.ownerCount).isEqualTo(1)
        assertThat(impact.memberCount).isEqualTo(1)
    }

    @Test
    fun `organization roster returns each member's direct workspace role and joinedAt`(): Unit = runBlocking {
        write { jdbc.update("UPDATE identity_runtime.organization_members SET joined_at = :joinedAt WHERE organization_id = :organization AND user_id = :user",
            mapOf("joinedAt" to Timestamp.from(now.plusSeconds(60)), "organization" to organizationId, "user" to targetUserId)) }
        val roster = PostgresOrganizationGovernanceOperations(jdbc, transactionRunner).readOrganizationMembers(user(ownerUserId), organization(organizationId))
        val members = (roster as OrganizationMembersQueryResult.Authorized).members
        val owner = members.single { it.user.userId == user(ownerUserId) }
        val target = members.single { it.user.userId == user(targetUserId) }
        assertThat(owner.joinedAt).isEqualTo(now)
        assertThat(target.joinedAt).isEqualTo(now.plusSeconds(60))
        assertThat(owner.workspaces.associate { it.id to it.role }).isEqualTo(mapOf(
            workspace(workspaceId) to WorkspaceRole.OWNER, workspace(secondWorkspaceId) to WorkspaceRole.OWNER,
        ))
        assertThat(target.workspaces.associate { it.id to it.role }).isEqualTo(mapOf(
            workspace(workspaceId) to WorkspaceRole.OWNER, workspace(secondWorkspaceId) to WorkspaceRole.EXPLORER,
        ))
        val candidates = PostgresWorkspaceAuthorizationOperations(jdbc, transactionRunner).readWorkspaceMemberCandidates(user(ownerUserId), workspace(workspaceId))
        assertThat((candidates as WorkspaceMemberCandidatesQueryResult.Authorized).users.single { it.user.userId == user(targetUserId) }.joinedAt)
            .isEqualTo(now.plusSeconds(60))
    }

    @Test
    fun `workspace lock makes owner add reject a target after organization removal commits`() {
        write { workspaceMemberRepository.delete(workspace(workspaceId), user(targetUserId)) }
        val removalPrepared = java.util.concurrent.CompletableFuture<Int>()
        val additionStarted = java.util.concurrent.CompletableFuture<Int>()
        val allowRemovalCommit = java.util.concurrent.CountDownLatch(1)
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        val observedRepository = object : cc.monomer.identity.domain.port.AuthorizationAssignmentRepository by repository {
            override fun actorIsOwnerAfterLock(actorUserId: UserId, workspaceId: WorkspaceId): Boolean {
                additionStarted.complete(jdbc.queryForObject("SELECT pg_backend_pid()", emptyMap<String, Any>(), Int::class.java)!!)
                return repository.actorIsOwnerAfterLock(actorUserId, workspaceId)
            }
        }
        val service = AddWorkspaceMemberService(
            OwnerWorkspaceMutationRunner(transactionRunner, observedRepository), observedRepository,
            workspaceMemberRepository, PostgresAuditLogRepository(jdbc),
            AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)), Clock.fixed(now, ZoneOffset.UTC),
        )
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val removal = executor.submit<cc.monomer.identity.domain.port.OrganizationMemberRemoval> {
                write {
                    val result = organizationRepository.deleteMember(organization(organizationId), user(targetUserId))
                    removalPrepared.complete(jdbc.queryForObject("SELECT pg_backend_pid()", emptyMap<String, Any>(), Int::class.java)!!)
                    check(allowRemovalCommit.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    result
                }
            }
            try {
                val removalPid = removalPrepared.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val addition = executor.submit<Throwable?> {
                    runCatching {
                        runBlocking {
                            service.add(AddWorkspaceMemberUseCase.Command(user(ownerUserId), workspace(workspaceId), user(targetUserId), null, WorkspaceRole.EXPLORER))
                        }
                    }.exceptionOrNull()
                }
                assertBlockedBy(additionStarted.get(5, java.util.concurrent.TimeUnit.SECONDS), removalPid, addition)
                allowRemovalCommit.countDown()
                assertThat(removal.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(cc.monomer.identity.domain.port.OrganizationMemberRemoval.Removed)
                assertThat(addition.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
            } finally {
                allowRemovalCommit.countDown()
            }
        }
        assertThat(organizationMembershipCount()).isZero()
        assertThat(workspaceMemberCount(workspaceId, targetUserId)).isZero()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id = :workspace AND action = 'workspace.member.add' AND resource_id = :target",
            mapOf("workspace" to workspaceId, "target" to targetUserId), Int::class.java,
        )).isZero()
    }

    @Test
    fun `workspace lock prevents organization removal between owner eligibility check and insert`() {
        write { workspaceMemberRepository.delete(workspace(workspaceId), user(targetUserId)) }
        val additionEligible = java.util.concurrent.CompletableFuture<Int>()
        val removalStarted = java.util.concurrent.CompletableFuture<Int>()
        val allowAddition = java.util.concurrent.CountDownLatch(1)
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        val observedRepository = object : cc.monomer.identity.domain.port.AuthorizationAssignmentRepository by repository {
            override fun isEligibleMemberRoleTarget(workspaceId: WorkspaceId, userId: UserId, requireDirectMembership: Boolean): Boolean {
                val eligible = repository.isEligibleMemberRoleTarget(workspaceId, userId, requireDirectMembership)
                check(eligible)
                additionEligible.complete(jdbc.queryForObject("SELECT pg_backend_pid()", emptyMap<String, Any>(), Int::class.java)!!)
                check(allowAddition.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return eligible
            }
        }
        val service = AddWorkspaceMemberService(
            OwnerWorkspaceMutationRunner(transactionRunner, observedRepository), observedRepository,
            workspaceMemberRepository, PostgresAuditLogRepository(jdbc),
            AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)), Clock.fixed(now, ZoneOffset.UTC),
        )
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val addition = executor.submit<WorkspaceMembership> {
                runBlocking {
                    service.add(AddWorkspaceMemberUseCase.Command(user(ownerUserId), workspace(workspaceId), user(targetUserId), null, WorkspaceRole.EXPLORER))
                }
            }
            try {
                val additionPid = additionEligible.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val removal = executor.submit<cc.monomer.identity.domain.port.OrganizationMemberRemoval> {
                    write {
                        removalStarted.complete(jdbc.queryForObject("SELECT pg_backend_pid()", emptyMap<String, Any>(), Int::class.java)!!)
                        organizationRepository.deleteMember(organization(organizationId), user(targetUserId))
                    }
                }
                assertBlockedBy(removalStarted.get(5, java.util.concurrent.TimeUnit.SECONDS), additionPid, removal)
                allowAddition.countDown()
                assertThat(addition.get(5, java.util.concurrent.TimeUnit.SECONDS).userId).isEqualTo(user(targetUserId))
                assertThat(removal.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(cc.monomer.identity.domain.port.OrganizationMemberRemoval.Removed)
            } finally {
                allowAddition.countDown()
            }
        }
        assertThat(organizationMembershipCount()).isZero()
        assertThat(workspaceMemberCount(workspaceId, targetUserId)).isZero()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id = :workspace AND action = 'workspace.member.add' AND resource_id = :target",
            mapOf("workspace" to workspaceId, "target" to targetUserId), Int::class.java,
        )).isEqualTo(1)
    }

    private fun assertBlockedBy(waitingPid: Int, blockingPid: Int, waitingTask: java.util.concurrent.Future<*>) {
        val waitDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(700)
        var blocked = false
        while (!blocked && !waitingTask.isDone && System.nanoTime() < waitDeadline) {
            blocked = jdbc.queryForObject(
                "SELECT :blockingPid = ANY(pg_blocking_pids(:waitingPid))",
                mapOf("blockingPid" to blockingPid, "waitingPid" to waitingPid), Boolean::class.java,
            ) == true
            if (!blocked) Thread.sleep(10)
        }
        assertThat(blocked).describedAs("concurrent membership changes must serialize on the Workspace lock").isTrue()
    }

    @Test
    fun `organization removal clears workspace access preserves OAuth and rejoin cannot restore access`() {
        val grantId = Uuid.generateV7().toJavaUuid()
        write {
            jdbc.update("INSERT INTO identity_runtime.agent_oauth_grants(id, owner_user_id, oauth_client_id, resource_audience, scopes) VALUES (:id, :user, 'test', 'test', ARRAY['agent.workspace'])", mapOf("id" to grantId, "user" to targetUserId, "organization" to organizationId))
            jdbc.update("INSERT INTO identity_runtime.agent_oauth_access_tokens(id, grant_id, token_hash, expires_at) VALUES (:id, :grant, :hash, now() + interval '1 hour')", mapOf("id" to Uuid.generateV7().toJavaUuid(), "grant" to grantId, "hash" to "test-$grantId"))
            organizationRepository.deleteMember(organization(organizationId), user(targetUserId))
        }
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM identity_runtime.agent_oauth_grants WHERE id = :id", mapOf("id" to grantId), Boolean::class.java)).isTrue()
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM identity_runtime.agent_oauth_access_tokens WHERE grant_id = :id", mapOf("id" to grantId), Boolean::class.java)).isTrue()
        write {
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES (:organization,:user,'MEMBER')", mapOf("organization" to organizationId,"user" to targetUserId))
        }
        assertThat(workspaceMemberCount(workspaceId, targetUserId)).isZero()
        assertThat(workspaceMemberCount(secondWorkspaceId, targetUserId)).isZero()
        assertNoDirectOwner(workspaceId, targetUserId)
        assertNoDirectOwner(secondWorkspaceId, targetUserId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["VIEWER", "EXPLORER", "EDITOR", "OWNER"])
    fun `workspace management roster candidates and roles require effective Owner`(roleName: String): Unit = runBlocking {
        val role = WorkspaceRole.valueOf(roleName)
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        write { repository.setMemberRole(workspace(workspaceId), user(targetUserId), role) }
        val queries = PostgresWorkspaceAuthorizationOperations(jdbc, transactionRunner)
        if (role == WorkspaceRole.OWNER) {
            assertManagementReadsAuthorized(queries, targetUserId, workspaceId)
        } else {
            assertManagementReadsDenied(queries, targetUserId, workspaceId)
        }
    }

    @Test
    fun `last organization admin cannot be removed or demoted and competing demotions retain one admin`() {
        val isolatedOrganization = OrganizationId.generate()
        write {
            organizationRepository.insert(Organization(isolatedOrganization, "Admin invariant", null, cc.monomer.identity.api.OrganizationPlan.FREE, now))
            organizationRepository.insertMember(OrganizationMembership(isolatedOrganization, user(ownerUserId), OrganizationRole.ADMIN, now))
        }
        assertThatThrownBy { write { organizationRepository.deleteMember(isolatedOrganization, user(ownerUserId)) } }
            .isInstanceOf(cc.monomer.identity.domain.port.LastOrganizationAdminException::class.java)
        assertThatThrownBy { write { organizationRepository.updateMemberRole(isolatedOrganization, user(ownerUserId), OrganizationRole.MEMBER) } }
            .isInstanceOf(cc.monomer.identity.domain.port.LastOrganizationAdminException::class.java)
        write { organizationRepository.insertMember(OrganizationMembership(isolatedOrganization, user(targetUserId), OrganizationRole.ADMIN, now)) }
        val start = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val outcomes = listOf(ownerUserId, targetUserId).map { target -> executor.submit<Boolean> {
                check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    write { organizationRepository.updateMemberRole(isolatedOrganization, user(target), OrganizationRole.MEMBER) }
                    true
                } catch (_: cc.monomer.identity.domain.port.LastOrganizationAdminException) { false }
            } }
            start.countDown()
            assertThat(outcomes.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id = :organization AND role = 'ADMIN'",
            mapOf("organization" to isolatedOrganization.value.toJavaUuid()), Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `competing personal owner demotions cannot remove the final anchor`() {
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val outcomes = listOf(ownerUserId, targetUserId).map { target -> executor.submit<Boolean> {
                start.await()
                try {
                    write { repository.setMemberRole(workspace(workspaceId), user(target), cc.monomer.identity.api.WorkspaceRole.EXPLORER) }
                    true
                } catch (_: LastWorkspaceOwnerException) { false }
            } }
            start.countDown()
            assertThat(outcomes.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE workspace_id = :workspace AND role = 'OWNER'", mapOf("workspace" to workspaceId), Int::class.java)).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }

    private suspend fun assertManagementReadsDenied(queries: PostgresWorkspaceAuthorizationOperations, actor: UUID, targetWorkspace: UUID) {
        if (actor == ownerUserId) {
            assertThat(queries.readWorkspaceMembers(user(actor), workspace(targetWorkspace)))
                .isInstanceOf(WorkspaceMembersQueryResult.Authorized::class.java)
        } else {
            assertThat(queries.readWorkspaceMembers(user(actor), workspace(targetWorkspace))).isEqualTo(WorkspaceMembersQueryResult.Denied)
        }
        assertThat(queries.readWorkspaceMemberCandidates(user(actor), workspace(targetWorkspace))).isEqualTo(WorkspaceMemberCandidatesQueryResult.Denied)
        assertThat(queries.readWorkspaceAuthorizationAssignments(user(actor), workspace(targetWorkspace))).isEqualTo(WorkspaceAuthorizationAssignmentsQueryResult.Denied)
    }

    private suspend fun assertManagementReadsAuthorized(queries: PostgresWorkspaceAuthorizationOperations, actor: UUID, targetWorkspace: UUID) {
        val roster = queries.readWorkspaceMembers(user(actor), workspace(targetWorkspace)) as WorkspaceMembersQueryResult.Authorized
        assertThat(roster.members.map { it.user.userId }).contains(user(actor))
        assertThat(roster.authorization.role).isEqualTo(WorkspaceRole.OWNER)
        assertThat(queries.readWorkspaceMemberCandidates(user(actor), workspace(targetWorkspace))).isInstanceOf(WorkspaceMemberCandidatesQueryResult.Authorized::class.java)
        assertThat(queries.readWorkspaceAuthorizationAssignments(user(actor), workspace(targetWorkspace))).isInstanceOf(WorkspaceAuthorizationAssignmentsQueryResult.Authorized::class.java)
    }

    private suspend fun addOwner(actor: UUID, targetWorkspace: UUID, target: UUID) {
        setRole(actor, targetWorkspace, target, WorkspaceRole.OWNER)
    }

    private suspend fun setRole(actor: UUID, targetWorkspace: UUID, target: UUID, role: WorkspaceRole) {
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        SetWorkspaceMemberRoleService(OwnerWorkspaceMutationRunner(transactionRunner, repository), repository,
            PostgresAuditLogRepository(jdbc), AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)),
            workspaceMemberRepository, Clock.fixed(now, ZoneOffset.UTC))
            .set(SetWorkspaceMemberRoleUseCase.Command(user(actor), workspace(targetWorkspace), user(target), role, null))
    }

    private suspend fun removeOwner(actor: UUID, targetWorkspace: UUID, target: UUID) {
        val repository = PostgresAuthorizationAssignmentRepository(jdbc)
        SetWorkspaceMemberRoleService(OwnerWorkspaceMutationRunner(transactionRunner, repository), repository,
            PostgresAuditLogRepository(jdbc), AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)), workspaceMemberRepository, Clock.fixed(now, ZoneOffset.UTC))
            .set(SetWorkspaceMemberRoleUseCase.Command(user(actor), workspace(targetWorkspace), user(target), cc.monomer.identity.api.WorkspaceRole.VIEWER, null))
    }

    private fun insertFixture() {
        write {
            val parameters = mapOf(
                "createdAt" to Timestamp.from(now),
                "organizationId" to organizationId,
                "workspaceId" to workspaceId,
                "secondWorkspaceId" to secondWorkspaceId,
                "ownerUserId" to ownerUserId,
                "targetUserId" to targetUserId,
                "relationId" to relationId,
                "secondRelationId" to secondRelationId,
            )
            jdbc.update(
                "INSERT INTO identity_runtime.organizations(id, display_name, created_at) VALUES (:organizationId, 'Lifecycle test organization', :createdAt)",
                parameters,
            )
            jdbc.update(
                "INSERT INTO identity_runtime.principals(id, kind, display_name, created_at) VALUES (:ownerUserId, 'user', 'Lifecycle owner', :createdAt), (:targetUserId, 'user', 'Lifecycle target', :createdAt)",
                parameters,
            )
            jdbc.update(
                "INSERT INTO identity_runtime.users(id, primary_email, created_at) VALUES (:ownerUserId, :ownerEmail, :createdAt), (:targetUserId, :targetEmail, :createdAt)",
                parameters + mapOf(
                    "ownerEmail" to "owner-${ownerUserId}@example.com",
                    "targetEmail" to "target-${targetUserId}@example.com",
                ),
            )
            jdbc.update(
                "INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, created_at) VALUES (:workspaceId, :organizationId, 'Lifecycle workspace', :createdAt), (:secondWorkspaceId, :organizationId, 'Lifecycle second workspace', :createdAt)",
                parameters,
            )
            jdbc.update(
                "INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organizationId, :ownerUserId, 'ADMIN', :createdAt), (:organizationId, :targetUserId, 'MEMBER', :createdAt)",
                parameters,
            )
            jdbc.update(
                "INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, invited_at, role) VALUES (:workspaceId, :ownerUserId, :createdAt, 'EXPLORER'), (:workspaceId, :targetUserId, :createdAt, 'EXPLORER'), (:secondWorkspaceId, :ownerUserId, :createdAt, 'EXPLORER'), (:secondWorkspaceId, :targetUserId, :createdAt, 'EXPLORER')",
                parameters,
            )
            jdbc.update(
                "UPDATE identity_runtime.workspace_members SET role = 'OWNER' WHERE (workspace_id = :workspaceId AND user_id = :ownerUserId) OR (workspace_id = :workspaceId AND user_id = :targetUserId) OR (workspace_id = :secondWorkspaceId AND user_id = :ownerUserId)",
                parameters,
            )


        }
    }

    private fun insertLastOwnerWorkspace() {
        val parameters = mapOf(
            "createdAt" to Timestamp.from(now),
            "organizationId" to organizationId,
            "workspaceId" to lastOwnerWorkspaceId,
            "targetUserId" to targetUserId,
            "relationId" to Uuid.generateV7().toJavaUuid(),
        )
        jdbc.update(
            "INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, created_at) VALUES (:workspaceId, :organizationId, 'Last owner workspace', :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, invited_at, role) VALUES (:workspaceId, :targetUserId, :createdAt, 'EXPLORER')",
            parameters,
        )
        jdbc.update(
            "UPDATE identity_runtime.workspace_members SET role = 'OWNER' WHERE (workspace_id = :workspaceId AND user_id = :targetUserId)",
            parameters,
        )

    }

    private fun assertNoDirectOwner(workspaceId: UUID, userId: UUID) {
        assertDirectOwner(
            workspaceId = workspaceId,
            userId = userId,
            ownerAssignments = 0,


        )
    }

    private fun assertDirectOwner(
        workspaceId: UUID,
        userId: UUID,
        ownerAssignments: Int,
    ) {
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM identity_runtime.workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId AND role = 'OWNER'",
            mapOf("workspaceId" to workspaceId, "userId" to userId), Int::class.java,
        )).isEqualTo(ownerAssignments)
    }

    private fun workspaceMemberCount(workspaceId: UUID, userId: UUID): Int = jdbc.queryForObject(
        "SELECT count(*) FROM identity_runtime.workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId",
        mapOf("workspaceId" to workspaceId, "userId" to userId),
        Int::class.java,
    )!!

    private fun organizationMembershipCount(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id = :organizationId AND user_id = :userId",
        mapOf("organizationId" to organizationId, "userId" to targetUserId),
        Int::class.java,
    )!!

    private fun authorizationRevision(workspaceId: UUID): Long = jdbc.queryForObject(
        "SELECT authorization_revision FROM identity_runtime.workspaces WHERE id = :workspaceId",
        mapOf("workspaceId" to workspaceId),
        Long::class.java,
    )!!

    private fun organization(id: UUID) = OrganizationId(id.toKotlinUuid())

    private fun workspace(id: UUID) = WorkspaceId(id.toKotlinUuid())

    private fun user(id: UUID) = UserId(id.toKotlinUuid())

    private fun <T> write(action: () -> T): T = checkNotNull(writeTemplate.execute { action() })

    companion object {
        @JvmField
        @RegisterExtension
        internal val postgres = TargetIdentityPostgres
    }
}
