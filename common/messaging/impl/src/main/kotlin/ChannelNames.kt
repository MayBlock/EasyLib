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
        is Target.Group -> "$namespace:msg:group:${target.name}"
        is Target.Instance -> "$namespace:msg:inst:${target.id}"
    }
}
