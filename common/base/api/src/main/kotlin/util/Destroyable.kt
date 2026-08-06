package com.github.mayblock.easylib.base.api.util

interface Destroyable {
    val isDestroyed: Boolean
    fun destroy()
}