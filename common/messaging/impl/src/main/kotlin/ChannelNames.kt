package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.Target

/**
 * [Target] 到 Redis 频道名的映射。
 *
 * 频道只按**寻址**划分，不含消息类型——把类型编进频道名会让频道数变成「寻址数 × 类型数」，
 * 并把类型名塞进线上协议。类型过滤在客户端做。
 */
internal object ChannelNames {

    fun of(namespace: String, target: Target): String = when (target) {
        is Target.All -> "$namespace:msg:all"
        is Target.Group -> groupPrefix(namespace) + target.name
        is Target.Instance -> "$namespace:msg:inst:${target.id}"
    }

    /**
     * [of] 对 [Target.Group] 的逆映射：从频道名反推群组名；非本命名空间的群组频道返回 null。
     *
     * 这让「已加入的群组」可以直接从监听器表的键推导出来，而不必单独维护一份群组集合——
     * 两份状态靠手工同步曾是 destroy 竞态下 groups 残留的直接根源。
     */
    fun groupOf(namespace: String, channel: String): String? {
        val prefix = groupPrefix(namespace)
        return if (channel.startsWith(prefix)) channel.substring(prefix.length) else null
    }

    private fun groupPrefix(namespace: String) = "$namespace:msg:group:"
}
