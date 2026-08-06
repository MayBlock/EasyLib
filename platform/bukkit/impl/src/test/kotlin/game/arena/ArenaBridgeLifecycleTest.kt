package com.github.mayblock.easylib.base.impl.bukkit.game.arena

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.base.impl.bukkit.game.arena.bridge.BridgeEvent
import io.mockk.mockk
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 覆盖 [AbstractBukkitArena] 的桥接生命周期对称性 BUG：
 * 旧实现在构造时创建一次 bridge、仅在首次 disable 时销毁，
 * 之后再次 enable 并不会重建 bridge，导致第二轮及以后完全失去事件桥接。
 */
class ArenaBridgeLifecycleTest {

    private lateinit var server: ServerMock

    @BeforeTest
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterTest
    fun tearDown() {
        MockBukkit.unmock()
    }

    private class TestPlayer(
        bukkitPlayer: Player,
        arena: BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>
    ) : AbstractBukkitArenaPlayer(bukkitPlayer, arena)

    private class TestEntity(bukkitEntity: Entity) : AbstractBukkitArenaEntity(bukkitEntity)

    private class TestArena(plugin: Plugin) :
        AbstractBukkitArena<TestPlayer, TestEntity>("lifecycle-test", plugin) {

        override fun createArenaEntity(entity: Entity): TestEntity = TestEntity(entity)
        override fun onDisableArena() {}
    }

    @Test
    fun `enable-disable-enable-disable 全程无异常且第二轮桥接仍生效`() {
        val plugin = MockBukkit.createMockPlugin()
        val arena = TestArena(plugin)

        arena.isArenaEnabled = true
        arena.isArenaEnabled = false
        // 第二轮 enable：旧实现中 bridge 只在构造时创建一次，这里不会重建，导致下面的事件桥接失效。
        arena.isArenaEnabled = true

        val bukkitPlayer = server.addPlayer()
        arena.addPlayer(TestPlayer(bukkitPlayer, arena))

        var bridgedEvents = 0
        arena.on {
            on<BridgeEvent.PlayerDropItemEvent> {
                bridgedEvents++
                isCancelled = true
            }
        }

        val drop = PlayerDropItemEvent(bukkitPlayer, mockk<Item>(relaxed = true))
        server.pluginManager.callEvent(drop)

        assertEquals(1, bridgedEvents, "第二轮 enable 之后 bridge 必须仍然把原生事件转发给 arena 监听器")
        assertTrue(drop.isCancelled)

        arena.isArenaEnabled = false
    }

    @Test
    fun `destroy 幂等--重复 disable 不抛异常`() {
        val plugin = MockBukkit.createMockPlugin()
        val arena = TestArena(plugin)

        arena.isArenaEnabled = true
        arena.isArenaEnabled = false
        arena.isArenaEnabled = true
        arena.isArenaEnabled = false
    }
}
