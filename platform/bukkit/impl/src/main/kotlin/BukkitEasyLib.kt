package com.github.mayblock.easylib.platform.bukkit.impl

import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.platform.bukkit.api.BukkitEasyLibApi
import com.github.mayblock.easylib.platform.bukkit.api.menu.MenuRegistry
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContextKey
import com.github.mayblock.easylib.platform.bukkit.impl.command.BukkitCommandRegistry
import com.github.mayblock.easylib.platform.bukkit.impl.item.CustomItemRegistryImpl
import com.github.mayblock.easylib.platform.bukkit.impl.menu.MenuManager
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.OverlayManager
import com.github.mayblock.easylib.platform.bukkit.impl.packet.BukkitPacketManager
import com.github.mayblock.easylib.platform.bukkit.impl.prompt.PromptApiImpl
import com.github.mayblock.easylib.platform.bukkit.impl.scheduler.BukkitExecutionContexts
import com.github.mayblock.easylib.platform.bukkit.impl.scheduler.BukkitTaskScheduler
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Bukkit 平台的 EasyLib 入口：由上游插件在 onEnable 中构造、onDisable 中 [close]。
 * 接入方式与最小示例见 `docs/getting-started.md`。
 */
class BukkitEasyLib(plugin: Plugin) : BukkitEasyLibApi {

    private val packetManager: PacketManager<Player> = BukkitPacketManager
    private val executionContexts = BukkitExecutionContexts(plugin)

    override val taskScheduler = BukkitTaskScheduler(plugin)

    private val menuManager = MenuManager(taskScheduler, executionContexts.sync, packetManager, plugin)

    // 注册其断线清理监听（onQuit）；shutdown() 时经 HandlerList.unregisterAll(this) 注销。
    override val promptApi = PromptApiImpl(packetManager).also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }
    override val customItemRegistry = CustomItemRegistryImpl(plugin)
    override val menuFactory = menuManager
    override val menuRegistry = menuManager
    override val overlayFactory = OverlayManager(taskScheduler, executionContexts.sync, packetManager, plugin)
    override val commandRegistry = BukkitCommandRegistry(plugin)

    @Suppress("UNCHECKED_CAST")
    override fun <T : BukkitExecutionContext> getExecutionContext(key: BukkitExecutionContextKey<T>): T =
        when (key) {
            BukkitExecutionContext.Sync -> executionContexts.sync as T
            BukkitExecutionContext.Async -> executionContexts.async as T
        }

    override fun close() {
        taskScheduler.cancelAllTasks()
        menuFactory.close()
        overlayFactory.close()
        customItemRegistry.shutdown()
        promptApi.shutdown()
    }
}
