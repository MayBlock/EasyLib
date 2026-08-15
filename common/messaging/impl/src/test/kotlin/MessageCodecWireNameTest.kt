package com.github.mayblock.easylib.messaging.impl

import com.github.mayblock.easylib.messaging.api.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@MessageType("com.example.alpha.v1")
data class Alpha(val v: String = "")

@MessageType("com.example.beta.v1")
data class Beta(val v: String = "")

/** 与 [Alpha] 声明了同一个线上名，用于验证冲突检测。 */
@MessageType("com.example.alpha.v1")
data class AlphaClash(val v: String = "")

data class Unannotated(val v: String = "")

class MessageCodecWireNameTest {

    private fun codec() = MessageCodec()

    @Test
    fun `读取注解上的线上名`() {
        assertEquals("com.example.alpha.v1", codec().wireNameOf(Alpha::class.java))
    }

    @Test
    fun `同一个类重复解析返回相同结果`() {
        val c = codec()
        assertEquals(c.wireNameOf(Alpha::class.java), c.wireNameOf(Alpha::class.java))
    }

    @Test
    fun `不同类的不同线上名互不干扰`() {
        val c = codec()
        assertEquals("com.example.alpha.v1", c.wireNameOf(Alpha::class.java))
        assertEquals("com.example.beta.v1", c.wireNameOf(Beta::class.java))
    }

    @Test
    fun `缺少注解抛 IllegalArgumentException`() {
        val e = assertFailsWith<IllegalArgumentException> { codec().wireNameOf(Unannotated::class.java) }
        assertEquals(true, e.message!!.contains("@MessageType"))
    }

    @Test
    fun `两个类声明同一线上名时抛 IllegalArgumentException`() {
        val c = codec()
        c.wireNameOf(Alpha::class.java)
        val e = assertFailsWith<IllegalArgumentException> { c.wireNameOf(AlphaClash::class.java) }
        assertEquals(true, e.message!!.contains("com.example.alpha.v1"))
    }

    @Test
    fun `close 后 wireNameOf 不再登记线上名`() {
        val c = codec()
        c.wireNameOf(Alpha::class.java)
        c.close()
        // 关闭后解析只读注解、不写注册表：AlphaClash 不会占住线上名，
        // 因而随后解析 Alpha 也不会被判为冲突——注册表保持空，
        // 消息类的 Class 不会在 close 之后被重新钉进强引用。
        assertEquals("com.example.alpha.v1", c.wireNameOf(AlphaClash::class.java))
        assertEquals("com.example.alpha.v1", c.wireNameOf(Alpha::class.java))
    }

    @Test
    fun `close 后 wireNameOf 仍校验注解`() {
        val c = codec()
        c.close()
        assertFailsWith<IllegalArgumentException> { c.wireNameOf(Unannotated::class.java) }
    }
}
