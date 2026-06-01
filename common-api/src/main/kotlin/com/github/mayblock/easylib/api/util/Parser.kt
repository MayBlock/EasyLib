package com.github.mayblock.easylib.api.util

fun interface Parser<T> {
    fun parse(value: Any): T?
}