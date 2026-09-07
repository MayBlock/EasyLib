package com.github.mayblock.easylib.platform.bukkit.impl.overlay.transport

import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.packetevents.api.packet.dsl.PacketCollector
import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.event.OverlaySlotActionEvent.Interact
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.OverlayView
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.protocol.world.BlockFace
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.PlayerInventory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketOverlayTransportTest {
    private var previousApi: PacketEventsAPI<*>? = null

    @BeforeTest
    fun setUp() {
        previousApi = PacketEvents.getAPI()
        PacketEvents.setAPI(mockk<PacketEventsAPI<Any>>(relaxed = true) {
            every { serverManager.version } returns ServerVersion.V_26_1_2
        })
    }

    @AfterTest
    fun tearDown() { PacketEvents.setAPI(previousApi) }

    @Test
    fun `prepare waits for sync snapshot before ready`() {
        val f = Fixture()
        var ready = false
        f.transport.prepare(f.player) { ready = true }

        assertFalse(ready)
        assertEquals(0, f.inventoryReads)
        f.sync.drain()
        assertTrue(ready)
        assertEquals(1, f.inventoryReads)
    }

    @Test
    fun `interactions use prepared slot without accessing Bukkit inventory`() {
        val f = Fixture()
        f.prepare()
        f.receive(PacketType.Play.Client.ANIMATION)
        f.receive(PacketType.Play.Client.ATTACK)
        f.receive(PacketType.Play.Client.PLAYER_DIGGING, WrapperPlayClientPlayerDigging(
            DiggingAction.SWAP_ITEM_WITH_OFFHAND, Vector3i(0, 0, 0), BlockFace.UP, 0
        ))

        assertEquals(listOf(38), f.interactions)
        assertEquals(1, f.inventoryReads)
        assertEquals(3, f.cancelledPackets)
        assertTrue(f.sync.tasks.isEmpty())
    }

    @Test
    fun `pending snapshot cancels held actions without waiting for sync`() {
        val f = Fixture()
        f.transport.prepare(f.player) { }
        f.receive(PacketType.Play.Client.ANIMATION)

        assertEquals(1, f.cancelledPackets)
        assertEquals(0, f.inventoryReads)
        assertTrue(f.interactions.isEmpty())
        assertEquals(1, f.sync.tasks.size)
    }

    @Test
    fun `inbound selected slot changes interaction target immediately`() {
        val f = Fixture()
        f.prepare()
        f.receive(PacketType.Play.Client.HELD_ITEM_CHANGE, WrapperPlayClientHeldItemChange(6))
        f.receive(PacketType.Play.Client.USE_ITEM)

        assertEquals(listOf(42), f.interactions)
        assertEquals(1, f.inventoryReads)
        assertEquals(1, f.cancelledPackets)
    }

    @Test
    fun `outbound selected slot correction changes interaction target`() {
        val f = Fixture()
        f.prepare()
        f.receive(PacketType.Play.Client.HELD_ITEM_CHANGE, WrapperPlayClientHeldItemChange(6))
        f.send(WrapperPlayServerHeldItemChange(1))
        f.receive(PacketType.Play.Client.ANIMATION)

        assertEquals(listOf(37), f.interactions)
        assertEquals(1, f.inventoryReads)
    }

    @Test
    fun `cancelled or invalid selected slots preserve last known slot`() {
        val f = Fixture()
        f.prepare()
        f.receive(PacketType.Play.Client.HELD_ITEM_CHANGE, WrapperPlayClientHeldItemChange(7), cancelled = true)
        f.receive(PacketType.Play.Client.HELD_ITEM_CHANGE, WrapperPlayClientHeldItemChange(-1))
        f.send(WrapperPlayServerHeldItemChange(8), cancelled = true)
        f.send(WrapperPlayServerHeldItemChange(9))
        f.receive(PacketType.Play.Client.ANIMATION)

        assertEquals(listOf(38), f.interactions)
        assertEquals(1, f.inventoryReads)
    }

    @Test
    fun `restore queues real inventory refresh on sync`() {
        val f = Fixture()
        f.prepare()
        f.viewer = false
        f.transport.restore(f.player)
        assertEquals(0, f.restores)

        f.sync.drain()
        assertEquals(1, f.restores)
    }

    @Test
    fun `delayed restore does not overwrite a new show`() {
        val f = Fixture()
        f.prepare()
        f.viewer = false
        f.transport.restore(f.player)
        f.viewer = true
        f.sync.drain()
        f.receive(PacketType.Play.Client.ANIMATION)

        assertEquals(0, f.restores)
        assertEquals(listOf(38), f.interactions)
    }

    @Test
    fun `dispose suppresses pending preparation and listener callbacks`() {
        val f = Fixture()
        var ready = false
        f.transport.prepare(f.player) { ready = true }
        f.subscription.dispose()
        f.sync.drain()
        f.receive(PacketType.Play.Client.ANIMATION)

        assertTrue(ready)
        assertEquals(0, f.inventoryReads)
        assertTrue(f.interactions.isEmpty())
        assertEquals(1, f.disposals)
    }

    @Test
    fun `forget invalidates pending preparation before player quits`() {
        val f = Fixture()
        var ready = false
        f.transport.prepare(f.player) { ready = true }
        f.transport.forget(f.player)
        f.viewer = false
        f.sync.drain()

        assertTrue(ready)
        assertEquals(0, f.inventoryReads)
    }

    @Test
    fun `forgotten preparation cannot replace a later show snapshot`() {
        val f = Fixture()
        f.transport.prepare(f.player) { }
        f.transport.forget(f.player)
        f.transport.prepare(f.player) { }
        f.receive(PacketType.Play.Client.HELD_ITEM_CHANGE, WrapperPlayClientHeldItemChange(5))
        f.sync.drain()
        f.receive(PacketType.Play.Client.ANIMATION)

        assertEquals(listOf(41), f.interactions)
        assertEquals(1, f.inventoryReads)
    }

    @Test
    fun `painting sends immediately without sync queue`() {
        val f = Fixture()
        f.transport.paintAll(f.player)
        f.transport.paint(f.player, 38)

        assertEquals(2, f.sends)
        assertTrue(f.sync.tasks.isEmpty())
        assertEquals(0, f.inventoryReads)
    }

    private class QueuedSync : BukkitExecutionContext.Sync {
        val tasks = ArrayDeque<() -> Unit>()
        var executing = false
        override val dispatcher = Dispatchers.Unconfined
        override val taskExecutor = TaskExecutor { tasks.addLast(it) }

        fun drain() {
            executing = true
            try { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
            finally { executing = false }
        }
    }

    private class Fixture {
        val sync = QueuedSync()
        var inventoryReads = 0
        var restores = 0
        var sends = 0
        var disposals = 0
        var viewer = true
        var cancelledPackets = 0
        val interactions = mutableListOf<Int>()
        private lateinit var listener: PacketListener
        private val inventory = mockk<PlayerInventory> {
            every { heldItemSlot } answers {
                assertTrue(sync.executing, "Bukkit inventory must only be read inside Sync")
                inventoryReads++
                2
            }
        }
        val player = mockk<Player> {
            every { uniqueId } returns UUID.randomUUID()
            every { getInventory() } returns this@Fixture.inventory
            every { updateInventory() } answers {
                assertTrue(sync.executing, "Bukkit inventory must only be restored inside Sync")
                restores++
            }
        }
        private val packetManager = mockk<PacketManager<Player>> {
            every { registerListener(any(), any()) } answers {
                listener = firstArg()
                Disposable { disposals++ }
            }
            every { collectPackets(any()) } returns mockk<PacketCollector>()
            every { sendPackets(player, any()) } answers { sends++ }
        }
        private val view = mockk<OverlayView> {
            every { isDeclared(any()) } answers { firstArg<Int>() in 36..45 }
        }
        val transport = PacketOverlayTransport(view, packetManager, sync)
        val subscription = transport.attach(object : OverlayTransport.Callbacks {
            override fun isViewer(player: Player) = viewer
            override fun onClick(player: Player, slot: Int, clickType: ClickType) = Unit
            override fun onInteract(player: Player, slot: Int, action: Interact.Action) {
                interactions += slot
            }
        })

        fun prepare() {
            transport.prepare(player) { }
            sync.drain()
        }

        fun receive(type: PacketType.Play.Client, wrapper: PacketWrapper<*>? = null, cancelled: Boolean = false) {
            var isCancelled = cancelled
            val event = mockk<PacketReceiveEvent>(relaxed = true) {
                every { getPlayer<Player>() } returns this@Fixture.player
                every { packetType } returns type
                every { serverVersion } returns ServerVersion.V_26_1_2
                every { user.clientVersion } returns ServerVersion.V_26_1_2.toClientVersion()
                every { lastUsedWrapper } returns wrapper
                every { isCancelled() } answers { isCancelled }
                every { setCancelled(any()) } answers { isCancelled = firstArg() }
            }
            listener.onPacketReceive(event)
            if (isCancelled) cancelledPackets++
        }

        fun send(wrapper: WrapperPlayServerHeldItemChange, cancelled: Boolean = false) {
            val event = mockk<PacketSendEvent>(relaxed = true) {
                every { getPlayer<Player>() } returns this@Fixture.player
                every { packetType } returns PacketType.Play.Server.HELD_ITEM_CHANGE
                every { serverVersion } returns ServerVersion.V_26_1_2
                every { user.clientVersion } returns ServerVersion.V_26_1_2.toClientVersion()
                every { lastUsedWrapper } returns wrapper
                every { isCancelled() } returns cancelled
            }
            listener.onPacketSend(event)
        }
    }
}
