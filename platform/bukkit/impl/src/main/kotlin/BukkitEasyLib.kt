package com.github.mayblock.easylib.base.impl.bukkit

import com.github.mayblock.easylib.base.api.EasyLibApi
import com.github.mayblock.easylib.base.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.base.api.bukkit.bukkitApi
import com.github.mayblock.easylib.base.impl.bukkit.command.BukkitCommandRegistry
import com.github.mayblock.easylib.base.impl.bukkit.item.CustomItemRegistryImpl
import com.github.mayblock.easylib.base.impl.bukkit.menu.MenuManager
import com.github.mayblock.easylib.base.impl.bukkit.overlay.OverlayManager
import com.github.mayblock.easylib.base.impl.bukkit.packet.BukkitPacketManager
import com.github.mayblock.easylib.base.impl.bukkit.prompt.PromptApiImpl
import com.github.mayblock.easylib.base.impl.bukkit.scheduler.BukkitDispatcherImpl
import com.github.mayblock.easylib.base.impl.bukkit.scheduler.BukkitTaskExecutorsImpl
import com.github.mayblock.easylib.base.impl.bukkit.scheduler.BukkitTaskScheduler
import com.github.mayblock.easylib.packetevents.api.PacketManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class BukkitEasyLib(
    plugin: Plugin,
) : BukkitEasyLibApi {

    companion object {
        internal val api: BukkitEasyLib get() = EasyLibApi.api.bukkitApi() as BukkitEasyLib
    }

    val packetManager: PacketManager<Player> = BukkitPacketManager
    override val taskExecutors = BukkitTaskExecutorsImpl(plugin)
    override val taskScheduler = BukkitTaskScheduler(plugin)
    override val dispatcher = BukkitDispatcherImpl(plugin)
    // 注册其断线清理监听（onQuit）；shutdown() 时经 HandlerList.unregisterAll(this) 注销。
    override val promptApi = PromptApiImpl(packetManager).also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }
    override val customItemRegistry = CustomItemRegistryImpl(plugin)
    override val menuFactory = MenuManager(taskScheduler, packetManager, plugin)
    override val overlayFactory = OverlayManager(taskScheduler, plugin)
    override val commandRegistry = BukkitCommandRegistry(plugin)

    override fun close() {
        taskScheduler.cancelAllTasks()
        menuFactory.close()
        overlayFactory.close()
        customItemRegistry.shutdown()
        promptApi.shutdown()
    }

    // 全局单例的发布必须放在类体最末尾——即所有属性都已完成初始化之后：
    // 若在属性初始化之前就把 `EasyLibApi.api` 指向本实例，任何在构造期间经全局单例回读本实例的代码
    // （如各组件内部再取 `BukkitEasyLib.api`），读到的就是半初始化对象（字段仍是 Kotlin 默认值），
    // 会导致 NPE 或读到过期状态。把发布动作挪到最后，能保证外部第一次拿到 `EasyLibApi.api` 时，
    // 本实例已经完全构造完毕。
    init {
        EasyLibApi.api = this
    }
}