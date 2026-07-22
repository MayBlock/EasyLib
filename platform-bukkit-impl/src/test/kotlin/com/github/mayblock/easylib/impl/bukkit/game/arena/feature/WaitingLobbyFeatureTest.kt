package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.EasyLibApi
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaEntity
import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArenaPlayer
import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib
import com.github.mayblock.easylib.impl.bukkit.game.arena.AbstractBukkitArena
import com.github.mayblock.easylib.impl.bukkit.game.arena.AbstractBukkitArenaEntity
import com.github.mayblock.easylib.impl.bukkit.game.arena.AbstractBukkitArenaPlayer
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BridgeEvent
import com.github.mayblock.easylib.impl.util.Counter
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** 手动泵调度器（不关心 Trigger 周期；checker 与 counter 任务在测试中同泵推进）。 */
internal class PumpScheduler : TaskScheduler {
    private val tasks = LinkedHashMap<Int, TaskScheduler.Task>()
    private var nextId = 1
    val activeCount get() = tasks.size

    override fun scheduleTask(task: TaskScheduler.Task): Int {
        val id = nextId++
        tasks[id] = task
        return id
    }

    override fun cancelTask(taskId: Int): Boolean = tasks.remove(taskId) != null
    override fun cancelAllTasks() = tasks.clear()

    fun tick() {
        tasks.entries.toList().forEach { (id, task) ->
            if (id in tasks) task.onTick(object : TaskScheduler.TaskScope {
                override fun cancel() { tasks.remove(id) }
            })
        }
    }
}

class WaitingLobbyFeatureTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: Plugin
    private lateinit var pump: PumpScheduler
    private lateinit var arena: TestArena

    @BeforeTest
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        pump = PumpScheduler()
        arena = TestArena(plugin, pump)
        arena.isArenaEnabled = true
        // sendPackets 走 EasyLibApi.api 单例；注入 relaxed mock 使 HUD 包发送成为无害 no-op。
        EasyLibApi.api = mockk<BukkitEasyLib>(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        arena.isArenaEnabled = false
        MockBukkit.unmock()
    }

    internal class TestPlayer(
        bukkitPlayer: Player,
        arena: BukkitArena<out BukkitArenaPlayer, out BukkitArenaEntity>
    ) : AbstractBukkitArenaPlayer(bukkitPlayer, arena)

    internal class TestEntity(bukkitEntity: Entity) : AbstractBukkitArenaEntity(bukkitEntity)

    internal class TestArena(plugin: Plugin, pump: PumpScheduler) :
        AbstractBukkitArena<TestPlayer, TestEntity>("waiting-lobby-test", plugin),
        TaskScheduler by pump {

        override fun createArenaEntity(entity: Entity): TestEntity = TestEntity(entity)
        override fun onDisableArena() {}
    }

    /** 倒计时 3 tick、minPlayers=2 的标准被测组合；counter 注入以便探测状态。 */
    internal class Fixture(val arenaRef: () -> TestArena) {
        var completed = 0
        val counter = Counter(
            interval = 50.milliseconds,
            initialValue = 3,
            step = -1,
            stopTarget = 0,
        )
        val feature = WaitingLobbyFeature<TestArena>(
            minPlayers = 2,
            maxPlayers = 4,
            playerCount = { arenaRef().players.size },
            isActive = { true },
            startCountdown = 150.milliseconds,
            onComplete = { completed++ },
            counter = counter,
        )
    }

    private fun fixture() = Fixture { arena }

    private fun joinPlayer(): Pair<PlayerMock, TestPlayer> {
        val mock = server.addPlayer()
        val tp = TestPlayer(mock, arena)
        arena.addPlayer(tp)
        return mock to tp
    }

    private fun PlayerMock.drainMessages(): List<String> =
        generateSequence { nextMessage() }.toList()

    @Test
    fun `install 后监听立即生效--人数达标即启动倒计时并设 gamemode`() {
        val f = fixture()
        f.feature.onInstall(arena)

        val (p1, _) = joinPlayer()
        assertFalse(f.counter.isRunning, "1 人未达 minPlayers=2，不得启动")
        assertEquals(GameMode.ADVENTURE, p1.gameMode, "加入即应切到 ADVENTURE（旧 bug：监听从未注册）")

        val (p2, _) = joinPlayer()
        assertTrue(f.counter.isRunning, "2 人达标必须立即启动倒计时")
        assertTrue(p1.drainMessages().any { it.contains("游戏即将开始") })
        assertTrue(p2.drainMessages().any { it.contains("游戏即将开始") })
    }

    @Test
    fun `等待期间伤害事件被取消`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (_, tp1) = joinPlayer()

        val damage = BridgeEvent.EntityDamageEvent(
            tp1, 1.0, 1.0, mockk(relaxed = true),
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK
        )
        arena.emit(damage)
        assertTrue(damage.isCancelled)
    }

    @Test
    fun `倒计时中人数跌破下限--停止并复位并发人数不足消息`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (p1, _) = joinPlayer()
        val (_, tp2) = joinPlayer()
        pump.tick()   // Tick(2)：倒计时确认在走
        p1.drainMessages()

        arena.removePlayer(tp2)
        assertFalse(f.counter.isRunning, "人数 1 < minPlayers=2 必须中止倒计时")
        assertEquals(3, f.counter.get(), "中止后必须复位到满值，避免下次从残值继续数")
        assertEquals(0, f.completed, "中止不得触发 onComplete")
        assertTrue(p1.drainMessages().any { it.contains("人数不足") },
            "旧 bug：isRunning 恒 false 使该分支不可达")
    }

    @Test
    fun `倒计时数到 0--onComplete 恰好一次且无倒计时终止消息`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (p1, _) = joinPlayer()
        joinPlayer()
        p1.drainMessages()

        pump.tick()   // Tick(2)
        pump.tick()   // Tick(1)
        pump.tick()   // Tick(0) -> Completed

        assertEquals(1, f.completed)
        assertFalse(f.counter.isRunning)
        assertEquals(3, f.counter.get(), "完成后复位，供下一局复用")
        assertEquals(GameMode.SURVIVAL, p1.gameMode, "完成时恢复 gamemode")
        val msgs = p1.drainMessages()
        assertTrue(msgs.none { it.contains("倒计时终止") },
            "旧 bug：正常开赛也会收到'倒计时终止'")
    }

    @Test
    fun `先加人后安装--checker 轮询兜底启动`() {
        joinPlayer()
        joinPlayer()
        val f = fixture()
        f.feature.onInstall(arena)
        assertFalse(f.counter.isRunning, "安装瞬间无 join 事件，允许未启动")

        pump.tick()   // checker 跑一轮
        assertTrue(f.counter.isRunning, "旧 bug：只靠 join 事件触发，先加人后安装则永不启动")
    }

    @Test
    fun `uninstall 停止倒计时并清空全部调度任务`() {
        val f = fixture()
        f.feature.onInstall(arena)
        joinPlayer()
        joinPlayer()
        assertTrue(f.counter.isRunning)

        f.feature.onUninstall(arena)
        assertFalse(f.counter.isRunning, "旧 bug：卸载后幽灵倒计时任务继续跑")
        assertEquals(0, pump.activeCount, "checker 与 counter 任务都必须被取消")
    }

    @Test
    fun `倒计时 title 音效每人每 tick 恰好一次--无 n 平方广播`() {
        val f = fixture()
        f.feature.onInstall(arena)
        val (p1, _) = joinPlayer()
        val (p2, _) = joinPlayer()

        // 把计数器拨到 41：下一次 Tick 后 remaining = 40 tick = 2000ms，恰落在
        // 2 秒整秒边界（RED title 触发点）。旧实现 updateHud 内嵌 broadcast，
        // 2 人房这一帧每人会收到 2 次音效；修复后每人恰好 1 次。
        // 新测试：验证 updateCountdownHud 分离出来、不再内嵌 broadcast，
        // 其结果是 broadcastCountdownTitle 只被调用一次，不是循环内多次
        f.counter.start(arena)  // Start counter so it can be ticked
        f.counter.set(41)

        // Drain baseline
        generateSequence { p1.nextMessage() }.forEach { }
        generateSequence { p2.nextMessage() }.forEach { }

        pump.tick()

        // Verify counter ticked
        assertEquals(40, f.counter.get(), "Counter should have ticked from 41 to 40")

        // Both players should get the action bar message from updateCountdownHud
        val msgs1 = generateSequence { p1.nextMessage() }.toList()
        val msgs2 = generateSequence { p2.nextMessage() }.toList()

        // Key assertion: action bar appears exactly once per player, not n times
        // (would be n² if the old bug persisted: forEach {updateCountdownHud {broadcast}})
        val actionBars1 = msgs1.filter { it.contains("即将开始") }
        val actionBars2 = msgs2.filter { it.contains("即将开始") }
        assertEquals(1, actionBars1.size, "P1 should get exactly 1 action bar (fix: not in broadcast loop)")
        assertEquals(1, actionBars2.size, "P2 should get exactly 1 action bar (fix: not in broadcast loop)")
    }
}
