package cc.monomer.identity.outbound.oauth

import cc.monomer.identity.api.OAuthClient
import cc.monomer.identity.application.agent.OAuthClientResolver
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class HttpsOAuthClientResolver(private val registeredClients: Map<String, OAuthClient>) : OAuthClientResolver {
    private val http = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!dnsCapacity.tryAcquire()) throw UnknownHostException("OAuth metadata DNS is unavailable")
                val pending = dnsExecutor.submit<List<InetAddress>> {
                    try {
                        val addresses = InetAddress.getAllByName(hostname).toList()
                        if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("OAuth metadata host is unavailable")
                        addresses
                    } finally { dnsCapacity.release() }
                }
                return try { pending.get(2, TimeUnit.SECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { throw UnknownHostException("OAuth metadata DNS timed out") }
                catch (_: java.util.concurrent.ExecutionException) { throw UnknownHostException("OAuth metadata host is unavailable") }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw UnknownHostException("OAuth metadata DNS was interrupted")
                }
            }
        })
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun resolve(clientId: String): OAuthClient? {
        registeredClients[clientId]?.let { return it }
        val uri = uri(clientId) ?: return null
        if (uri.scheme != "https" || !namedMetadataHost(uri.host) || uri.userInfo != null || uri.fragment != null || uri.rawQuery != null || uri.port !in setOf(-1,443)) return null
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(clientId).header("Accept", "application/json").build()).execute().use { response ->
                    if (response.code != 200 || response.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > 65536) return@use null
                    val bytes = body.byteStream().readNBytes(65537)
                    if (bytes.size > 65536) return@use null
                    parseMetadata(clientId, bytes.toString(Charsets.UTF_8))
                }
            } catch (_: java.io.IOException) { null }
              catch (_: com.nimbusds.oauth2.sdk.ParseException) { null }
              catch (_: IllegalArgumentException) { null }
        }
    }

    internal fun parseMetadata(clientId: String, body: String): OAuthClient? = try {
        val json = JSONObjectUtils.parse(body)
        if (json["client_id"] != clientId || json["token_endpoint_auth_method"] != "none") return null
        val metadata = ClientMetadata.parse(json)
        val grants = metadata.grantTypes?.map { it.value }?.toSet() ?: setOf("authorization_code")
        if ("authorization_code" !in grants) return null
        if (metadata.responseTypes?.any { it.toString() != "code" } == true) return null
        val redirects = metadata.redirectionURIs?.map { it.toString() }?.toSet() ?: return null
        if (redirects.isEmpty() || redirects.size > 32 || redirects.any { !safeRedirect(it) }) return null
        val name = metadata.name?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        OAuthClient(clientId, name, redirects)
    } catch (_: com.nimbusds.oauth2.sdk.ParseException) { null }
      catch (_: IllegalArgumentException) { null }

    override fun acceptsRedirect(client: OAuthClient, redirectUri: String): Boolean {
        if (!safeRedirect(redirectUri)) return false
        if (redirectUri in client.redirectUris) return true
        val candidate = uri(redirectUri) ?: return false
        if (candidate.scheme != "http" || candidate.host !in setOf("127.0.0.1", "[::1]", "localhost") || candidate.port !in 1..65535) return false
        return client.redirectUris.any { registered ->
            val allowed = uri(registered)
            allowed != null && allowed.scheme == candidate.scheme && allowed.host == candidate.host &&
                allowed.rawPath == candidate.rawPath && allowed.rawQuery == candidate.rawQuery && allowed.userInfo == null && allowed.fragment == null
        }
    }

    private fun namedMetadataHost(host: String?): Boolean {
        val labels = host?.split('.') ?: return false
        return labels.size >= 2 && labels.all { DNS_LABEL.matches(it) } &&
            labels.last().firstOrNull()?.isLetter() == true
    }

    private fun safeRedirect(value: String): Boolean {
        val target = uri(value) ?: return false
        if (target.host == null || target.userInfo != null || target.fragment != null) return false
        return target.scheme == "https" || (target.scheme == "http" && target.host in setOf("127.0.0.1", "[::1]", "localhost"))
    }
    private fun uri(value: String): URI? = try { URI(value) } catch (_: java.net.URISyntaxException) { null }

    companion object {
        private val DNS_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        private val dnsCapacity = java.util.concurrent.Semaphore(8)
        private val dnsExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

        internal fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            if (bytes.size == 16) return bytes[0] in 0x20..0x3f && !(bytes[0] == 0x20 && bytes[1] == 2) &&
                !(bytes[0] == 0x20 && bytes[1] == 1 && (bytes[2] == 0 || (bytes[2] == 0x0d && bytes[3] == 0xb8)))
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return a !in setOf(0,10,127) && a < 224 && !(a == 100 && b in 64..127) &&
                !(a == 169 && b == 254) && !(a == 172 && b in 16..31) && !(a == 192 && (b == 168 || b == 0 || b == 2)) &&
                !(a == 198 && (b in 18..19 || (b == 51 && c == 100))) && !(a == 203 && b == 0 && c == 113)
        }
    }
}
