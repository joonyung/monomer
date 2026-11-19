package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.model.AuditLogEntry

interface AuditLogRepository {
    fun insert(entry: AuditLogEntry)
}
