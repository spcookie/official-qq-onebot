package uesugi.official.qq.onebot

import java.util.concurrent.ConcurrentHashMap

/** 全局 IdMapper 注册表，允许外部按 bot key 查找并动态更新映射。 */
object IdMapperRegistry {
    private val mappers = ConcurrentHashMap<String, IdMapper>()

    fun register(key: String, mapper: IdMapper) {
        mappers[key] = mapper
    }

    fun get(key: String): IdMapper? = mappers[key]
}
