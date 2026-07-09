package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.PlayerOverlay
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** 立即同步执行每个被排任务一次的假调度器（并记录 isAsync）。 */
private class AsyncTrackingScheduler(val asyncFlags: MutableList<Boolean> = mutableListOf()) : TaskScheduler {
    override fun scheduleTask(task: TaskScheduler.Task): Int { asyncFlags += task.isAsync; task.onTick(); return 0 }
    override fun cancelTask(taskId: Int): Boolean = true
    override fun cancelAllTasks() {}
}

class OverlayUpdateLoopTest {

    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `update 规则异步 tick 并在物品变化时重绘`() {
        val scheduler = AsyncTrackingScheduler()
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = ItemStack(Material.CLOCK, 5) }
        }.build(ItemStack(Material.AIR))
        val grid = SlotGrid(mapOf(4 to spec))
        val repaints = mutableListOf<Int>()
        OverlayUpdateLoop(mockk<PlayerOverlay>(), grid, scheduler, { repaints += it }).start()

        assertEquals(Material.CLOCK, grid[4]!!.item.type)
        assertEquals(listOf(4), repaints)
        assertEquals(listOf(true), scheduler.asyncFlags) // overlay 保持异步
    }

    @Test
    fun `update 规则赋值的外部对象以副本存入，事后改动不波及内部`() {
        val scheduler = AsyncTrackingScheduler()
        val template = ItemStack(Material.CLOCK, 1) // 规则块持有的外部模板（惯用写法）
        val spec = OverlaySlotBuilder().apply {
            onUpdate(TaskScheduler.Trigger.Interval(1.seconds)) { item = template }
        }.build(ItemStack(Material.AIR))
        val grid = SlotGrid(mapOf(4 to spec))
        OverlayUpdateLoop(mockk<PlayerOverlay>(), grid, scheduler) { }.start()

        assertEquals(Material.CLOCK, grid[4]!!.item.type) // 赋值内容已生效
        template.amount = 99 // tick 之后外部继续改模板
        assertEquals(1, grid[4]!!.item.amount) // 内部存的是副本，不受影响
    }
}
