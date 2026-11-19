package cc.monomer.control.operations

import cc.monomer.connector.api.ConnectorOperationsQueries
import cc.monomer.control.agentapp.responses
import cc.monomer.dashboard.api.DashboardOperationsQueries
import cc.monomer.explore.api.ExploreOperationsQueries
import cc.monomer.identity.api.AgentAppDisplays
import cc.monomer.identity.api.IdentityOperationsQueries
import cc.monomer.identity.api.OperationsAccessPort
import cc.monomer.semantic.api.SemanticOperationsQueries
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.transaction.TransactionException
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/ops/v1")
class OperationsJourneysController(
    private val authorization: OperationsAccessPort,
    private val identity: IdentityOperationsQueries,
    private val connectors: ConnectorOperationsQueries,
    private val explore: ExploreOperationsQueries,
    private val dashboards: DashboardOperationsQueries,
    private val semantic: SemanticOperationsQueries,
    private val agentAppDisplays: AgentAppDisplays,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/journeys")
    suspend fun journeys(exchange: ServerWebExchange): OperationsJourneysResponse {
        val access = authorization.authenticateRead(exchange, logger)
        val queriedAt = clock.instant()
        val query = journeysQuery(exchange.request.queryParams, queriedAt)
        val population = required { identity.journeyPopulation(access, query.to, query.excludedOrganizationIds, MAX_JOURNEY_ORGANIZATIONS + 1) }
        if (population.organizations.size > MAX_JOURNEY_ORGANIZATIONS) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Too many organizations for the journeys view.")
        }
        val workspaceIds = population.organizations.flatMap { it.workspaceIds }.toSet()
        val organizationIds = population.organizations.map { it.id }.toSet()
        val sources = coroutineScope {
            val firstConnectors = async { availableOrNull { connectors.firstConnectorCreatedAt(access, workspaceIds) } }
            val firstSampleConnectors = async { availableOrNull { connectors.firstSampleConnectorCreatedAt(access, workspaceIds) } }
            val firstAgentAuthorizations = async { availableOrNull { identity.firstAgentAuthorizations(access, organizationIds) } }
            val exploreActivity = async { availableOrNull { explore.journeyActivity(access, workspaceIds, query.from, query.to) } }
            val dashboardActivity = async { availableOrNull { dashboards.journeyActivity(access, workspaceIds, query.from, query.to) } }
            val semanticActivity = async { availableOrNull { semantic.journeyActivity(access, workspaceIds, query.from, query.to) } }
            OperationsJourneySources(
                population, firstConnectors.await(), firstSampleConnectors.await(), firstAgentAuthorizations.await(),
                exploreActivity.await(), dashboardActivity.await(), semanticActivity.await(),
            )
        }
        val assembly = OperationsJourneyAssembly(sources, query.weeks)
        val agentAppNames = required { agentAppDisplays.responses(assembly.agentClientIds) }.mapValues { it.value.name }
        return assembly.response(queriedAt, query.from, query.to, agentAppNames)
    }

    private suspend fun <T> availableOrNull(read: suspend () -> T): T? =
        try { read() } catch (_: DataAccessException) { null } catch (_: TransactionException) { null }

    private suspend fun <T> required(read: suspend () -> T): T = try { read() } catch (_: TransactionException) {
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operations data is unavailable.")
    }

    private fun journeysQuery(parameters: MultiValueMap<String, String>, now: Instant): OperationsJourneysQuery = try {
        require(parameters.keys.all { it in JOURNEYS_QUERY_PARAMETERS })
        val from = Instant.parse(requireNotNull(parameters["from"]?.singleOrNull()))
        val to = Instant.parse(requireNotNull(parameters["to"]?.singleOrNull()))
        require(isUtcWeekStart(from) && isUtcWeekStart(to) && from < to)
        val weekCount = Duration.between(from, to).toDays() / 7
        require(weekCount in 1..MAX_JOURNEY_WEEKS)
        val currentWeekStart = now.plus(CLIENT_CLOCK_SKEW_TOLERANCE).atOffset(ZoneOffset.UTC).toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay().toInstant(ZoneOffset.UTC)
        require(to <= currentWeekStart.plus(7, ChronoUnit.DAYS))
        val excluded = parameters["excludeOrganizationId"].orEmpty()
        require(excluded.size <= MAX_EXCLUDED_ORGANIZATIONS)
        excluded.forEach { require(UUID.fromString(it).toString() == it) }
        val firstWeek = from.atOffset(ZoneOffset.UTC).toLocalDate()
        OperationsJourneysQuery(from, to, (0 until weekCount).map { firstWeek.plusWeeks(it) }, excluded.toSet())
    } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operations query.")
    } catch (_: java.time.DateTimeException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operations query.")
    }

    private fun isUtcWeekStart(instant: Instant): Boolean = instant.atOffset(ZoneOffset.UTC).let {
        it.dayOfWeek == DayOfWeek.MONDAY && it.toLocalTime() == LocalTime.MIDNIGHT
    }

    companion object {
        const val MAX_JOURNEY_ORGANIZATIONS = 1_000
        const val MAX_JOURNEY_WEEKS = 26L
        const val MAX_EXCLUDED_ORGANIZATIONS = 50
        private val CLIENT_CLOCK_SKEW_TOLERANCE: Duration = Duration.ofMinutes(5)
        private val JOURNEYS_QUERY_PARAMETERS = setOf("from", "to", "excludeOrganizationId")
    }
}

internal data class OperationsJourneysQuery(val from: Instant, val to: Instant, val weeks: List<LocalDate>, val excludedOrganizationIds: Set<String>)
