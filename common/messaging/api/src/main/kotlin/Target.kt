package com.github.mayblock.easylib.messaging.api

/**
 * 消息的投递目标。
 *
 * 频道名由目标推导，调用方不需要（也不应该）自己拼频道字符串。
 */
sealed interface Target {

    /** 投递给所有实例。 */
    data object All : Target

    /** 投递给加入了群组 [name] 的所有实例。 */
    data class Group(val name: String) : Target

    /** 投递给 id 为 [id] 的那个实例。 */
    data class Instance(val id: String) : Target
}
