package com.github.mayblock.easylib.impl.bukkit.prompt

import com.github.mayblock.easylib.api.bukkit.prompt.Prompt
import com.github.mayblock.easylib.api.util.Vector
import com.github.mayblock.easylib.impl.bukkit.BukkitEasyLib.Companion.api
import com.github.mayblock.easylib.impl.bukkit.util.sendPackets
import com.github.mayblock.easylib.packetevents.util.toVector3i
import com.github.mayblock.easylib.packetevents.util.updateSign
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.entity.Player
import kotlin.coroutines.resume

object PromptImpl : Prompt {

    private val promptList = mutableMapOf<Vector3i, (String?) -> Unit>()

    override fun openPrompt(
        player: Player,
        prompt1: String?,
        prompt2: String?,
        block: (String?) -> Unit
    ) {
        val position = player.location.block.let {
            Vector(it.x, (it.y + 10).coerceAtMost(player.world.maxHeight - 1), it.z)
        }
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
        promptList[position.toVector3i()] = block
    }

    override suspend fun openPrompt(
        player: Player,
        prompt1: String?,
        prompt2: String?
    ): String? {
        val callback = suspendCancellableCoroutine { cont ->
            openPrompt(player, prompt1, prompt2) { result ->
                cont.resume(result)
            }
        }
        return callback
    }

    init {
        api.packetManager.registerListener(object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                if (e.packetType != PacketType.Play.Client.UPDATE_SIGN) {
                    return
                }
                val packet = WrapperPlayClientUpdateSign(e)
                val vector = packet.blockPosition
                val result = packet.textLines[0].ifBlank { null }
                e.user.sendPackets {
                    forBlock(vector) {
                        blockChange(WrappedBlockState.getDefaultState(StateTypes.AIR))
                    }
                }
                promptList[vector]?.invoke(result)
            }
        })
    }
}