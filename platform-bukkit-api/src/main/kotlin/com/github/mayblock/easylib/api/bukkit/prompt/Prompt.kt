package com.github.mayblock.easylib.api.bukkit.prompt

import org.bukkit.entity.Player

interface Prompt {

    fun openPrompt(player: Player, prompt1: String? = null, prompt2: String? = null, block: (String?) -> Unit)
    suspend fun openPrompt(player: Player, prompt1: String? = null, prompt2: String? = null): String?
}