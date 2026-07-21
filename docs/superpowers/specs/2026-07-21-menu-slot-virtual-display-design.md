# 菜单槽位虚拟显示层（onUpdate 数据包化）设计

日期：2026-07-21
状态：已与维护者逐节确认（§设计七节无异议）；实施于 2026-07-21 计划
前置调查：PacketEvents 假物品行业实践调查（见 §2）；代码库关键节点核实（见 §3）

## 1. 背景与目标

现状：`SlotUpdateEvent(menu, index, var item)` 无 `player` 字段；菜单侧 `SlotUpdateLoop` 在规则执行后把结果**写回真实容器**（`menu.setItem`），即 onUpdate 是真实修改——被美化过的物品会被玩家真实取走（显示元数据污染物品），也无法按玩家个性化。

目标：
- `SlotUpdateEvent` 提供 `player`。
- onUpdate 内对 `item` 的修改成为**纯视觉的 fake modify**：仅通过数据包影响该玩家看到的样子，不改动真实容器物品。
- 取出放行时玩家拿到的自然是真实物品（恢复真身）；放入放行后下一轮计算以新的真实物品为基底重新美化。

非目标：PlayerOverlay 侧不动（已是纯数据包虚拟层）；不新增读取显示物品的公开 API（`menu.getItem` 维持真实层语义）；不实现定向补发带宽优化（见 §10 未来演进）。

## 2. 行业调查结论（要点与置信度）

调查基于 1.21.x 协议资料 + PacketEvents 2.x 源码；26.x（协议 775+）无公开资料，沿用 1.21.2+ 语义为高置信推测，落地以 PacketEvents wrapper 字段为准。

1. **出站改写（rewrite）是业界主方案**〔确认；Triton 生产实现、ProtocolLib 时代通行做法〕：拦截 clientbound `WINDOW_ITEMS`/`SET_SLOT` 换物品，stateId/windowId 原样透传。服务器任何时机的刷新都经过改写层 → 客户端从不见到真实物品，零闪回、无递归。
2. **主动刷新不自己造包**〔机制推导，高置信〕：伪造包的 stateId 无从取值；发错值 → 客户端回显错值 → 服务器判定失步 → 全量重发真实内容（闪回循环）。安全做法：`player.updateInventory()` 让服务器按正确 stateId 自发全量包，再被改写层变假。
3. **stateId 机制**〔确认，minecraft.wiki〕：服务器下发序列号、客户端点击时回显、不匹配则服务器全量重同步；客户端不会因 stateId 拒收 clientbound 包（忽略仅按 windowId：非当前活动窗口的包被丢弃）。
4. **点击一致性 server-authoritative**〔确认〕：客户端按可见物品做本地预测，服务器按真实物品比对纠正；纠正包被改写层接住 → 视图必然收敛，无刷物风险。
5. **1.21.2+ 包拆分**〔确认〕：光标/玩家背包拆为独立包（`SET_CURSOR_ITEM`/`SET_PLAYER_INVENTORY`），旧 windowId=-1/-2 语义废弃；**顶部容器槽只走 `WINDOW_ITEMS`/`SET_SLOT`**，本设计只改写这两种。
6. **amount 伪造风险**〔高置信推测〕：显示数量≠真实数量时，双击聚堆/shift/拖拽的客户端预测大面积错位，多槽跳变（纯视觉、必收敛）。建议 amount 伪造用于纯展示槽。

## 3. 代码库核实锚点（反幻觉）

- `SlotUpdateLoop`（menu/slot）：读容器 → 跑规则 → `if (event.item != current) menu.setItem(...)` 写回——本设计删除该写回。
- `RealChestView.attachHideMask`：出站改写既有先例（拦 `WINDOW_ITEMS`/`SET_SLOT`，viewer + `windowId != 0` 判定归属本菜单窗口，底部区换 AIR）。显示层 listener 与之同模式同文件。
- `PacketOverlayTransport`：「改写 + 主动补发」双通道先例；其裸发 stateId=0 可行的特权在于 windowId 恒 0 且入站取消点击（服务器不知情、失步到不了服务器）——chest 菜单点击必须让服务器处理，不具备该特权，故走纯改写。
- 多观察者：`ViewerRegistry` + 共享真实 `Inventory`；`view.closeAll()`。
- 开窗时序：`MenuInteractionListener.onInvOpen`（`InventoryOpenEvent`）→ `handleOpen`；CraftBukkit 在该事件之后才发 OpenScreen/初始内容包 ⇒ 种子计算来得及（真机冒烟项）。
- 门控：`ChestSlotGate` 已拒绝 `COLLECT_TO_CURSOR`/`CLONE_STACK`，amount 伪造的最坏路径被掐掉一半。
- packet DSL：`containerSetSlot(windowId, stateId, slot, item)`/`containerItems(...)` 已存在（本设计不用主动发包，仅未来演进用）。

## 4. 已定决策（维护者拍板）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 显示模型 | **每观察者独立**：每 trigger 周期对每 viewer 各触发一次事件（player=该 viewer），缓存按 (player, slot) | 支持按玩家个性化（多语言名、各自进度）；改写层天然按接收者工作 |
| 基底语义 | **真实物品为基底**：每次触发 `item` 初值 = 容器当前真实物品克隆 | 显示 = f(真实, player, 时刻)，无漂移、可重算；放入新物品自动以其为基底。代价：`amount += 1` 不再跨周期累积（需自存计数器） |
| 实现路线 | **方案 A**：出站改写为唯一显示通道 + `updateInventory` 驱动刷新 | 调查结论与仓内先例的交点；零闪回、stateId 零风险、实现最小。B（定向补发）留作未来带宽优化 |

## 5. 分层模型与不变量

- **真实层**（不动）：`RealChestView` 的真实 `Inventory` 是取出/放入/点击/shift 语义的唯一事实源；`menu.getItem/setItem` 读写真实层。
- **显示层**（新增）：按 (viewer, slot) 缓存的假物品，只存在于发往该玩家的数据包里。
- **不变量**：显示 = f(真实物品副本, player, 时刻)，由 onUpdate 规则计算；引擎不写回容器。真实修改的唯一通道是显式 `menu.setItem`（回调内亦可）。
- 推论：取出放行 → Bukkit 原生搬运真实物品（恢复真身零成本）；放入放行 → 下轮以新真实物品为基底重新美化。

## 6. API 变化

`SlotUpdateEvent(menu, index, player, displayItem)`：新增 `val player: Player`，参数位对齐 `SlotClickEvent`。构造器破坏性变更 + 语义变更（维护者已确认）。

KDoc 重写为显示层契约：
- `displayItem` 初值 = 真实物品克隆；对它的修改**纯视觉**（仅影响该 player 所见）。
- 每次触发从真实基底重算；跨周期累积需自存状态。
- 同槽**同 trigger** 多规则合并串行（priority 升序，后者可见前者修改）——保留现行为。
- 同槽**不同 trigger** 的规则组各自从真实基底全量重算、后触发者整体覆盖（组间不再经容器串联）。明示：一个槽的显示规则应共用一个 trigger。
- 需要真实变更用 `menu.setItem`。

同步更新：`SlotScope.onUpdate` KDoc（"统一写入容器一次"→显示层语义）、`menu-architecture-design.md` 相应章节。

## 7. 组件与职责

**`SlotDisplayMap`（impl 新增，对标 overlay `SlotMap`）**
- `ConcurrentHashMap<UUID, ConcurrentHashMap<Int, Entry>>`；`Entry = (bukkitItem 变更比较用, peItem 预转换供 netty 直读)`。
- 主线程写（计算 + SpigotConversionUtil 转换），netty 线程只读。
- 操作：`compute` 结果存入、`invalidate(slot)`（全 viewer）、`remove(viewer)`、`lookup(viewer, slot)`。

**`RealChestView.attachDisplayMask(isViewer, lookup)`（新增，与 attachHideMask 同模式同文件）**
- 出站拦 `WINDOW_ITEMS`/`SET_SLOT`；viewer 且 `windowId != 0` 且 `slot < topSize`：查 `lookup`，命中换假物品，未命中透传真实。
- `carriedItem`/光标包/底部区不碰（底部归 hideMask；光标必须真实——放行取出时拿真身正是需求）。
- 仅当菜单存在任何 `updateRules` 时注册；无 onUpdate 的菜单零开销、行为与今日完全一致。

**`SlotUpdateLoop` 重写（调度骨架保留）**
- 保留：同 trigger 归组、priority 串行、按需启停（0→1 start、→0 stop）。
- 任务体：对每个当前 viewer 构造 `SlotUpdateEvent(menu, index, player, displayItem)` 初值为真实物品克隆 → 跑组内规则 → 与缓存比较，变更则存 `SlotDisplayMap` 并记该 player 入脏集。
- 另暴露 `seed(player)`（开窗种子，§8 范围规则）与按槽重算入口（供 setItem/shift/点击后触点调用）；Delay 组"本周期已触发"标记是 loop 的运行态（`SlotSpec` 保持零运行态的既有契约）。
- 脏集消费：每 tick 至多一次的合并刷新任务，对每个脏 player 调 `player.updateInventory()`（服务器自发全量包、stateId 天然正确、经改写层变假；多槽/多 trigger 同 tick 变更合并为一次重同步）。

**`RealChestMenu` 编排**：装配上述组件；四条数据流触点（§8）。

## 8. 数据流

**开窗**：`handleOpen`：`viewers.add` → 为该 viewer **种子计算**（同步跑规则组）→ `updateLoop.start()` → 派发 `MenuOpenEvent`。CraftBukkit 在 `InventoryOpenEvent` 后才发包 ⇒ 首帧 `WINDOW_ITEMS` 已过热缓存，玩家第一帧即见假物品。种子范围：Once 与 Interval 组必跑；Delay 组仅当本激活周期已到期触发过（组加"本周期已触发"标记；迟到观察者补上已生效显示，未到期的尊重延迟语义）。

**定时刷新**：trigger 任务 → 重算 → 有变更 → 脏集 → 合并 `updateInventory`。真实物品未变 + 规则时不变 ⇒ 结果相同 ⇒ 不刷新（比较短路）。

**真实变更·已知新值（同步重算，零间隙）**：`setItem` 与 shift-入菜单引擎写入后，立即对所有 viewer 同步重算该槽 + 脏刷。其他 viewer 收到服务器变更广播时缓存已新鲜。

**真实变更·原生点击（≤1 tick 间隙）**：FireTake/FireSwap/FirePlace/拖拽**放行**后，容器由 NMS 在事件返回后才改，无法同步读新值。做法：立即 `invalidate` 该槽全 viewer（改写层未命中 → 透传真实，与服务器广播内容一致，不会"旧假叠新真"）+ 排下一 tick 重算脏刷恢复美化。**被拒绝的点击不失效缓存**——回滚纠正包被改写层接住，零闪烁（精品菜单最常见路径）。

## 9. 线程模型与生命周期

- 规则执行、缓存写入、PE 转换全在主线程（延续现行契约，用户回调可安全触 Bukkit API）；netty 线程只做 ConcurrentHashMap 读 + 引用赋值（薄胶水，不跑用户代码）。
- `handleClose`/quit 兜底：`SlotDisplayMap.remove(viewer)`；最后一人离开停 loop（现行语义）；`destroy` dispose hideMask 与 displayMask。
- 无 onUpdate 规则的菜单：不建 map、不注册 listener。

## 10. 边界、取舍与未来演进

- **amount 伪造抖动**：允许搬运的槽位上会触发客户端预测抖动（多槽跳变，纯视觉、必收敛）。KDoc 建议 amount 伪造用于纯展示槽。
- **≤1 tick 美化间隙**：原生点击放行路径特有（透传真实值，语义正确只是"素颜一帧"）；被拒路径零闪烁。
- **带宽**：时钟类每人每秒一次全窗重同步（约 63 槽 NBT），无感知。高频多槽动画若实测成瓶颈 → 未来演进：被动捕获 stateId（改写层顺手记录最近下发值）+ `sendPacketSilently` 定向补发单槽假包，改写层继续兜底（即 A+B，作为增量优化而非首版）。
- **兼容**：Geyser/ViaVersion 共存建议灰度验证（有 SET_SLOT 改写致 Bedrock 玩家异常的历史案例）。
- **真机冒烟前提**：`InventoryOpenEvent` 先于首包的 NMS 时序；调度器 Interval 首次触发时机（影响种子是否必要跑 Interval 组——设计按"必跑"落，首帧不留空窗）。

## 11. 测试策略

MockBukkit 单测（缓存语义全链路）：
- 事件携带正确 player；双 viewer 按 `player.name` 分支得到独立缓存。
- 真实基底不累积：两次触发 `amount += 1` 结果相同。
- 触发后容器物品不变（写回已删的回归断言）。
- 开窗种子填充、关窗清理、放行取出后 invalidate、setItem 同步重算。
- 同 trigger 多规则 priority 串行保留；不同 trigger 组覆盖语义。

改写 listener 保持薄胶水（查表 + 赋值），与 hideMask 同精度标准走真机冒烟清单：首帧无闪、时钟走字、拒绝取出零闪烁、放行取出光标/背包为真身、放入后重新美化、双客户端显示隔离、hide 遮罩与显示层共存、（如有条件）Geyser 客户端过一遍。

现有 `RealChestMenuUpdateTest` 的"写入真实容器"等断言按新契约重写。

## 12. 实施影响面（预估）

- platform-bukkit-api：`SlotEvent.kt`（SlotUpdateEvent 构造器 + KDoc）、`SlotScope.kt`（onUpdate KDoc）。
- platform-bukkit-impl：`SlotUpdateLoop` 重写（含 seed/重算入口与 Delay 已触发标记等运行态）、`SlotDisplayMap` 新增、`RealChestView`（attachDisplayMask）、`RealChestMenu`（装配 + 四触点）；`SlotSpec` 不动（维持零运行态契约）。
- 测试：`RealChestMenuUpdateTest` 重写 + 新增显示层用例；其余菜单测试不受影响（不依赖 onUpdate 写回）。
- 文档：`menu-architecture-design.md` 更新 onUpdate 语义章节。
