package com.github.mayblock.easylib.messaging.api

import kotlin.annotation.Target

/**
 * 声明一个消息类的**线上标识**。
 *
 * 这个字符串是跨实例协议的一部分，与 Kotlin 类名、包名彻底解耦——重命名或移动消息类
 * 不会破坏协议，而依赖 FQCN 就会（一次 IDE 重构就能静默改掉线上格式）。
 *
 * 建议采用反向 DNS 前缀（CloudEvents 的推荐做法），由该域名的组织定义语义，
 * 顺带规避跨插件的线上名冲突：
 *
 * ```
 * @MessageType("com.example.playerMove.v1")
 * data class PlayerMove(val player: String)
 * ```
 *
 * **版本入名**：对消息类做不兼容改动（删字段、改名、改类型、加必填字段）时必须提升名字里的
 * 版本号。老实例因为线上名不匹配而干净地收不到，而不是收到后解不开——前者是定义好的行为，
 * 后者是不可预料的生产故障。新增**带默认值**的字段是兼容改动，不必升版本。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class MessageType(val value: String)
