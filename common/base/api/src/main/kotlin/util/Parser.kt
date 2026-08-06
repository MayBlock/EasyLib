package com.github.mayblock.easylib.base.api.util

fun interface Parser<T> {
    fun parse(value: Any): T?
}