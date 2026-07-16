package com.github.mayblock.easylib.api.repository

interface BaseRepository<T : Any, ID : Any> {

    fun create(entity: T): T
    fun findById(id: ID): T?
    fun findAll(): List<T>
    fun update(entity: T): T
    fun deleteById(id: ID): Boolean
    fun existsById(id: ID): Boolean = findById(id) != null
}