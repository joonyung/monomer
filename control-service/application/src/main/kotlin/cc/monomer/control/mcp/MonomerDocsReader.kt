package cc.monomer.control.mcp

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient

sealed interface MonomerDocsResult {
    data class Read(val markdown: String) : MonomerDocsResult
    data object NotFound : MonomerDocsResult
    data object TooLarge : MonomerDocsResult
    data object Unavailable : MonomerDocsResult
}

class MonomerDocsReader(origin: String) {
    private val originUri = URI(origin).also {
        require(it.host != null && it.userInfo == null && it.rawQuery == null && it.rawFragment == null)
        require(it.rawPath.isNullOrEmpty() || it.rawPath == "/")
        require(it.port == -1 || it.port in 1..65535)
        require(it.scheme == "https" || it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "[::1]"))
    }
    val agentApiReferenceUrl: String = originUri.resolve(AGENT_API_REFERENCE_PATH).toString()
    private val client = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(false).disableRetry(true)))
        .codecs { it.defaultCodecs().maxInMemorySize(MAXIMUM_BYTES) }
        .build()
    private val logger = LoggerFactory.getLogger(MonomerDocsReader::class.java)

    suspend fun read(path: String): MonomerDocsResult {
        require(validPath(path))
        val result = try {
            client.get().uri(originUri.resolve(path)).header("Accept", "text/markdown, text/plain")
                .exchangeToMono { response ->
                    when {
                        response.statusCode().value() == 404 -> Mono.just<MonomerDocsResult>(MonomerDocsResult.NotFound)
                        response.statusCode().value() != 200 -> Mono.just<MonomerDocsResult>(MonomerDocsResult.Unavailable)
                        response.headers().contentLength().orElse(0) > MAXIMUM_BYTES -> Mono.just<MonomerDocsResult>(MonomerDocsResult.TooLarge)
                        response.headers().contentType().orElse(null)?.let { "${it.type}/${it.subtype}" } !in setOf("text/plain", "text/markdown") -> Mono.just<MonomerDocsResult>(MonomerDocsResult.Unavailable)
                        else -> response.bodyToMono(ByteArray::class.java).defaultIfEmpty(byteArrayOf()).map<MonomerDocsResult> { bytes ->
                            val text = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes)).toString()
                            if (text.isBlank()) MonomerDocsResult.Unavailable else MonomerDocsResult.Read(text)
                        }
                    }
                }.timeout(Duration.ofSeconds(5)).awaitSingle()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DataBufferLimitException) {
            MonomerDocsResult.TooLarge
        } catch (_: Exception) {
            MonomerDocsResult.Unavailable
        }
        if (result == MonomerDocsResult.TooLarge || result == MonomerDocsResult.Unavailable) {
            logger.warn("Monomer documentation could not be read: {}", result.javaClass.simpleName)
        }
        return result
    }

    companion object {
        const val AGENT_API_REFERENCE_PATH = "/en/docs/reference/agent-api/"
        const val ROOT_PATH = "/en/llms.txt"
        const val MAXIMUM_BYTES = 65_536
        private val DOCUMENT_PATH = Regex("/en/(?:[a-z0-9]+(?:-[a-z0-9]+)*/)*(?:llms\\.txt|index\\.md)")
        fun validPath(path: String): Boolean = path.length <= 512 && (path == "/llms.txt" || DOCUMENT_PATH.matches(path))
    }
}
