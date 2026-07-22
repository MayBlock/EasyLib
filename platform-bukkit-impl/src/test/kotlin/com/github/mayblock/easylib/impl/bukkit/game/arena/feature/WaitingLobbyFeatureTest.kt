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

    /**
     * 测试专用 seam：MockBukkit 4.114.0 的 PlayerMock.sendTitle(title, subtitle, ...) 内部把
     * subtitle 塞进 LinkedTransferQueue（不容忍 null 元素），而生产代码
     * broadcastCountdownTitle 恰好以 `sendTitle(title, null, 0, 20, 0)` 调用（真实 Bukkit
     * 对 null subtitle 是容忍的，MockBukkit 这里是环境侧差异，非本任务改动范围）。
     * 不打这个补丁，第一次 sendTitle 调用就会抛 NPE，被 SimpleEventBus.emit 吞掉并中止整个
     * forEach——不论新旧实现，第二个玩家都轮不到，n² 判别信号会被这个无关异常完全掩盖。
     * 用反射把该私有 Queue 字段换成允许 null 元素的 LinkedList，使 sendTitle 能正常跑完，
     * 从而让 heardSounds 计数真实反映 broadcastCountdownTitle 的调用/循环结构。
     * 不改动被测生产代码，只在测试侧规避这一个 MockBukkit 环境 quirk。
     */
    private fun neutralizeMockBukkitNullSubtitleQueueBug(player: PlayerMock) {
        val field = PlayerMock::class.java.getDeclaredField("subitles")
        field.isAccessible = true
        field.set(player, java.util.LinkedList<String>())
    }

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
        neutralizeMockBukkitNullSubtitleQueueBug(p1)
        neutralizeMockBukkitNullSubtitleQueueBug(p2)

        // 把计数器拨到 41：下一次 Tick 后 remaining = 40 tick = 2000ms，恰落在
        // 2 秒整秒边界（RED title 触发点）。旧实现把 broadcastCountdownTitle 内嵌进
        // 逐玩家的 updateCountdownHud（forEach { updateCountdownHud { broadcast(全员) } }），
        // 2 人房这一帧每人会被 playSound/sendTitle 命中 2 次（n²）；修复后 broadcast
        // 与逐玩家 HUD 平级，只跑一次循环，每人恰好 1 次。
        // action bar 消息由 updateCountdownHud 逐玩家发送，在两种实现下都是每人 1 次，
        // 无法区分新旧实现，因此只作为次要校验；音效计数（heardSounds）才是本用例的判别项。
        f.counter.start(arena)  // Start counter so it can be ticked
        f.counter.set(41)

        // Drain baseline
        generateSequence { p1.nextMessage() }.forEach { }
        generateSequence { p2.nextMessage() }.forEach { }
        val soundsBefore1 = p1.heardSounds.size
        val soundsBefore2 = p2.heardSounds.size

        pump.tick()

        // Verify counter ticked
        assertEquals(40, f.counter.get(), "Counter should have ticked from 41 to 40")

        // 判别项：UI_BUTTON_CLICK 音效计数。旧实现（broadcast 内嵌在逐玩家 forEach 里）会使
        // 每人收到 playerCount 次（此处 2 人房 = 2 次）；修复后 broadcast 与逐玩家循环平级，
        // 每人恰好 1 次。MockBukkit PlayerMock.getHeardSounds() 是可计数 API（非布尔式
        // assertSoundHeard），能真正区分 n 与 n²。
        // MockBukkit 记录的 sound key 用 "." 分隔（如 "ui.button.click"，非 Sound 枚举名），
        // 这里不依赖具体 key 字符串——本用例路径上只有 broadcastCountdownTitle 会调用
        // playSound，直接比较调用前后的 heardSounds 计数差即可，无需按 key 过滤。
        val soundHits1 = p1.heardSounds.size - soundsBefore1
        val soundHits2 = p2.heardSounds.size - soundsBefore2
        assertEquals(1, soundHits1, "P1 应恰好收到 1 次 UI_BUTTON_CLICK（旧 bug：n² 广播会命中 2 次）")
        assertEquals(1, soundHits2, "P2 应恰好收到 1 次 UI_BUTTON_CLICK（旧 bug：n² 广播会命中 2 次）")

        // 次要校验：action bar 仍应每人恰好一次（由 updateCountdownHud 保证，不受本 bug 影响）
        val actionBars1 = generateSequence { p1.nextMessage() }.count { it.contains("即将开始") }
        val actionBars2 = generateSequence { p2.nextMessage() }.count { it.contains("即将开始") }
        assertEquals(1, actionBars1, "P1 should get exactly 1 action bar")
        assertEquals(1, actionBars2, "P2 should get exactly 1 action bar")
    }
}
