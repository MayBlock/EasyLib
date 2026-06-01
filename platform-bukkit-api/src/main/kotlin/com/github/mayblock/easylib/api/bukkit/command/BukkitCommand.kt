package com.github.mayblock.easylib.api.bukkit.command

import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.mayblock.easylib.api.command.Command
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender

abstract class BukkitCommand(
    name: String,
    description: String? = null,
    val permission: String? = null,
): Command(name, description) {

    init {
        context {
            echoMessage = { _, msg, _, err ->
                sender.sendMessage((if (err) ChatColor.RED.toString() else "") + msg)
            }
        }
    }

    val sender by requireObject<CommandSender>()

    protected fun BaseCliktCommand<*>.player(
        name: String = "player",
        help: String = "the player to target",
        helpTags: Map<String, String> = emptyMap(),
        completionCandidates: CompletionCandidates? = null
    ) = argument(name, help, helpTags, completionCandidates).convert {
        Bukkit.getPlayer(it) ?: fail("player $it is offline or not found")
    }

    abstract override fun run()
}