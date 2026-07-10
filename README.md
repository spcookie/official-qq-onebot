# official-qq-onebot

官方 QQ Bot → OneBot v11 协议桥接器。将[官方 QQ 群聊机器人 API](https://bot.q.qq.com/wiki/)（Gateway WebSocket +
OpenAPI）转换为标准 OneBot v11 的正向 WebSocket 服务，使 Erii 等上游消费方无需修改即可接入官方 QQ 平台。

## 架构

```
官方 QQ Gateway (WebSocket)          OneBot v11 Client (erii-core)
        │                                      │
        ▼                                      ▼
OfficialQqGatewayClient ──► OneBotBridge ◄── OneBotServer (WS)
        │                        │
        ▼                        ▼
OfficialQqApiClient (HTTP)   MessageConverter / IdMapper / MessageStore
```

- **OfficialQqGatewayClient** — 连接官方 Gateway，处理 Identify/Resume/心跳，分发群消息、成员变动、群管理事件
- **OfficialQqApiClient** — 封装官方 OpenAPI：发送群消息、上传富媒体、撤回消息
- **OneBotBridge** — 基于 `onebot-lib` 启动 OneBot v11 WS 服务端，注册 action handler，推送事件
- **MessageConverter** — 官方消息 ↔ OneBot 消息段双向转换（文本/@/图片/语音/markdown）
- **IdMapper** — 官方 `openid`（字符串） ↔ OneBot `user_id`/`group_id`（Long）双向映射，支持配置文件和自动哈希映射
- **MessageStore** — LRU 有界内存消息表，支持 `get_msg`/`delete_msg` 和重复事件去重

## 快速开始

### 前置条件

- JDK 17+
- 已注册的[官方 QQ 机器人](https://q.qq.com/)，获取 AppID 和 ClientSecret

### 构建

```bash
./gradlew build
```

### 配置

在 `src/main/resources/application.conf` 或通过 `-Dconfig.path=/path/to/config.conf` 指定配置文件：

```hocon
qq {
  app-id = "your_app_id"
  client-secret = "your_client_secret"
  api-base-url = "https://api.sgroup.qq.com"
  gateway-url = "wss://api.sgroup.qq.com/websocket"
  intents = 1107296256
  shard = [0, 1]
}

onebot {
  host = "0.0.0.0"
  port = 3001
  access-token = null
  self-id = 123456789
  nickname = "MyBot"
}

id-map {
  groups {
    # 本地群ID = "官方group_openid"
    10001 = "A1B2C3..."
  }
  members {
    # 本地用户ID = "官方member_openid"
    20001 = "D4E5F6..."
  }
  auto = false
}
```

| 配置项                           | 说明                               |
|-------------------------------|----------------------------------|
| `qq.app-id`                   | 官方机器人 AppID                      |
| `qq.client-secret`            | 官方机器人 ClientSecret               |
| `qq.intents`                  | Gateway 订阅的 intents 位掩码          |
| `qq.shard`                    | 分片信息 `[current, total]`          |
| `onebot.host` / `onebot.port` | OneBot WS 服务监听地址                 |
| `onebot.access-token`         | OneBot access_token，可为 null      |
| `onebot.self-id`              | 机器人自身 QQ 号（Long）                 |
| `id-map.groups`               | 本地 group_id → 官方 group_openid 映射 |
| `id-map.members`              | 本地 user_id → 官方 member_openid 映射 |
| `id-map.auto`                 | 是否自动将未知 openid 哈希映射为 Long ID     |

### 运行

```bash
./gradlew run
```

启动后 OneBot WS 服务监听 `ws://0.0.0.0:3001`，erii-core 或其他 OneBot 客户端可直接连接。

## 支持的 OneBot Action

| Action                               | 说明                           |
|--------------------------------------|------------------------------|
| `get_login_info`                     | 返回配置的 self_id 和 nickname     |
| `get_status`                         | 返回 online=true               |
| `get_version_info`                   | 返回 appName 和 protocolVersion |
| `can_send_image` / `can_send_record` | 返回 true                      |
| `get_group_list`                     | 返回 id-map 中所有已配置群            |
| `get_group_info`                     | 返回单个群信息                      |
| `get_group_member_info`              | 返回单个成员信息                     |
| `get_group_member_list`              | 返回 id-map 中所有已配置成员           |
| `get_msg`                            | 从 MessageStore 查询历史消息        |
| `delete_msg`                         | 调用官方 API 撤回消息                |
| `send_group_msg`                     | 发送群消息（文本/markdown/图片/语音）     |

## 支持的事件

| 官方事件                                     | OneBot 事件            |
|------------------------------------------|----------------------|
| `GROUP_AT_MESSAGE_CREATE`                | `GroupMessageEvent`  |
| `GROUP_MESSAGE_CREATE`                   | `GroupMessageEvent`  |
| `GROUP_MEMBER_ADD`                       | `GroupIncreaseEvent` |
| `GROUP_MEMBER_REMOVE`                    | `GroupDecreaseEvent` |
| `GROUP_ADD_ROBOT`                        | `GroupIncreaseEvent` |
| `GROUP_DEL_ROBOT`                        | `GroupDecreaseEvent` |
| `GROUP_MSG_RECEIVE` / `GROUP_MSG_REJECT` | `RawEvent`           |

## 项目结构

```
src/main/kotlin/uesugi/official/qq/onebot/
├── Main.kt                    # 入口：组装依赖并启动桥接器
├── BridgeConfig.kt            # HOCON 配置加载与类型化
├── OneBotBridge.kt            # OneBot WS 服务端 + action handler
├── OfficialQqGatewayClient.kt # 官方 Gateway WS 客户端
├── OfficialQqApiClient.kt     # 官方 OpenAPI HTTP 客户端
├── OfficialModels.kt          # 官方 API/Gateway 数据结构
├── MessageConverter.kt        # 消息双向转换
├── IdMapper.kt                # openid ↔ Long ID 双向映射
└── MessageStore.kt            # LRU 消息缓存
```

## 技术栈

- **Kotlin 2.2** + **Ktor 3.3** (HTTP Client + WebSocket)
- **onebot-lib** — OneBot v11 服务端实现
- **kotlinx.serialization** — JSON 序列化
- **Typesafe Config** — HOCON 配置
- **kotlinx.coroutines** — 异步/并发
