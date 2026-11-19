package cc.monomer.control.auth.inbound.jwks

import cc.monomer.identity.outbound.jwt.JwtKeySource
import cc.monomer.identity.outbound.jwt.publicJwk
import com.nimbusds.jose.jwk.JWKSet
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class JwksController(private val keySource: JwtKeySource) {

    @GetMapping("/.well-known/jwks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): Map<String, Any> = JWKSet(listOf(keySource.publicJwk())).toJSONObject(true)
}
