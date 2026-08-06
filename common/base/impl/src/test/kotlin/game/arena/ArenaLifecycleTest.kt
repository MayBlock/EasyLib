package com.github.mayblock.easylib.base.impl.game.arena

import com.github.mayblock.easylib.base.api.game.arena.Arena
import com.github.mayblock.easylib.base.api.game.arena.ArenaEntity
import com.github.mayblock.easylib.base.api.game.arena.ArenaPlayer
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArenaLifecycleTest {

    private class FakePlayer : ArenaPlayer {
        override lateinit var arena: Arena<out ArenaPlayer, out ArenaEntity>
        override val name: String = "fake-player"
        override val uuid: UUID = UUID.randomUUID()
        override var displayName: String = "fake-player"
        override fun sendMessage(message: String) {}
    }

    private class FakeEntity : ArenaEntity {
        override val name: String = "fake-entity"
        override val uuid: UUID = UUID.randomUUID()
    }

    private class TestArena(private val onEnableAction: () -> Unit) :
        AbstractArena<FakePlayer, FakeEntity>("test-arena") {
        var disableCalls = 0
            private set

        override fun onEnableArena() = onEnableAction()
        override fun onDisableArena() {
            disableCalls++
        }
    }

    @Test
    fun `isArenaEnabled stays false and exception propagates when onEnableArena throws`() {
        val arena = TestArena(onEnableAction = { throw IllegalStateException("boom") })

        assertFailsWith<IllegalStateException> { arena.isArenaEnabled = true }
        assertFalse(arena.isArenaEnabled)
    }

    @Test
    fun `isArenaEnabled becomes true when onEnableArena succeeds`() {
        val arena = TestArena(onEnableAction = {})

        arena.isArenaEnabled = true

        assertTrue(arena.isArenaEnabled)
    }

    @Test
    fun `disabling an enabled arena runs onDisableArena and flips flag`() {
        val arena = TestArena(onEnableAction = {})
        arena.isArenaEnabled = true

        arena.isArenaEnabled = false

        assertFalse(arena.isArenaEnabled)
        assertTrue(arena.disableCalls == 1)
    }

    @Test
    fun `addPlayer error message identifies the offending player`() {
        val arena = TestArena(onEnableAction = {})
        arena.isArenaEnabled = true
        val player = FakePlayer()
        arena.addPlayer(player)

        val error = assertFailsWith<IllegalArgumentException> { arena.addPlayer(player) }
        assertTrue(
            error.message?.contains(player.name) == true,
            "expected message to mention player name, was: ${error.message}"
        )
    }
}
