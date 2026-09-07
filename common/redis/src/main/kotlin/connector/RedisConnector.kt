package com.github.mayblock.easylib.redis.connector

interface RedisConnector {

    val addresses: List<String>
    val database: Int
    val username: String?
    val password: String?
    val clientName: String
}