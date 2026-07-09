package uesugi.official.qq.onebot

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

/** 官方 QQ 侧的网关、OpenAPI 和鉴权配置。 */
data class QqConfig(
    val appId: String,
    val clientSecret: String,
    val apiBaseUrl: String,
    val gatewayUrl: String,
    val intents: Int,
    val shard: List<Int>,
)

/** 暴露给 erii-core 连接的 OneBot WebSocket 服务配置。 */
data class OneBotBridgeConfig(
    val host: String,
    val port: Int,
    val accessToken: String?,
    val selfId: Long,
    val nickname: String,
)

/** 将官方 openid 显式映射为 OneBot 生态使用的数字 ID。 */
data class IdMapConfig(
    val groups: Map<Long, String>,
    val members: Map<Long, String>,
    val auto: Boolean = false,
)

/** 桥接器的完整运行配置，由 HOCON 配置文件合并默认 classpath 配置得到。 */
data class BridgeConfig(
    val qq: QqConfig,
    val onebot: OneBotBridgeConfig,
    val idMap: IdMapConfig,
) {
    companion object {
        /** 从 -Dconfig.path 或 CONFIG_PATH 指定的文件读取配置；未指定时读取 classpath 默认配置。 */
        fun load(): BridgeConfig {
            val configPath = System.getProperty("config.path") ?: System.getenv("CONFIG_PATH")
            val config = if (configPath.isNullOrBlank()) {
                ConfigFactory.load()
            } else {
                ConfigFactory.parseFile(File(configPath)).withFallback(ConfigFactory.load()).resolve()
            }
            return fromConfig(config)
        }

        /** 将 Typesafe Config 转换为强类型配置，集中处理默认值和路径读取。 */
        fun fromConfig(config: Config): BridgeConfig {
            val qq = config.getConfig("qq")
            val onebot = config.getConfig("onebot")
            return BridgeConfig(
                qq = QqConfig(
                    appId = qq.getString("app-id"),
                    clientSecret = qq.getString("client-secret"),
                    apiBaseUrl = qq.getString("api-base-url").trimEnd('/'),
                    gatewayUrl = qq.getString("gateway-url"),
                    intents = qq.getInt("intents"),
                    shard = qq.getIntList("shard").map { it.toInt() },
                ),
                onebot = OneBotBridgeConfig(
                    host = onebot.getString("host"),
                    port = onebot.getInt("port"),
                    accessToken = onebot.optionalString("access-token")?.takeIf { it.isNotBlank() },
                    selfId = onebot.getLong("self-id"),
                    nickname = onebot.getString("nickname"),
                ),
                idMap = IdMapConfig(
                    groups = config.readLongStringMap("id-map.groups"),
                    members = config.readLongStringMap("id-map.members"),
                    auto = config.optionalBoolean("id-map.auto") ?: false,
                ),
            )
        }

        /** 可选字符串读取器，用于 access-token 这类允许为空的配置项。 */
        private fun Config.optionalString(path: String): String? =
            if (hasPath(path)) getString(path) else null

        /** 可选布尔值读取器，用于开关型配置项。 */
        private fun Config.optionalBoolean(path: String): Boolean? =
            if (hasPath(path)) getBoolean(path) else null

        /** 读取 "本地数字 ID -> 官方 openid" 的映射表。 */
        private fun Config.readLongStringMap(path: String): Map<Long, String> {
            if (!hasPath(path)) return emptyMap()
            val section = getConfig(path)
            return section.root().keys.associate { key ->
                key.toLong() to section.getString(key)
            }
        }
    }
}
