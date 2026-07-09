package uesugi.official.qq.onebot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** 官方 QQ Gateway 的通用 payload，所有 op/t/d/s 都先落到这个壳里。 */
@Serializable
data class GatewayPayload(
    val id: String? = null,
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null,
)

/** Gateway Hello 事件中的心跳间隔。 */
@Serializable
data class HelloData(
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long,
)

/** Identify 登录包，声明 token、订阅 intents 和分片信息。 */
@Serializable
data class IdentifyPayload(
    val token: String,
    val intents: Int,
    val shard: List<Int>,
    val properties: IdentifyProperties = IdentifyProperties(),
)

/** 官方要求的客户端属性字段，字段名必须是 $os/$browser/$device。 */
@Serializable
data class IdentifyProperties(
    @SerialName($$"$os")
    val os: String = System.getProperty("os.name") ?: "unknown",
    @SerialName($$"$browser")
    val browser: String = "official-qq-onebot",
    @SerialName($$"$device")
    val device: String = "official-qq-onebot",
)

/** Resume 包，用于断线后尝试恢复同一 Gateway session。 */
@Serializable
data class ResumePayload(
    val token: String,
    @SerialName("session_id")
    val sessionId: String,
    val seq: Int?,
)

/** READY 事件中返回的会话 ID。 */
@Serializable
data class ReadyData(
    @SerialName("session_id")
    val sessionId: String,
)

/** 获取 app access token 的请求体。 */
@Serializable
data class AppAccessTokenRequest(
    val appId: String,
    val clientSecret: String,
)

/** 获取 app access token 的响应体。 */
@Serializable
data class AppAccessTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
)

/** 官方 OpenAPI 失败时常见的错误体。 */
@Serializable
data class OfficialErrorResponse(
    val code: Int = 0,
    val message: String = "",
)

/** 官方群消息里的发送者信息。 */
@Serializable
data class OfficialAuthor(
    @SerialName("member_openid")
    val memberOpenid: String,
    @SerialName("member_role")
    val memberRole: String = "member",
    val bot: Boolean = false,
)

/** 官方群消息附件，图片/语音/视频会通过 content_type 区分。 */
@Serializable
data class OfficialAttachment(
    val id: String? = null,
    val filename: String? = null,
    val url: String? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val size: Long? = null,
)

/** 官方群消息事件，覆盖 GROUP_AT_MESSAGE_CREATE 和 GROUP_MESSAGE_CREATE 的共同字段。 */
@Serializable
data class OfficialGroupMessageEvent(
    val id: String,
    val author: OfficialAuthor,
    val content: String = "",
    val timestamp: String,
    @SerialName("group_openid")
    val groupOpenid: String,
    val attachments: List<OfficialAttachment> = emptyList(),
)

/** 官方群成员进出事件，部分事件使用 member_openid，部分事件还带 op_member_openid。 */
@Serializable
data class OfficialGroupMemberEvent(
    val timestamp: Long,
    @SerialName("group_openid")
    val groupOpenid: String,
    @SerialName("member_openid")
    val memberOpenid: String? = null,
    @SerialName("op_member_openid")
    val opMemberOpenid: String? = null,
)

/** 上传群聊富媒体资源的请求体。 */
@Serializable
data class UploadGroupFileRequest(
    @SerialName("file_type")
    val fileType: Int,
    val url: String? = null,
    @SerialName("file_data")
    val fileData: String? = null,
)

/** 上传群聊富媒体资源后，发送消息接口需要使用 file_info。 */
@Serializable
data class UploadGroupFileResponse(
    @SerialName("file_uuid")
    val fileUuid: String = "",
    @SerialName("file_info")
    val fileInfo: String,
    val ttl: Int = 0,
)

/** 发送官方群消息的请求体，文本和 media 共用这个结构。 */
@Serializable
data class SendGroupMessageRequest(
    val content: String = "",
    @SerialName("msg_type")
    val msgType: Int,
    val markdown: MarkdownInfo? = null,
    val media: MediaInfo? = null,
    @SerialName("msg_id")
    val msgId: String? = null,
    @SerialName("msg_seq")
    val msgSeq: Int? = null,
)

/** 官方 markdown 消息体；当前桥接 OneBot markdown 段的原生 content 形式。 */
@Serializable
data class MarkdownInfo(
    val content: String,
)

/** 官方 media 消息引用上传接口返回的 file_info。 */
@Serializable
data class MediaInfo(
    @SerialName("file_info")
    val fileInfo: String,
)

/** 官方群消息发送成功后的响应。 */
@Serializable
data class SendGroupMessageResponse @OptIn(ExperimentalTime::class) constructor(
    val id: String,
    val timestamp: Instant? = null,
)

