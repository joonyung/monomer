@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.sample

import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.*
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.DataInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class SampleMetricDuckDbIntegrationTest {
    @Test
    fun `latest v3 seeded metrics render and execute at current and future dates with one field active account edit`() = verifyDataset("acme-cloud@3", "")

    @Test
    fun `preserved v2 metrics retain actual original rendered expectations`() = verifyDataset("acme-cloud@2", "versions/2")

    @Test
    fun `preserved v1 metrics retain actual original rendered expectations`() = verifyDataset("acme-cloud@1", "versions/1")

    @Test
    fun `paid activity cohorts exclude unconverted accounts and activity before conversion`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("testkit/data/duckdb/acme-cloud/generate.sql")) }
        val datasetRoot = root.resolve("testkit/data/duckdb/acme-cloud")
        val driver = root.resolve("execution-service/application/build/install/application/lib/duckdb_jdbc-1.5.4.0.jar")
        assertTrue(Files.isRegularFile(driver))
        val temporary = Files.createTempDirectory("sample-paid-cohorts")
        try {
            val generator = temporary.resolve("fixture.sql")
            Files.writeString(generator, """
                CREATE SCHEMA acme;
                CREATE TABLE acme.accounts(account_id BIGINT, created_at DATE);
                INSERT INTO acme.accounts VALUES (1, '2026-01-30'), (2, '2026-02-05'), (3, '2026-02-10'), (4, '2026-02-28'), (5, '2026-03-20');
                CREATE TABLE acme.trials(account_id BIGINT, converted_at DATE);
                INSERT INTO acme.trials VALUES (1, '2026-02-13'), (2, '2026-02-19'), (3, NULL), (4, '2026-03-15'), (5, '2026-04-03');
                CREATE TABLE acme.daily_account_activity(account_id BIGINT, activity_date DATE, active_users INTEGER);
                INSERT INTO acme.daily_account_activity VALUES
                    (1, '2026-02-01', 1), (2, '2026-02-20', 1), (2, '2026-03-10', 2),
                    (3, '2026-02-20', 1), (4, '2026-03-18', 1), (5, '2026-03-25', 1), (0, '2026-03-31', 0);
            """.trimIndent())
            val database = temporary.resolve("cohorts.duckdb")
            javaFixture(driver, datasetRoot.resolve("GenerateDataset.java"), listOf(generator.toString(), database.toString()), temporary.resolve("generate.out"), temporary.resolve("generate.err"))
            val definition = SampleDefinitionBundle().read("acme-cloud@3", ConnectorId.generate(), LocalDate.parse("2026-04-01")).dashboards.getValue("product-engagement")
            val query = definition.getValue("queries").jsonObject.getValue("cohort_activity").jsonObject
            val sql = query.getValue("sql").jsonPrimitive.content.replace("{{start}}", "'2026-02-01'").replace("{{end}}", "'2026-03-31'")
            val queries = Files.createDirectory(temporary.resolve("queries"))
            Files.writeString(queries.resolve("cohorts.sql"), sql)
            javaFixture(driver, datasetRoot.resolve("ExecuteRenderedMetrics.java"), listOf(database.toString(), queries.toString()), temporary.resolve("results.bin"), temporary.resolve("execute.err"))
            val result = readResults(temporary.resolve("results.bin")).getValue("cohorts")
            assertEquals(24, result.rows.size)
            fun cell(month: String, age: Int) = result.rows.single { it[0] == month && it[1] == age.toString() }
            assertEquals(listOf("2026-02-01", "0", "2", "1", "0.5", "2026-03-31"), cell("2026-02-01", 0))
            assertEquals(listOf("2026-02-01", "1", "2", "1", "0.5", "2026-03-31"), cell("2026-02-01", 1))
            assertEquals(listOf("2026-03-01", "0", "1", "1", "1.0", "2026-03-31"), cell("2026-03-01", 0))
            assertNull(cell("2026-02-01", 2)[3])
            assertNull(cell("2026-02-01", 2)[4])
            assertNull(cell("2026-03-01", 1)[3])
            assertNull(cell("2026-03-01", 1)[4])
        } finally { temporary.toFile().deleteRecursively() }
    }

    private fun verifyDataset(dataset: String, fixtureDirectory: String) {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("testkit/data/duckdb/acme-cloud/generate.sql")) }
        val datasetRoot = root.resolve("testkit/data/duckdb/acme-cloud")
        val fixture = datasetRoot.resolve(fixtureDirectory)
        val driver = root.resolve("execution-service/application/build/install/application/lib/duckdb_jdbc-1.5.4.0.jar")
        assertTrue(Files.isRegularFile(driver), "Run execution-service ./gradlew check before the sample metric integration gate.")
        val temporary = Files.createTempDirectory("sample-rendered-metrics")
        try {
            val database = temporary.resolve("acme_cloud.duckdb")
            javaFixture(driver, datasetRoot.resolve("GenerateDataset.java"), listOf(fixture.resolve("generate.sql").toString(), database.toString()), temporary.resolve("generate.out"), temporary.resolve("generate.err"))
            val today = LocalDate.now()
            val definitions = SampleDefinitionBundle().read(dataset, ConnectorId.generate(), today)
            val expectations = ObjectMapper().readTree(fixture.resolve("expectations.json").toFile())
            val expectedValues = (expectations["metrics"].fields().asSequence() + expectations["componentMetrics"].fields().asSequence()).associate { it.key to it.value.asText().toBigDecimal() }
            assertEquals(if (dataset == "acme-cloud@3") 20 else 14, expectedValues.size)
            assertEquals(expectedValues.keys, definitions.metrics.map { it.name.value }.toSet())
            assertEquals(expectedValues.keys, expectations["renderCases"].map { it["metric"].asText() }.toSet())
            val manifest = manifest(definitions, false)
            val futureManifest = manifest(definitions, true)
            val renderer = MetricFlowSemanticRenderer.production()
            assertEquals(SemanticMetricFlowValidationResult.Valid, renderer.validate(manifest))
            assertEquals(SemanticMetricFlowValidationResult.Valid, renderer.validate(futureManifest))
            val queries = temporary.resolve("queries")
            Files.createDirectories(queries)
            val metricColumns = mutableMapOf<String, String>()
            for (renderCase in expectations["renderCases"]) {
                val name = renderCase["metric"].asText()
                val grain = renderCase["metricTimeGrain"].takeUnless { it.isNull }?.asText()?.let(SemanticTimeGranularity::valueOf)
                for ((prefix, snapshot, date) in listOf(Triple("current", manifest, today), Triple("future", futureManifest, today.plusDays(30)))) {
                    val start = if (renderCase["range"].asText() == "LAST_DAY") date.minusDays(1) else date.minusDays(90)
                    val rendered = render(renderer, snapshot, query(name, grain, start, date.minusDays(1), emptyList()))
                    val label = "$prefix-$name"
                    metricColumns[label] = rendered.metricColumn()
                    Files.writeString(queries.resolve("$label.sql"), rendered.sql)
                }
            }
            val activeId = manifest.metrics.entries.single { it.value.name.value == "active_accounts" }.key
            val active = assertIs<SemanticMetricDefinition.Cumulative>(manifest.metrics.getValue(activeId))
            val predicate = assertIs<SemanticMetricFilter.Predicate>(active.filters.single())
            assertEquals("active_users", predicate.dimension.name.value)
            val edited = active.copy(filters = listOf(predicate.copy(dimension = predicate.dimension.copy(name = SemanticElementName("core_actions")))))
            val editedManifest = manifest.copy(metrics = manifest.metrics + (activeId to edited))
            val lastDay = query("active_accounts", SemanticTimeGranularity.DAY, today.minusDays(1), today.minusDays(1), emptyList())
            val savedResult = render(renderer, manifest, lastDay)
            Files.writeString(queries.resolve("saved-active-before-definition-edit.sql"), savedResult.sql)
            Files.writeString(queries.resolve("refreshed-active.sql"), render(renderer, editedManifest, lastDay).sql)
            assertEquals("active_users", assertIs<SemanticMetricFilter.Predicate>(assertIs<SemanticMetricDefinition.Cumulative>(manifest.metrics.getValue(activeId)).filters.single()).dimension.name.value)
            val dashboardLabels = mutableListOf<Pair<String, String>>()
            for ((name, dashboard) in definitions.dashboards.filterValues { dashboard ->
                dashboard.getValue("queries").jsonObject.values.any { entry ->
                    entry.jsonObject["query"]?.jsonObject?.get("metrics")?.jsonArray?.any { it.jsonPrimitive.content == "active_accounts" } == true
                }
            }) {
                val dashboardQuery = dashboard.getValue("queries").jsonObject.values.map { it.jsonObject["query"]?.jsonObject }.filterNotNull()
                    .first { item -> item.getValue("metrics").jsonArray.any { it.jsonPrimitive.content == "active_accounts" } }
                val grain = dashboardQuery.getValue("groupBys").jsonArray.map { it.jsonObject }
                    .single { it.getValue("kind").jsonPrimitive.content == "METRIC_TIME" }.getValue("granularity").jsonPrimitive.content
                val request = query("active_accounts", SemanticTimeGranularity.valueOf(grain), today.minusDays(90), today.minusDays(1), emptyList())
                val before = "dashboard-$name-before"
                val after = "dashboard-$name-after"
                dashboardLabels += before to after
                Files.writeString(queries.resolve("$before.sql"), render(renderer, manifest, request).sql)
                Files.writeString(queries.resolve("$after.sql"), render(renderer, editedManifest, request).sql)
            }
            assertEquals(2, dashboardLabels.size)
            val planQuery = query("active_accounts", SemanticTimeGranularity.WEEK, today.minusDays(90), today.minusDays(1), listOf(SemanticGroupBy.Dimension(SemanticElementName("daily_account_activity"), SemanticElementName("plan"), null)))
            val planRendered = render(renderer, manifest, planQuery)
            Files.writeString(queries.resolve("by-plan.sql"), planRendered.sql)
            val selectedPlan = planQuery.copy(filters = listOf(SemanticQueryFilter.Text(SemanticElementReference(SemanticElementName("daily_account_activity"), SemanticElementName("plan")), SemanticTextFilterOperator.IN, listOf("Team"))))
            Files.writeString(queries.resolve("by-plan-selected.sql"), render(renderer, manifest, selectedPlan).sql)
            Files.writeString(queries.resolve("direct-by-plan.sql"), "SELECT plan, count(DISTINCT account_id) active_accounts FROM acme.daily_account_activity WHERE activity_date BETWEEN current_date - 7 AND current_date - 1 AND active_users >= 1 GROUP BY plan")
            val foreignActiveColumns = buildMap {
                if (dataset == "acme-cloud@3") {
                    val region = SemanticQueryFilter.Text(SemanticElementReference(SemanticElementName("accounts"), SemanticElementName("region")), SemanticTextFilterOperator.IN, listOf("North America"))
                    val plan = SemanticQueryFilter.Text(SemanticElementReference(SemanticElementName("daily_account_activity"), SemanticElementName("plan")), SemanticTextFilterOperator.IN, listOf("Team"))
                    for ((selectionName, filters) in listOf("region" to listOf(region), "region-plan" to listOf(region, plan))) {
                        for ((field, snapshot) in listOf("active_users" to manifest, "core_actions" to editedManifest)) {
                            val label = "foreign-$selectionName-$field"
                            val rendered = render(renderer, snapshot, lastDay.copy(filters = filters))
                            put(label, rendered.metricColumn())
                            Files.writeString(queries.resolve("$label.sql"), rendered.sql)
                            val planPredicate = if (selectionName == "region-plan") " AND activity.plan = 'Team'" else ""
                            Files.writeString(queries.resolve("$label-direct.sql"), "SELECT count(DISTINCT activity.account_id) active_accounts FROM acme.daily_account_activity activity JOIN acme.accounts accounts USING (account_id) WHERE activity.activity_date BETWEEN current_date - 7 AND current_date - 1 AND activity.$field >= 1 AND accounts.region = 'North America'$planPredicate")
                        }
                    }
                }
            }
            val dashboardQueries = if (dataset == "acme-cloud@3") writeDashboardQueries(definitions, manifest, renderer, queries) else emptyMap()
            if (dataset == "acme-cloud@3") writePaidCohortOracleQueries(definitions, queries)
            Files.writeString(queries.resolve("time-dimension-types.sql"), timeDimensionColumnTypesQuery(definitions))
            val seoulManifest = manifest.copy(reportingTimeZone = ZoneId.of("Asia/Seoul"))
            assertEquals(planRendered.sql, render(renderer, seoulManifest, planQuery).sql, "DATE time dimensions read the same in every Reporting time zone")
            Files.writeString(queries.resolve("current-last-day.sql"), "SELECT max(activity_date) = current_date - 1 FROM acme.daily_account_activity")
            Files.writeString(queries.resolve("future-last-day.sql"), "SELECT max(activity_date) = current_date + 29 FROM main.future_daily_account_activity")
            javaFixture(driver, datasetRoot.resolve("ExecuteRenderedMetrics.java"), listOf(database.toString(), queries.toString()), temporary.resolve("results.bin"), temporary.resolve("execute.err"))
            val results = readResults(temporary.resolve("results.bin"))
            for ((name, expected) in expectedValues) {
                val now = results.getValue("current-$name").singleValue(metricColumns.getValue("current-$name"))
                val future = results.getValue("future-$name").singleValue(metricColumns.getValue("future-$name"))
                assertNumber(expected, now, name)
                assertNumber(expected, future, "$name in 30 days")
                println("sample_metric_equality metric=$name current=$now future=$future expected=$expected")
            }
            val timeColumnTypes = results.getValue("time-dimension-types").rows
            assertEquals(definitions.models.sumOf { model -> model.dimensions.count { it.type == SemanticDimensionKind.TIME } } + 1, timeColumnTypes.size)
            assertEquals(setOf("DATE"), timeColumnTypes.map { it[1] }.toSet(), timeColumnTypes.toString())
            assertEquals("true", results.getValue("current-last-day").rows.single().single())
            assertEquals("true", results.getValue("future-last-day").rows.single().single())
            assertNumber(expectedValues.getValue("active_accounts"), results.getValue("saved-active-before-definition-edit").singleValue(savedResult.metricColumn()), "Saved result before definition edit")
            val coreAccounts = results.getValue("refreshed-active").singleValue(savedResult.metricColumn())
            assertNumber(expectations["coreActionActiveAccounts"].asText().toBigDecimal(), coreAccounts, "Core action edit")
            val drop = 1 - coreAccounts.toDouble() / expectedValues.getValue("active_accounts").toDouble()
            assertTrue(drop in 0.25..0.35, "Active account drop=$drop")
            for ((before, after) in dashboardLabels) {
                val previous = results.getValue(before).timeSeries()
                val rerun = results.getValue(after).timeSeries()
                assertEquals(previous.keys, rerun.keys)
                assertTrue(previous.isNotEmpty())
                previous.forEach { (time, value) -> assertTrue(1 - rerun.getValue(time).toDouble() / value.toDouble() in 0.25..0.35, "$before at $time") }
            }
            val plans = results.getValue("by-plan")
            val planColumn = plans.columns.indexOf(planRendered.outputColumns.single { (it.source as? SemanticMetricFlowOutputColumnSource.GroupBy)?.groupBy is SemanticGroupBy.Dimension }.sqlName)
            val timeColumn = plans.columns.indexOf(planRendered.outputColumns.single { (it.source as? SemanticMetricFlowOutputColumnSource.GroupBy)?.groupBy is SemanticGroupBy.MetricTime }.sqlName)
            val countColumn = plans.columns.indexOf(planRendered.metricColumn())
            assertEquals(setOf("Starter", "Team", "Business"), plans.rows.map { it[planColumn] }.toSet())
            assertEquals(plans.rows.filter { it[planColumn] == "Team" }, results.getValue("by-plan-selected").rows, "Local Plan filter keeps the same cumulative Team counts")
            val weekly = results.getValue(dashboardLabels.first { it.first.contains("product") }.first).timeSeries()
            val grouped = plans.rows.groupBy { requireNotNull(it[timeColumn]) }
            assertEquals(weekly.keys, grouped.keys)
            grouped.forEach { (time, rows) ->
                assertEquals(3, rows.size)
                assertNumber(weekly.getValue(time), rows.sumOf { requireNotNull(it[countColumn]).toBigDecimal() }, "Plan fanout at $time")
            }
            val direct = results.getValue("direct-by-plan").rows.associate { requireNotNull(it[0]) to requireNotNull(it[1]).toBigDecimal() }
            assertEquals(direct, grouped.getValue(grouped.keys.max()).associate { requireNotNull(it[planColumn]) to requireNotNull(it[countColumn]).toBigDecimal() })
            foreignActiveColumns.forEach { (label, column) ->
                val expected = results.getValue("$label-direct").singleValue("active_accounts")
                val actual = results.getValue(label).singleValue(column)
                assertNumber(expected, actual, label)
                println("sample_cumulative_foreign_filter label=$label expected=$expected actual=$actual")
            }
            verifyDashboardQueries(dashboardQueries, results, expectedValues)
            println("sample_metric_edit login=${expectedValues.getValue("active_accounts")} core=$coreAccounts drop=$drop dashboards=${dashboardLabels.size} plans=$direct")
        } finally { temporary.toFile().deleteRecursively() }
    }

    private data class DashboardQueryEvidence(val columns: List<String>, val groupedColumns: List<String>, val metricColumns: Map<String, String>, val failure: String?)

    private fun writePaidCohortOracleQueries(definitions: SampleDefinitions, destination: Path) {
        val range = definitions.dashboards.getValue("product-engagement").getValue("variables").jsonObject.getValue("cohort_date_range").jsonObject.getValue("initial").jsonObject
        val start = LocalDate.parse(range.getValue("start").jsonPrimitive.content)
        val end = LocalDate.parse(range.getValue("end").jsonPrimitive.content)
        val observedThrough = "least(DATE '$end', (SELECT max(activity_date) FROM acme.daily_account_activity))"
        val convertedInRange = "trials.converted_at BETWEEN DATE '$start' AND $observedThrough"
        Files.writeString(destination.resolve("paid-cohort-denominator.sql"), """
            SELECT strftime(trials.converted_at, '%Y-%m-01') AS cohort_month, count(DISTINCT trials.account_id) AS cohort_accounts
            FROM acme.trials trials WHERE $convertedInRange GROUP BY 1 ORDER BY 1
        """.trimIndent())
        Files.writeString(destination.resolve("paid-cohort-monthly-active.sql"), """
            SELECT strftime(trials.converted_at, '%Y-%m-01') AS cohort_month,
                   strftime(activity.activity_date, '%Y-%m-01') AS observation_month,
                   count(DISTINCT activity.account_id) AS active_accounts
            FROM acme.trials trials JOIN acme.daily_account_activity activity USING (account_id)
            WHERE $convertedInRange AND activity.active_users > 0
              AND activity.activity_date >= trials.converted_at AND activity.activity_date <= $observedThrough
            GROUP BY 1, 2 ORDER BY 1, 2
        """.trimIndent())
        Files.writeString(destination.resolve("paid-cohort-crossing-month.sql"), """
            SELECT count(DISTINCT trials.account_id) AS crossing_accounts
            FROM acme.trials trials JOIN acme.accounts accounts USING (account_id)
            WHERE $convertedInRange AND strftime(trials.converted_at, '%Y-%m') <> strftime(accounts.created_at, '%Y-%m')
        """.trimIndent())
    }

    private fun writeDashboardQueries(
        definitions: SampleDefinitions,
        manifest: SemanticMetricFlowManifest,
        renderer: MetricFlowSemanticRenderer,
        destination: Path,
    ): Map<String, DashboardQueryEvidence> = buildMap {
        for ((dashboardName, definition) in definitions.dashboards) {
            DashboardDefinitionDecoder.decode(definition)
            val initial = definition.getValue("variables").jsonObject.mapValues { it.value.jsonObject.getValue("initial") }
            for (filtered in listOf(false, true)) {
                val variables = if (filtered) initial + mapOf("selectedRegion" to JsonArray(listOf(JsonPrimitive("North America"))), "selectedPlan" to JsonArray(listOf(JsonPrimitive("Team")))) else initial
                for ((queryName, raw) in definition.getValue("queries").jsonObject) {
                    val named = raw.jsonObject
                    val label = "seed-$dashboardName-$queryName" + if (filtered) "-filtered" else ""
                    if (named.getValue("type").jsonPrimitive.content == "SQL") {
                        var sql = named.getValue("sql").jsonPrimitive.content
                        for ((alias, binding) in named.getValue("bindings").jsonObject) {
                            val reference = binding.jsonObject
                            assertEquals("VARIABLE", reference.getValue("kind").jsonPrimitive.content)
                            val variableName = reference.getValue("name").jsonPrimitive.content
                            assertEquals("DATE_RANGE", definition.getValue("variables").jsonObject.getValue(variableName).jsonObject.getValue("type").jsonPrimitive.content)
                            assertTrue(reference.getValue("endpoint").jsonPrimitive.content in setOf("start", "end"))
                            val value = variables.getValue(variableName).jsonObject.getValue(reference.getValue("endpoint").jsonPrimitive.content).jsonPrimitive.content
                            sql = sql.replace("{{" + alias + "}}", "'" + value.replace("'", "''") + "'")
                        }
                        Files.writeString(destination.resolve("$label.sql"), sql)
                        put(label, DashboardQueryEvidence(named.getValue("columns").jsonArray.map { it.jsonPrimitive.content }, emptyList(), emptyMap(), null))
                    } else {
                        val query = named.getValue("query").jsonObject
                        val filters = query.getValue("filters").jsonArray.mapNotNull { item ->
                            val filter = item.jsonObject
                            val value = filter.getValue("value").let { source ->
                                if (source is JsonObject && source["kind"]?.jsonPrimitive?.content == "VARIABLE") variables.getValue(source.getValue("name").jsonPrimitive.content) else source
                            }
                            if (value is JsonNull) {
                                assertEquals("OMIT", filter.getValue("emptyBehavior").jsonPrimitive.content)
                                null
                            } else JsonObject(filter.filterKeys { it != "value" && it != "emptyBehavior" } + ("values" to if (value is JsonArray) value else JsonArray(listOf(value))))
                        }
                        val range = variables.getValue(query.getValue("metricTimeRange").jsonObject.getValue("name").jsonPrimitive.content).jsonObject
                        val resolved = JsonObject(query + ("filters" to JsonArray(filters)) + ("metricTimeRange" to buildJsonObject {
                            for (endpoint in listOf("start", "end")) put(endpoint, buildJsonObject { put("kind", "DATE"); put("value", range.getValue(endpoint)) })
                        }))
                        val request = seedSemanticQuery(resolved)
                        val metrics = request.metrics.map { name -> manifest.metrics.entries.single { it.value.name == name }.key }
                        val renderResult = renderer.render(manifest, request, metrics)
                        if (renderResult !is SemanticMetricFlowRenderResult.Rendered) { put(label, DashboardQueryEvidence(emptyList(), emptyList(), emptyMap(), "$label: $renderResult")); continue }
                        val rendered = renderResult
                        Files.writeString(destination.resolve("$label.sql"), rendered.sql)
                        val grouped = rendered.outputColumns.filter { it.source is SemanticMetricFlowOutputColumnSource.GroupBy }.map { it.sqlName }
                        val metricColumns = rendered.outputColumns.mapNotNull { column ->
                            (column.source as? SemanticMetricFlowOutputColumnSource.Metric)?.let { source -> manifest.metrics.getValue(source.metricId).name.value to column.sqlName }
                        }.toMap()
                        put(label, DashboardQueryEvidence(rendered.outputColumns.map { it.sqlName }, grouped, metricColumns, null))
                    }
                }
            }
        }
        assertEquals(38, size)
    }

    private fun seedSemanticQuery(query: JsonObject): SemanticQuerySpec {
        fun groupBy(raw: JsonElement): SemanticGroupBy {
            val value = raw.jsonObject
            return when (value.getValue("kind").jsonPrimitive.content) {
                "METRIC_TIME" -> SemanticGroupBy.MetricTime(SemanticTimeGranularity.valueOf(value.getValue("granularity").jsonPrimitive.content))
                "DIMENSION" -> SemanticGroupBy.Dimension(SemanticElementName(value.getValue("semanticModelName").jsonPrimitive.content), SemanticElementName(value.getValue("name").jsonPrimitive.content), value["granularity"]?.jsonPrimitive?.content?.let(SemanticTimeGranularity::valueOf))
                else -> error("Sample seed uses an unsupported group-by")
            }
        }
        val range = query.getValue("metricTimeRange").jsonObject
        return SemanticQuerySpec(
            query.getValue("metrics").jsonArray.map { SemanticElementName(it.jsonPrimitive.content) },
            query.getValue("groupBys").jsonArray.map(::groupBy),
            SemanticMetricTimeRange(SemanticTimeRangeEndpoint.Date(LocalDate.parse(range.getValue("start").jsonObject.getValue("value").jsonPrimitive.content)), SemanticTimeRangeEndpoint.Date(LocalDate.parse(range.getValue("end").jsonObject.getValue("value").jsonPrimitive.content))),
            query.getValue("filters").jsonArray.map { raw ->
                val filter = raw.jsonObject
                assertEquals("TEXT", filter.getValue("kind").jsonPrimitive.content)
                val dimension = filter.getValue("dimension").jsonObject
                SemanticQueryFilter.Text(SemanticElementReference(SemanticElementName(dimension.getValue("semanticModelName").jsonPrimitive.content), SemanticElementName(dimension.getValue("name").jsonPrimitive.content)), SemanticTextFilterOperator.valueOf(filter.getValue("operator").jsonPrimitive.content), filter.getValue("values").jsonArray.map { it.jsonPrimitive.content })
            },
            query.getValue("orderBy").jsonArray.map { raw ->
                val order = raw.jsonObject
                val target = order.getValue("target").jsonObject
                assertEquals("GROUP_BY", target.getValue("kind").jsonPrimitive.content)
                SemanticQueryOrder(SemanticQueryOrderTarget.GroupBy(groupBy(target.getValue("groupBy"))), SemanticQueryOrderDirection.valueOf(order.getValue("direction").jsonPrimitive.content))
            },
            query.getValue("rowLimit").jsonPrimitive.int,
        )
    }

    private fun verifyDashboardQueries(evidence: Map<String, DashboardQueryEvidence>, results: Map<String, Result>, expected: Map<String, BigDecimal>) {
        if (evidence.isEmpty()) return
        for ((label, query) in evidence) {
            if (query.failure != null) continue
            val result = results.getValue(label)
            assertEquals(query.columns, result.columns, "$label output columns")
            if (!label.endsWith("-filtered")) assertTrue(result.rows.isNotEmpty(), "$label has no sample rows")
            if (query.groupedColumns.isNotEmpty()) {
                val positions = query.groupedColumns.map(result.columns::indexOf)
                val keys = result.rows.map { row -> positions.joinToString("\u0000") { position -> row[position].orEmpty() } }
                assertEquals(keys.sorted(), keys, "$label group ordering")
            }
            println("sample_dashboard_query label=$label rows=${result.rows.size} columns=${result.columns}")
        }
        fun result(dashboard: String, query: String) = results.getValue("seed-$dashboard-$query")
        fun sum(dashboard: String, query: String, metric: String) = result(dashboard, query).let { rows ->
            val column = evidence.getValue("seed-$dashboard-$query").metricColumns.getValue(metric)
            rows.rows.sumOf { requireNotNull(it[rows.columns.indexOf(column)]).toBigDecimal() }
        }
        val mrrTotal = sum("revenue-movements", "mrr_total", "mrr")
        assertNumber(expected.getValue("mrr"), mrrTotal, "MRR grand total uses last snapshot")
        println("sample_snapshot_total metric=mrr expected=${expected.getValue("mrr")} actual=$mrrTotal")
        assertNumber(expected.getValue("mrr"), sum("revenue-movements", "mrr_by_region", "mrr"), "MRR region total")
        assertNumber(expected.getValue("mrr"), sum("customer-segments", "industry_mrr", "mrr"), "MRR account dimension join")
        assertNumber(expected.getValue("paid_accounts"), sum("customer-segments", "segment_accounts", "paid_accounts"), "Paid account heatmap join")
        assertNumber(expected.getValue("paid_accounts"), sum("customer-segments", "channel_scorecard", "paid_accounts"), "Channel scorecard join")
        val bridge = result("revenue-movements", "bridge")
        fun bridgeValue(field: String) = requireNotNull(bridge.rows.single()[bridge.columns.indexOf(field)]).toBigDecimal()
        assertNumber(bridgeValue("ending_mrr"), bridgeValue("starting_mrr") + bridgeValue("new_mrr") + bridgeValue("expansion_mrr") - bridgeValue("contraction_mrr") - bridgeValue("churned_mrr"), "Snapshot bridge")
        assertEquals(result("revenue-movements", "bridge"), results.getValue("seed-revenue-movements-bridge-filtered"), "SQL bridge takes date range only")
        assertEquals(result("revenue-movements", "top_accounts"), results.getValue("seed-revenue-movements-top_accounts-filtered"), "SQL table takes date range only")
        assertEquals(result("product-engagement", "cohort_activity"), results.getValue("seed-product-engagement-cohort_activity-filtered"), "SQL cohorts take date range only")
        val cohort = result("product-engagement", "cohort_activity")
        val retention = cohort.columns.indexOf("activity_retention")
        val denominators = results.getValue("paid-cohort-denominator").rows.associate { requireNotNull(it[0]) to requireNotNull(it[1]).toBigDecimal() }
        val activeByMonth = results.getValue("paid-cohort-monthly-active").rows.associate { (requireNotNull(it[0]) to requireNotNull(it[1])) to requireNotNull(it[2]).toBigDecimal() }
        assertTrue(results.getValue("paid-cohort-crossing-month").singleValue("crossing_accounts") > BigDecimal.ZERO, "Actual sample conversions cross signup month boundaries")
        assertEquals(denominators.keys, cohort.rows.map { requireNotNull(it[cohort.columns.indexOf("cohort_month")]) }.toSet(), "Cohorts group conversion dates within the rolling twelve-month range")
        assertEquals(denominators.size * 12, cohort.rows.size)
        assertEquals((0..11).map(Int::toString).toSet(), cohort.rows.map { it[cohort.columns.indexOf("months_since_conversion")] }.toSet())
        for (row in cohort.rows) {
            val cohortMonth = requireNotNull(row[cohort.columns.indexOf("cohort_month")])
            val age = requireNotNull(row[cohort.columns.indexOf("months_since_conversion")]).toLong()
            val observationMonth = YearMonth.from(LocalDate.parse(cohortMonth)).plusMonths(age)
            val observedThrough = LocalDate.parse(requireNotNull(row[cohort.columns.indexOf("observed_through")]))
            val denominator = denominators.getValue(cohortMonth)
            assertNumber(denominator, requireNotNull(row[cohort.columns.indexOf("cohort_accounts")]).toBigDecimal(), "$cohortMonth paid conversion denominator")
            if (observationMonth.atEndOfMonth() <= observedThrough) {
                val active = activeByMonth[cohortMonth to observationMonth.atDay(1).toString()] ?: BigDecimal.ZERO
                assertNumber(active, requireNotNull(row[cohort.columns.indexOf("active_accounts")]).toBigDecimal(), "$cohortMonth age=$age paid monthly active numerator")
                assertNumber(active, requireNotNull(row[retention]).toBigDecimal().multiply(denominator), "$cohortMonth age=$age activity retention")
            } else {
                assertNull(row[cohort.columns.indexOf("active_accounts")], "$cohortMonth age=$age incomplete or future numerator")
                assertNull(row[retention], "$cohortMonth age=$age incomplete or future retention")
            }
        }
        assertTrue(cohort.rows.any { it[retention] == null }, "Future cohort cells remain unobserved")
        assertTrue(cohort.rows.filter { it[retention] != null }.all { requireNotNull(it[retention]).toDouble() in 0.0..1.0 }, "Retention denominator is the cohort account population")
        println("sample_paid_activity_cohorts months=${denominators.size} rows=${cohort.rows.size} denominator_total=${denominators.values.sumOf { it }} crossing_accounts=${results.getValue("paid-cohort-crossing-month").singleValue("crossing_accounts")}")
        val failures = evidence.values.mapNotNull { it.failure }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun manifest(definitions: SampleDefinitions, future: Boolean): SemanticMetricFlowManifest {
        val connector = definitions.models.first().sourceRelations.single().connectorId
        fun address(schema: String, relation: String) = CatalogPhysicalAddress(null, if (future) "main" else schema, if (future) "future_$relation" else relation, null)
        return SemanticMetricFlowManifest(connector, SemanticSqlDialect.DUCKDB,
            definitions.models.associate { model ->
                val relation = model.sourceRelations.single()
                model.name to SemanticMetricFlowModel(model, address(relation.schema, relation.relation), dateTimeDimensions(model))
            },
            definitions.metrics.mapIndexed { index, metric -> MetricId(kotlin.uuid.Uuid.parse("00000000-0000-7000-8000-${(index + 1).toString().padStart(12, '0')}")) to metric }.toMap(),
            definitions.timeSpine.timeSpines.map { SemanticMetricFlowTimeSpine(address(it.relation.schema, it.relation.relation), it.primaryColumn.name, it.primaryColumn.granularity) },
            ZoneId.of("UTC"))
    }

    // Every sample time dimension reads a plain DATE column; the test also reads those columns' DuckDB types.
    private fun dateTimeDimensions(model: SemanticModelDefinition): Map<SemanticElementName, SemanticMetricFlowTimeDimension> =
        model.dimensions.filter { it.type == SemanticDimensionKind.TIME }.associate { dimension ->
            dimension.name to SemanticMetricFlowTimeDimension(SemanticTimeKind.DATE, null, SemanticTimeOperand.Column(dimension.expression, false, null))
        }

    private fun timeDimensionColumnTypesQuery(definitions: SampleDefinitions): String =
        definitions.models.flatMap { model ->
            val relation = model.sourceRelations.single()
            model.dimensions.filter { it.type == SemanticDimensionKind.TIME }.map { dimension ->
                "SELECT '${relation.relation}.${dimension.expression}' AS time_column, data_type FROM information_schema.columns " +
                    "WHERE table_schema = '${relation.schema}' AND table_name = '${relation.relation}' AND column_name = '${dimension.expression}'"
            }
        }.joinToString(" UNION ALL ") + " UNION ALL SELECT 'time_spine_day.date_day', data_type FROM information_schema.columns " +
            "WHERE table_schema = 'acme' AND table_name = 'time_spine_day' AND column_name = 'date_day' ORDER BY time_column"

    private fun query(name: String, grain: SemanticTimeGranularity?, start: LocalDate, end: LocalDate, dimensions: List<SemanticGroupBy>): SemanticQuerySpec =
        SemanticQuerySpec(listOf(SemanticElementName(name)), (grain?.let { listOf(SemanticGroupBy.MetricTime(it)) } ?: emptyList()) + dimensions,
            SemanticMetricTimeRange(SemanticTimeRangeEndpoint.Date(start), SemanticTimeRangeEndpoint.Date(end)), emptyList(), emptyList(), 1000)

    private fun render(renderer: MetricFlowSemanticRenderer, manifest: SemanticMetricFlowManifest, query: SemanticQuerySpec): SemanticMetricFlowRenderResult.Rendered {
        val metric = manifest.metrics.entries.single { it.value.name == query.metrics.single() }.key
        return assertIs<SemanticMetricFlowRenderResult.Rendered>(renderer.render(manifest, query, listOf(metric)), query.toString())
    }

    private fun SemanticMetricFlowRenderResult.Rendered.metricColumn(): String = outputColumns.single { it.source is SemanticMetricFlowOutputColumnSource.Metric }.sqlName

    private fun javaFixture(driver: Path, source: Path, arguments: List<String>, output: Path, error: Path) {
        val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "--enable-native-access=ALL-UNNAMED", "-cp", driver.toString(), source.toString()) + arguments)
            .redirectOutput(output.toFile()).redirectError(error.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Fixture process did not finish within its deadline")
            assertEquals(0, process.exitValue(), Files.readString(error))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture process cleanup failed")
            }
        }
    }

    private data class Result(val columns: List<String>, val rows: List<List<String?>>) {
        fun singleValue(column: String): BigDecimal = requireNotNull(rows.single()[columns.indexOf(column)]).toBigDecimal()
        fun timeSeries(): Map<String, BigDecimal> = rows.associate { requireNotNull(it[0]) to requireNotNull(it[1]).toBigDecimal() }
    }

    private fun readResults(path: Path): Map<String, Result> = DataInputStream(Files.newInputStream(path)).use { input ->
        buildMap {
            repeat(input.readInt()) {
                val name = input.readUTF()
                val columns = List(input.readInt()) { input.readUTF() }
                val rows = List(input.readInt()) { List(columns.size) { if (input.readBoolean()) input.readUTF() else null } }
                put(name, Result(columns, rows))
            }
            assertEquals(-1, input.read())
        }
    }

    private fun assertNumber(expected: BigDecimal, actual: BigDecimal, message: String) {
        val tolerance = expected.abs().max(BigDecimal.ONE).multiply(BigDecimal("0.000000000001"))
        assertTrue(expected.subtract(actual).abs() <= tolerance, "$message expected=$expected actual=$actual")
    }
}
