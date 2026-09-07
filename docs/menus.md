# 箱子菜单

`MenuFactory`（例如持有实例上的 `easyLib.menuFactory`）用 DSL 构建箱子式 GUI。菜单基于**真实容器**（Bukkit `Inventory`），点击、拖拽、开关由 EasyLib 统一拦截并转成菜单事件；玩家背包区域默认用数据包屏蔽，防止误操作。

## 基本用法

```kotlin
val menu = easyLib.menuFactory.createChestMenu(ChestMenuType.GENERIC_9X3) {
    page(Component.text("Shop")) {
        slot(10) {
            item(Material.DIAMOND_SWORD) { setDisplayName("§bSword  §7(100 coins)") }
            onClick {                       // 接收者：InventoryClickEvent（含 player / index / type: ClickType）
                if (type.isShiftClick) return@onClick
                buy(player, "sword")
                player.closeInventory()
            }
        }
        slot(1, 4) { item(Material.EMERALD) }   // (row, column) 写法，等价 slot(13)
        slot(18..25) { item(Material.GRAY_STAINED_GLASS_PANE) }   // 范围填充
        closeButton(26)                       // 内置：屏障 + 点击关闭
    }
}

menu.open(player)
```

- `ChestMenuType.GENERIC_9X1 … GENERIC_9X6`，`type.size` 为格数。
- 一个 `createChestMenu` 至少要有一个 `page`；多页见下文。
- 未声明的槽位为空且不可交互。
- 返回的 `ChestMenu` 暴露 `title`、`type`、`open(player)`、`getItem(index)` / `setItem(index, item)`（读写真实容器，全体观看者可见）、`destroy()`、`isDestroyed`，以及事件订阅侧（见下）。**同一个菜单实例可给多名玩家打开**，容器共享。

## 槽位 DSL（`SlotScope`）

| 方法 | 说明 |
| --- | --- |
| `item(ItemStack)` / `item(Material, amount) { meta }` | 槽位初始物品 |
| `onClick(priority) { }` | 点击回调，接收者 `InventoryClickEvent`（`player`、`index`、`type: ClickType`、`menu`） |
| `onTake(priority) { }` | 玩家试图**取出**本槽物品时的把关（默认拒绝，见下） |
| `onPlace(priority) { }` | 玩家试图**放入**物品到本槽时的把关（默认拒绝，见下） |
| `onUpdate(trigger, priority) { }` | 按周期重算该槽面向**每个观察者**的显示物品（纯视觉，见下） |

回调体内不能再调用 `item(...)` 等构建期声明——DSL 标记刻意禁止在运行期改写声明。

### 取出 / 放入把关

菜单槽位**默认锁定**：`SlotTakeEvent` / `SlotPlaceEvent` 初始 `isCancelled = true`，只有处理器显式改成 `false` 才放行。这让「是否允许搬运」可以按玩家/物品动态决定：

```kotlin
val menu = easyLib.menuFactory.createChestMenu(ChestMenuType.GENERIC_9X3, hidePlayerInventory = false) {
    page(Component.text("Trade")) {
        slot(13) {
            item(Material.AIR)
            onPlace { isCancelled = item.type != Material.BEDROCK }   // 只允许放非基岩
            onTake { isCancelled = false }                            // 随时可拿回
        }
    }
}
```

- 声明了 `onPlace` 放行处理器的菜单**必须**以 `hidePlayerInventory = false` 创建，否则构建期报错（玩家背包被屏蔽时无从放入）。
- 处理器只做把关/观察，**不要**手动增减物品：放行时由 Bukkit 原生完成搬运，重复给予会导致复制。
- 多个处理器（DSL 与外部 `menu.on { on<SlotTakeEvent> {} }`）在同一总线上按 `Priority` 升序执行，后者可覆盖前者的决定。
- shift 点击把背包物品移入菜单时，EasyLib 会向所有声明了 `onPlace` 的槽依次分发并自动扣减来源格。

### 显示更新规则（`onUpdate`）

```kotlin
slot(4) {
    item(Material.CLOCK)
    onUpdate(Trigger.Interval(1.seconds)) {
        // 接收者 SlotUpdateScope：index / viewer / displayItem
        displayItem = displayItem.clone().apply {
            itemMeta = itemMeta?.apply { setDisplayName("§e${viewer.name}  ${LocalTime.now()}") }
        }
    }
}
```

- 规则按 `trigger` 周期**对每个观察者各触发一次**，`displayItem` 初值是真实物品的克隆，修改只影响该玩家看到的样子，**不写回容器**。
- 每次触发都从真实物品重新开始（不会跨周期累积）。
- 同一槽多个规则应共用同一个 `trigger`：同 trigger 合并串行（按 priority），不同 trigger 会互相覆盖。
- 需要全体生效的真实变更用 `menu.setItem(index, item)`。
- 回调在主线程执行。

## 多页菜单

```kotlin
easyLib.menuFactory.createChestMenu(ChestMenuType.GENERIC_9X6) {
    setNextPageItem(Material.ARROW) { setDisplayName("§aNext") }        // 默认槽位 size-4
    setPreviousPageItem(Material.ARROW, slot = type.size - 6)           // 默认槽位 size-6
    repeat(3) { i ->
        page(Component.text("Items")) { /* ... */ }
    }
}
```

- 多于一页时，各页标题自动追加 ` (i/n)`，并在导航槽位放上翻页物品；导航槽位不可在 `page` 内另行声明（构建期报错）。
- 每一页都是独立的 `ChestMenu` 实例；`createChestMenu` 返回第一页。

## 菜单事件

`Menu` 实现 `EventSource<MenuEvent>`，可用[事件总线](events.md)的 `on` DSL 订阅：

```kotlin
val handle = menu.on("shop") {
    on<MenuOpenEvent> { log("${player.name} opened") }
    on<MenuCloseEvent> { /* player */ }
    on<SlotClickEvent> { /* 任意槽位点击（DSL onClick 也是这类事件的监听器） */ }
    on<MenuDestroyEvent> { handle.dispose() }   // 销毁前最后的清理时机
}
```

## 生命周期

- `menu.destroy()`：关闭所有观看者、注销更新任务、派发 `MenuDestroyEvent` 后拆除总线；幂等。
- `BukkitEasyLib.close()` 会销毁本实例创建的全部菜单，因此**临时菜单不必手动销毁**；但长期运行中频繁创建的一次性菜单应在用完后 `destroy()`，否则会一直被工厂持有。
- 玩家断线时视为关闭菜单。

## 查询活跃菜单

`easyLib.menuRegistry`（`MenuRegistry`）提供只读查询：`getActiveMenu(player)`、`hasActiveMenu(player)`、`getViewers(menu)`。
