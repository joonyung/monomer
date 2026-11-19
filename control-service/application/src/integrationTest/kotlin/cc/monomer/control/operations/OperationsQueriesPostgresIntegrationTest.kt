package cc.monomer.control.operations

import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.identity.api.OperationsAccess
import cc.monomer.identity.outbound.postgres.PostgresIdentityOperationsQueries
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreOperationsQueries
import io.mockk.every
import io.mockk.mockk
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*

class OperationsQueriesPostgresIntegrationTest {
    @Test fun `owner aggregates preserve identity cardinality and UTC cutoff semantics`()=runBlocking {
        val support=EphemeralPostgresAuthoritySupport
        support.verifyEphemeralIdentity()
        support.applyAuthoritySchemasTwice()
        val jdbc=JdbcTemplate(support.dataSource)
        val manager=DataSourceTransactionManager(support.dataSource)
        val readTransaction=TransactionTemplate(manager).apply{isReadOnly=true;timeout=5}
        val runner=cc.monomer.identity.outbound.postgres.SpringTransactionRunner(TransactionTemplate(manager),readTransaction, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val user=UUID.randomUUID();val a=UUID.randomUUID();val b=UUID.randomUUID();val w=UUID.randomUUID();val w2=UUID.randomUUID()
        val from=Instant.parse("2026-09-01T00:00:00Z");val to=from.plusSeconds(86400)
        TransactionTemplate(manager).execute {
            jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES(?,'user','Operator fixture')",user)
            jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(?,'fixture@example.test',?)",user,Timestamp.from(from))
            for(organization in listOf(a,b)){
                jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(?,'Fixture')",organization)
                jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(?,?,'ADMIN')",organization,user)
            }
            for(workspace in listOf(w,w2)){
                jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name,created_at) VALUES(?,?,'Fixture',?)",workspace,a,Timestamp.from(from))
                jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(?,?,'OWNER')",workspace,user)
            }
        }
        val runIds=listOf(UUID(0,1),UUID(0,2),UUID(0,3),UUID(0,4),UUID(0,5))
        fun run(index:Int,created:Instant,terminal:Instant?,state:String){
            jdbc.update("""INSERT INTO explore.runs(run_id,workspace_id,idempotency_key,owner_principal_id,connector_id,connector_type,kind,request_digest,state,failure_code,revision,created_at,updated_at,terminal_at)
                VALUES(?,?,?,?,?,'POSTGRESQL','SQL',?,?,?,0,?,?,?)""",runIds[index],w,"fixture-$index",user,UUID.randomUUID(),"sha256:"+"a".repeat(64),state,when(state){"FAILED"->"EXECUTION_FAILED";"CANCELLED"->"CANCELLED_BY_ACTOR";else->null},Timestamp.from(created),Timestamp.from(terminal ?: created),terminal?.let(Timestamp::from))
        }
        run(0,from,from.plusSeconds(10),"SUCCEEDED")
        run(1,from,from.plusSeconds(20),"FAILED")
        run(2,from,from.plusSeconds(30),"CANCELLED")
        run(3,to,to.plusSeconds(30),"SUCCEEDED")
        run(4,from.minusSeconds(10),from,"SUCCEEDED")
        for(deleted in listOf(false,true)) {
            val connectorId=UUID.randomUUID();val version=UUID.randomUUID()
            jdbc.update("""INSERT INTO connector.credential_versions(credential_version_id,workspace_id,connector_id,connector_type,key_version,nonce,additional_authenticated_data,ciphertext,envelope_digest,created_at)
                VALUES(?,?,?,'postgresql','test',decode(repeat('00',12),'hex'),decode('00','hex'),decode(repeat('00',17),'hex'),?,?)""",version,w,connectorId,"sha256:"+"a".repeat(64),Timestamp.from(from))
            jdbc.update("""INSERT INTO connector.connectors(connector_id,workspace_id,connector_type,display_name,properties,credential_version_id,authority_revision,created_at,updated_at,deleted_at)
                VALUES(?,?,'postgresql','Fixture','{"host":"example.test","port":"5432","database":"fixture"}',?,1,?,?,?)""",connectorId,w,if(deleted)null else version,Timestamp.from(from),Timestamp.from(from),if(deleted)Timestamp.from(from)else null)
        }
        val access=mockk<OperationsAccess>();every{access.expiresAt} returns Instant.now().plusSeconds(3600)
        val connectors=cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorOperationsQueries(support.dataSource,readTransaction,cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher(support.dataSource))
        assertEquals(1L,connectors.counts(access,setOf(w.toString()))[w.toString()]!!.single().count)
        val identity=PostgresIdentityOperationsQueries(support.dataSource,runner)
        val explore=PostgresExploreOperationsQueries(support.dataSource,readTransaction,cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher(support.dataSource))
        val overview=identity.overview(access,from,to)
        assertEquals(1L,overview.currentUsers);assertEquals(1L,overview.newUsers);assertEquals(2L,overview.currentOrganizations)
        val users=identity.users(access,null,null,null,null,null,51)
        assertEquals(1,users.size);assertEquals(2L,users.single().organizationCount);assertEquals(2L,users.single().workspaceCount)
        assertEquals(2,identity.user(access,user.toString())!!.organizations.size)
        assertTrue(identity.users(access,"%",null,null,null,null,51).isEmpty())
        assertTrue(identity.users(access,null,to,to.plusSeconds(100),null,null,51).isEmpty())
        val workspacePage=identity.workspaces(access,null,null,null,null,null,1)
        val workspaceNext=identity.workspaces(access,null,null,null,workspacePage.single().createdAt,workspacePage.single().id,1)
        assertEquals(2,(workspacePage+workspaceNext).map{it.id}.toSet().size)
        assertEquals(listOf(w.toString()),identity.workspaces(access,null,null,setOf(w.toString()),null,null,10).map{it.id})
        assertEquals(1,identity.workspace(access,w.toString())!!.members.size)
        val counts=explore.overview(access,from,to)
        assertEquals(3L,counts.runs);assertEquals(1L,counts.workspacesWithExploreRuns)
        assertEquals(2L,counts.succeeded);assertEquals(1L,counts.failed);assertEquals(1L,counts.cancelled);assertEquals(2.0/3,counts.successRate)
        val page=explore.runs(access,from,to,null,null,null,"createdAt",null,null,2)
        assertEquals(listOf(runIds[2].toString(),runIds[1].toString()),page.map{it.id})
        val next=explore.runs(access,from,to,null,null,null,"createdAt",page.last().createdAt,page.last().id,2)
        assertEquals(listOf(runIds[0].toString()),next.map{it.id})
        assertEquals(2,explore.runs(access,from,to,null,null,"SUCCEEDED","terminalAt",null,null,100).size)
        assertNull(explore.overview(access,to.plusSeconds(100),to.plusSeconds(200)).successRate)
        assertEquals(setOf(w.toString()),explore.activeWorkspaceIds(access,from,to))
        val workspaceRuns=explore.workspaceCounts(access,setOf(w.toString(),w2.toString()),from,to)
        assertEquals(3L,workspaceRuns[w.toString()]!!.runs)
        assertEquals(1L,workspaceRuns[w.toString()]!!.failed)
        assertEquals(3L,workspaceRuns[w.toString()]!!.sql)
        assertNull(workspaceRuns[w2.toString()])
        every{access.expiresAt} returns Instant.EPOCH
        assertFailsWith<cc.monomer.identity.api.OperationsUnauthenticatedException>{identity.overview(access,from,to)}
        Unit
    }
    companion object {
        @JvmField @RegisterExtension val postgres=EphemeralPostgresAuthoritySupport
    }
}
