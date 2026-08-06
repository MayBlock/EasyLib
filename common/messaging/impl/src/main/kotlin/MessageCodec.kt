package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.MessageType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 信封的编解码，以及消息类到线上名的解析。
 *
 * [idGenerator] 与 [clock] 是为了让编码结果可测——生产使用默认值即可。
 */
internal class MessageCodec(
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Instant = { Clock.System.now() },
) {

    /** 线上名 -> 声明它的类。用于检测两个类抢同一个名字。 */
    private val claimedNames = ConcurrentHashMap<String, Class<*>>()

    /**
     * 取 [type] 的线上名。
     *
     * 全程不做按名查类：注解从调用方给的 Class 对象上读取。Bukkit 下每个插件有独立
     * classloader，`Class.forName` 会用本库的 loader 而不是消息类所属插件的，必然出错。
     */
    fun wireNameOf(type: KClass<*>): String {
        val java = type.java
        val annotation = java.getAnnotation(MessageType::class.java)
        requireNotNull(annotation) {
            "${java.name} is not annotated with @MessageType; " +
                "a message class must declare its wire identity explicitly"
        }
        val name = annotation.value
        val previous = claimedNames.putIfAbsent(name, java)
        require(previous == null || previous == java) {
            "wire name '$name' is claimed by both ${previous!!.name} and ${java.name}"
        }
        return name
    }
}
