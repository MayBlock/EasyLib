package com.github.mayblock.easylib.platform.bukkit.api.scheduler

import kotlinx.coroutines.CoroutineDispatcher

interface BukkitDispatcher {
    val sync: CoroutineDispatcher
    val async: CoroutineDispatcher
}