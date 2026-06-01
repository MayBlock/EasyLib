package com.github.mayblock.easylib.api.bukkit

import com.github.mayblock.easylib.api.EasyLibApi

interface BukkitEasyLibApi : EasyLibApi {
    val dispatcher: BukkitDispatcher
}

fun EasyLibApi.bukkitApi(): BukkitEasyLibApi = this as BukkitEasyLibApi