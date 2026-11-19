package cc.monomer.pages.adapter.outbound.messaging

/**
 * Per-process instance id used by the PG event bus to skip self-origin NOTIFY events
 * (the same-instance subscriber already received them via the local afterCommit fast-path).
 *
 * A fresh random UUID per process — not the hostname — so two instances on the same host
 * still dedup correctly. (The existing [cc.monomer.pages.domain.dashboard.execution.InstanceIdentifier]
 * is hostname-based and serves a different purpose: tagging which host owns a DashboardRun.)
 */
data class InstanceIdHolder(val value: String)
