package com.github.mayblock.easylib.api.serialze

interface Serializer<Serialized, Object> {
    fun serialize(): Serialized
    val deserializer: Deserializer<Serialized, Object>
}

interface Deserializer<Serialized, Object> {
    fun deserialize(serialized: Serialized): Object
}