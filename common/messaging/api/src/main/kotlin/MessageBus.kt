package com.github.mayblock.easylib.messaging.api

interface MessageBus {

    fun publish(channel: String, message: Any)
    fun subscribe(channel: String, handler: (Any) -> Unit)
}