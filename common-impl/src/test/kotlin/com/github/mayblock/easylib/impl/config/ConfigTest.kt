package com.github.mayblock.easylib.impl.config

import com.github.mayblock.easylib.api.config.valueOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse

abstract class ConfigTest {

    enum class Gender {
        MALE, FEMALE
    }

    interface TestConfig {
        fun isEmpty(): Boolean
        var name: String
        var gender: Gender
        var age: Int
        var signature: String?
    }

    private lateinit var config: TestConfig

    @BeforeEach
    fun setup() {
        config = createConfig()
    }

    @AfterEach
    fun cleanup() {
        file.delete()
    }

    @Test
    fun `config should not be empty after loadProperties`() {
        assertFalse(config.isEmpty())
    }

    @Test
    fun `default values should be correct`() {
        assertAll(
            { assertEquals("Amanda", config.name) },
            { assertEquals(Gender.FEMALE, config.gender) },
            { assertEquals(30, config.age) },
            { assertNull(config.signature) }
        )
    }

    @Test
    fun `written values should be returned correctly`() {
        config.name = "Jay"
        config.gender = Gender.MALE
        config.age = 47
        config.signature = "台湾男歌手、词曲作家、演员及制作人"

        assertAll(
            { assertEquals("Jay", config.name) },
            { assertEquals(Gender.MALE, config.gender) },
            { assertEquals(47, config.age) },
            { assertEquals("台湾男歌手、词曲作家、演员及制作人", config.signature) }
        )
    }

    abstract val file: File
    abstract fun createConfig(): TestConfig
}

class TestYamlConfig : ConfigTest() {

    override val file: File = createTempFile(suffix = "").toFile()

    override fun createConfig(): TestConfig = object : YamlConfig(file), TestConfig {
        override fun isEmpty() = isConfigEmpty()
        override var name by value("name", "Amanda")
        override var gender by enumValue("gender", Gender.FEMALE)
        override var age by value("age", 30)
        override var signature by valueOrNull<String>("signature")

        init {
            loadProperties()
        }
    }
}