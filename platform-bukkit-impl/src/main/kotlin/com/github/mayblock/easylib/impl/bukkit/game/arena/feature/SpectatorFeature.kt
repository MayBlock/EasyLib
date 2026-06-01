package com.github.mayblock.easylib.impl.bukkit.game.arena.feature

import com.github.mayblock.easylib.api.bukkit.game.arena.BukkitArena
import com.github.mayblock.easylib.api.event.on
import com.github.mayblock.easylib.api.feature.Feature
import com.github.mayblock.easylib.api.feature.FeatureKey
import com.github.mayblock.easylib.api.game.arena.event.ArenaLeaveEvent
import com.github.mayblock.easylib.api.service.require
import com.github.mayblock.easylib.api.util.Disposable
import com.github.mayblock.easylib.impl.bukkit.game.arena.bridge.BridgeEvent
import com.github.mayblock.easylib.impl.bukkit.game.arena.service.SpectatorService
import com.github.mayblock.easylib.impl.bukkit.sendTitle
import com.github.mayblock.easylib.packetevents.PacketManager
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.entity.Player

class SpectatorFeature<T : BukkitArena<*, *>>(
    private val manager: PacketManager<Player>
) : Feature<T> {

    companion object Key : FeatureKey<SpectatorFeature<*>>("SpectatorFeature")

    private var offArenaListeners: Disposable? = null
    private var offPacketListeners: Disposable? = null

    override fun onInstall(context: T) {
        val spectatorService = context.services.require(SpectatorService)
        offArenaListeners = onArenaListener(context, spectatorService)
        offPacketListeners = onPacketListener(spectatorService)
    }

    override fun onUninstall(context: T) {
        offArenaListeners?.dispose()
        offPacketListeners?.dispose()
    }

    private fun onArenaListener(arena: BukkitArena<*, *>, service: SpectatorService<*>): Disposable {
        return arena.on {
            on<ArenaLeaveEvent> { // when player removed
                val spectator = service.getSpectator(player.uuid) ?: return@on
                service.removeSpectator(spectator.arenaPlayer)
            }
            on<BridgeEvent.PlayerToggleSneakEvent> {
                val spectator = service.getSpectator(player.uuid) ?: return@on
                if (isSneaking && spectator.watching != null) {
                    spectator.stopWatching()
                    player.bukkitPlayer?.sendTitle(
                        title = "已离开观察",
                        subtitle = "",
                        fadeIn = 0,
                        stay = 60,
                    )
                }
            }
        }
    }

    private fun onPacketListener(service: SpectatorService<*>): Disposable {
        val listener = (object : PacketListener {
            override fun onPacketReceive(e: PacketReceiveEvent) {
                val type = e.packetType
                if (type != PacketType.Play.Client.ATTACK) {
                    return
                }
                val spectator = service.getSpectator(e.user.uuid) ?: return
                val target = resolveTarget(e, service) ?: return
                spectator.watch(target)
                spectator.arenaPlayer.bukkitPlayer?.sendTitle(
                    title = "你正在观察 ${target.name}",
                    subtitle = "点击 “潜行键” 可离开观察",
                    fadeIn = 0,
                    stay = 60,
                )
            }
        }).let {
            manager.registerListener(it)
        }
        return Disposable { manager.unregisterListeners(listener) }
    }

    private fun resolveTarget(
        e: PacketReceiveEvent,
        service: SpectatorService<*>
    ): Player? {
        if (e.packetType != PacketType.Play.Client.ATTACK) {
            return null
        }
        return (WrapperPlayClientAttack(e).entityId.let {
            SpigotConversionUtil.getEntityById(null, it)
        } as? Player)?.takeIf { target ->
            service.getPlayersWithoutSpectator().any { it.uuid == target.uniqueId }
        }
    }
}