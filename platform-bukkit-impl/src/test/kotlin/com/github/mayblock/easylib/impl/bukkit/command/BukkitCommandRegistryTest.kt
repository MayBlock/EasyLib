package com.github.mayblock.easylib.impl.bukkit.command

import com.github.mayblock.easylib.api.bukkit.command.BukkitCommand
import org.bukkit.command.CommandSender
import org.bukkit.command.SimpleCommandMap
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 无操作命令：仅记录是否被 Clikt 解析流程实际执行到，用于验证注销后命令不可再被执行。 */
private class NoopCommand(
    name: String,
    private val aliasMap: Map<String, List<String>> = emptyMap()
) : BukkitCommand(name) {

    var executed = false
        private set

    override fun aliases(): Map<String, List<String>> = aliasMap

    override fun execute(sender: CommandSender) {
        executed = true
    }
}

/**
 * 覆盖 [BukkitCommandRegistry] 的精确注销：
 * 修复前 `unregister` 只调 `Command#unregister`，不清 `SimpleCommandMap#knownCommands`，
 * 命令名/别名/`plugin:name` 形式依旧能通过 `commandMap.getCommand` 解析到（且能被执行）；
 * `unregisterAll` 直接调 `commandMap.clearCommands()`，会清空整个服务器的命令表（含其它插件的）。
 */
class BukkitCommandRegistryTest {

    private lateinit var plugin: org.bukkit.plugin.Plugin
    private lateinit var registry: BukkitCommandRegistry

    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        registry = BukkitCommandRegistry(plugin)
    }

    @AfterTest
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `unregister 后命令从 CommandMap 彻底消失且不可再执行`() {
        val command = NoopCommand("greet")
        registry.register(command)

        assertTrue(registry.isRegistered("greet"))
        assertTrue(plugin.server.dispatchCommand(plugin.server.consoleSender, "greet"))
        assertTrue(command.executed)

        assertTrue(registry.unregister("greet"))

        assertNull(plugin.server.commandMap.getCommand("greet"))
        assertFalse(plugin.server.dispatchCommand(plugin.server.consoleSender, "greet"))
        assertKnownCommandsDoNotContain("greet")
    }

    @Test
    fun `unregisterAll 只清空本注册表登记的命令，不影响 CommandMap 中的其它命令`() {
        val ours = NoopCommand("ours")
        registry.register(ours)

        val untouched = object : org.bukkit.command.Command("untouched") {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
        }
        plugin.server.commandMap.register("otherplugin", untouched)

        registry.unregisterAll()

        assertNull(plugin.server.commandMap.getCommand("ours"))
        assertEquals(untouched, plugin.server.commandMap.getCommand("untouched"))
    }

    @Test
    fun `别名与命名空间形式也一并从 knownCommands 移除`() {
        val command = NoopCommand("main", mapOf("al" to listOf("al")))
        registry.register(command)

        assertTrue(registry.unregister("main"))

        assertNull(plugin.server.commandMap.getCommand("main"))
        assertNull(plugin.server.commandMap.getCommand("al"))
        assertNull(plugin.server.commandMap.getCommand("${plugin.name}:main"))
    }

    private fun assertKnownCommandsDoNotContain(name: String) {
        val field = SimpleCommandMap::class.java.getDeclaredField("knownCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val known = field.get(plugin.server.commandMap) as Map<String, org.bukkit.command.Command>
        assertTrue(known.keys.none { it == name || it.endsWith(":$name") })
    }
}
