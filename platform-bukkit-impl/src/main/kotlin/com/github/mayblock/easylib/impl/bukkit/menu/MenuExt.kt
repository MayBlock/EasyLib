package com.github.mayblock.easylib.impl.bukkit.menu

/** Bukkit 物品「空」判定：null、AIR 系或数量非正。chest 与 overlay 共用。 */
internal fun org.bukkit.inventory.ItemStack?.isEmptyStack(): Boolean =
    this == null || type.isAir || amount <= 0
