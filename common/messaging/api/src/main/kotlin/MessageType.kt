package com.github.mayblock.easylib.messaging.api

import kotlin.annotation.Target

/**
 * 声明一个消息类的**线上标识**：跨实例协议的一部分，与 Kotlin 类名/包名解耦。
 *
 * 建议反向 DNS 前缀并把版本号写进名字（如 `"com.example.playerMove.v1"`），
 * 对消息类做不兼容改动时升版本号。payload 字段类型限制与更多约定见 `docs/message-bus.md`。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class MessageType(val value: String)
