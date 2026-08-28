package com.github.mayblock.easylib.platform.bukkit.impl.game.arena.bridge

import com.github.mayblock.easylib.base.api.event.on
import com.github.mayblock.easylib.base.impl.game.arena.AbstractEventfulArena
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArena
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.platform.bukkit.api.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.platform.bukkit.impl.game.arena.AbstractBukkitArenaEntity
import com.github.mayblock.easylib.platform.bukkit.impl.game.arena.AbstractBukkitArenaPlayer
import io.mockk.mockk
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.*

/**
 * 覆盖 [BukkitEventBridge] 的三个已知 BUG：
 * 1. 反取消其他插件已经取消的原生事件；
 * 2. `PlayerMoveEvent.to` 的写回是 no-op（改的是 e.to 而不是 arena 事件里被修改后的值）；
 * 3. 未 enable 的 arena 收到实体生成事件时会因 `spawnEntity` 的前置校验直接抛异常。
 */
class BukkitEventBridgeTest {

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

    private class TestArena :
        AbstractEventfulArena<TestPlayer, TestEntity>("bridge-test"),
        BukkitArena<TestPlayer, TestEntity> {

        override fun createArenaEntity(entity: Entity): TestEntity = TestEntity(entity)
        override fun onEnableArena() {}
        override fun onDisableArena() {}
    }

    private fun bridgeFor(arena: TestArena) = BukkitEventBridge.create(arena, MockBukkit.createMockPlugin())

    @Test
    fun `其他监听器已取消的事件不被 bridge 反取消`() {
        val arena = TestArena()
        arena.isArenaEnabled = true
        bridgeFor(arena)
        val bukkitPlayer = server.addPlayer()
        arena.addPlayer(TestPlayer(bukkitPlayer, arena))

        val drop = PlayerDropItemEvent(bukkitPlayer, mockk<Item>(relaxed = true))
        // 模拟另一个更早执行（同为 LOWEST 或更靠前）的插件监听器已经取消了该事件。
        drop.isCancelled = true

        server.pluginManager.callEvent(drop)

        assertTrue(drop.isCancelled, "bridge 不应该在 arena 监听器未主动改动时把其他插件的取消状态覆盖回去")
    }

    @Test
    fun `arena 监听器修改 PlayerMoveEvent to 后 Bukkit 事件的 to 被真正更新`() {
        val arena = TestArena()
        arena.isArenaEnabled = true
        bridgeFor(arena)
        val bukkitPlayer = server.addPlayer()
        arena.addPlayer(TestPlayer(bukkitPlayer, arena))

        val from = bukkitPlayer.location.clone()
        val originalTo = from.clone().add(1.0, 0.0, 0.0)
        val mutatedTo = from.clone().add(5.0, 0.0, 0.0)

        arena.on {
            on<BridgeEvent.PlayerMoveEvent> { to = mutatedTo }
        }

        val move = PlayerMoveEvent(bukkitPlayer, from, originalTo)
        server.pluginManager.callEvent(move)

        assertEquals(mutatedTo, move.to)
    }

    @Test
    fun `未 enable 的 arena 收到实体生成事件不抛异常`() {
        val arena = TestArena() // 全程不 enable
        bridgeFor(arena)

        val world = server.addSimpleWorld("bridge-test-world")
        // world.spawn 会触发真实的 CreatureSpawnEvent（继承 EntitySpawnEvent 的 HandlerList），
        // 从而驱动 BukkitEventBridge 的 onEntitySpawn。
        world.spawn(world.spawnLocation, Zombie::class.java)
    }
}
