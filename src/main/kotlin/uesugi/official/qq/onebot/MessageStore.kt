package uesugi.official.qq.onebot

import uesugi.onebot.core.model.GroupSender
import uesugi.onebot.core.model.MessageContent
import java.util.concurrent.atomic.AtomicInteger

/** 桥接器内存中的消息记录，用于把官方字符串消息 ID 映射成本地 Int message_id。 */
data class StoredMessage(
    val localId: Int,
    val officialId: String,
    val groupId: Long,
    val groupOpenid: String,
    val userId: Long,
    val time: Long,
    val message: MessageContent,
    val rawMessage: String,
    val sender: GroupSender,
)

/** 有界消息表：支持 get_msg/delete_msg/message_sent，同时避免长期运行时无限增长。 */
class MessageStore(private val maxEntries: Int = 4096) {
    /** OneBot message_id 需要 Int，这里从 1 开始递增分配。 */
    private val nextId = AtomicInteger(1)

    /** LRU 表，按本地 message_id 保存完整消息。 */
    private val byLocal = object : LinkedHashMap<Int, StoredMessage>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, StoredMessage>?): Boolean =
            size > maxEntries
    }

    /** 官方消息 ID 到本地 message_id 的索引，用于重复事件去重。 */
    private val officialToLocal = mutableMapOf<String, Int>()

    /** 记住一条官方或本地发送后的消息；相同 officialId 会复用已有本地 ID。 */
    @Synchronized
    fun remember(
        officialId: String,
        groupId: Long,
        groupOpenid: String,
        userId: Long,
        time: Long,
        message: MessageContent,
        rawMessage: String,
        sender: GroupSender,
    ): StoredMessage {
        val existingId = officialToLocal[officialId]
        if (existingId != null) {
            byLocal[existingId]?.let { return it }
        }
        val localId = nextId.getAndIncrement()
        val stored = StoredMessage(localId, officialId, groupId, groupOpenid, userId, time, message, rawMessage, sender)
        byLocal[localId] = stored
        officialToLocal[officialId] = localId
        trimOfficialIndex()
        return stored
    }

    /** 按 OneBot message_id 查找消息。 */
    @Synchronized
    fun get(localId: Int): StoredMessage? = byLocal[localId]

    /** 删除本地消息映射；通常在官方撤回成功后调用。 */
    @Synchronized
    fun remove(localId: Int): StoredMessage? {
        val removed = byLocal.remove(localId)
        if (removed != null) officialToLocal.remove(removed.officialId)
        return removed
    }

    /** LRU 淘汰后同步清理官方 ID 索引，避免悬空映射。 */
    private fun trimOfficialIndex() {
        val liveIds = byLocal.values.mapTo(mutableSetOf()) { it.officialId }
        officialToLocal.keys.retainAll(liveIds)
    }
}
