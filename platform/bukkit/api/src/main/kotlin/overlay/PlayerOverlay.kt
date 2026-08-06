package com.github.mayblock.easylib.api.bukkit.overlay

import com.github.mayblock.easylib.api.bukkit.overlay.slot.dsl.OverlayUpdateScope
import com.github.mayblock.easylib.base.api.event.EventSource
import com.github.mayblock.easylib.base.api.util.Destroyable
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家背包覆盖层：用数据包在玩家自己的背包窗口上叠加虚拟显示并捕获交互，**非真实容器**。
 * 只做「展示 + 交互」，不与真实背包做物品转移（故无取出/放入的转移事件契约）。
 *
 * 对外只暴露订阅侧事件源（[EventSource]），可监听 [OverlayEvent]（show/hide、slot 点击/交互）。
 * 实例只应经 [PlayerOverlayFactory] 创建。
 *
 * 打开任意容器界面（箱子、工作台等，玩家自己背包视图除外）会自动隐藏覆盖层，防止绕过覆盖层
 * 直接看到/操作真实背包。
 */
interface PlayerOverlay : Destroyable, EventSource<OverlayEvent> {
    /**
     * 对该玩家开启覆盖层（发送初始虚拟物品并登记观察者）。
     *
     * **须在主线程调用**：内部会同步为该玩家跑一遍各槽的 `onUpdate` 显示规则（见 [OverlayUpdateScope]）
     * 并读写更新循环的内部记账状态，均非线程安全。
     * @throws IllegalStateException 覆盖层已销毁（[destroy] 之后）
     */
    fun show(player: Player)

    /**
     * 对该玩家关闭覆盖层并还原真实背包渲染；此前未开启返回 false。
     *
     * **须在主线程调用**：可能触发更新循环停止并清理其内部记账状态，与 [show]/[setItem] 共享同一套
     * 非线程安全的状态。
     * @throws IllegalStateException 覆盖层已销毁（[destroy] 之后）
     */
    fun hide(player: Player): Boolean

    /**
     * 某声明槽位**共享基底**虚拟物品的**拷贝**；未声明或为空（AIR/数量≤0）返回 null。改动返回值不影响覆盖层。
     *
     * 这是共享基底，**不是**某个观察者实际看到的物品：每个观察者实际所见由该槽的 `onUpdate` 显示规则
     * （见 [OverlayUpdateScope]）在此基底之上各算一份，本方法**不反映** `onUpdate` 的结果——不同观察者
     * 可能看到互不相同的物品，且都可能与本方法的返回值不同。
     */
    fun getItem(index: Int): ItemStack?

    /**
     * 改写某声明槽位**共享基底**的虚拟物品并重绘给所有观察者；`null` 等价清空（AIR）。
     * 存入的是 [item] 的**拷贝**：调用后继续改动原对象不影响覆盖层。
     *
     * 这只改变基底：每个观察者最终看到的物品仍由该槽的 `onUpdate` 显示规则（见 [OverlayUpdateScope]）
     * 在新基底上各自重算得出，不同观察者即便面对同一次写入也可能看到不同结果。
     *
     * **须在主线程调用**：会同步触发该槽 `onUpdate` 规则对每个观察者的重算。
     * @throws IllegalArgumentException 槽位未在构建时声明
     */
    fun setItem(index: Int, item: ItemStack?)

    companion object {
        /** 覆盖层窗口格数：玩家背包窗口的 46 格（合成格 + 盔甲 + 主背包 + 热键栏 + 副手）。 */
        const val OVERLAY_SIZE = 46
    }
}
