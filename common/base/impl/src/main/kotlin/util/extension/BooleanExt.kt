package com.github.mayblock.easylib.base.impl.util.extension

inline fun Boolean.ifTrue(block: () -> Unit): Boolean {
    if (this) block()
    return this
}

inline fun Boolean.ifFalse(block: () -> Unit): Boolean {
    if (!this) block()
    return this
}