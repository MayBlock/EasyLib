package com.github.mayblock.easylib.api.bukkit

import kotlinx.coroutines.CoroutineDispatcher

interface BukkitDispatcher {
    val sync: CoroutineDispatcher
    val async: CoroutineDispatcher
}