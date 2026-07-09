package uesugi.official.qq.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ID 映射器测试，覆盖官方 openid 和本地 Long ID 的双向转换。 */
class IdMapperTest {
    /** 已配置 ID 应能双向转换；未配置 openid 应返回 null，交给上层丢弃事件。 */
    @Test
    fun mapsIdsBothWays() {
        val mapper = IdMapper(IdMapConfig(groups = mapOf(1L to "g"), members = mapOf(2L to "m")))

        assertEquals(1L, mapper.officialGroupToLocal("g"))
        assertEquals("g", mapper.localGroupToOfficial(1L))
        assertEquals(2L, mapper.officialMemberToLocal("m"))
        assertEquals("m", mapper.localMemberToOfficial(2L))
        assertNull(mapper.officialGroupToLocal("missing"))
    }

    /** auto=false 时保持严格模式，未知 openid 不会自动生成 ID。 */
    @Test
    fun keepsStrictModeByDefault() {
        val mapper = IdMapper(IdMapConfig(groups = emptyMap(), members = emptyMap()))

        assertNull(mapper.officialGroupToLocal("g"))
        assertNull(mapper.officialMemberToLocal("m"))
    }

    /** auto=true 时未知 openid 会生成稳定 ID，并能反向查回 openid。 */
    @Test
    fun autoMapsUnknownIdsWhenEnabled() {
        val mapper = IdMapper(IdMapConfig(groups = emptyMap(), members = emptyMap(), auto = true))

        val groupId = mapper.officialGroupToLocal("g")
        val memberId = mapper.officialMemberToLocal("m")

        assertEquals(groupId, mapper.officialGroupToLocal("g"))
        assertEquals("g", mapper.localGroupToOfficial(groupId!!))
        assertEquals(memberId, mapper.officialMemberToLocal("m"))
        assertEquals("m", mapper.localMemberToOfficial(memberId!!))
    }

    /** 手动配置优先于自动映射，已配置 ID 不会被重新生成。 */
    @Test
    fun explicitMappingsWinOverAutoMapping() {
        val mapper = IdMapper(IdMapConfig(groups = mapOf(1L to "g"), members = mapOf(2L to "m"), auto = true))

        assertEquals(1L, mapper.officialGroupToLocal("g"))
        assertEquals(2L, mapper.officialMemberToLocal("m"))
    }
}
