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
import org.bukkit.entity.Player

abstract class BukkitCommand(
    name: String,
    description: String? = null,
    val permission: String? = null,
    val playerOnly: Boolean = false,
): Command(name, description) {

    init {
        context {
            echoMessage = { _, msg, _, err ->
                sender.sendMessage((if (err) ChatColor.RED.toString() else "") + msg)
            }
        }
    }

    private val sender by requireObject<CommandSender>()

    protected fun BaseCliktCommand<*>.player(
        name: String = "player",
        help: String = "the player to target",
        helpTags: Map<String, String> = emptyMap(),
        completionCandidates: CompletionCandidates? = null
    ) = argument(name, help, helpTags, completionCandidates).convert {
        Bukkit.getPlayer(it) ?: fail("player $it is offline or not found")
    }

    final override fun run() {
        if (playerOnly && sender !is Player) {
            echo("Only players can use this command")
            return
        }
        execute(sender)
    }

    abstract fun execute(sender: CommandSender)
}