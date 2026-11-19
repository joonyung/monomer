package cc.monomer.execution.application

import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ExecutionRunSnapshot

interface RunDispatchAssignmentCodec {
    fun canonicalizeTemplate(assignmentTemplate: BinaryValue): BinaryValue

    fun readSql(canonicalTemplate: BinaryValue, snapshot: ExecutionRunSnapshot): String?
}
