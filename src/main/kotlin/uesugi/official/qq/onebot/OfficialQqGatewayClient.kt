package uesugi.official.qq.onebot

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory

/** 网关事件的内部类型，先按桥接器需要的群聊事件做最小封装。 */
sealed interface OfficialGatewayEvent {
    /** 官方群消息事件。 */
    data class GroupMessage(val type: String, val event: OfficialGroupMessageEvent) : OfficialGatewayEvent

    /** 官方群成员进出事件。 */
    data class GroupMember(val type: String, val event: OfficialGroupMemberEvent) : OfficialGatewayEvent

    /** 官方群管理事件：机器人进退群、群消息接收开关。 */
    data class GroupManagement(val type: String, val event: OfficialGroupMemberEvent) : OfficialGatewayEvent
}

/** 官方 QQ Gateway WebSocket 客户端，负责登录、心跳、重连和事件分发。 */
class OfficialQqGatewayClient(
    private val qq: QqConfig,
    private val tokenProvider: TokenProvider,
    private val onEvent: suspend (OfficialGatewayEvent) -> Unit,
    private val client: HttpClient = HttpClient { install(WebSockets) },
) {
    private val log = LoggerFactory.getLogger(OfficialQqGatewayClient::class.java)
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            log.error("Gateway coroutine failed", e)
        },
    )
    private var runner: Job? = null
    private var lastSeq: Int? = null
    private var sessionId: String? = null

    /** 启动后台网关连接循环。 */
    fun start() {
        if (runner != null) return
        log.info("Starting official QQ gateway client")
        runner = scope.launch { runLoop() }
    }

    /** 停止网关连接和内部协程。 */
    fun stop() {
        log.info("Stopping official QQ gateway client")
        runner?.cancel()
        runner = null
        scope.cancel()
    }

    /** 长连接主循环：断开后等待 3 秒重连。 */
    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                client.webSocket(qq.gatewayUrl) {
                    log.info("Connected to official QQ gateway")
                    var heartbeatJob: Job? = null
                    try {
                        // 官方所有网关消息都通过 GatewayPayload 外壳承载。
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val payload = officialJson.decodeFromString(GatewayPayload.serializer(), frame.readText())
                            if (payload.s != null) lastSeq = payload.s
                            when (payload.op) {
                                0 -> handleDispatch(payload)
                                7 -> error("Gateway requested reconnect")
                                9 -> {
                                    log.warn("Gateway reported invalid session; next connection will identify again")
                                    sessionId = null
                                    error("Invalid session")
                                }

                                10 -> {
                                    val hello = officialJson.decodeFromJsonElement<HelloData>(payload.d!!)
                                    heartbeatJob?.cancel()
                                    // 按官方下发的 heartbeat_interval 周期发送 op=1 心跳。
                                    heartbeatJob = launch {
                                        while (isActive) {
                                            delay(hello.heartbeatInterval)
                                            outgoing.send(
                                                Frame.Text(
                                                    officialJson.encodeToString(
                                                        GatewayPayload.serializer(),
                                                        GatewayPayload(op = 1, d = lastSeq?.let { JsonPrimitive(it) })
                                                    )
                                                )
                                            )
                                        }
                                    }
                                    identifyOrResume()
                                }

                                11 -> log.debug("Official QQ heartbeat acknowledged")
                                else -> log.debug("Ignoring gateway opcode {}", payload.op)
                            }
                        }
                    } finally {
                        heartbeatJob?.cancel()
                    }
                }
            } catch (e: Exception) {
                log.warn("Official QQ gateway disconnected: {}", e.message)
                delay(3000)
            }
        }
    }

    /** 根据是否已有 sessionId，选择 Identify 或 Resume。 */
    private suspend fun DefaultClientWebSocketSession.identifyOrResume() {
        val token = "QQBot ${tokenProvider.token()}"
        val currentSession = sessionId
        val payload = if (currentSession != null) {
            GatewayPayload(op = 6, d = officialJson.encodeToJsonElement(ResumePayload(token, currentSession, lastSeq)))
        } else {
            GatewayPayload(op = 2, d = officialJson.encodeToJsonElement(IdentifyPayload(token, qq.intents, qq.shard)))
        }
        outgoing.send(Frame.Text(officialJson.encodeToString(GatewayPayload.serializer(), payload)))
    }

    /** 处理 op=0 Dispatch 事件，只把当前桥接器关心的事件交给上层。 */
    private suspend fun handleDispatch(payload: GatewayPayload) {
        val type = payload.t ?: return
        val data = payload.d ?: return
        when (type) {
            "READY" -> {
                sessionId = officialJson.decodeFromJsonElement<ReadyData>(data).sessionId
                log.info("Official QQ gateway ready; session={}", sessionId)
            }

            "RESUMED" -> log.info("Official QQ gateway resumed")
            "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE" -> {
                onEvent(OfficialGatewayEvent.GroupMessage(type, officialJson.decodeFromJsonElement(data)))
            }

            "GROUP_MEMBER_ADD", "GROUP_MEMBER_REMOVE" -> {
                onEvent(OfficialGatewayEvent.GroupMember(type, officialJson.decodeFromJsonElement(data)))
            }

            "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MSG_RECEIVE", "GROUP_MSG_REJECT" -> {
                onEvent(OfficialGatewayEvent.GroupManagement(type, officialJson.decodeFromJsonElement(data)))
            }

            else -> log.debug("Ignoring official QQ event {}", type)
        }
    }
}
