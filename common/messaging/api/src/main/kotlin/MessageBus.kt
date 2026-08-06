package com.github.mayblock.easylib.messaging.api

import com.github.mayblock.easylib.base.api.util.Destroyable
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * 跨实例消息总线。
 *
 * **投递语义是 fire-and-forget，允许丢失。** 实例宕机、重启、甚至一次短暂重连期间的消息
 * 全部消失且不补发。适合过期作废的消息（玩家切服通知、全网公告、对局事件）；需要至少一次
 * 送达的场景本总线不适用。
 *
 * 需要查询对端**状态**（如「好友是否在线」）时不要用消息广播——那会让流量与「玩家数 ×
 * 好友数」成正比。状态应写入分布式缓存供人直接读取，消息只用于通知变更。
 */
interface MessageBus : Destroyable {

    /** 本实例的 id，跨重启稳定。 */
    val instanceId: String

    /** 当前已加入的群组，返回不可变快照，不随后续 join/leave 变化。 */
    val groups: Set<String>

    /**
     * 向 [target] 投递 [message]。
     *
     * [message] 的类必须标注 [MessageType]，否则抛 [IllegalArgumentException]。
     */
    suspend fun publish(target: Target, message: Any)

    /**
     * 订阅类型为 [type] 的消息。
     *
     * 返回冷 [Flow]：真正的消费发生在 `collect`，取消 collect 即退订。取消只是脱离内部的
     * 消息分发，不会注销 Redis 监听器（其它订阅方仍在用）。
     *
     * [includeSelf] 为 false（默认）时，本实例自己发出的消息不会回灌给自己。
     *
     * [type] 缺少 [MessageType] 注解、或其线上名已被另一个类占用时，**立即**抛
     * [IllegalArgumentException]（不是等到 collect 才抛）。
     *
     * **返回的 Flow 永不完成**，[Destroyable.destroy] 之后也不会——关停只是让新消息不再到来。
     * 因此 `bus.subscribe<X>().collect { }` 会一直挂着；请在一个你能取消的作用域里 collect，
     * 不要指望它自行结束。
     */
    fun <M : Any> subscribe(type: KClass<M>, includeSelf: Boolean = false): Flow<Envelope<M>>

    /** 加入群组 [name]，之后会收到发往该群组的消息。已加入时是空操作。 */
    suspend fun joinGroup(name: String)

    /** 离开群组 [name]。未加入时是空操作。 */
    suspend fun leaveGroup(name: String)
}

/** [MessageBus.subscribe] 的 reified 便捷形式。 */
inline fun <reified M : Any> MessageBus.subscribe(includeSelf: Boolean = false): Flow<Envelope<M>> =
    subscribe(M::class, includeSelf)
