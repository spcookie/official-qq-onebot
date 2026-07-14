package uesugi.official.qq.onebot

import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** ID 映射器：在官方 QQ 的 openid 和 OneBot 需要的 Long ID 之间做双向转换。 */
class IdMapper(config: IdMapConfig) {
    private val log = LoggerFactory.getLogger(IdMapper::class.java)
    private val auto = config.auto

    /** 本地群 ID -> 官方 group_openid。 */
    private val groupToOfficial = config.groups.toMutableMap()

    /** 官方 group_openid -> 本地群 ID。 */
    private val officialToGroup = config.groups.entries.associate { it.value to it.key }.toMutableMap()

    /** 本地成员 ID -> 官方 member_openid。 */
    private val memberToOfficial = config.members.toMutableMap()

    /** 官方 member_openid -> 本地成员 ID。 */
    private val officialToMember = config.members.entries.associate { it.value to it.key }.toMutableMap()

    /** 将官方群 openid 转换成本地 OneBot group_id；auto=true 时会自动生成稳定 ID。 */
    @Synchronized
    fun officialGroupToLocal(openid: String): Long? =
        officialToGroup[openid] ?: autoMap(openid, "group", groupToOfficial, officialToGroup)

    /** 将本地 OneBot group_id 转换成官方群 openid。 */
    @Synchronized
    fun localGroupToOfficial(groupId: Long): String? = groupToOfficial[groupId]

    /** 将官方群成员 openid 转换成本地 OneBot user_id；auto=true 时会自动生成稳定 ID。 */
    @Synchronized
    fun officialMemberToLocal(openid: String): Long? =
        officialToMember[openid] ?: autoMap(openid, "member", memberToOfficial, officialToMember)

    /** 将本地 OneBot user_id 转换成官方群成员 openid。 */
    @Synchronized
    fun localMemberToOfficial(userId: Long): String? = memberToOfficial[userId]

    /** 返回所有已配置或运行期自动生成的群映射，用于 get_group_list 等信息类 action。 */
    @Synchronized
    fun allGroups(): Map<Long, String> = groupToOfficial.toMap()

    /** 运行时动态添加成员映射。 */
    @Synchronized
    fun addMemberMapping(localUserId: Long, openid: String) {
        memberToOfficial[localUserId] = openid
        officialToMember[openid] = localUserId
        log.info("Added member mapping: {} -> {}", localUserId, openid)
    }

    /** 运行时动态添加群映射。 */
    @Synchronized
    fun addGroupMapping(localGroupId: Long, groupOpenid: String) {
        groupToOfficial[localGroupId] = groupOpenid
        officialToGroup[groupOpenid] = localGroupId
        log.info("Added group mapping: {} -> {}", localGroupId, groupOpenid)
    }

    /** 返回所有已配置或运行期自动生成的成员映射，用于 get_group_member_list 等信息类 action。 */
    @Synchronized
    fun allMembers(): Map<Long, String> = memberToOfficial.toMap()

    /** 自动映射只在开关开启时生效；手动配置冲突时不会被覆盖。 */
    private fun autoMap(
        openid: String,
        namespace: String,
        localToOfficial: MutableMap<Long, String>,
        officialToLocal: MutableMap<String, Long>,
    ): Long? {
        if (!auto) return null
        var candidate = stablePositiveId("$namespace:$openid")
        while (true) {
            val existingOpenid = localToOfficial[candidate]
            if (existingOpenid == null) {
                localToOfficial[candidate] = openid
                officialToLocal[openid] = candidate
                log.info("Auto mapped official {} openid to local id: {} -> {}", namespace, openid, candidate)
                return candidate
            }
            if (existingOpenid == openid) {
                officialToLocal[openid] = candidate
                return candidate
            }
            candidate = nextCandidate(candidate)
        }
    }

    /** 使用 SHA-256 的前 63 bit 生成稳定正数 Long，避免 JVM hashCode 随实现变化。 */
    private fun stablePositiveId(value: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var result = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            result = (result shl 8) or (digest[index].toLong() and 0xff)
        }
        return result and Long.MAX_VALUE
    }

    /** 极低概率哈希冲突时线性探测下一个正数 ID。 */
    private fun nextCandidate(value: Long): Long =
        if (value == Long.MAX_VALUE) 1 else value + 1
}
