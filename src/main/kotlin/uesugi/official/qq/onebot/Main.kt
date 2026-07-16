package uesugi.official.qq.onebot

import com.typesafe.config.Config
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class OfficialQqOnebotRuntime internal constructor(
    private val instances: List<OfficialQqOnebotInstance>,
    private val log: Logger,
) {
    @Volatile
    private var stopped = false
    internal var shutdownHook: Thread? = null

    suspend fun stop() {
        if (stopped) return
        stopped = true
        runCatching {
            shutdownHook?.let { Runtime.getRuntime().removeShutdownHook(it) }
        }
        instances.asReversed().forEach { instance ->
            log.info("Stopping official QQ OneBot({}) bridge", instance.key)
            runCatching { instance.gateway.stop() }
                .onFailure { log.warn("Failed to stop official QQ gateway({})", instance.key, it) }
            runCatching { instance.bridge.stop() }
                .onFailure { log.warn("Failed to stop OneBot bridge({})", instance.key, it) }
        }
    }
}

internal data class OfficialQqOnebotInstance(
    val key: String,
    val gateway: OfficialQqGatewayClient,
    val bridge: OneBotBridge,
)

suspend fun runOfficialQqOnebot(
    config: Config? = null,
    log: Logger = LoggerFactory.getLogger("official-qq-onebot"),
    gatewayEventHandler: (OfficialGatewayEvent, OfficialQqApiClient) -> Boolean = { _, _ -> true },
    installShutdownHook: Boolean = true,
): OfficialQqOnebotRuntime {
    // 组装桥接器的所有依赖
    val configMap = if (config == null) {
        BridgeConfig.load()
    } else {
        BridgeConfig.fromConfig(config)
    }
    val instances = mutableListOf<OfficialQqOnebotInstance>()

    configMap.forEach { (key, config) ->
        val ids = IdMapper(config.idMap)
        IdMapperRegistry.register(key, ids)
        val store = MessageStore()
        val tokenProvider = AppAccessTokenProvider(config.qq)
        val api = OfficialQqApiClient(config.qq, tokenProvider)
        val converter = MessageConverter(config, ids, store)
        val bridge = OneBotBridge(config, ids, store, api, converter)

        // 官方事件先转换为 OneBot 事件，再推送给已连接的 OneBot 客户端。
        val gateway = OfficialQqGatewayClient(config.qq, tokenProvider, onEvent = { event ->
            if (!gatewayEventHandler(event, api)) return@OfficialQqGatewayClient
            when (event) {
                is OfficialGatewayEvent.GroupMessage -> {
                    converter.toOneBotGroupMessage(event.type, event.event)?.let { bridge.push(it) }
                }

                is OfficialGatewayEvent.GroupMember -> {
                    converter.toMemberNotice(event.type, event.event)?.let { bridge.push(it) }
                }

                is OfficialGatewayEvent.GroupManagement -> {
                    converter.toGroupManagementNotice(event.type, event.event)?.let { bridge.push(it) }
                }
            }
        })

        bridge.start()
        gateway.start()
        instances += OfficialQqOnebotInstance(key, gateway, bridge)
        log.info("Official QQ OneBot({}) bridge started on ws://{}:{}", key, config.onebot.host, config.onebot.port)
    }

    val runtime = OfficialQqOnebotRuntime(instances, log)
    if (installShutdownHook) {
        runtime.shutdownHook = Thread {
            runBlocking { runtime.stop() }
        }
        Runtime.getRuntime().addShutdownHook(runtime.shutdownHook)
    }
    return runtime
}

/** 独立桥接进程入口：启动 OneBot WS 服务，并连接官方 QQ Gateway。 */
fun main() {
    runBlocking {
        runOfficialQqOnebot()
        awaitCancellation()
    }
}
