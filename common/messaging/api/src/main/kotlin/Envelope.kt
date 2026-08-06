package com.github.mayblock.easylib.messaging.api

import kotlin.time.Instant

/**
 * 一条收到的消息及其元数据。
 *
 * @property payload 解码后的消息体
 * @property id 消息唯一 id，用于日志关联（排查「发出去了但对方没处理」）
 * @property senderId 发送方实例 id
 * @property time 发送方生成消息的时刻，用于延迟观测
 */
data class Envelope<out M : Any>(
    val payload: M,
    val id: String,
    val senderId: String,
    val time: Instant,
)
