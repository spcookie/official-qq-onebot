package uesugi.official.qq.onebot

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import uesugi.onebot.core.config.OneBotConfig
import uesugi.onebot.core.dispatch.MiddlewareException
import uesugi.onebot.core.message.imageFile
import uesugi.onebot.core.message.imageUrl
import uesugi.onebot.core.model.*
import uesugi.onebot.core.transport.JsonFactory
import uesugi.onebot.lib.server.OneBotServer
import uesugi.onebot.lib.server.api.*
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** OneBot 侧服务端：接收 action，并把官方 QQ 事件推送给 onebot。 */
class OneBotBridge(
    private val config: BridgeConfig,
    private val ids: IdMapper,
    private val store: MessageStore,
    private val api: OfficialQqApiClient,
    private val converter: MessageConverter,
) {
    private val log = LoggerFactory.getLogger(OneBotBridge::class.java)

    /** onebot-lib 提供的 WS server，桥接器只负责注册 action handler 和推送事件。 */
    private val server = OneBotServer(
        OneBotConfig(
            wsForwardServerEnable = true,
            heartbeatEnable = true,
            wsForwardServerHost = config.onebot.host,
            wsForwardServerPort = config.onebot.port,
            accessToken = config.onebot.accessToken,
            selfId = config.onebot.selfId,
            appName = "official-qq-onebot",
        ),
    )

    init {
        log.info("Registering OneBot action handlers")
        registerActions()
    }

    /** 启动 OneBot WebSocket 服务端。 */
    suspend fun start() {
        log.info("Starting OneBot bridge server on ws://{}:{}", config.onebot.host, config.onebot.port)
        server.start()
        log.info("OneBot bridge server started")
    }

    /** 停止 OneBot WebSocket 服务端。 */
    suspend fun stop() {
        log.info("Stopping OneBot bridge server")
        server.stop()
        log.info("OneBot bridge server stopped")
    }

    /** 向已连接的 OneBot 客户端推送事件。 */
    suspend fun push(event: OneBotEvent) {
        log.info("Push OneBot event: post_type={}, self_id={}", event.postType, event.selfId)
        server.pushEvent(event)
    }

    /** 注册 erii-core 当前会用到的 OneBot action。 */
    private fun registerActions() {
        server.onGetLoginInfo {
            log.debug("Handle get_login_info")
            LoginInfo(userId = config.onebot.selfId, nickname = config.onebot.nickname)
        }
        server.onGetStatus {
            log.debug("Handle get_status")
            StatusInfo(online = true, good = true)
        }
        server.onGetVersionInfo {
            log.debug("Handle get_version_info")
            VersionInfo(appName = "official-qq-onebot", appVersion = "1.0-SNAPSHOT", protocolVersion = "v11")
        }
        server.onCanSendMarkdown {
            log.debug("Handle can_send_markdown")
            CanSendResult(true)
        }
        server.onCanSendImage {
            log.debug("Handle can_send_image")
            CanSendResult(true)
        }
        server.onCanSendRecord {
            log.debug("Handle can_send_record")
            CanSendResult(true)
        }

        server.onGetGroupList {
            log.debug("Handle get_group_list")
            val groups = ids.allGroups().keys.map {
                GroupInfo(
                    groupId = it,
                    groupName = it.toString(),
                    memberCount = ids.allMembers().size,
                    maxMemberCount = 0
                )
            }
            RawActionResult(JsonFactory.base.encodeToJsonElement(ListSerializer(GroupInfo.serializer()), groups))
        }
        server.onGetGroupInfo { req: GetGroupInfoRequest ->
            log.debug("Handle get_group_info: group_id={}", req.groupId)
            requireKnownGroup(req.groupId)
            GroupInfo(
                groupId = req.groupId,
                groupName = req.groupId.toString(),
                memberCount = ids.allMembers().size,
                maxMemberCount = 0
            )
        }
        server.onGetGroupMemberInfo { req: GetGroupMemberInfoRequest ->
            log.debug("Handle get_group_member_info: group_id={}, user_id={}", req.groupId, req.userId)
            requireKnownGroup(req.groupId)
            requireKnownMember(req.userId)
            memberInfo(req.groupId, req.userId)
        }
        server.onGetGroupMemberList { req: GetGroupMemberListRequest ->
            log.debug("Handle get_group_member_list: group_id={}", req.groupId)
            requireKnownGroup(req.groupId)
            val members = ids.allMembers().keys.map { memberInfo(req.groupId, it) }
            RawActionResult(JsonFactory.base.encodeToJsonElement(ListSerializer(GroupMemberInfo.serializer()), members))
        }
        server.onGetMsg { req: GetMsgRequest ->
            log.debug("Handle get_msg: message_id={}", req.messageId)
            val stored =
                store.get(req.messageId) ?: throw MiddlewareException(1404, "message not found: ${req.messageId}")
            MessageInfo(
                time = stored.time,
                messageType = "group",
                messageId = stored.localId,
                realId = stored.localId,
                sender = Sender(stored.sender.userId, stored.sender.nickname),
                message = stored.message,
            )
        }
        server.onDeleteMsg { req: DeleteMsgRequest ->
            log.info("Handle delete_msg: message_id={}", req.messageId)
            val stored =
                store.get(req.messageId) ?: throw MiddlewareException(1404, "message not found: ${req.messageId}")
            api.deleteGroupMessage(stored.groupOpenid, stored.officialId)
            store.remove(req.messageId)
            log.info("Deleted official message: local_id={}, official_id={}", req.messageId, stored.officialId)
            RawActionResult(JsonObject(emptyMap()))
        }
        server.onSendGroupMsg { req: SendGroupMsgRequest ->
            log.info("Handle send_group_msg: group_id={}, segments={}", req.groupId, req.message.size)
            sendGroup(req.groupId, req.message)
        }
    }

    /** 将 OneBot group 消息拆分并调用官方 QQ OpenAPI 发送。 */
    private suspend fun sendGroup(groupId: Long, message: List<MessageSegment>): MessageIdResult {
        val groupOpenid = ids.localGroupToOfficial(groupId)
            ?: throw MiddlewareException(1404, "unknown group_id: $groupId")
        val parts = converter.toOutgoingParts(message)
        log.info("Sending group message: group_id={}, official_group={}, parts={}", groupId, groupOpenid, parts.size)
        var firstLocalId: Int? = null
        var msgSeq = 1
        for (part in parts) {
            val response = when {
                part.markdownContent != null -> {
                    log.debug("Sending markdown group message: group_id={}", groupId)
                    api.sendGroupMessage(
                        groupOpenid,
                        SendGroupMessageRequest(
                            msgType = 2,
                            markdown = MarkdownInfo(part.markdownContent),
                            msgSeq = msgSeq++,
                        ),
                    )
                }

                part.mediaFileType != null -> {
                    log.debug(
                        "Uploading media for group message: group_id={}, file_type={}",
                        groupId,
                        part.mediaFileType
                    )
                    val uploaded = api.uploadGroupFile(groupOpenid, part.mediaFileType, part.mediaUrl, part.mediaData)
                    api.sendGroupMessage(
                        groupOpenid,
                        SendGroupMessageRequest(
                            content = part.content,
                            msgType = 7,
                            media = MediaInfo(uploaded.fileInfo),
                            msgSeq = msgSeq++
                        )
                    )
                }

                else -> {
                    api.sendGroupMessage(
                        groupOpenid,
                        SendGroupMessageRequest(content = part.content, msgType = 0, msgSeq = msgSeq++)
                    )
                }
            }
            val localId = rememberSentMessage(response, groupId, groupOpenid, message)
            log.info("Sent group message part: local_id={}, official_id={}", localId, response.id)
            if (firstLocalId == null) firstLocalId = localId
        }
        return MessageIdResult(firstLocalId ?: 0)
    }

    /** 记录发送成功的消息，并合成 message_sent 事件。 */
    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    private suspend fun rememberSentMessage(
        response: SendGroupMessageResponse,
        groupId: Long,
        groupOpenid: String,
        message: List<MessageSegment>,
    ): Int {
        val now = response.timestamp?.toEpochMilliseconds() ?: (System.currentTimeMillis() / 1000)
        val sender =
            GroupSender(config.onebot.selfId, config.onebot.nickname, card = config.onebot.nickname, role = "member")
        val raw = message.joinToString("") { segment ->
            segment.data["text"]?.toString()?.trim('"') ?: "[${segment.type}]"
        }
        val message = message.map { ms ->
            when (ms.type) {
                MessageSegment.IMAGE -> imageSegment(
                    file = Uuid.random().toHexString() + ".gif",
                    url = ms.imageUrl ?: ms.imageFile?.takeIf { it.startsWith("base64://") }
                    ?: throw MiddlewareException(1404, "image url not found"),
                )

                else -> ms
            }
        }
        val stored = store.remember(response.id, groupId, groupOpenid, config.onebot.selfId, now, message, raw, sender)
        log.debug("Push synthetic message_sent event: local_id={}, official_id={}", stored.localId, response.id)

        server.pushEvent(
            MessageSentEvent.of(
                GroupMessageEvent(
                    time = now,
                    selfId = config.onebot.selfId,
                    messageId = stored.localId,
                    groupId = groupId,
                    userId = config.onebot.selfId,
                    message = message,
                    rawMessage = raw,
                    sender = sender,
                )
            )
        )
        return stored.localId
    }

    /** 校验本地 group_id 是否有显式配置到官方 group_openid。 */
    private fun requireKnownGroup(groupId: Long) {
        if (ids.localGroupToOfficial(groupId) == null) {
            throw MiddlewareException(1404, "unknown group_id: $groupId")
        }
    }

    /** 校验本地 user_id 是否有显式配置到官方 member_openid；机器人自身 ID 例外。 */
    private fun requireKnownMember(userId: Long) {
        if (ids.localMemberToOfficial(userId) == null && userId != config.onebot.selfId) {
            throw MiddlewareException(1404, "unknown user_id: $userId")
        }
    }

    /** 构造静态群成员信息；官方群聊接口当前没有完整成员资料查询能力。 */
    private fun memberInfo(groupId: Long, userId: Long): GroupMemberInfo =
        GroupMemberInfo(
            groupId = groupId,
            userId = userId,
            nickname = if (userId == config.onebot.selfId) config.onebot.nickname else userId.toString(),
            card = if (userId == config.onebot.selfId) config.onebot.nickname else "",
            role = if (userId == config.onebot.selfId) "admin" else "member",
        )
}
