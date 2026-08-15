package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelNamesTest {

    @Test
    fun `All 映射到 all 频道`() {
        assertEquals("easylib:msg:all", ChannelNames.of("easylib", Target.All))
    }

    @Test
    fun `Group 映射到 group 频道`() {
        assertEquals("easylib:msg:group:lobby", ChannelNames.of("easylib", Target.Group("lobby")))
    }

    @Test
    fun `Instance 映射到 inst 频道`() {
        assertEquals("easylib:msg:inst:bedwars-3", ChannelNames.of("easylib", Target.Instance("bedwars-3")))
    }

    @Test
    fun `namespace 参与拼接`() {
        assertEquals("mynet:msg:all", ChannelNames.of("mynet", Target.All))
    }

    @Test
    fun `groupOf 与 of 互为逆映射`() {
        val channel = ChannelNames.of("easylib", Target.Group("lobby"))
        assertEquals("lobby", ChannelNames.groupOf("easylib", channel))
    }

    @Test
    fun `groupOf 对非群组频道返回 null`() {
        assertNull(ChannelNames.groupOf("easylib", "easylib:msg:all"))
        assertNull(ChannelNames.groupOf("easylib", "easylib:msg:inst:bedwars-3"))
        // namespace 不匹配的频道不属于本总线。
        assertNull(ChannelNames.groupOf("mynet", "easylib:msg:group:lobby"))
    }
}
