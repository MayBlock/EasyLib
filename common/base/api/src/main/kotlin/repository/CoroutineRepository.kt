package com.github.mayblock.easylib.base.api.repository

interface CoroutineRepository<T : Any, ID : Any> {

    suspend fun create(entity: T): T
    suspend fun findById(id: ID): T?
    suspend fun findAll(): List<T>
    suspend fun update(entity: T): T
    suspend fun deleteById(id: ID): Boolean
    suspend fun existsById(id: ID): Boolean = findById(id) != null
}