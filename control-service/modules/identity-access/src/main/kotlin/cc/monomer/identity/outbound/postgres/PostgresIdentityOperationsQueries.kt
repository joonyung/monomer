package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.api.*
import cc.monomer.identity.api.OperationsAccess
import cc.monomer.identity.api.OperationsUnauthenticatedException
import java.time.Instant
import java.sql.Timestamp
import java.sql.ResultSet
import java.util.UUID
import java.sql.Connection
import javax.sql.DataSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import cc.monomer.identity.domain.port.TransactionRunner

class PostgresIdentityOperationsQueries(dataSource: DataSource, private val transactionRunner: TransactionRunner) : IdentityOperationsQueries {

    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private suspend fun <T> read(access: OperationsAccess, block: () -> T): T {
        if (!access.expiresAt.isAfter(Instant.now())) throw OperationsUnauthenticatedException()
        return transactionRunner.readOnly {
            check(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            jdbc.jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'")
            block()
        }
    }
    private fun params(from: Instant, to: Instant): Map<String, Any> {
        require(from < to && java.time.Duration.between(from, to) <= java.time.Duration.ofDays(30))
        return mapOf("from" to Timestamp.from(from), "to" to Timestamp.from(to))
    }

    private val userSelect = """
        SELECT u.id, p.display_name, u.primary_email, u.created_at,
        (SELECT count(*) FROM identity_runtime.organization_members m WHERE m.user_id=u.id) organization_count,
        (SELECT count(*) FROM identity_runtime.workspace_members m WHERE m.user_id=u.id) workspace_count
        FROM identity_runtime.users u JOIN identity_runtime.principals p ON p.id=u.id
    """.trimIndent()
    private val workspaceSelect = """
        SELECT w.id,w.display_name,w.organization_id,a.display_name organization_name,w.created_at,
        (SELECT count(*) FROM identity_runtime.workspace_members m WHERE m.workspace_id=w.id) member_count
        FROM identity_runtime.workspaces w JOIN identity_runtime.organizations a ON a.id=w.organization_id
    """.trimIndent()
    private fun userRow(r: ResultSet) = OperationsUser(r.getString("id"),r.getString("display_name"),r.getString("primary_email"),r.getTimestamp("created_at").toInstant(),r.getLong("organization_count"),r.getLong("workspace_count"))
    private fun workspaceRow(r: ResultSet) = OperationsWorkspace(r.getString("id"),r.getString("display_name"),r.getString("organization_id"),r.getString("organization_name"),r.getTimestamp("created_at").toInstant(),r.getLong("member_count"))
    override suspend fun overview(access: OperationsAccess, from: Instant, to: Instant) = read(access) {
        val p=params(from,to)
        val counts=jdbc.query("""SELECT (SELECT count(*) FROM identity_runtime.users) users,
            (SELECT count(*) FROM identity_runtime.organizations) organizations,
            (SELECT count(*) FROM identity_runtime.workspaces) workspaces,
            (SELECT count(*) FROM identity_runtime.users WHERE created_at>=:from AND created_at<:to) new_users""",p) { r,_ ->
            listOf("users","organizations","workspaces","new_users").map(r::getLong)
        }.single()
        val daily=jdbc.query("SELECT (created_at AT TIME ZONE 'UTC')::date::text AS bucket_date,count(*) count FROM identity_runtime.users WHERE created_at>=:from AND created_at<:to GROUP BY 1 ORDER BY 1",p) { r,_ -> OperationsIdentityDaily(r.getString("bucket_date"),r.getLong("count")) }
        val recent=jdbc.query("$userSelect WHERE u.created_at>=:from AND u.created_at<:to ORDER BY u.created_at DESC,u.id DESC LIMIT 10",p) { r,_ -> userRow(r) }
        OperationsIdentityOverview(counts[0],counts[1],counts[2],counts[3],daily,recent)
    }
    override suspend fun users(access: OperationsAccess,q:String?,from:Instant?,to:Instant?,before:Instant?,beforeId:String?,limit:Int)=read(access) {
        require(limit in 1..101)
        val p=mutableMapOf<String,Any>("limit" to limit)
        val conditions=mutableListOf("TRUE")
        if(q!=null){ require(q.length<=200); p["q"]="%${q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")}%"; conditions+="(p.display_name ILIKE :q OR u.primary_email ILIKE :q)" }
        if(from!=null && to!=null){ p.putAll(params(from,to)); conditions+="u.created_at>=:from AND u.created_at<:to" }
        if(before!=null && beforeId!=null){p["before"]=Timestamp.from(before);p["beforeId"]=UUID.fromString(beforeId);conditions+="(u.created_at,u.id)<(:before,:beforeId)"}
        jdbc.query("$userSelect WHERE ${conditions.joinToString(" AND ")} ORDER BY u.created_at DESC,u.id DESC LIMIT :limit",p){r,_->userRow(r)}
    }
    override suspend fun user(access:OperationsAccess,id:String)=read(access) {
        val p=mapOf("id" to UUID.fromString(id))
        val user=jdbc.query("$userSelect WHERE u.id=:id",p){r,_->userRow(r)}.singleOrNull() ?: return@read null
        val organizations=jdbc.query("SELECT a.id,a.display_name,m.role FROM identity_runtime.organization_members m JOIN identity_runtime.organizations a ON a.id=m.organization_id WHERE m.user_id=:id ORDER BY a.id",p){r,_->OperationsOrganizationMembership(r.getString("id"),r.getString("display_name"),r.getString("role"))}
        val workspaces=jdbc.query("""SELECT w.id,w.display_name,w.organization_id,m.role
          FROM identity_runtime.workspace_members m JOIN identity_runtime.workspaces w ON w.id=m.workspace_id
          JOIN identity_runtime.organization_members a ON a.organization_id=w.organization_id AND a.user_id=m.user_id
          WHERE m.user_id=:id ORDER BY w.id""",p){r,_->OperationsWorkspaceMembership(r.getString("id"),r.getString("display_name"),r.getString("organization_id"),r.getString("role"))}
        OperationsUserDetail(user,organizations,workspaces)
    }
    override suspend fun workspaces(access:OperationsAccess,q:String?,organizationId:String?,activeWorkspaceIds:Set<String>?,before:Instant?,beforeId:String?,limit:Int)=read(access) {
        require(limit in 1..101)
        if(activeWorkspaceIds!=null && activeWorkspaceIds.isEmpty()) return@read emptyList()
        val p=mutableMapOf<String,Any>("limit" to limit)
        val conditions=mutableListOf("TRUE")
        if(q!=null){require(q.length<=200);p["q"]="%${q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")}%";conditions+="(w.display_name ILIKE :q OR w.id::text ILIKE :q OR a.display_name ILIKE :q OR a.id::text ILIKE :q)"}
        if(organizationId!=null){p["organizationId"]=UUID.fromString(organizationId);conditions+="w.organization_id=:organizationId"}
        if(activeWorkspaceIds!=null){p["ids"]=activeWorkspaceIds.map(UUID::fromString);conditions+="w.id IN (:ids)"}
        if(before!=null && beforeId!=null){p["before"]=Timestamp.from(before);p["beforeId"]=UUID.fromString(beforeId);conditions+="(w.created_at,w.id)<(:before,:beforeId)"}
        jdbc.query("$workspaceSelect WHERE ${conditions.joinToString(" AND ")} ORDER BY w.created_at DESC,w.id DESC LIMIT :limit",p){r,_->workspaceRow(r)}
    }
    override suspend fun workspace(access:OperationsAccess,id:String)=read(access){
        val p=mapOf("id" to UUID.fromString(id))
        val workspace=jdbc.query("$workspaceSelect WHERE w.id=:id",p){r,_->workspaceRow(r)}.singleOrNull() ?: return@read null
        val members=jdbc.query("""SELECT u.id,p.display_name,u.primary_email,m.role
          FROM identity_runtime.workspace_members m JOIN identity_runtime.workspaces w ON w.id=m.workspace_id
          JOIN identity_runtime.users u ON u.id=m.user_id JOIN identity_runtime.principals p ON p.id=u.id
          JOIN identity_runtime.organization_members a ON a.organization_id=w.organization_id AND a.user_id=m.user_id
          WHERE w.id=:id ORDER BY u.id""",p){r,_->OperationsMember(r.getString("id"),r.getString("display_name"),r.getString("primary_email"),r.getString("role"))}
        OperationsWorkspaceDetail(workspace,members)
    }
    override suspend fun workspaceNames(access:OperationsAccess,ids:Set<String>)=read(access){
        require(ids.size<=100)
        if(ids.isEmpty()) emptyMap() else jdbc.query("SELECT id,display_name FROM identity_runtime.workspaces WHERE id IN (:ids)",mapOf("ids" to ids.map(UUID::fromString))){r,_->r.getString("id") to r.getString("display_name")}.toMap()
    }
    override suspend fun journeyPopulation(access:OperationsAccess,createdBefore:Instant,excludedOrganizationIds:Set<String>,limit:Int)=read(access){
        require(limit in 1..1001 && excludedOrganizationIds.size<=50)
        val p=mapOf("createdBefore" to Timestamp.from(createdBefore),"excluded" to uuidArray(excludedOrganizationIds),"limit" to limit)
        val organizations=jdbc.query("""SELECT id,display_name,created_at FROM identity_runtime.organizations
            WHERE created_at<:createdBefore AND id<>ALL(:excluded) ORDER BY created_at DESC,id DESC LIMIT :limit""",p){r,_->
            Triple(r.getString("id"),r.getString("display_name"),r.getTimestamp("created_at").toInstant())
        }
        val excludedOrganizations=jdbc.query("SELECT count(*) count FROM identity_runtime.organizations WHERE created_at<:createdBefore AND id=ANY(:excluded)",p){r,_->r.getLong("count")}.single()
        val ids=mapOf("ids" to uuidArray(organizations.map{it.first}))
        val membersByOrganization=jdbc.query("SELECT organization_id,user_id FROM identity_runtime.organization_members WHERE organization_id=ANY(:ids)",ids){r,_->
            r.getString("organization_id") to r.getString("user_id")
        }.groupBy({it.first},{it.second})
        val workspacesByOrganization=jdbc.query("SELECT organization_id,id FROM identity_runtime.workspaces WHERE organization_id=ANY(:ids)",ids){r,_->
            r.getString("organization_id") to r.getString("id")
        }.groupBy({it.first},{it.second})
        val contactsByOrganization=jdbc.query("""SELECT organization_id,display_name,primary_email FROM (
              SELECT m.organization_id,p.display_name,u.primary_email,
                row_number() OVER (PARTITION BY m.organization_id ORDER BY u.created_at,u.id) contact_rank
              FROM identity_runtime.organization_members m JOIN identity_runtime.users u ON u.id=m.user_id JOIN identity_runtime.principals p ON p.id=u.id
              WHERE m.role='ADMIN' AND m.organization_id=ANY(:ids)) admins
            WHERE contact_rank<=3 ORDER BY organization_id,contact_rank""",ids){r,_->
            r.getString("organization_id") to OperationsJourneyContact(r.getString("display_name"),r.getString("primary_email"))
        }.groupBy({it.first},{it.second})
        OperationsJourneyPopulation(organizations.map{(id,name,createdAt)->
            OperationsJourneyOrganization(id,name,createdAt,membersByOrganization[id].orEmpty().toSet(),workspacesByOrganization[id].orEmpty().toSet(),contactsByOrganization[id].orEmpty())
        },excludedOrganizations)
    }
    override suspend fun firstAgentAuthorizations(access:OperationsAccess,organizationIds:Set<String>)=read(access){
        jdbc.query("""SELECT m.organization_id,min(g.issued_at) first_issued_at
            FROM identity_runtime.organization_members m JOIN identity_runtime.agent_oauth_grants g ON g.owner_user_id=m.user_id
            WHERE m.organization_id=ANY(:ids) GROUP BY m.organization_id""",mapOf("ids" to uuidArray(organizationIds))){r,_->
            r.getString("organization_id") to r.getTimestamp("first_issued_at").toInstant()
        }.toMap()
    }
    private fun uuidArray(ids:Collection<String>)=object:AbstractSqlTypeValue(){
        override fun createTypeValue(connection:Connection,sqlType:Int,typeName:String?):Any=connection.createArrayOf("uuid",ids.map(UUID::fromString).toTypedArray())
    }
}
