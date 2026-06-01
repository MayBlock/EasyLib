package com.github.mayblock.easylib.impl.bukkit.command

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.parse
import com.github.mayblock.easylib.api.bukkit.command.BukkitCommand
import org.bukkit.ChatColor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ProxyCommand(
    private val command: BukkitCommand
) : org.bukkit.command.Command(command.commandName) {

    private val logger: Logger by lazy { LoggerFactory.getLogger(ProxyCommand::class.java) }

    init {
        command.apply {
            description?.let(::setDescription)
            usage.takeIf { it.isNotBlank() }?.let(::setUsage)
            permission?.let(::setPermission)
            aliases().takeIf { it.isNotEmpty() }?.map { it.key }?.let(::setAliases)
        }
    }

    override fun execute(
        sender: org.bukkit.command.CommandSender,
        commandLabel: String,
        args: Array<out String>
    ): Boolean {
        val args = (command.aliases()[commandLabel]?.drop(1) ?: emptyList()) + args
        val perm = permission
        if (!perm.isNullOrBlank() && !sender.hasPermission(perm)) {
            sender.sendMessage("You do not have permission to use this command.")
            return false
        }
        try {
            command.context {
                obj = sender
            }.parse(args)
            return true
        } catch (e: Exception) {
            if (e is CliktError) {
                sender.sendMessage("${ChatColor.RED}${(e.message ?: command.getFormattedHelp(e).takeIf { !it.isNullOrBlank() } ?: "Executing command '$commandLabel' failed!")}")
            } else {
                e.printStackTrace()
                logger.error("Error while executing command: $e")
            }
        }
        return false
    }
}