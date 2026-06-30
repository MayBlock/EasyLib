package com.github.mayblock.easylib.api.util

data class Priority(val priority: Int) : Comparable<Priority> {

    override fun compareTo(other: Priority) = priority.compareTo(other.priority)

    companion object {
        val DEFAULT = Priority(10)
    }
}