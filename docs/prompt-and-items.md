# Prompt 与自定义物品

## Prompt：告示牌文本输入

`PromptApi`（`EasyLibApi.api.bukkitApi().promptApi`）用一块**只在客户端可见**的假告示牌向玩家索要一行文本：打开告示牌编辑器，玩家确认后拿到第一行内容，方块随即恢复原样。

```kotlin
// 回调风格
api.promptApi.openPrompt(player, prompt1 = "§7Enter arena name", prompt2 = "§7then press Done") { text ->
    if (text == null) player.sendMessage("Cancelled")
    else createArena(text)
}

// 挂起风格
scope.launch {
    val name = api.promptApi.openPrompt(player, "§7Enter arena name") ?: return@launch
    createArena(name)
}
```

- 玩家输入写在告示牌第 1 行，第 2 行是固定的 `^^^^^^` 指示，`prompt1` / `prompt2` 显示在第 3、4 行。
- 结果为 `null` 的情况：玩家提交了空行、玩家在提交前断线、同一玩家再次调用 `openPrompt`（旧的以 `null` 结算）、EasyLib `close()`。
- 回调总是在**主线程**执行，无需自行切线程。

## 自定义物品

`CustomItemRegistry`（`EasyLibApi.api.bukkitApi().customItemRegistry`）定义带持久身份的物品：身份以 `NamespacedKey` 写入物品的 PDC，与外观无关、跨重启稳定——重启后用同一 key 再次 `define` 即可继续识别旧物品栈。

### 定义

```kotlin
val key = NamespacedKey(plugin, "teleport_wand")

val wand = api.customItemRegistry.define(Material.BLAZE_ROD, key) {
    meta {                                   // 外观；可多次调用，按顺序应用
        setDisplayName("§dTeleport Wand")
        lore = listOf("§7Right click to teleport")
        addEnchant(Enchantment.UNBREAKING, 1, true)
        addItemFlags(ItemFlag.HIDE_ENCHANTS)
    }
    meta<Damageable> { damage = 10 }          // 需要特定 ItemMeta 子类型时

    onInteract {                              // 手持交互：CustomItemInteraction（event / player / item / cancel / consume）
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return@onInteract
        cancel()                              // 取消原版交互
        teleportForward(player)
        consume(1)                            // 从触发手扣 1 个（创造模式不消耗）
    }
    onInventoryClick {                        // 背包/容器中点击：CustomItemClick
        if (event.click.isShiftClick) cancel() // 禁止 shift 移动
    }
    onDrop { cancel() }                       // 禁止丢弃（物品回到背包）
    onConsume { /* 仅可食用/饮用材质有意义；cancel() 阻止消耗 */ }
}
```

- `define` 对已注册的 key 抛 `IllegalArgumentException`；`isRegistered(key)` 可先查。
- 库中的自定义物品**不能被放置为方块**：可放置材质右键方块时原版放置会被拦下；需要「放置类」行为请在 `onInteract` 里按 `RIGHT_CLICK_BLOCK` 自行实现。
- 各回调只在底层 Bukkit 事件**未被其它监听器取消**时触发。
- 回调作用域内 `cancel()` 取消底层事件；`consume(n)` 只在 `onInteract` / `onInventoryClick` 中可用，后者调用即隐含 `cancel()`。

### 使用

```kotlin
wand.createStack(amount = 3)                 // 带身份的物品栈
wand.give(player, 1)                         // 发放；放不下的掉在脚下
wand.matches(stack)                          // 是否本物品（看 PDC，不看外观）
wand.take(player, 2)                         // 扣除；不足则不动并返回 false（含盔甲/副手槽）

api.customItemRegistry.fromStack(stack)      // 由任意物品栈反查已注册的自定义物品
api.customItemRegistry.get(key)
api.customItemRegistry.unregister(key)       // 注销后回调立即停止
```

`BukkitEasyLib.close()` 会注销全部自定义物品并卸下监听器。
