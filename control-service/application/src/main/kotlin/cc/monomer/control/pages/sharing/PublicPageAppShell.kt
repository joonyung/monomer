package cc.monomer.control.pages.sharing

import cc.monomer.control.publicapi.ControlPublicApiProperties
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class PublicPageAppShell(private val properties: ControlPublicApiProperties, private val client: HttpClient) {
    @Autowired
    constructor(properties: ControlPublicApiProperties) : this(properties,
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build(),
    )

    suspend fun read(): String {
        val uri = URI(PublicPageHtml.origin(properties.webOrigin) + "/index.html")
        val body = AtomicReference<BoundedAppShellBody?>()
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET()
            .header("Accept", "text/html").build()
        val pending = client.sendAsync(request) { info ->
            val valid = info.statusCode() in 200..299 &&
                info.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().equals("text/html", true)
            BoundedAppShellBody(valid).also(body::set)
        }
        return try {
            val bytes = withTimeout(2_000) { pending.await().body() }
            val html = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            PublicPageHtml.validateShell(html)
            html
        } finally {
            body.get()?.cancel()
            pending.cancel(true)
        }
    }
}

internal class BoundedAppShellBody(private val accepted: Boolean) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun getBody(): CompletableFuture<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        if (!accepted || cancelled.get()) {
            value.cancel()
            result.completeExceptionally(IllegalStateException("Invalid App shell response"))
        } else value.request(1)
    }
    override fun onNext(items: List<ByteBuffer>) {
        if (cancelled.get()) return
        for (item in items) {
            if (item.remaining() > 256 * 1024 - bytes.size()) {
                cancel()
                result.completeExceptionally(IllegalStateException("App shell too large"))
                return
            }
            val chunk = ByteArray(item.remaining())
            item.get(chunk)
            bytes.write(chunk)
        }
        subscription?.request(1)
    }
    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
    fun cancel() { cancelled.set(true); subscription?.cancel() }
}
