package com.github.mayblock.easylib.api.repository.adapter

import com.github.mayblock.easylib.api.repository.BaseRepository
import com.github.mayblock.easylib.api.repository.CoroutineRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CoroutineRepositoryAdapter<T : Any, ID : Any>(
    private val delegate: BaseRepository<T, ID>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : CoroutineRepository<T, ID> {

    override suspend fun create(entity: T): T = withContext(dispatcher) {
        delegate.create(entity)
    }

    override suspend fun findById(id: ID): T? = withContext(dispatcher) {
        delegate.findById(id)
    }

    override suspend fun findAll(): List<T> = withContext(dispatcher) {
        delegate.findAll()
    }

    override suspend fun update(entity: T): T = withContext(dispatcher) {
        delegate.update(entity)
    }

    override suspend fun deleteById(id: ID): Boolean = withContext(dispatcher) {
        delegate.deleteById(id)
    }
}

fun <T : Any, ID : Any> BaseRepository<T, ID>.asCoroutineRepository(): CoroutineRepository<T, ID> =
    CoroutineRepositoryAdapter(this)