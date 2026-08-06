package com.github.mayblock.easylib.api.bukkit.scheduler

import kotlinx.coroutines.CoroutineDispatcher

interface BukkitDispatcher {
    val sync: CoroutineDispatcher
    val async: CoroutineDispatcher
}