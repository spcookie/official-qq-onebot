package uesugi.official.qq.onebot

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 官方 QQ OpenAPI 客户端测试，使用 Ktor MockEngine 避免访问真实网络。 */
class OfficialQqApiClientTest {
    /** 发送消息时必须带 QQBot 鉴权头，并能解析官方返回的消息 ID。 */
    @Test
    fun sendsGroupMessageWithQqBotAuth() = runTest {
        var authHeader: String? = null
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(officialJson) }
            engine {
                addHandler { request ->
                    authHeader = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"id":"msg-id","timestamp":123}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val api = OfficialQqApiClient(
            QqConfig(
                "app",
                "secret",
                "https://api.sgroup.qq.com",
                "wss://api.sgroup.qq.com/websocket/",
                1,
                listOf(0, 1)
            ),
            StaticTokenProvider("token"),
            client,
        )

        val response = api.sendGroupMessage("group-openid", SendGroupMessageRequest(content = "hello", msgType = 0))

        assertEquals("msg-id", response.id)
        assertEquals("QQBot token", authHeader)
    }

    /** markdown 消息请求体必须符合官方格式：msg_type=2 且带 markdown.content。 */
    @Test
    fun serializesMarkdownMessageBody() {
        val body = officialJson.encodeToString(
            SendGroupMessageRequest.serializer(),
            SendGroupMessageRequest(
                msgType = 2,
                markdown = MarkdownInfo("# title"),
                msgSeq = 1,
            ),
        )

        assertTrue(body.contains(""""msg_type":2"""))
        assertTrue(body.contains(""""markdown":{"content":"# title"}"""))
    }
}
