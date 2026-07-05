package com.github.mayblock.easylib.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.slot.InventoryClickEvent
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.impl.bukkit.menu.slot.SlotBuilder
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排的任务一次的假调度器（断言 isAsync=false）。 */
private class AsyncTrackingScheduler(val asyncFlags: MutableList<Boolean> = mutableListOf()) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int { asyncFlags += task.isAsync; task.onTick(); return 0 }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

class RealChestMenuUpdateTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test fun `更新规则在主线程 tick 并写入真实容器`() {
        val scheduler = AsyncTrackingScheduler()
        val spec = SlotBuilder(InventoryClickEvent::class.java).apply {
            onUpdate(trigger = TaskScheduler.Trigger.Interval(1.seconds)) {
                item = ItemStack(Material.CLOCK, 5)
            }
        }.build(ItemStack(Material.AIR))
        val m = RealChestMenu(scheduler, Component.text("t"), ChestMenuType.GENERIC_9X3, mapOf(4 to spec), hidePlayerInventory = false)
        // 构造末尾已 startUpdates → AsyncTrackingScheduler 立即执行了一次 onTick
        assertEquals(Material.CLOCK, m.bukkitInventory.getItem(4)!!.type)
        assertEquals(5, m.bukkitInventory.getItem(4)!!.amount)
        assertEquals(listOf(false), scheduler.asyncFlags) // 主线程（非异步）
    }
}
