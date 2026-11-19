package cc.monomer.control

import cc.monomer.control.publicapi.ControlPublicApiProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(
    ControlRuntimeProperties::class,
    ControlObjectStorageProperties::class,
    ControlPublicApiProperties::class,
)
class ControlApplication

fun main(arguments: Array<String>) {
    runApplication<ControlApplication>(*arguments)
}
