package com.github.mayblock.easylib.impl.bukkit

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.BukkitEasyLibApi
import com.github.mayblock.easylib.api.bukkit.bukkitApi
import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.command.BukkitCommandRegistry
import com.github.mayblock.easylib.impl.bukkit.extension.ItemExtensionApiImpl
import com.github.mayblock.easylib.impl.bukkit.menu.MenuManager
import com.github.mayblock.easylib.impl.bukkit.overlay.OverlayManager
import com.github.mayblock.easylib.impl.bukkit.packet.BukkitPacketManager
import com.github.mayblock.easylib.impl.bukkit.prompt.PromptApiImpl
import com.github.mayblock.easylib.impl.bukkit.prompt.PromptQuitListener
import com.github.mayblock.easylib.impl.bukkit.scheduler.BukkitTaskScheduler
import com.github.mayblock.easylib.packetevents.PacketManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin

class BukkitEasyLib(
    plugin: Plugin,
) : BukkitEasyLibApi {

    companion object {
        internal val api: BukkitEasyLib get() = EasyLibApi.api.bukkitApi() as BukkitEasyLib
    }

    override val taskScheduler: TaskScheduler = BukkitTaskScheduler(plugin)
    override val dispatcher = BukkitDispatcherImpl(plugin)
    override val promptApi: PromptApi by lazy { PromptApiImpl }
    override val itemExtensionApi = ItemExtensionApiImpl(plugin)
    override val menuFactory = MenuManager(taskScheduler, plugin)
    override val overlayFactory = OverlayManager(taskScheduler, plugin)
    override val commandRegistry = BukkitCommandRegistry(plugin)
    val packetManager: PacketManager<Player> = BukkitPacketManager

    /** Prompt 断线清理监听器（见 [PromptQuitListener]）；随本实例注册，`close()` 时注销。 */
    private val promptQuitListener = PromptQuitListener().also {
        Bukkit.getPluginManager().registerEvents(it, plugin)
    }

    override fun close() {
        taskScheduler.cancelAllTasks()
        menuFactory.close()
        overlayFactory.close()
        commandRegistry.unregisterAll()
        HandlerList.unregisterAll(itemExtensionApi)
        HandlerList.unregisterAll(promptQuitListener)
        PromptApiImpl.shutdown()
    }

    // 全局单例的发布必须放在类体最末尾——即所有属性都已完成初始化之后：
    // `promptApi` 是 `by lazy { PromptApiImpl }`，一旦有人在构造期间提前触碰它（或直接引用 PromptApiImpl 触发其
    // object 的 <clinit>），PromptApiImpl 的 init 块会立刻读 `EasyLibApi.api.bukkitApi()`；
    // 若那时 `EasyLibApi.api` 已经指向本实例、但本实例自身的属性（taskScheduler/commandRegistry/...）还没初始化完，
    // 读到的就是半初始化对象（字段仍是 Kotlin 默认值），会导致 NPE 或读到过期状态。
    // 把发布动作挪到最后，能保证外部第一次拿到 `EasyLibApi.api` 时，本实例已经完全构造完毕。
    init {
        EasyLibApi.api = this
    }
}