package uesugi.official.qq.onebot

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import uesugi.onebot.core.config.OneBotConfig
import uesugi.onebot.core.model.RawEvent
import uesugi.onebot.sdk.client.OneBotClient
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.sdk.message.buildMessage
import uesugi.onebot.sdk.message.text
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/** 本地集成测试：用真实 OneBotClient 连接桥接器暴露的 OneBot WS server。 */
class OneBotBridgeIntegrationTest {
    /** 验证 Erii 侧最关键路径：send_group_msg 成功后能收到合成的 message_sent 事件。 */
    @Test
    fun oneBotClientCanSendAndReceiveMessageSent() = runTest {
        val port = availablePort()
        val config = BridgeConfig(
            qq = QqConfig(
                "app",
                "secret",
                "https://api.sgroup.qq.com",
                "wss://api.sgroup.qq.com/websocket/",
                1,
                listOf(0, 1)
            ),
            onebot = OneBotBridgeConfig("127.0.0.1", port, null, 10000, "Erii"),
            idMap = IdMapConfig(groups = mapOf(10001L to "group-openid"), members = mapOf(20001L to "member-openid")),
        )
        val apiClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(officialJson) }
            engine {
                addHandler {
                    respond(
                        content = """{"id":"sent-official-id","timestamp":123}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val ids = IdMapper(config.idMap)
        val store = MessageStore()
        val converter = MessageConverter(config, ids, store)
        val bridge = OneBotBridge(
            config,
            ids,
            store,
            OfficialQqApiClient(config.qq, StaticTokenProvider("token"), apiClient),
            converter
        )
        val client = OneBotClient(
            OneBotConfig(
                wsForwardClientEnable = true,
                wsForwardClientUseUniversal = true,
                wsForwardClientUrl = "ws://127.0.0.1:$port",
            ),
        )
        val event = CompletableDeferred<RawEvent>()

        try {
            bridge.start()
            client.onEvent("message_sent") {
                if (it is RawEvent) event.complete(it)
            }
            client.start()

            val messageId = client.sendGroupMsg(10001, buildMessage { text("hello") })
            val sent = withTimeout(5000) { event.await() }

            assertEquals(1, messageId)
            assertEquals("message_sent", sent.postType)
        } finally {
            client.stop()
            bridge.stop()
        }
    }

    /** 为每次测试动态分配空闲端口，降低本机端口冲突概率。 */
    private fun availablePort(): Int =
        ServerSocket(0).use { it.localPort }
}
