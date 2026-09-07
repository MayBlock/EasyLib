package com.github.mayblock.easylib.platform.bukkit.impl.prompt

import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.base.api.util.Vector
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.mayblock.easylib.packetevents.api.packet.updateSign
import com.github.mayblock.easylib.packetevents.api.util.toVector3i
import com.github.mayblock.easylib.platform.bukkit.api.prompt.PromptApi
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.BukkitExecutionContext
import com.github.mayblock.easylib.platform.bukkit.api.scheduler.execute
import com.github.mayblock.easylib.platform.bukkit.impl.util.sendPackets
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 告示牌式输入提示：借 PacketEvents 给玩家发一块只在客户端可见的假告示牌并打开编辑器，
 * 客户端回填文本后经 [WrapperPlayClientUpdateSign] 收包拿到结果、把方块还原为它本来的样子、回调结果。
 */
class PromptApiImpl(
    private val packetManager: PacketManager<Player>
) : PromptApi, Listener {

    private val logger = LoggerFactory.getLogger(PromptApiImpl::class.java)

    /**
     * key 为玩家 UUID（而非告示牌坐标）：避免两名玩家同时在同一坐标（如同一格子, 不同世界扩展下的巧合）
     * 触发 prompt 时互相覆盖，同时用 [ConcurrentHashMap] 保证收包线程与主线程并发访问安全。
     * value 为「告示牌实际放置的坐标, 结果回调」。
     */
    private val promptList = ConcurrentHashMap<UUID, Pair<Vector3i, (String?) -> Unit>>()

    private var offListener: Disposable = packetManager.registerListener(object : PacketListener {
        override fun onPacketReceive(e: PacketReceiveEvent) {
            if (e.packetType != PacketType.Play.Client.UPDATE_SIGN) {
                return
            }
            val uuid = e.user.uuid
            val (position, callback) = promptList[uuid] ?: return
            val packet = WrapperPlayClientUpdateSign(e)
            if (packet.blockPosition != position) return
            promptList.remove(uuid)
            val result = packet.textLines[0].ifBlank { null }
            val player = Bukkit.getPlayer(uuid)
            // 不再无脑发 AIR：把客户端此前看到的假告示牌还原为服务端此刻的真实方块状态。
            if (player != null && player.isOnline) {
                val block = player.world.getBlockAt(position.x, position.y, position.z)
                context(packetManager) {
                    player.sendPackets {
                        forBlock(position) {
                            blockChange(SpigotConversionUtil.fromBukkitBlockData(block.blockData))
                        }
                    }
                }
            } else {
                logger.debug("Player {} went offline before prompt block restore could run", uuid)
            }
            callback(result)
        }
    })

    override fun openPrompt(
        player: Player,
        prompt1: String?,
        prompt2: String?,
        block: (String?) -> Unit
    ) {
        // 同一玩家二次调用：先以 null 结算并移除旧回调，防止旧 pending 泄漏，
        // 也防止旧告示牌位置的后续回包错误地命中已经被替换的新 prompt。
        promptList.remove(player.uniqueId)?.second?.invoke(null)

        val position = player.location.let {
            Vector(it.blockX, (it.blockY + 10).coerceAtMost(player.world.maxHeight - 1), it.blockZ)
        }
        val vector3i = position.toVector3i()
        context(packetManager) {
            player.sendPackets {
                forBlock(position) {
                    blockChange(WrappedBlockState.getDefaultState(StateTypes.OAK_SIGN))
                    updateSign(
                        null,
                        "^^^^^^^^^^^^^^^",
                        prompt1,
                        prompt2,
                        isFrontText = true
                    )
                    openSignEditor(isFrontText = true)
                }
            }
        }
        promptList[player.uniqueId] = vector3i to block
    }

    override suspend fun openPrompt(
        player: Player,
        prompt1: String?,
        prompt2: String?
    ): String? = suspendCancellableCoroutine { cont ->
        openPrompt(player, prompt1, prompt2) { result ->
            cont.resume(result)
        }
        cont.invokeOnCancellation { promptList.remove(player.uniqueId) }
    }

    /**
     * 由 [com.github.mayblock.easylib.platform.bukkit.impl.BukkitEasyLib] 在 `close()` 时调用：
     * 注销 packet listener，避免 plugin 卸载/重载后残留监听器持续持有引用；
     * 尚未提交的 prompt 以 `null` 结算，使挂起版调用方不会永久挂起。
     */
    internal fun shutdown() {
        offListener.dispose()
        HandlerList.unregisterAll(this)
        val pending = promptList.values.toList()
        promptList.clear()
        pending.forEach { (_, callback) -> callback(null) }
    }

    /**
     * 断线玩家的 pending prompt 以 `null` 结算并移除，
     * 防止玩家中途下线导致 [promptList] 条目永久残留
     */
    @EventHandler
    private fun onQuit(e: PlayerQuitEvent) {
        promptList.remove(e.player.uniqueId)?.second?.invoke(null)
    }
}
