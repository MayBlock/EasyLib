package com.github.mayblock.easylib.platform.bukkit.impl.util

import org.bukkit.Material
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemStackExtTest {

    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterTest
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `meta block modifications are persisted to the item`() {
        val item = stack(Material.ARROW) {
            setDisplayName("Next")
        }
        assertEquals("Next", item.itemMeta?.displayName)
    }
}
