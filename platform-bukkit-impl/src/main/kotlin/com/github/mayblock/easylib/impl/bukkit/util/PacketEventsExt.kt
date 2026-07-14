package com.github.mayblock.easylib.impl.bukkit.util

import com.github.retrooper.packetevents.protocol.item.ItemStack
import io.github.retrooper.packetevents.util.SpigotConversionUtil

fun org.bukkit.inventory.ItemStack.fromBukkit(): ItemStack = SpigotConversionUtil.fromBukkitItemStack(this)