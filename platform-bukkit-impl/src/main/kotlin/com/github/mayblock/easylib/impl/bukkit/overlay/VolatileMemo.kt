package com.github.mayblock.easylib.impl.bukkit.overlay

/**
 * 单槽惰性记忆化：以单个 `@Volatile` 引用持有不可变的 `(key, value)` 对。
 *
 * 相比「两个普通缓存字段各自读写」，一次 volatile 读即拿到一致的一对——[Entry] 的字段为 `val`（final），
 * 经 volatile 写安全发布，故消除撕裂读与首次并发调用的 NPE。并发未命中时至多重复计算
 * （[compute] 须为 key 的纯函数，故幂等，后写胜出，[get] 始终返回等于 `compute(key)` 的值）。
 *
 * 惰性：[compute] 只在首次 [get] 时调用，构造期不触发——这对 packet 物品缓存尤为关键，
 * 可避免构造期触发 PacketEvents 静态初始化（`ItemStack.EMPTY` 需活的 PacketEvents API）。
 */
internal class VolatileMemo<K : Any, V : Any>(private val compute: (K) -> V) {

    private class Entry<K : Any, V : Any>(val key: K, val value: V)

    @Volatile
    private var entry: Entry<K, V>? = null

    fun get(key: K): V {
        val snapshot = entry
        // `===` 快路径避免热路径上对未变物品做完整 equals；`==` 覆盖值相等的新实例。
        if (snapshot != null && (key === snapshot.key || key == snapshot.key)) return snapshot.value
        val value = compute(key)
        entry = Entry(key, value)
        return value
    }
}
