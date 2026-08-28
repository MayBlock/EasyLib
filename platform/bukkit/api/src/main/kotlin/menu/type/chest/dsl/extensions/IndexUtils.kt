package com.github.mayblock.easylib.platform.bukkit.api.menu.type.chest.dsl.extensions

/**
 * 矩形区域 [rows] × [columns] 对应的槽位下标，按行拆成若干段连续区间。
 *
 * 不能合并成一个 `first..last`：一列（`rows = 0..2, columns = 0..0`）的下标是 0、9、18，
 * 合并后会把 1..8、10..17 这些不在区域内的槽位一并声明。
 */
internal fun indexOf(rows: IntRange, columns: IntRange): List<IntRange> =
    rows.map { row -> indexOf(row, columns.first)..indexOf(row, columns.last) }

internal fun indexOf(x: Int, y: Int) = x * 9 + y
