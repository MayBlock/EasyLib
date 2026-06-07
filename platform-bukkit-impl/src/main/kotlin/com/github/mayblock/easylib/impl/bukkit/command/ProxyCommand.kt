package com.github.mayblock.easylib.impl.bukkit.command

import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.core.*
import com.github.mayblock.easylib.api.bukkit.command.BukkitCommand
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ProxyCommand internal constructor(
    private val command: BukkitCommand
) : Command(command.commandName) {

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
        sender: CommandSender,
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

    override fun tabComplete(
        sender: CommandSender,
        alias: String,
        args: Array<out String>
    ): List<String> {
        val typing = args.lastOrNull() ?: ""
        val completed = args.dropLast(1)
        return resolveCompletions(this.command, completed, sender)
            .filter { it.startsWith(typing, ignoreCase = true) }
            .sorted()
    }

    /**
     * 递归解析命令树，返回当前光标位置的所有候选词。
     *
     * @param cmd  当前命令节点
     * @param done 光标之前已完成的 token 列表（不含正在输入的最后一个词）
     */
    private fun resolveCompletions(
        cmd: CoreCliktCommand,
        done: List<String>,
        sender: CommandSender
    ): List<String> {
        if (done.isEmpty()) {
            return cmd.registeredSubcommandNames() +
                    cmd.registeredOptions().flatMap { it.names }
        }
        val head = done.first()
        val tail = done.drop(1)

        // ① 匹配子命令 → 递归进入
        val sub = cmd.registeredSubcommands().firstOrNull { it.commandName == head }
        if (sub != null) return resolveCompletions(sub, tail, sender)

        // ② head 是选项（以 - 开头）
        if (head.startsWith("-")) {
            val option = cmd.registeredOptions().firstOrNull { head in it.names }
            if (option != null) {
                // Option.nvalues 是 IntRange，用 .last 判断最多消费几个值
                val maxValues = option.nvalues.last
                if (maxValues > 0 && tail.size < maxValues) {
                    // 当前光标仍在该选项的值位置
                    return resolveCandidates(option.completionCandidates)
                }
                // 已消费完选项值，继续解析剩余 token
                val remaining = tail.drop(maxValues.coerceAtLeast(0))
                return resolveCompletions(cmd, remaining, sender)
            }
            // 未知选项，回退到当前命令可用的选项名
            return cmd.registeredOptions().flatMap { it.names }
        }

        // ③ head 是位置参数的值 → 计算当前消费到第几个 argument
        val positionalTokens = collectPositionalTokens(done, cmd)
        val arguments = cmd.registeredArguments()

        // 确定光标对应的是哪个 argument
        var consumed = 0
        for (arg in arguments) {
            val capacity = if (arg.nvalues < 0) Int.MAX_VALUE else arg.nvalues
            if (positionalTokens.size < consumed + capacity) {
                // 光标落在这个 argument 内
                return resolveCandidates(arg.completionCandidates)
            }
            consumed += capacity
        }

        // 所有 argument 已满，可能还有子命令或选项
        return cmd.registeredSubcommandNames() +
                cmd.registeredOptions().flatMap { it.names }
    }

    /**
     * 从 done 中过滤掉"选项 + 选项值"的 token，只保留位置参数的 token。
     * Option.nvalues 是 IntRange → 用 .last；Argument.nvalues 是 Int → 直接用。
     */
    private fun collectPositionalTokens(
        done: List<String>,
        cmd: CoreCliktCommand
    ): List<String> {
        val positional = mutableListOf<String>()
        var i = 0
        while (i < done.size) {
            val token = done[i]
            if (token.startsWith("-")) {
                // Option.nvalues: IntRange
                val opt = cmd.registeredOptions().firstOrNull { token in it.names }
                val skip = opt?.nvalues?.last?.coerceAtLeast(0) ?: 0
                i += 1 + skip
            } else {
                positional.add(token)
                i++
            }
        }
        return positional
    }

    /**
     * 将 CompletionCandidates 转换为实际的字符串候选词列表
     */
    private fun resolveCandidates(
        candidates: CompletionCandidates,
    ): List<String> = when (candidates) {
        is CompletionCandidates.Fixed -> candidates.candidates.toList()
        is CompletionCandidates.Username -> Bukkit.getOnlinePlayers().map { it.name }
        else -> emptyList()
    }
}