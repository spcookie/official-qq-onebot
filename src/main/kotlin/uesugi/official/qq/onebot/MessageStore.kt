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

    // ---- 被动回复追踪 ----

    /** 被动消息有效期（5 分钟）。 */
    private val replyTtlMillis = 5 * 60 * 1000L

    /** 每条消息最大回复次数。 */
    private val maxReplyCount = 5

    /** 按 groupId 分组，记录群内收到的消息的 officialId、到达时间和已回复次数。 */
    private data class ReplyEntry(
        val officialId: String,
        val receivedAt: Long,
        val replyCount: AtomicInteger = AtomicInteger(0),
    )

    /** groupId → (officialId → ReplyEntry)，LRU 淘汰旧群。 */
    private val replyByGroup = object : LinkedHashMap<Long, LinkedHashMap<String, ReplyEntry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LinkedHashMap<String, ReplyEntry>>?): Boolean =
            size > 64
    }

    /**
     * 记录一条收到的消息，供后续被动回复消费。
     * 仅记录非机器人自身的消息。
     */
    @Synchronized
    fun recordReceived(groupId: Long, officialId: String) {
        val entries = replyByGroup.getOrPut(groupId) { LinkedHashMap() }
        // 不覆盖已有记录（同一条消息可能被多次推送，只记录首次到达时间）
        if (entries.containsKey(officialId)) return
        // 清理本群过期条目
        val now = System.currentTimeMillis()
        entries.entries.removeAll { (_, e) -> now - e.receivedAt > replyTtlMillis }
        entries[officialId] = ReplyEntry(officialId, now)
    }

    /**
     * 尝试消费一个可用的被动回复目标。
     * 返回 (officialId, msgSeq) 或 null（无可用目标时应降级为主动发送）。
     */
    @Synchronized
    fun consumeReplyTarget(groupId: Long): Pair<String, Int>? {
        val entries = replyByGroup[groupId] ?: return null
        val now = System.currentTimeMillis()

        // 清理过期条目
        entries.entries.removeAll { (_, e) -> now - e.receivedAt > replyTtlMillis }

        // 找第一个未超过回复上限的消息（最早到达的优先消费）
        val entry = entries.values.firstOrNull { it.replyCount.get() < maxReplyCount } ?: return null

        val seq = entry.replyCount.incrementAndGet()
        return entry.officialId to seq
    }

    // ---- 消息存储 ----

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
