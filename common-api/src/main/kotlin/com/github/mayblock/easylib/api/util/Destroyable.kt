package com.github.mayblock.easylib.api.util

interface Destroyable {
    val isDestroyed: Boolean
    fun destroy()
}