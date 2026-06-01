package com.github.mayblock.easylib.api.game.arena

interface ArenaManager<T : Arena<out ArenaPlayer, out ArenaEntity>, B : ArenaBuilder<T>> {
    fun createArena(builder: B.() -> Unit): T
}

interface ArenaBuilder<T : Arena<*, *>> {
    fun build(): T
}

