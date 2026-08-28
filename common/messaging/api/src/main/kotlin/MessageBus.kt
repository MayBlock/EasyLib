package com.github.mayblock.easylib.messaging.api

import com.github.mayblock.easylib.base.api.util.Destroyable
import kotlinx.coroutines.flow.Flow

/**
 * 跨实例消息总线。
 *
 * 投递语义是 fire-and-forget 的**至多一次**：允许丢失、绝不重复、发送失败不重试。
 * **上游插件停用时必须调用 [destroy]**，否则长寿命的 Redis 客户端会一直持有消息类，泄漏插件 classloader。
 * 使用方式、适用场景与注意事项见 `docs/message-bus.md`。
 *
 * [destroy] 之后 [publish] / [subscribe] / [joinGroup] / [leaveGroup] 一律抛 [IllegalStateException]；
 * 已经在 collect 的 Flow 不会自行结束，需要调用方取消自己的作用域。
 */
interface MessageBus : Destroyable {

    /** 本实例的 id，跨重启稳定。 */
    val instanceId: String

    /** 当前已加入的群组，返回不可变快照，不随后续 join/leave 变化。 */
    val groups: Set<String>

    /**
     * 向 [target] 投递 [message]。
     * @throws IllegalArgumentException [message] 的类未标注 [MessageType]
     */
    suspend fun publish(target: Target, message: Any)

    /**
     * 订阅类型为 [type] 的消息，返回冷 [Flow]：collect 才开始消费，取消 collect 即退订；
     * **Flow 永不完成**（[destroy] 之后也不会），请在可取消的作用域里 collect。
     * [includeSelf] 为 false（默认）时不接收本实例自己发出的消息。
     * @throws IllegalArgumentException [type] 缺少 [MessageType] 或其线上名已被另一个类占用（立即抛出，不等到 collect）
     */
    fun <M : Any> subscribe(type: Class<M>, includeSelf: Boolean = false): Flow<Envelope<M>>

    /** 加入群组 [name]，之后会收到发往该群组的消息。已加入时是空操作。 */
    suspend fun joinGroup(name: String)

    /** 离开群组 [name]。未加入时是空操作。 */
    suspend fun leaveGroup(name: String)
}

/** [MessageBus.subscribe] 的 reified 便捷形式。 */
inline fun <reified M : Any> MessageBus.subscribe(includeSelf: Boolean = false): Flow<Envelope<M>> =
    subscribe(M::class.java, includeSelf)
