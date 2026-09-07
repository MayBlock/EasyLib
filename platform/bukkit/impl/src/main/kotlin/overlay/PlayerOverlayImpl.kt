package com.github.mayblock.easylib.platform.bukkit.impl.overlay

import com.github.mayblock.easylib.base.api.event.EventSource
import com.github.mayblock.easylib.base.api.scheduler.TaskExecutor
import com.github.mayblock.easylib.base.api.scheduler.TaskScheduler
import com.github.mayblock.easylib.platform.bukkit.api.overlay.*
import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.event.OverlaySlotActionEvent
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.OverlaySlotSpec
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.SlotMap
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.slot.SlotUpdateLoop
import com.github.mayblock.easylib.platform.bukkit.impl.overlay.transport.OverlayTransport
import com.github.mayblock.easylib.platform.bukkit.impl.util.SlotDisplayMap
import com.github.mayblock.easylib.platform.bukkit.impl.util.isEmptyStack
import com.github.mayblock.easylib.platform.bukkit.impl.util.stack
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

/** 覆盖层状态即时变更，回调与渲染串行执行；状态锁内不执行用户代码或发送数据包。 */
internal class PlayerOverlayImpl(
    specs: Map<Int, OverlaySlotSpec>,
    private val map: SlotMap,
    private val display: SlotDisplayMap,
    scheduler: TaskScheduler,
    executionContext: BukkitExecutionContext,
    private val transport: OverlayTransport,
    private val dispatcher: OverlayEventDispatcher = OverlayEventDispatcher(),
) : PlayerOverlay, EventSource<OverlayEvent> by dispatcher {

    private class Session(var ready: Boolean = false, var notified: Boolean = false)

    private val lock = Any()
    private val viewers = mutableMapOf<Player, Session>()
    private val callbacks = CallbackQueue(executionContext.taskExecutor)
    private val updateLoop = SlotUpdateLoop(map, scheduler, callbacks, ::readyViewers, ::updateDisplay, ::repaint)

    @Volatile
    override var isDestroyed: Boolean = false
        private set

    private val transportSub = transport.attach(TransportCallbacks())

    init {
        dispatcher.wireSlotHandlers(specs)
    }

    override fun show(player: Player) {
        synchronized(lock) {
            check(!isDestroyed) { "this overlay is destroyed!" }
            val existing = viewers[player]
            val session = existing ?: Session().also { viewers[player] = it }
            callbacks.add {
                if (isCurrent(player, session)) {
                    updateLoop.start()
                    transport.prepare(player) {
                        callbacks.execute {
                            synchronized(lock) {
                                if (viewers[player] !== session) return@execute
                                session.ready = true
                            }
                            updateLoop.seed(player)
                            if (isCurrent(player, session)) transport.paintAll(player)
                            notifyShown(player, session)
                        }
                    }
                }
            }
        }
        callbacks.dispatch()
    }

    override fun hide(player: Player): Boolean = removeViewer(player, restore = true, checkDestroyed = true)

    /** 断线只清理逻辑状态，不还原已经断开的客户端。 */
    internal fun onPlayerQuit(player: Player) { removeViewer(player, restore = false) }

    internal fun hideIfViewing(player: Player) { removeViewer(player, restore = true) }

    private fun removeViewer(player: Player, restore: Boolean, checkDestroyed: Boolean = false): Boolean {
        synchronized(lock) {
            if (checkDestroyed) check(!isDestroyed) { "this overlay is destroyed!" }
            val session = viewers.remove(player) ?: return false
            display.remove(player.uniqueId)
            val stop = viewers.isEmpty()
            callbacks.add {
                if (stop) updateLoop.stop()
                transport.forget(player)
                notifyShown(player, session)
                dispatcher.publish(OverlayHideEvent(this, player))
                if (restore) transport.restore(player)
            }
        }
        callbacks.dispatch()
        return true
    }

    private fun isCurrent(player: Player, session: Session): Boolean =
        synchronized(lock) { viewers[player] === session }

    /** 首帧被 hide 抢先取消时，仍按逻辑开启、关闭的顺序通知。只在回调队列内调用。 */
    private fun notifyShown(player: Player, session: Session) {
        if (session.notified) return
        session.notified = true
        dispatcher.publish(OverlayShowEvent(this, player))
    }

    private fun readyViewers(): List<Player> = synchronized(lock) {
        viewers.filterValues { it.ready }.keys.toList()
    }

    private fun repaint(player: Player, index: Int) {
        if (synchronized(lock) { viewers[player]?.ready == true }) transport.paint(player, index)
    }

    /** 捕获不可变基底及会话身份；重入 setItem、hide 或 destroy 后，旧计算直接失效。 */
    private fun updateDisplay(player: Player, index: Int, compute: (ItemStack) -> ItemStack): Boolean {
        val (session, base) = synchronized(lock) {
            val session = viewers[player] ?: return false
            if (!session.ready) return false
            session to (map[index]?.item ?: return false)
        }
        val result = compute(base.clone())
        return synchronized(lock) {
            if (viewers[player] !== session || map[index]?.item !== base) false
            else display.commit(player.uniqueId, index, base, result)
        }
    }

    override fun getItem(index: Int): ItemStack? =
        map[index]?.item?.takeUnless { it.isEmptyStack() }?.clone()

    override fun setItem(index: Int, item: ItemStack?) {
        synchronized(lock) {
            val slot = requireNotNull(map[index]) { "slot $index is not declared on this overlay" }
            slot.item = item ?: stack(Material.AIR)
            display.invalidate(index)
            callbacks.add {
                updateLoop.recomputeSlot(index)
                readyViewers().forEach { repaint(it, index) }
            }
        }
        callbacks.dispatch()
    }

    override fun destroy() {
        synchronized(lock) {
            if (isDestroyed) return
            isDestroyed = true
            val sessions = viewers.toMap()
            viewers.clear()
            display.clear()
            callbacks.add {
                sessions.forEach { (player, session) ->
                    transport.forget(player)
                    notifyShown(player, session)
                    dispatcher.publish(OverlayHideEvent(this, player))
                    transport.restore(player)
                }
                try {
                    dispatcher.publish(OverlayDestroyEvent(this))
                } finally {
                    dispatcher.close()
                }
            }
        }
        // 资源释放不等待可能仍在执行的用户回调。
        updateLoop.close()
        transportSub.dispose()
        callbacks.dispatch()
    }

    private inner class TransportCallbacks : OverlayTransport.Callbacks {
        override fun isViewer(player: Player): Boolean = synchronized(lock) { player in viewers }

        private fun publish(player: Player, event: OverlaySlotActionEvent) {
            synchronized(lock) {
                val session = viewers[player] ?: return
                callbacks.add { if (isCurrent(player, session) && session.notified) dispatcher.publish(event) }
            }
            callbacks.dispatch()
        }

        override fun onClick(player: Player, slot: Int, clickType: ClickType) =
            publish(player, OverlaySlotActionEvent.Click(this@PlayerOverlayImpl, slot, player, clickType))

        override fun onInteract(player: Player, slot: Int, action: OverlaySlotActionEvent.Interact.Action) =
            publish(player, OverlaySlotActionEvent.Interact(this@PlayerOverlayImpl, slot, player, action))
    }

    /** 入队与状态变更一同排序，调度和执行在锁外进行；重入调用只追加后续工作。 */
    private class CallbackQueue(private val executor: TaskExecutor) : TaskExecutor {
        private val queue = ArrayDeque<() -> Unit>()
        private var running = false

        fun add(block: () -> Unit) { synchronized(queue) { queue.addLast(block) } }

        override fun execute(task: () -> Unit) {
            add(task)
            dispatch()
        }

        fun dispatch() {
            synchronized(queue) {
                if (running || queue.isEmpty()) return
                running = true
            }
            var started = false
            try {
                executor.execute { started = true; drain() }
            } catch (error: Throwable) {
                if (!started) synchronized(queue) { running = false }
                throw error
            }
        }

        private fun drain() {
            var failure: Throwable? = null
            while (true) {
                val next = synchronized(queue) {
                    if (queue.isEmpty()) { running = false; null } else queue.removeFirst()
                } ?: break
                try { next() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
}
