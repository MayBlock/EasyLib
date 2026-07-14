package com.github.mayblock.easylib.impl.bukkit.command

import com.github.mayblock.easylib.api.bukkit.command.BukkitCommand
import com.github.mayblock.easylib.api.command.Command
import com.github.mayblock.easylib.api.command.CommandRegistry

import org.bukkit.command.SimpleCommandMap
import org.bukkit.plugin.Plugin
import org.slf4j.LoggerFactory

class BukkitCommandRegistry internal constructor(
    private val plugin: Plugin
) : CommandRegistry {

    private val logger by lazy { LoggerFactory.getLogger(BukkitCommandRegistry::class.java) }

    private val commandMap: SimpleCommandMap by lazy {
        plugin.server.let { server ->
            server::class.java.getDeclaredField("commandMap").apply {
                isAccessible = true
            }.get(server) as SimpleCommandMap
        }
    }

    override fun isRegistered(commandName: String) = commandMap.getCommand(commandName) != null

    override fun register(vararg commands: Command) {
        val actualCommands = commands.mapNotNull { command ->
            if (isRegistered(command.commandName)) {
                logger.warn("command ${command.commandName} was registered, skipped it")
                return@mapNotNull null
            }
            if (command !is BukkitCommand) {
                logger.warn("command must be an instance of ${BukkitCommand::class.java.name}, skipped it")
                return@mapNotNull null
            }
            ProxyCommand(command)
        }
        actualCommands.forEach(::register)
        logger.debug("Successfully registered ${actualCommands.size} commands")
    }

    private fun register(command: org.bukkit.command.Command) {
        commandMap.register(plugin.name, command)
        logger.debug("Registered command ${command.name}")
    }

    override fun unregister(command: Command) = unregister(command.commandName)
    override fun unregister(commandName: String) = commandMap.getCommand(commandName)?.unregister(commandMap) ?: false
    override fun unregisterAll() = commandMap.clearCommands()
}