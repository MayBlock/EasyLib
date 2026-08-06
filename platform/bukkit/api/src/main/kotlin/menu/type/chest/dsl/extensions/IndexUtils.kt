package com.github.mayblock.easylib.api.bukkit.menu.type.chest.dsl.extensions

internal fun indexOf(rows: IntRange, columns: IntRange): IntRange {
    val indices = rows.flatMap { row ->
        columns.map { column -> indexOf(row, column) }
    }.sorted()
    return indices.first()..indices.last()
}

internal fun indexOf(x: Int, y: Int) = x * 9 + y