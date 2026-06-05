package com.github.mayblock.easylib.api.bukkit.menu

interface VirtualPlayerInventoryMenu : VirtualMenu {

    override val size: Int get() = INVENTORY_SIZE

    companion object {
        const val INVENTORY_SIZE = 46
    }
}