package cc.monomer.explore.adapter.outbound.postgres

import cc.monomer.explore.api.*
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.OperationsAccess
import cc.monomer.identity.api.OperationsUnauthenticatedException
import java.time.Instant
import java.time.LocalDate
import java.sql.Connection
import java.sql.Timestamp
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations

class PostgresExploreOperationsQueries(
    dataSource: DataSource,
    private val transaction: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : ExploreOperationsQueries {

    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    init {
        val definition = requireNotNull(transaction as? TransactionDefinition)
        require(definition.isReadOnly && definition.timeout == 5)
    }
    private suspend fun <T> read(access: OperationsAccess, block: () -> T): T = withContext(blockingDispatcher) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Operations reads require an independent root transaction"
        }
        if (!access.expiresAt.isAfter(Instant.now())) throw OperationsUnauthenticatedException()
        requireNotNull(transaction.execute {
            jdbc.jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'")
            Box(block())
        }).value
    }
    private data class Box<T>(val value: T)
    private fun params(from: Instant, to: Instant): Map<String, Any> {
        require(from < to && java.time.Duration.between(from, to) <= java.time.Duration.ofDays(30))
        return mapOf("from" to Timestamp.from(from), "to" to Timestamp.from(to))
    }

    private val runSelect="SELECT run_id,workspace_id,kind,state,created_at,terminal_at,failure_code FROM explore.runs"
    private fun row(r:ResultSet)=OperationsRun(r.getString("run_id"),r.getString("workspace_id"),r.getString("kind"),r.getString("state"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("terminal_at")?.toInstant(),r.getString("failure_code"))
    override suspend fun runs(access:OperationsAccess,from:Instant,to:Instant,workspaceId:String?,kind:String?,state:String?,timeBasis:String,before:Instant?,beforeId:String?,limit:Int)=read(access){
        require(limit in 1..101)
        require(timeBasis in setOf("createdAt","terminalAt"))
        require(kind==null || kind in setOf("SQL","SEMANTIC"))
        require(state==null || state in setOf("RUNNING","SUCCEEDED","FAILED","CANCELLED"))
        val timeColumn=if(timeBasis=="createdAt") "created_at" else "terminal_at"
        val p=params(from,to).toMutableMap();p["limit"]=limit
        val conditions=mutableListOf("$timeColumn>=:from AND $timeColumn<:to")
        if(workspaceId!=null){p["workspace"]=UUID.fromString(workspaceId);conditions+="workspace_id=:workspace"}
        if(kind!=null){p["kind"]=kind;conditions+="kind=:kind"}
        if(state!=null){p["state"]=state;conditions+="state=:state"}
        if(before!=null && beforeId!=null){p["before"]=Timestamp.from(before);p["beforeId"]=UUID.fromString(beforeId);conditions+="(created_at,run_id)<(:before,:beforeId)"}
        jdbc.query("$runSelect WHERE ${conditions.joinToString(" AND ")} ORDER BY created_at DESC,run_id DESC LIMIT :limit",p){r,_->row(r)}
    }
    override suspend fun run(access:OperationsAccess,id:String)=read(access){jdbc.query("$runSelect WHERE run_id=:id",mapOf("id" to UUID.fromString(id))){r,_->row(r)}.singleOrNull()}
    override suspend fun overview(access:OperationsAccess,from:Instant,to:Instant)=read(access){
        val p=params(from,to)
        val created=jdbc.query("SELECT count(*) count,count(DISTINCT workspace_id) workspaces,count(*) FILTER(WHERE state='RUNNING') running FROM explore.runs WHERE created_at>=:from AND created_at<:to",p){r,_->Triple(r.getLong("count"),r.getLong("workspaces"),r.getLong("running"))}.single()
        val terminal=jdbc.query("SELECT count(*) FILTER(WHERE state='SUCCEEDED') succeeded,count(*) FILTER(WHERE state='FAILED') failed,count(*) FILTER(WHERE state='CANCELLED') cancelled FROM explore.runs WHERE terminal_at>=:from AND terminal_at<:to",p){r,_->Triple(r.getLong("succeeded"),r.getLong("failed"),r.getLong("cancelled"))}.single()
        val daily=jdbc.query("SELECT (created_at AT TIME ZONE 'UTC')::date::text AS bucket_date,count(*) count FROM explore.runs WHERE created_at>=:from AND created_at<:to GROUP BY 1 ORDER BY 1",p){r,_->OperationsExploreDaily(r.getString("bucket_date"),r.getLong("count"))}
        val recent=jdbc.query("$runSelect WHERE state='FAILED' AND created_at>=:from AND created_at<:to ORDER BY created_at DESC,run_id DESC LIMIT 10",p){r,_->row(r)}
        val denominator=terminal.first+terminal.second
        OperationsExploreOverview(created.first,created.second,terminal.first,terminal.second,terminal.third,created.third,if(denominator==0L)null else terminal.first.toDouble()/denominator,daily,recent)
    }
    override suspend fun workspaceCounts(access:OperationsAccess,ids:Set<String>,from:Instant,to:Instant)=read(access){
        require(ids.size<=100)
        if(ids.isEmpty()) return@read emptyMap()
        val p=params(from,to)+("ids" to ids.map(UUID::fromString))
        jdbc.query("""SELECT workspace_id,count(*) runs,count(*) FILTER(WHERE state='SUCCEEDED') succeeded,count(*) FILTER(WHERE state='FAILED') failed,
          count(*) FILTER(WHERE state='CANCELLED') cancelled,count(*) FILTER(WHERE state='RUNNING') running,count(*) FILTER(WHERE kind='SQL') sql,count(*) FILTER(WHERE kind='SEMANTIC') semantic
          FROM explore.runs WHERE workspace_id IN (:ids) AND created_at>=:from AND created_at<:to GROUP BY workspace_id""",p){r,_->r.getString("workspace_id") to OperationsRunCounts(r.getLong("runs"),r.getLong("succeeded"),r.getLong("failed"),r.getLong("cancelled"),r.getLong("running"),r.getLong("sql"),r.getLong("semantic"))}.toMap()
    }
    override suspend fun activeWorkspaceIds(access:OperationsAccess,from:Instant,to:Instant)=read(access){
        jdbc.query("SELECT DISTINCT workspace_id FROM explore.runs WHERE created_at>=:from AND created_at<:to",params(from,to)){r,_->r.getString("workspace_id")}.toSet()
    }
    override suspend fun journeyActivity(access:OperationsAccess,workspaceIds:Set<String>,from:Instant,to:Instant)=read(access){
        require(from<to && java.time.Duration.between(from,to)<=java.time.Duration.ofDays(26*7))
        val p=mapOf("ids" to uuidArray(workspaceIds),"from" to Timestamp.from(from),"to" to Timestamp.from(to))
        val milestones=jdbc.query("""SELECT workspace_id,owner_principal_id,
            min(created_at) FILTER(WHERE state='SUCCEEDED') first_succeeded_at,min(created_at) FILTER(WHERE credential_surface='AGENT') first_agent_at
            FROM explore.runs WHERE workspace_id=ANY(:ids) AND (state='SUCCEEDED' OR credential_surface='AGENT')
            GROUP BY workspace_id,owner_principal_id""",p){r,_->
            OperationsExploreActorMilestone(r.getString("workspace_id"),r.getString("owner_principal_id"),r.getTimestamp("first_succeeded_at")?.toInstant(),r.getTimestamp("first_agent_at")?.toInstant())
        }
        val weekly=jdbc.query("""SELECT workspace_id,(date_trunc('week',created_at AT TIME ZONE 'UTC'))::date week_start,owner_principal_id,credential_surface,agent_client_id,connector_type='DUCKDB' sample_dataset,
            count(*) runs,count(*) FILTER(WHERE state='SUCCEEDED') succeeded_runs
            FROM explore.runs WHERE workspace_id=ANY(:ids) AND created_at>=:from AND created_at<:to GROUP BY 1,2,3,4,5,6""",p){r,_->
            OperationsExploreWeeklyRuns(r.getString("workspace_id"),r.getObject("week_start",LocalDate::class.java),r.getString("owner_principal_id"),
                r.getString("credential_surface")?.let(CredentialSurface::valueOf),r.getString("agent_client_id")?.let(::AgentClientId),r.getBoolean("sample_dataset"),r.getLong("runs"),r.getLong("succeeded_runs"))
        }
        OperationsExploreJourneyActivity(milestones,weekly)
    }
    private fun uuidArray(ids:Collection<String>)=object:AbstractSqlTypeValue(){
        override fun createTypeValue(connection:Connection,sqlType:Int,typeName:String?):Any=connection.createArrayOf("uuid",ids.map(UUID::fromString).toTypedArray())
    }
}
