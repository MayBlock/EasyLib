package com.github.mayblock.easylib.api.command

interface CommandRegistry {

    fun isRegistered(commandName: String): Boolean
    fun register(vararg commands: Command)
    fun unregister(command: Command): Boolean
    fun unregister(commandName: String): Boolean
    fun unregisterAll()
}