package com.github.mayblock.easylib.platform.bukkit.impl.util

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * 观察者集合：netty 线程与主线程并发读、主线程写（并发集合 + 快照遍历）。纯状态，不含策略。
 * menu 与 overlay 共用（menu 侧容器事件全在主线程，overlay 侧发包回调来自 netty 线程）。
 */
internal class ViewerRegistry {

    // netty（包收发）与主线程都会读写观察者集合，用并发集合防数据竞争（详见 repaint 里的补充过滤）。
    private val viewers: MutableSet<Player> = ConcurrentHashMap.newKeySet()

    val isEmpty: Boolean get() = viewers.isEmpty()

    operator fun contains(player: Player): Boolean = player in viewers

    fun snapshot(): List<Player> = viewers.toList()

    fun add(player: Player): Boolean = viewers.add(player)

    fun remove(player: Player): Boolean = viewers.remove(player)

    fun clear() = viewers.clear()
}
