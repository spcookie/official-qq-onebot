package uesugi.official.qq.onebot

import com.typesafe.config.Config
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

suspend fun runOfficialQqOnebot(
    config: Config? = null,
    log: Logger = LoggerFactory.getLogger("official-qq-onebot"),
    gatewayEventHandler: (OfficialGatewayEvent, OfficialQqApiClient) -> Boolean = { _, _ -> true }
) {
    // 组装桥接器的所有依赖
    val configMap = if (config == null) {
        BridgeConfig.load()
    } else {
        BridgeConfig.fromConfig(config)
    }
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

        // JVM 退出时尽量优雅关闭 WS server 和官方 Gateway 连接。
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                log.info("Stopping official QQ OneBot({}) bridge", key)
                gateway.stop()
                bridge.stop()
            }
        })

        bridge.start()
        gateway.start()
        log.info("Official QQ OneBot({}) bridge started on ws://{}:{}", key, config.onebot.host, config.onebot.port)
    }
}

/** 独立桥接进程入口：启动 OneBot WS 服务，并连接官方 QQ Gateway。 */
fun main() {
    runBlocking {
        runOfficialQqOnebot()
        awaitCancellation()
    }
}
