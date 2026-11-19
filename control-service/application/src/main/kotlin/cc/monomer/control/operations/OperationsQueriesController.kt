package cc.monomer.control.operations

import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.control.observability.addCorrelationId
import cc.monomer.identity.api.*
import cc.monomer.explore.api.*
import cc.monomer.connector.api.*
import cc.monomer.control.observability.RequestLogContext
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ResponseStatusException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.HandlerMapping
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/ops/v1")
class OperationsQueriesController(
    private val authorization: OperationsAccessPort,
    private val identity: IdentityOperationsQueries,
    private val explore: ExploreOperationsQueries,
    private val connectors: ConnectorOperationsQueries,
) {
    private val logger=LoggerFactory.getLogger(javaClass)
    private suspend fun access(exchange:ServerWebExchange):OperationsAccess=authorization.authenticateRead(exchange,logger)
    @GetMapping("/overview")
    suspend fun overview(@RequestParam(required=false) from:String?,@RequestParam(required=false) to:String?,exchange:ServerWebExchange):OperationsOverviewResponse {
        val access=access(exchange);val now=Instant.now();val period=period(from,to,now)
        val unavailable=mutableListOf<String>()
        val users=try { identity.overview(access,period.first,period.second) } catch(_:DataAccessException){unavailable+="identity";null}
        val runs=try { explore.overview(access,period.first,period.second) } catch(_:DataAccessException){unavailable+="explore";null}
        val days=days(period.first,period.second)
        return OperationsOverviewResponse(now,period.first,period.second,
            users?.copy(daily=days.map{day->OperationsIdentityDaily(day,users.daily.find{it.date==day}?.newUsers ?: 0)}),
            runs?.copy(daily=days.map{day->OperationsExploreDaily(day,runs.daily.find{it.date==day}?.runs ?: 0)}),unavailable)
    }
    @GetMapping("/users")
    suspend fun users(@RequestParam(required=false) q:String?,@RequestParam(required=false) from:String?,@RequestParam(required=false) to:String?,@RequestParam(required=false) cursor:String?,@RequestParam(required=false) limit:Int?,exchange:ServerWebExchange):OperationsPage<OperationsUser> {
        val access=access(exchange);val now=Instant.now();val period=if(from==null && to==null)null else period(from,to,now)
        val size=pageSize(limit);val search=search(q);val scope="users|$search|$period";val before=boundary(cursor,scope)
        val items=identity.users(access,search,period?.first,period?.second,before?.at,before?.id,size+1)
        return page(now,period,items,size,scope){it.createdAt to it.id}
    }
    @GetMapping("/users/{id}")
    suspend fun user(@PathVariable id:String,exchange:ServerWebExchange):OperationsUserResponse {
        val access=access(exchange);val detail=identity.user(access,id(id)) ?: missing()
        return OperationsUserResponse(Instant.now(),null,null,detail.user,detail.organizations,detail.workspaces)
    }
    @GetMapping("/workspaces")
    suspend fun workspaces(@RequestParam(required=false) q:String?,@RequestParam(required=false) organizationId:String?,@RequestParam(required=false) withExploreRuns:Boolean?,@RequestParam(required=false) from:String?,@RequestParam(required=false) to:String?,@RequestParam(required=false) cursor:String?,@RequestParam(required=false) limit:Int?,exchange:ServerWebExchange):OperationsPage<OperationsWorkspaceItem> {
        val access=access(exchange);valid{require(cursor==null || (from!=null && to!=null))};val now=Instant.now();val period=period(from,to,now);val size=pageSize(limit);val search=search(q)
        val organization=organizationId?.let(::id);val scope="workspaces|$search|$organization|${withExploreRuns==true}|$period";val before=boundary(cursor,scope)
        val active=if(withExploreRuns==true) explore.activeWorkspaceIds(access,period.first,period.second) else null
        val workspaces=identity.workspaces(access,search,organization,active,before?.at,before?.id,size+1)
        val selected=workspaces.take(size);val ids=selected.map{it.id}.toSet()
        val connectorCounts=connectors.counts(access,ids);val runCounts=explore.workspaceCounts(access,ids,period.first,period.second)
        val items=selected.map{w->OperationsWorkspaceItem(w.id,w.name,w.organizationId,w.organizationName,w.createdAt,w.memberCount,connectorCounts[w.id].orEmpty().sumOf{it.count},runCounts[w.id]?.runs ?: 0,runCounts[w.id]?.failed ?: 0)}
        val next=if(workspaces.size>size) items.last().let{OperationsBoundary(it.createdAt,it.id).encode(scope)} else null
        return OperationsPage(now,period.first,period.second,items,next)
    }
    @GetMapping("/workspaces/{id}")
    suspend fun workspace(@PathVariable id:String,@RequestParam(required=false) from:String?,@RequestParam(required=false) to:String?,exchange:ServerWebExchange):OperationsWorkspaceResponse {
        val access=access(exchange);val now=Instant.now();val period=period(from,to,now);val workspaceId=id(id)
        val detail=identity.workspace(access,workspaceId) ?: missing()
        val connectorCounts=connectors.counts(access,setOf(workspaceId))[workspaceId].orEmpty()
        val counts=explore.workspaceCounts(access,setOf(workspaceId),period.first,period.second)[workspaceId] ?: OperationsRunCounts(0,0,0,0,0,0,0)
        val recent=explore.runs(access,period.first,period.second,workspaceId,null,null,"createdAt",null,null,10).map{wire(it,detail.workspace.name,now)}
        return OperationsWorkspaceResponse(now,period.first,period.second,detail.workspace,detail.members,connectorCounts,counts,recent)
    }
    @GetMapping("/runs")
    suspend fun runs(@RequestParam(required=false) workspaceId:String?,@RequestParam(required=false) kind:String?,@RequestParam(required=false) state:String?,@RequestParam(required=false) timeBasis:String?,@RequestParam(required=false) from:String?,@RequestParam(required=false) to:String?,@RequestParam(required=false) cursor:String?,@RequestParam(required=false) limit:Int?,exchange:ServerWebExchange):OperationsPage<OperationsRunItem> {
        val access=access(exchange);valid{require(cursor==null || (from!=null && to!=null))};val now=Instant.now();val period=period(from,to,now);val size=pageSize(limit);val workspace=workspaceId?.let(::id);val basis=timeBasis ?: "createdAt"
        valid {require(basis in setOf("createdAt","terminalAt"));require(kind==null || kind in setOf("SQL","SEMANTIC"));require(state==null || state in setOf("RUNNING","SUCCEEDED","FAILED","CANCELLED"))}
        val scope="runs|$workspace|$kind|$state|$basis|$period";val before=boundary(cursor,scope)
        val runs=explore.runs(access,period.first,period.second,workspace,kind,state,basis,before?.at,before?.id,size+1)
        val names=identity.workspaceNames(access,runs.take(size).map{it.workspaceId}.toSet())
        return page(now,period,runs.map{wire(it,names[it.workspaceId],now)},size,scope){it.createdAt to it.id}
    }
    @GetMapping("/runs/{id}")
    suspend fun run(@PathVariable id:String,exchange:ServerWebExchange):OperationsRunResponse {
        val access=access(exchange);val now=Instant.now();val run=explore.run(access,id(id)) ?: missing()
        val names=identity.workspaceNames(access,setOf(run.workspaceId))
        return OperationsRunResponse(now,null,null,wire(run,names[run.workspaceId],now))
    }
    private fun wire(run:OperationsRun,name:String?,now:Instant)=OperationsRunItem(run.id,run.workspaceId,name,run.kind,run.state,run.createdAt,run.terminalAt,Duration.between(run.createdAt,run.terminalAt ?: now).toMillis().coerceAtLeast(0),run.failureCode)
    private fun period(from:String?,to:String?,now:Instant):Pair<Instant,Instant> = valid {
        require((from==null)==(to==null))
        val end=to?.let(Instant::parse) ?: now
        val start=from?.let(Instant::parse) ?: end.minus(Duration.ofDays(7))
        require(start<end && Duration.between(start,end)<=Duration.ofDays(30))
        start to end
    }
    private fun days(from:Instant,to:Instant):List<String> = generateSequence(from.atOffset(ZoneOffset.UTC).toLocalDate()){it.plusDays(1)}.takeWhile{it.atStartOfDay().toInstant(ZoneOffset.UTC)<to}.map(LocalDate::toString).toList()
    private fun pageSize(limit:Int?)=(limit ?: 50).also{valid{require(it in 1..100)}}
    private fun search(q:String?)=q?.trim()?.takeIf{it.isNotEmpty()}?.also{valid{require(it.length<=200)}}
    private fun id(value:String):String=valid{UUID.fromString(value).toString().also{require(it==value)}}
    private fun boundary(cursor:String?,scope:String)=cursor?.let{valid{OperationsBoundary.decode(it,scope)}}
    private fun <T> valid(block:()->T):T=try{block()}catch(_:IllegalArgumentException){throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid operations query.")}catch(_:java.time.DateTimeException){throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid operations query.")}
    private fun missing():Nothing=throw ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found.")
    private fun <T> page(now:Instant,period:Pair<Instant,Instant>?,items:List<T>,size:Int,scope:String,key:(T)->Pair<Instant,String>):OperationsPage<T>{
        val selected=items.take(size)
        val next=if(items.size>size)key(selected.last()).let{OperationsBoundary(it.first,it.second).encode(scope)} else null
        return OperationsPage(now,period?.first,period?.second,selected,next)
    }
}

internal suspend fun OperationsAccessPort.authenticateRead(exchange:ServerWebExchange,logger:Logger):OperationsAccess {
    exchange.response.headers.cacheControl="no-store"
    val startedAt=System.nanoTime()
    val requestId=exchange.request.id
    exchange.response.beforeCommit {
        Mono.fromRunnable {
            logger.atInfo()
                .addKeyValue("event", "operations_read")
                .addCorrelationId("request_id", requestId)
                .addKeyValue("route", exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString())
                .addKeyValue("status", exchange.response.statusCode?.value() ?: 200)
                .addKeyValue("elapsed_ms", (System.nanoTime()-startedAt)/1_000_000)
                .log("operations_read")
        }
    }
    return when(val bearer=BearerCredentials.fromSingleAuthorizationHeader(exchange.request.headers)){
        is BearerAuthorization.Presented->authenticate(bearer.credential)
        BearerAuthorization.Missing,BearerAuthorization.Malformed->throw OperationsUnauthenticatedException()
    }
}

internal data class OperationsBoundary(val at:Instant,val id:String){
    fun encode(scope:String):String=Base64.getUrlEncoder().withoutPadding().encodeToString("${digest(scope)}|$at|$id".toByteArray())
    companion object {
        private fun digest(scope:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()))
        fun decode(value:String,scope:String):OperationsBoundary {
            require(value.length<=512 && value.matches(Regex("[A-Za-z0-9_-]+")))
            val parts=String(Base64.getUrlDecoder().decode(value),Charsets.UTF_8).split('|')
            require(parts.size==3 && parts[0]==digest(scope))
            val at=Instant.parse(parts[1]);val id=UUID.fromString(parts[2]).toString()
            return OperationsBoundary(at,id).also{require(it.encode(scope)==value)}
        }
    }
}

data class OperationsPage<T>(val queriedAt:Instant,val from:Instant?,val to:Instant?,val items:List<T>,val nextCursor:String?)
data class OperationsOverviewResponse(val queriedAt:Instant,val from:Instant,val to:Instant,val identity:OperationsIdentityOverview?,val explore:OperationsExploreOverview?,val unavailablePanels:List<String>)
data class OperationsUserResponse(val queriedAt:Instant,val from:Instant?,val to:Instant?,val user:OperationsUser,val organizations:List<OperationsOrganizationMembership>,val workspaces:List<OperationsWorkspaceMembership>)
data class OperationsWorkspaceItem(val id:String,val name:String,val organizationId:String,val organizationName:String,val createdAt:Instant,val memberCount:Long,val connectorCount:Long,val exploreRuns:Long,val failedRuns:Long)
data class OperationsWorkspaceResponse(val queriedAt:Instant,val from:Instant,val to:Instant,val workspace:OperationsWorkspace,val members:List<OperationsMember>,val connectors:List<OperationsConnectorCount>,val runs:OperationsRunCounts,val recentRuns:List<OperationsRunItem>)
data class OperationsRunItem(val id:String,val workspaceId:String,val workspaceName:String?,val kind:String,val state:String,val createdAt:Instant,val terminalAt:Instant?,val elapsedMs:Long,val failureCode:String?)
data class OperationsRunResponse(val queriedAt:Instant,val from:Instant?,val to:Instant?,val run:OperationsRunItem)

@RestControllerAdvice(assignableTypes=[OperationsQueriesController::class,OperationsJourneysController::class])
class OperationsQueriesProblemHandler {
    @ExceptionHandler(OperationsUnauthenticatedException::class)
    fun unauthenticated(exchange:ServerWebExchange)=problem(exchange,HttpStatus.UNAUTHORIZED,"Operations authentication required.")
    @ExceptionHandler(OperationsForbiddenException::class)
    fun forbidden(exchange:ServerWebExchange)=problem(exchange,HttpStatus.FORBIDDEN,"Operations access denied.")
    @ExceptionHandler(org.springframework.web.server.ServerWebInputException::class)
    fun malformed(exchange:ServerWebExchange)=problem(exchange,HttpStatus.BAD_REQUEST,"Invalid operations query.")
    @ExceptionHandler(DataAccessException::class)
    fun unavailable(exchange:ServerWebExchange)=problem(exchange,HttpStatus.SERVICE_UNAVAILABLE,"Operations data is unavailable.")
    @ExceptionHandler(ResponseStatusException::class)
    fun invalid(error:ResponseStatusException,exchange:ServerWebExchange):ProblemDetail {
        exchange.response.headers.cacheControl="no-store"
        val retryable = error.statusCode == HttpStatus.SERVICE_UNAVAILABLE
        if (retryable) exchange.response.headers.set("Retry-After", "2")
        RequestLogContext.problem("HTTP_${error.statusCode.value()}")
        return ProblemDetail.forStatusAndDetail(error.statusCode,error.reason ?: "Invalid operations request.").also {
            it.setProperty("requestId",exchange.request.id)
            it.setProperty("traceId",exchange.request.id)
            it.setProperty("retryable", retryable)
        }
    }
    private fun problem(exchange:ServerWebExchange,status:HttpStatus,detail:String):ProblemDetail {
        exchange.response.headers.cacheControl="no-store"
        val retryable = status == HttpStatus.SERVICE_UNAVAILABLE
        if (retryable) exchange.response.headers.set("Retry-After", "2")
        RequestLogContext.problem("HTTP_${status.value()}")
        return ProblemDetail.forStatusAndDetail(status,detail).also {
            it.setProperty("requestId",exchange.request.id)
            it.setProperty("traceId",exchange.request.id)
            it.setProperty("retryable", retryable)
        }
    }
}
