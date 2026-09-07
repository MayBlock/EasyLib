# 玩家背包覆盖层

`PlayerOverlayFactory`（例如持有实例上的 `easyLib.overlayFactory`）创建 **PlayerOverlay**：用数据包在玩家自己的背包窗口上叠加虚拟物品并捕获点击/交互，**不改动真实背包**。典型用途：大厅/等待房间的功能物品栏、小游戏 HUD 式快捷栏。

与[箱子菜单](menus.md)的区别：覆盖层没有真实容器，也没有取出/放入的搬运语义——只有「展示 + 交互」。

## 基本用法

```kotlin
val overlay = easyLib.overlayFactory.create {
    // 槽位下标使用玩家背包窗口的 46 格布局：
    // 0 合成结果, 1-4 合成格, 5-8 盔甲, 9-35 主背包, 36-44 热键栏, 45 副手
    slot(36) {                                   // 热键栏第 1 格
        item(Material.COMPASS) { setDisplayName("§aTeleporter") }
        onAction {                               // 接收者：OverlaySlotActionEvent（密封）
            when (this) {
                is OverlaySlotActionEvent.Click    -> player.sendMessage("clicked with $clickType")
                is OverlaySlotActionEvent.Interact -> if (action == OverlaySlotActionEvent.Interact.Action.RIGHT_CLICK) openTeleporter(player)
            }
        }
    }
    slot(44) {                                   // 热键栏最后一格
        item(Material.RED_BED) { setDisplayName("§cLeave") }
        onAction<OverlaySlotActionEvent.Interact> { leave(player) }   // 只关心交互
    }
    slot(9..35) { item(Material.GRAY_STAINED_GLASS_PANE) }            // 主背包全部盖住
}

overlay.show(player)     // 对该玩家显示，支持同步和异步调用
overlay.hide(player)     // 立即移除逻辑状态，再还原真实背包渲染
overlay.destroy()        // 对所有玩家 hide 并拆除
```

- 同一个覆盖层可对多名玩家 `show`；未声明的槽位显示为空白。
- `show` / `hide` / `setItem` / `destroy` 支持同步和异步调用；逻辑状态与共享基底立即生效，渲染和回调可能稍后完成。`hide()` 返回实际是否移除了观察状态。
- 玩家打开任意其它容器界面（箱子、工作台等）时覆盖层自动隐藏，防止绕过覆盖层操作真实背包；断线时视为 hide。
- 数据包线程即时拦截操作，`onAction`、`onUpdate` 和生命周期事件统一转发到所选上下文，同一覆盖层内串行执行；默认使用 Sync。

只消费线程安全数据快照的回调可显式选择 Async：

```kotlin
val async = easyLib.getExecutionContext(BukkitExecutionContext.Async)
val overlay = easyLib.overlayFactory.create(context = async) {
    slot(36) {
        item(Material.PAPER)
        onUpdate(Trigger.Interval(1.seconds)) {
            displayItem.amount = publishedCount.get().coerceIn(1, 64) // 例如 AtomicInteger 快照
        }
    }
}
```

首帧 seed、定时更新和 `setItem` 重算遵守同一上下文。异步回调不能直接读取要求主线程的 Bukkit 状态，也不能并发修改传给库的可变物品对象。库只在初始化手持槽快照、恢复真实背包等边界使用 Sync；纯数据包路径直接执行，不等待主线程。

同一覆盖层的旧计算不会覆盖新基底，也不会在 hide/destroy 后恢复显示。首帧就绪前的交互只拦截、不派发用户回调。正常开启时先提交首帧，再通知 Show；若首帧被提前隐藏取消，则仍按 Show → Hide 顺序通知逻辑状态变化。destroy 的 Hide 通知早于 Destroy，订阅者看到的 `isDestroyed` 已为 true。

Async 可减轻主线程上的计算负担，但不保证吞吐量提升；回调仍按序执行，触发器仍使用服务端 tick。是否改善性能需在实际负载下测量。

## 运行时改物品

```kotlin
overlay.setItem(36, ItemStack(Material.CLOCK))   // 改共享基底并重绘给所有观察者；槽位必须已声明
overlay.getItem(36)                              // 共享基底的拷贝（不反映 onUpdate 的每人结果）
```

## 显示更新规则（`onUpdate`）

与菜单相同的显示层契约：

```kotlin
slot(40) {
    item(Material.PAPER)
    onUpdate(Trigger.Interval(1.seconds)) {     // OverlayUpdateScope：index / viewer / displayItem
        displayItem.amount = game.remainingSeconds(viewer).coerceIn(1, 64)
    }
}
```

- 按 `trigger` 周期对每个观察者各算一次；`displayItem` 初值为共享基底的克隆，修改只影响该观察者看到的样子。
- 每次触发都从基底重算，不跨周期累积。
- 同一槽的多条规则应共用同一 `trigger`。
- 需要全体生效的真实变更用 `overlay.setItem`。

## 事件

`PlayerOverlay` 实现 `EventSource<OverlayEvent>`：

```kotlin
overlay.on("lobby") {
    on<OverlayShowEvent> { /* player */ }
    on<OverlayHideEvent> { /* player；hide / destroy / 断线都会派发 */ }
    on<OverlaySlotActionEvent.Click> { /* 任意槽位点击 */ }
    on<OverlayDestroyEvent> { /* 最后清理时机 */ }
}
```

## 生命周期

`overlay.destroy()` 幂等；`BukkitEasyLib.close()` 会销毁本实例创建的全部覆盖层。
