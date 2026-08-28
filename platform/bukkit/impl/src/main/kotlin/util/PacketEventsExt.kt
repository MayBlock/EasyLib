package com.github.mayblock.easylib.platform.bukkit.impl.util

import com.github.retrooper.packetevents.protocol.item.ItemStack
import io.github.retrooper.packetevents.util.SpigotConversionUtil

fun ItemStack.toBukkit(): org.bukkit.inventory.ItemStack = SpigotConversionUtil.toBukkitItemStack(this)
fun org.bukkit.inventory.ItemStack.fromBukkit(): ItemStack = SpigotConversionUtil.fromBukkitItemStack(this)