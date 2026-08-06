package com.github.mayblock.easylib.messaging.impl

import com.fasterxml.jackson.databind.JsonNode

/**
 * 线上 JSON 信封。
 *
 * [payload] 保持为未解码的 [JsonNode]：只有线上名匹配的订阅方才会把它解成具体类型，
 * 不匹配的消息完全不必付解码成本。
 *
 * [time] 是 ISO-8601 字符串而不是 `Instant`——这样不需要给 Jackson 装任何时间模块，
 * 转换在 [MessageCodec] 的边界上做。
 */
internal data class WireEnvelope(
    val id: String,
    val sender: String,
    val type: String,
    val time: String,
    val payload: JsonNode,
)
