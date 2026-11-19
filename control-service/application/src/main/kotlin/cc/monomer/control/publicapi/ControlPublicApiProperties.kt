package cc.monomer.control.publicapi

import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("monomer.control.public-api")
data class ControlPublicApiProperties(
    val webOrigin: URI,
) {
    init {
        require(webOrigin.isAbsolute)
        require(webOrigin.scheme in setOf("http", "https"))
        require(webOrigin.path.isEmpty() || webOrigin.path == "/")
        require(webOrigin.query == null && webOrigin.fragment == null)
    }
}
