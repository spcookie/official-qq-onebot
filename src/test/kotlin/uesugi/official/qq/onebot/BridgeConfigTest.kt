package uesugi.official.qq.onebot

import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** 配置读取测试，保证 HOCON 映射能正确落到强类型配置。 */
class BridgeConfigTest {
    /** 显式 ID 映射是桥接器兼容 OneBot Long ID 的基础，必须稳定读取。 */
    @Test
    fun loadsExplicitIdMaps() {
        val config = ConfigFactory.parseString(
            """
            qq {
              app-id = app
              client-secret = secret
              api-base-url = "https://api.sgroup.qq.com"
              gateway-url = "wss://api.sgroup.qq.com/websocket/"
              intents = 33554432
              shard = [0, 1]
            }
            onebot {
              host = "127.0.0.1"
              port = 6700
              access-token = ""
              self-id = 10000
              nickname = "Erii"
            }
            id-map {
              auto = true
              groups { "10001" = "group-openid" }
              members { "20001" = "member-openid" }
            }
            """.trimIndent(),
        )

        val loaded = BridgeConfig.fromConfig(config)

        // 同时验证 QQ 基础配置和双向 ID 映射配置的读取结果。
        assertEquals("app", loaded.qq.appId)
        assertEquals(true, loaded.idMap.auto)
        assertEquals("group-openid", loaded.idMap.groups[10001])
        assertEquals("member-openid", loaded.idMap.members[20001])
    }
}
