package uesugi.official.qq.onebot

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.math.max

/** 官方 OpenAPI 调用失败时抛出的异常，保留 HTTP 状态码便于上层映射。 */
class OfficialQqApiException(message: String, val status: Int? = null) : RuntimeException(message)

/** token 来源抽象，生产使用 app access token，测试可使用静态 token。 */
interface TokenProvider {
    suspend fun token(): String
}

/** 官方 app access token 提供器，自动缓存并在过期前刷新。 */
class AppAccessTokenProvider(
    private val qq: QqConfig,
    private val client: HttpClient = defaultHttpClient(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : TokenProvider {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var expiresAt: Long = 0

    /** 返回可用 token；距离过期不足 60 秒时主动刷新。 */
    override suspend fun token(): String = mutex.withLock {
        val current = cachedToken
        if (current != null && nowSeconds() < expiresAt - 60) {
            return@withLock current
        }
        val response: AppAccessTokenResponse = client.post("https://bots.qq.com/app/getAppAccessToken") {
            contentType(ContentType.Application.Json)
            setBody(AppAccessTokenRequest(qq.appId, qq.clientSecret))
        }.body()
        cachedToken = response.accessToken
        expiresAt = nowSeconds() + max(1, response.expiresIn)
        response.accessToken
    }
}

/** 测试用 token 提供器，避免单元测试依赖真实鉴权接口。 */
class StaticTokenProvider(private val value: String) : TokenProvider {
    override suspend fun token(): String = value
}

/** 官方 QQ OpenAPI 客户端，封装群消息发送、富媒体上传和撤回。 */
class OfficialQqApiClient(
    private val qq: QqConfig,
    private val tokenProvider: TokenProvider,
    private val client: HttpClient = defaultHttpClient(),
) {
    private val log = LoggerFactory.getLogger(OfficialQqApiClient::class.java)

    /** 上传群聊富媒体文件，返回发送 media 消息所需的 file_info。 */
    suspend fun uploadGroupFile(
        groupOpenid: String,
        fileType: Int,
        url: String?,
        fileData: String?
    ): UploadGroupFileResponse {
        val response = client.post("${qq.apiBaseUrl}/v2/groups/$groupOpenid/files") {
            officialAuth()
            contentType(ContentType.Application.Json)
            setBody(UploadGroupFileRequest(fileType = fileType, url = url, fileData = fileData))
        }
        ensureSuccess(response, "upload group file")
        return response.body()
    }

    /** 发送官方群消息；文本 msg_type=0，markdown msg_type=2，富媒体 msg_type=7。 */
    suspend fun sendGroupMessage(groupOpenid: String, request: SendGroupMessageRequest): SendGroupMessageResponse {
        val response = client.post("${qq.apiBaseUrl}/v2/groups/$groupOpenid/messages") {
            officialAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        ensureSuccess(response, "send group message")
        return response.body()
    }

    /** 撤回群消息；官方只允许在权限和时间窗口内撤回。 */
    suspend fun deleteGroupMessage(groupOpenid: String, officialMessageId: String) {
        val response = client.delete("${qq.apiBaseUrl}/v2/groups/$groupOpenid/messages/$officialMessageId") {
            officialAuth()
            contentType(ContentType.Application.Json)
        }
        ensureSuccess(response, "delete group message")
    }

    /** 给官方 OpenAPI 请求补 QQBot 鉴权头。 */
    private suspend fun HttpRequestBuilder.officialAuth() {
        header(HttpHeaders.Authorization, "QQBot ${tokenProvider.token()}")
    }

    /** 统一处理 HTTP 非 2xx 响应，并尽量提取官方错误码。 */
    private suspend fun ensureSuccess(response: HttpResponse, action: String) {
        val code = response.status.value
        if (code in 200..299) return
        val message = runCatching { response.body<OfficialErrorResponse>() }
            .map { "${it.code}: ${it.message}" }
            .getOrDefault(response.status.description)
        log.warn("Official QQ API failed during {}: HTTP {}, {}", action, code, message)
        throw OfficialQqApiException("$action failed: $message", code)
    }
}

/** 默认 HTTP client，生产代码和多数测试共享同一套 JSON 配置。 */
fun defaultHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(officialJson)
    }
}

/** 官方接口 JSON 配置：忽略未知字段，便于兼容官方字段增量。 */
val officialJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}
