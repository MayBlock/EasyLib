package com.github.mayblock.easylib.api.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import kotlin.String

abstract class Command(
    name: String,
    val description: String? = null,
): CoreCliktCommand(
    name = name,
) {
    override fun help(context: Context): String = description ?: ""
    override fun aliases(): Map<String, List<String>> = emptyMap()
}