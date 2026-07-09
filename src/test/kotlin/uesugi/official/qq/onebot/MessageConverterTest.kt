package uesugi.official.qq.onebot

import kotlinx.serialization.json.JsonPrimitive
import uesugi.onebot.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 消息转换测试，验证官方 QQ 事件和 OneBot 消息段之间的兼容规则。 */
class MessageConverterTest {
    /** 测试用固定配置，包含一个群和一个成员的显式映射。 */
    private val config = BridgeConfig(
        qq = QqConfig(
            "app",
            "secret",
            "https://api.sgroup.qq.com",
            "wss://api.sgroup.qq.com/websocket/",
            1,
            listOf(0, 1)
        ),
        onebot = OneBotBridgeConfig("127.0.0.1", 6700, null, 10000, "Erii"),
        idMap = IdMapConfig(groups = mapOf(10001L to "group-openid"), members = mapOf(20001L to "member-openid")),
    )
    private val mapper = IdMapper(config.idMap)
    private val converter = MessageConverter(config, mapper, MessageStore())

    /** GROUP_AT_MESSAGE_CREATE 需要补 at(selfId)，让 Erii 能按现有逻辑识别 @ 机器人。 */
    @Test
    fun convertsAtGroupMessageToOneBotMessage() {
        val result = converter.toOneBotGroupMessage(
            "GROUP_AT_MESSAGE_CREATE",
            OfficialGroupMessageEvent(
                id = "official-msg",
                author = OfficialAuthor(memberOpenid = "member-openid"),
                content = " hello",
                timestamp = "2023-11-06T13:37:18+08:00",
                groupOpenid = "group-openid",
            ),
        )

        assertNotNull(result)
        assertEquals(10001L, result.groupId)
        assertEquals(20001L, result.userId)
        assertEquals("at", result.message.first().type)
        assertEquals("text", result.message[1].type)
    }

    /** 未配置 member_openid 的事件不应生成随机 ID，避免污染 Erii 的用户记忆。 */
    @Test
    fun dropsUnknownIds() {
        val result = converter.toOneBotGroupMessage(
            "GROUP_MESSAGE_CREATE",
            OfficialGroupMessageEvent(
                id = "official-msg",
                author = OfficialAuthor(memberOpenid = "missing"),
                content = "hello",
                timestamp = "2023-11-06T13:37:18+08:00",
                groupOpenid = "group-openid",
            ),
        )

        assertNull(result)
    }

    /** 官方 QQ 不支持的 OneBot 段应降级为可读文本，图片仍拆成富媒体 part。 */
    @Test
    fun degradesUnsupportedOutboundSegments() {
        val parts = converter.toOutgoingParts(
            listOf(
                textSegment("hi "),
                atSegment(20001),
                MessageSegment("face", mapOf("id" to JsonPrimitive("14"))),
                imageSegment("base64://abc"),
            ),
        )

        assertEquals("""hi <qqbot-at-user id="member-openid"/>[表情:14]""", parts[0].content)
        assertEquals(1, parts[1].mediaFileType)
        assertEquals("abc", parts[1].mediaData)
    }

    /** OneBot markdown 段应独立转换为官方 msg_type=2 的 markdown part。 */
    @Test
    fun convertsMarkdownOutboundSegment() {
        val parts = converter.toOutgoingParts(
            listOf(
                textSegment("before"),
                MessageSegment("markdown", mapOf("content" to JsonPrimitive("# title"))),
                textSegment("after"),
            ),
        )

        assertEquals("before", parts[0].content)
        assertEquals("# title", parts[1].markdownContent)
        assertEquals("after", parts[2].content)
    }

    /** 群成员事件字段有时只有 op_member_openid，转换层应兼容。 */
    @Test
    fun convertsMemberAddWithFallbackOpenid() {
        val result = converter.toMemberNotice(
            "GROUP_MEMBER_ADD",
            OfficialGroupMemberEvent(
                timestamp = 123,
                groupOpenid = "group-openid",
                opMemberOpenid = "member-openid",
            ),
        )

        assertEquals(20001L, (result as GroupIncreaseEvent).userId)
    }

    /** 机器人被添加到群时，用 OneBot 群成员增加事件表达机器人入群。 */
    @Test
    fun convertsRobotAddToGroupIncrease() {
        val result = converter.toGroupManagementNotice(
            "GROUP_ADD_ROBOT",
            OfficialGroupMemberEvent(
                timestamp = 123,
                groupOpenid = "group-openid",
                opMemberOpenid = "member-openid",
            ),
        )

        result as GroupIncreaseEvent
        assertEquals(10000L, result.userId)
        assertEquals(20001L, result.operatorId)
    }

    /** 机器人被移出群时，用 OneBot 群成员减少事件表达机器人退群。 */
    @Test
    fun convertsRobotDeleteToGroupDecrease() {
        val result = converter.toGroupManagementNotice(
            "GROUP_DEL_ROBOT",
            OfficialGroupMemberEvent(
                timestamp = 123,
                groupOpenid = "group-openid",
                opMemberOpenid = "member-openid",
            ),
        )

        result as GroupDecreaseEvent
        assertEquals("kick", result.subType)
        assertEquals(10000L, result.userId)
    }

    /** 群主动消息接收/拒绝开关没有 OneBot 强类型，保留为 RawEvent。 */
    @Test
    fun convertsGroupMessageSwitchToRawEvent() {
        val result = converter.toGroupManagementNotice(
            "GROUP_MSG_REJECT",
            OfficialGroupMemberEvent(
                timestamp = 123,
                groupOpenid = "group-openid",
                opMemberOpenid = "member-openid",
            ),
        )

        result as RawEvent
        assertEquals("notice", result.postType)
        assertEquals("group_msg_reject", result.rawDetailType)
    }
}
