package com.github.mayblock.easylib.api.bukkit.menu.event

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

fun interface SlotClickListener<in E> {
    fun onClick(event: E)
}

interface SlotUpdateListener<in E> {
    val trigger: TaskScheduler.Trigger
    fun onUpdate(event: E)
}

open class ClickEvent(
    val index: Int,
    val player: Player
)

open class UpdateEvent(
    val index: Int,
    var item: ItemStack
)