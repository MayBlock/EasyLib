package com.github.mayblock.easylib.impl.bukkit.prompt

import com.github.mayblock.easylib.api.bukkit.prompt.PromptApi
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.api.util.Vector
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.packetevents.packet.updateSign
import com.github.mayblock.easylib.packetevents.util.toVector3i
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 告示牌式输入提示：借 PacketEvents 给玩家发一块只在客户端可见的假告示牌并打开编辑器，
 * 客户端回填文本后经 [WrapperPlayClientUpdateSign] 收包拿到结果、把方块还原为它本来的样子、回调结果。
 *
 * 线程契约：无论是 [openPrompt] 的回调版本还是 suspend 版本，结果回调与方块恢复都
 * 经由 [com.github.mayblock.easylib.api.scheduler.TaskScheduler] 调度到 Bukkit 主线程执行——
 * 调用方无需（也不应该）自行切线程。PacketEvents 的收包发生在网络线程而非主线程，
 * 直接在其中调用 `Player#sendBlockChange` 等 Bukkit API 是不安全的。
 */
object PromptApiImpl : PromptApi {

    private val logger = LoggerFactory.getLogger(PromptApiImpl::class.java)

    /**
     * key 为玩家 UUID（而非告示牌坐标）：避免两名玩家同时在同一坐标（如同一格子, 不同世界扩展下的巧合）
     * 触发 prompt 时互相覆盖，同时用 [ConcurrentHashMap] 保证收包线程与主线程并发访问安全。
     * value 为「告示牌实际放置的坐标, 结果回调」。
     */
    private val promptList = ConcurrentHashMap<UUID, Pair<Vector3i, (String?) -> Unit>>()

    private val packetListenerDisposable: Disposable

    override fun openPrompt(
        player: Player,
        prompt1: String?,
        prompt2: String?,
        block: (String?) -> Unit
    ) {
        // 同一玩家二次调用：先以 null 结算并移除旧回调，防止旧 pending 泄漏，
        // 也防止旧告示牌位置的后续回包错误地命中已经被替换的新 prompt。
        promptList.remove(player.uniqueId)?.second?.invoke(null)

        val position = player.location.block.let {
            Vector(it.x, (it.y + 10).coerceAtMost(player.world.maxHeight - 1), it.z)
        }
        val vector3i = position.toVector3i()
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
     * 由 [com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib] 在 `close()` 时调用：
     * 注销 packet listener，避免 plugin 卸载/重载后残留监听器持续持有引用。
     */
    internal fun shutdown() {
        packetListenerDisposable.dispose()
    }

    /**
     * 由 [PromptQuitListener]（挂在 `PlayerQuitEvent`）调用：断线玩家的 pending prompt 以 `null` 结算并移除，
     * 防止玩家中途下线导致 [promptList] 条目永久残留（原实现完全没有处理断线，属于泄漏）。
     */
    internal fun onPlayerQuit(uuid: UUID) {
        promptList.remove(uuid)?.second?.invoke(null)
    }

    init {
        packetListenerDisposable = api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                if (e.packetType != PacketType.Play.Client.UPDATE_SIGN) {
                    return
                }
                val uuid = e.user.uuid
                // 非本 API 发起的 pending：说明玩家在编辑一块真实告示牌，直接放行，不拦截也不清空它。
                val pending = promptList[uuid] ?: return
                val packet = WrapperPlayClientUpdateSign(e)
                if (packet.blockPosition != pending.first) {
                    // 玩家手上确实有个 pending prompt，但这个包编辑的是别的坐标（真实告示牌），与本次 prompt 无关。
                    return
                }
                // 先移除再处理：防止同一 pending 被握手期间的重复包/竞态触发两次 resume。
                promptList.remove(uuid)
                val result = packet.textLines[0].ifBlank { null }
                val (position, callback) = pending
                val player = Bukkit.getPlayer(uuid)

                api.taskScheduler.scheduleTask {
                    onTick = {
                        // 不再无脑发 AIR：把客户端此前看到的假告示牌还原为服务端此刻的真实方块状态。
                        if (player != null && player.isOnline) {
                            val location = Location(
                                player.world,
                                position.x.toDouble(),
                                position.y.toDouble(),
                                position.z.toDouble()
                            )
                            player.sendBlockChange(location, location.block.blockData)
                        } else {
                            logger.debug("Player {} went offline before prompt block restore could run", uuid)
                        }
                        callback(result)
                    }
                }
            }
        })
    }
}
