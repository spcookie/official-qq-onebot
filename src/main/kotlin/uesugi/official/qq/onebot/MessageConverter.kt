package uesugi.official.qq.onebot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import uesugi.onebot.core.model.*
import java.time.OffsetDateTime

/** 一次官方发送动作的最小单位；文本和单个富媒体会拆成不同 part。 */
data class OutgoingPart(
    val content: String,
    val markdownContent: String? = null,
    val mediaFileType: Int? = null,
    val mediaUrl: String? = null,
    val mediaData: String? = null,
)

/** 负责 OneBot 消息段和官方 QQ 群聊消息结构之间的双向转换。 */
class MessageConverter(
    private val config: BridgeConfig,
    private val ids: IdMapper,
    private val store: MessageStore,
) {
    private val log = LoggerFactory.getLogger(MessageConverter::class.java)

    /** 将官方 QQ 群消息事件转换为 OneBot GroupMessageEvent；未知 ID 会被丢弃。 */
    fun toOneBotGroupMessage(type: String, event: OfficialGroupMessageEvent): GroupMessageEvent? {
        val groupId = ids.officialGroupToLocal(event.groupOpenid)
        if (groupId == null) {
            log.warn("Drop event {}: unknown group_openid={}", type, event.groupOpenid)
            return null
        }
        val userId = ids.officialMemberToLocal(event.author.memberOpenid)
        if (userId == null) {
            log.warn("Drop event {}: unknown member_openid={}", type, event.author.memberOpenid)
            return null
        }
        // GROUP_AT_MESSAGE_CREATE 的 content 通常不包含真实 @ 段，这里补一个 at(selfId) 兼容 @ 识别。
        val segments = buildList {
            when (type) {
                "GROUP_AT_MESSAGE_CREATE" -> {
                    add(atSegment(config.onebot.selfId))
                    if (event.content.isNotBlank()) add(textSegment(event.content))
                }

                "GROUP_MESSAGE_CREATE" -> {
                    if (event.content.isNotBlank()) {
                        val matches = Regex("<@(\\w+)>").findAll(event.content)
                        var lastEnd = 0
                        for (match in matches) {
                            val range = match.groups[0]!!.range
                            if (lastEnd != range.first) {
                                add(textSegment(event.content.substring(lastEnd, range.first)))
                            }
                            val localId = ids.officialMemberToLocal(match.groups[1]!!.value)
                            if (localId != null) {
                                add(atSegment(localId))
                            } else {
                                log.warn("Drop segment {}: unknown member_openid={}", type, match.groups[1]!!.value)
                            }
                            lastEnd = range.last + 1
                        }
                        if (lastEnd != event.content.length) {
                            add(textSegment(event.content.substring(lastEnd, event.content.length)))
                        }
                    }
                }
            }
            event.attachments.forEach { add(it.toSegment()) }
        }
        val time = event.timestamp.toEpochSeconds()
        val sender =
            GroupSender(userId = userId, nickname = userId.toString(), card = "", role = event.author.memberRole)
        store.recordReceived(groupId, event.id)
        val stored = store.remember(
            officialId = event.id,
            groupId = groupId,
            groupOpenid = event.groupOpenid,
            userId = userId,
            time = time,
            message = segments,
            rawMessage = event.content,
            sender = sender,
        )
        return GroupMessageEvent(
            time = time,
            selfId = config.onebot.selfId,
            messageId = stored.localId,
            groupId = groupId,
            userId = userId,
            message = segments,
            rawMessage = event.content,
            sender = sender,
        )
    }

    /** 将官方群成员进出事件转换为 OneBot notice 事件。 */
    fun toMemberNotice(type: String, event: OfficialGroupMemberEvent): OneBotEvent? =
        when (type) {
            "GROUP_MEMBER_ADD" -> {
                val groupId = ids.officialGroupToLocal(event.groupOpenid) ?: return null
                val userId = event.effectiveMemberOpenid()?.let { ids.officialMemberToLocal(it) } ?: return null
                GroupIncreaseEvent(
                    time = event.timestamp,
                    selfId = config.onebot.selfId,
                    groupId = groupId,
                    operatorId = event.opMemberOpenid?.let { ids.officialMemberToLocal(it) } ?: 0,
                    userId = userId,
                )
            }

            "GROUP_MEMBER_REMOVE" -> {
                val groupId = ids.officialGroupToLocal(event.groupOpenid) ?: return null
                val userId = event.effectiveMemberOpenid()?.let { ids.officialMemberToLocal(it) } ?: return null
                GroupDecreaseEvent(
                    time = event.timestamp,
                    selfId = config.onebot.selfId,
                    groupId = groupId,
                    operatorId = event.opMemberOpenid?.let { ids.officialMemberToLocal(it) } ?: 0,
                    userId = userId,
                )
            }

            else -> null
        }

    /** 将群管理事件转换为 OneBot 事件；OneBot 无强类型的事件保留为 RawEvent。 */
    fun toGroupManagementNotice(type: String, event: OfficialGroupMemberEvent): OneBotEvent? {
        val groupId = ids.officialGroupToLocal(event.groupOpenid)
        if (groupId == null) {
            log.warn("Drop group management event {}: unknown group_openid={}", type, event.groupOpenid)
            return null
        }
        val operatorId = event.opMemberOpenid?.let { ids.officialMemberToLocal(it) } ?: 0
        return when (type) {
            "GROUP_ADD_ROBOT" -> GroupIncreaseEvent(
                time = event.timestamp,
                selfId = config.onebot.selfId,
                groupId = groupId,
                operatorId = operatorId,
                userId = config.onebot.selfId,
            )

            "GROUP_DEL_ROBOT" -> GroupDecreaseEvent(
                time = event.timestamp,
                selfId = config.onebot.selfId,
                subType = "kick",
                groupId = groupId,
                operatorId = operatorId,
                userId = config.onebot.selfId,
            )

            "GROUP_MSG_RECEIVE", "GROUP_MSG_REJECT" -> RawEvent(
                time = event.timestamp,
                selfId = config.onebot.selfId,
                postType = "notice",
                rawDetailType = type.lowercase(),
                raw = JsonObject(
                    mapOf(
                        "time" to JsonPrimitive(event.timestamp),
                        "self_id" to JsonPrimitive(config.onebot.selfId),
                        "post_type" to JsonPrimitive("notice"),
                        "notice_type" to JsonPrimitive(type.lowercase()),
                        "group_id" to JsonPrimitive(groupId),
                        "operator_id" to JsonPrimitive(operatorId),
                        "group_openid" to JsonPrimitive(event.groupOpenid),
                        "op_member_openid" to JsonPrimitive(event.opMemberOpenid.orEmpty()),
                    ),
                ),
            )

            else -> null
        }
    }

    /** 将 OneBot 消息段拆成官方 QQ 可发送的文本/富媒体片段。 */
    fun toOutgoingParts(message: MessageContent): List<OutgoingPart> {
        val parts = mutableListOf<OutgoingPart>()
        val text = StringBuilder()
        val markdown = StringBuilder()

        // 官方一条消息只能表达一种主要类型；遇到富媒体前先提交累积文本。
        fun flushText() {
            if (text.isNotEmpty()) {
                parts += OutgoingPart(content = text.toString())
                text.clear()
            }
        }

        fun flushMarkdown() {
            if (markdown.isNotEmpty()) {
                parts += OutgoingPart(content = "", markdownContent = markdown.toString())
                markdown.clear()
            }
        }

        val aggregated = message.distinctBy { it.type }.count() > 1


        for (segment in message) {
            when (segment.type) {
                "text" -> {
                    if (aggregated) {
                        markdown.append(segment.stringData("text").orEmpty())
                    } else {
                        text.append(segment.stringData("text").orEmpty())
                    }
                }

                "markdown" -> {
                    markdown.append(segment.stringData("content").orEmpty())
                }

                "at" -> {
                    // 官方群聊 @ 使用 XML-like 标签，必须把本地 user_id 映射回 member_openid。
                    val raw = segment.stringData("qq")
                    if (raw == "all") {
                        if (aggregated) {
                            markdown.append("<qqbot-at-everyone />")
                        } else {
                            parts += OutgoingPart(content = "", markdownContent = "<qqbot-at-everyone />")
                        }
                    } else {
                        val local = raw?.toLongOrNull()
                        val openid = local?.let { ids.localMemberToOfficial(it) }
                        if (openid != null) {
                            if (aggregated) {
                                markdown.append("""<qqbot-at-user id="$openid" />""")
                            } else {
                                parts += OutgoingPart(
                                    content = "",
                                    markdownContent = """<qqbot-at-user id="$openid" />"""
                                )
                            }
                        } else text.append("@").append(raw.orEmpty())
                    }
                }

                "image" -> {
                    // 图片走 /files 上传，file_type=1。
                    flushText()
                    flushMarkdown()
                    parts += OutgoingPart(
                        content = "",
                        mediaFileType = 1,
                        mediaUrl = segment.stringData("url") ?: segment.stringData("file")
                            ?.takeUnless { it.startsWith("base64://") },
                        mediaData = segment.stringData("file")?.removePrefix("base64://")
                            ?.takeIf { segment.stringData("file")?.startsWith("base64://") == true },
                    )
                }

                "record", "voice" -> {
                    // 语音走 /files 上传，file_type=3。
                    flushText()
                    parts += OutgoingPart(
                        content = "",
                        mediaFileType = 3,
                        mediaUrl = segment.stringData("url") ?: segment.stringData("file")
                            ?.takeUnless { it.startsWith("base64://") },
                        mediaData = segment.stringData("file")?.removePrefix("base64://")
                            ?.takeIf { segment.stringData("file")?.startsWith("base64://") == true },
                    )
                }
                // 官方群聊无法直接表达 QQ 表情和 reply 段，降级为可读文本。
                "face" -> text.append("[表情:${segment.stringData("id").orEmpty()}]")
                "reply" -> text.append("[回复:${segment.stringData("id").orEmpty()}]")
                else -> text.append("[${segment.type}]")
            }
        }
        flushText()
        flushMarkdown()
        return parts.ifEmpty { listOf(OutgoingPart(content = "")) }
    }

    /** 将官方附件按 content_type 转成 OneBot 消息段。 */
    private fun OfficialAttachment.toSegment(): MessageSegment {
        val file = url ?: id ?: filename ?: "unknown"
        return when {
            contentType?.startsWith("image/") == true -> imageSegment(file = file, url = url)
            contentType?.startsWith("audio/") == true -> recordSegment(file = file, url = url)
            contentType?.startsWith("video/") == true -> videoSegment(file = file, url = url)
            else -> textSegment("[附件:${filename ?: id ?: "unknown"}]")
        }
    }

    /** 从 OneBot MessageSegment 的 data 字段里安全读取字符串。 */
    private fun MessageSegment.stringData(key: String): String? =
        data[key]?.jsonPrimitive?.contentOrNull

    /** 群成员事件文档中字段存在差异，这里兼容 member_openid 和 op_member_openid。 */
    private fun OfficialGroupMemberEvent.effectiveMemberOpenid(): String? =
        memberOpenid ?: opMemberOpenid

    /** 官方 timestamp 是 RFC3339 字符串；失败时退回当前秒级时间。 */
    private fun String.toEpochSeconds(): Long =
        runCatching { OffsetDateTime.parse(this).toEpochSecond() }.getOrDefault(System.currentTimeMillis() / 1000)
}