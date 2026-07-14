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

    /** 本注册表实际登记过的命令：`unregisterAll` 只应清理这些，绝不能碰服务器上其它插件注册的命令。 */
    private val registered = mutableMapOf<String, ProxyCommand>()

    private val commandMap: SimpleCommandMap by lazy { resolveCommandMap() }

    /**
     * 健壮地拿到服务器的 [SimpleCommandMap]：
     * 1. 先尝试 `Server#getCommandMap()`（Paper 近年公开的 API；用反射调用以避免编译期绑死 Paper）。
     * 2. 失败则沿 `server` 的类层级向上找私有字段 `commandMap`（原版 CraftServer 及绝大多数分支/Mock 实现的做法），
     *    而不是只查 `server::class.java` 这一层——避免 Mock/代理类把字段声明在父类上时反射不到。
     * 3. 两条路都失败则抛出带排查指引的异常，而不是让 NPE/ClassCastException 悄悄发生在别处。
     */
    private fun resolveCommandMap(): SimpleCommandMap {
        val server = plugin.server
        try {
            val getCommandMap = server.javaClass.getMethod("getCommandMap")
            (getCommandMap.invoke(server) as? SimpleCommandMap)?.let { return it }
        } catch (_: NoSuchMethodException) {
            // 该服务端实现没有公开的 getCommandMap()，走字段反射兜底。
        }

        var clazz: Class<*>? = server.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField("commandMap").apply { isAccessible = true }
                (field.get(server) as? SimpleCommandMap)?.let { return it }
            } catch (_: NoSuchFieldException) {
                // 当前层级没有该字段，继续向上找。
            }
            clazz = clazz.superclass
        }

        throw IllegalStateException(
            "Unable to resolve the server's SimpleCommandMap on ${server.javaClass.name}. " +
                "This platform/mock is not supported by BukkitCommandRegistry; " +
                "expected a genuine Spigot/Paper server (or a MockBukkit ServerMock in tests)."
        )
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

    private fun register(command: ProxyCommand) {
        commandMap.register(plugin.name, command)
        registered[command.name.lowercase()] = command
        logger.debug("Registered command ${command.name}")
    }

    override fun unregister(command: Command) = unregister(command.commandName)

    /**
     * 精确注销：从 [registered] 摘取本注册表登记的 [ProxyCommand]，调其 `unregister`，
     * 并从 `SimpleCommandMap#knownCommands` 里把该命令对应的全部条目（主名、别名、`plugin:name` 形式）一并摘除。
     * 修复前只调用了 `Command#unregister`，`knownCommands` 里的引用不会被清，
     * 命令依旧能通过 `commandMap.getCommand` 解析到并被执行。
     */
    override fun unregister(commandName: String): Boolean {
        val cmd = registered.remove(commandName.lowercase()) ?: return false
        cmd.unregister(commandMap)
        val known = knownCommands
        if (known != null) {
            // 故意先收集 key 再逐个 known.remove(key)，而不是 known.entries.removeIf { ... }：
            // 现代 Paper（含本模块测试用的 MockBukkit）的 knownCommands 实际注入的是一个
            // Brigadier-forwarding 的 Map 视图（`BukkitBrigadierForwardingMapMock` 等），其
            // entrySet() 的迭代器 remove() 是不生效的空操作，但 Map#remove(key) 本身是正确实现的。
            // 只依赖 entrySet().removeIf 在这类环境下会“看起来移除成功”实则命令依旧可解析——已用调试断言验证过。
            known.entries.filter { it.value === cmd }.map { it.key }.forEach(known::remove)
        } else {
            logger.warn(
                "knownCommands is unavailable via reflection; '$commandName' was unregistered from the " +
                    "CommandMap but may remain resolvable under its old aliases/namespace until the JVM reloads it."
            )
        }
        return true
    }

    /** 只注销本注册表登记过的命令；不再调用 `commandMap.clearCommands()`（那会清空全服所有插件的命令）。 */
    override fun unregisterAll() {
        registered.keys.toList().forEach { unregister(it) }
    }

    /**
     * `SimpleCommandMap#knownCommands` 的反射句柄：故意用 `SimpleCommandMap::class.java` 而非
     * `commandMap::class.java`，因为该字段声明在 `SimpleCommandMap` 本身（子类/Mock 实现直接继承其内存布局），
     * 用声明类反射总能取到，不受运行时实际子类影响。
     *
     * 若反射失败（例如某些非 SimpleCommandMap 派生的 CommandMap 实现），降级为仅调用
     * `cmd.unregister(commandMap)`（见 [unregister]），并 warn 提示 —— 生产环境的 Spigot/Paper 均为
     * `SimpleCommandMap` 子类，不受影响。
     */
    @Suppress("UNCHECKED_CAST")
    private val knownCommands: MutableMap<String, org.bukkit.command.Command>? by lazy {
        runCatching {
            SimpleCommandMap::class.java.getDeclaredField("knownCommands")
                .apply { isAccessible = true }
                .get(commandMap) as MutableMap<String, org.bukkit.command.Command>
        }.onFailure {
            logger.warn("Unable to access SimpleCommandMap#knownCommands via reflection: ${it.message}")
        }.getOrNull()
    }
}