package stream.kleeamp.mobile.net

import kotlinx.coroutines.Dispatchers
import stream.kleeamp.mobile.BuildConfig
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A URL with secrets removed: userinfo, query and fragment go, host and path
 * stay for debuggability. Provider stream/cover URLs bake in API keys,
 * tokens and even passwords, and the raw URL otherwise lands in UI error
 * strings, logcat and persisted rows.
 */
internal fun redactUrl(url: String): String {
    val clean = runCatching {
        url.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")
            ?.query(null)?.fragment(null)?.build().toString()
    }.getOrNull()
    return clean ?: url.substringBefore('?').substringBefore('#')
}

/**
 * One shared client. radio-browser.info asks every client to identify itself,
 * so the User-Agent is not optional politeness - it is how they rate-limit.
 */
object Http {
    const val USER_AGENT = "kleeamp-mobile/${BuildConfig.VERSION_NAME} (+https://cliamp.stream)"

    @Volatile private var cacheDir: java.io.File? = null

    /**
     * Stable per-device id for servers that key sessions by device. Jellyfin
     * 12 refuses password login without one (`request.DeviceId` is mandatory
     * in `AuthenticateNewSessionInternal`), and a fresh random id per launch
     * would pile up a new device row on every login - so this is read once
     * from ANDROID_ID rather than generated.
     */
    @Volatile var deviceId: String = "unknown-device"
        private set

    /** Called once from Application so artwork lookups can be cached on disk. */
    fun init(context: android.content.Context) {
        cacheDir = java.io.File(context.cacheDir, "http")
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )?.takeIf { it.isNotBlank() }?.let { deviceId = it }
        }
    }

    /**
     * The client that carries audio. Deliberately not [client].
     *
     * A radio stream is a response body that never ends, and the API client
     * carries a 30 second callTimeout, which covers the whole call including
     * reading that body. It is not currently firing, because Media3 drives its
     * own read loop, but a call timeout on a client used for endless bodies is
     * a trap waiting for a Media3 upgrade to spring. The response cache is
     * dropped for the same reason: there is nothing useful to cache in a live
     * stream, and it would only churn disk.
     *
     * readTimeout is the one that matters here. It is per-read, so it bounds a
     * silent stall without bounding the stream, and the Reconnector picks up
     * from there.
     */
    val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { cacheDir?.let { cache(okhttp3.Cache(it, 48L * 1024 * 1024)) } }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Cover art fetches (homepage scrapes, image downloads). Art is
     * decorative - a dead station homepage must never hold a pool worker for
     * half a minute while the rest of the list waits behind it - so this runs
     * much tighter timeouts than [client]. A miss just shows the plate and
     * retries later through the normal backoff.
     *
     * Station sites are random hosts; cap per-host connections so one dying
     * homepage cannot soak the whole art lane.
     */
    val artClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { cacheDir?.let { cache(okhttp3.Cache(it, 48L * 1024 * 1024)) } }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 16
                    maxRequestsPerHost = 2
                },
            )
            .build()
    }

    /**
     * Lyrics lookups (LRCLib). Lyrics are content the user explicitly opened,
     * not decoration, so this gets patient timeouts and its own lane: the art
     * client's tight budgets and per-host caps kept pushing lyric calls into
     * failures that needed manual retries.
     */
    val lyricsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun text(url: String): String = text(url, emptyMap())

    /** GET [url] with extra headers; used by providers that auth via headers. */
    suspend fun text(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        request(url, headers) { r -> r.body.string() }
    }

    /** GET returning the raw response for callers that need status/codec. */
    suspend fun call(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response =
        execute(url, headers, method = null, body = null)

    /** POST [body] as JSON with [headers]. */
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        request(url, headers, method = "POST", body = body) { r -> r.body.string() }
    }

    private suspend fun execute(
        url: String,
        headers: Map<String, String>,
        method: String?,
        body: String?,
    ): okhttp3.Response = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (method != null && body != null) {
            b.method(method, body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else if (method != null) {
            b.method(method, null)
        }
        client.newCall(b.build()).execute()
    }

    private suspend fun <T> request(
        url: String,
        headers: Map<String, String>,
        method: String? = null,
        body: String? = null,
        block: (okhttp3.Response) -> T,
    ): T {
        val r = execute(url, headers, method, body)
        return if (!r.isSuccessful) {
            r.close()
            error("HTTP ${r.code} for ${redactUrl(url)}")
        } else {
            r.use { block(it) }
        }
    }

    /** Fire-and-forget; used for the directory's click counter. */
    suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
