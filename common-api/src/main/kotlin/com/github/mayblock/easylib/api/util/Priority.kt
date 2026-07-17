package com.github.mayblock.easylib.api.util

data class Priority(val priority: Int) : Comparable<Priority> {

    override fun compareTo(other: Priority) = priority.compareTo(other.priority)

    companion object {
        val DEFAULT = Priority(10)

        /**
         * 最后执行、只观察不修改（语义同 Bukkit 的 `EventPriority.MONITOR`）。
         *
         * 事件按 priority 升序触发，故本值（[Int.MAX_VALUE]）使监听排在最末，
         * 不存在更大的优先级。但这不构成「绝对最后」的强保证：[Priority] 构造器公开，
         * 其他订阅方可传入等值，届时按订阅顺序决胜负。
         */
        val MONITOR = Priority(Int.MAX_VALUE)
    }
}