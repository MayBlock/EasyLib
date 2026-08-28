package com.github.mayblock.easylib.platform.bukkit.impl.overlay.transport

import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.platform.bukkit.api.overlay.slot.event.OverlaySlotActionEvent
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * 覆盖层与客户端之间的通道策略：渲染（遮罩发包）、还原、以及入站交互的拦截回调。
 * 收敛 overlay 与客户端之间的**全部**交互；除实现类外，overlay 集群不得再出现任何
 * 客户端协议相关类型（如 PacketEvents）。
 */
internal interface OverlayTransport {
    /** 向该玩家全量渲染覆盖层（show 时调用）。 */
    fun paintAll(player: Player)

    /** 向该玩家重绘单个槽位。 */
    fun paint(player: Player, slot: Int)

    /** 还原该玩家的真实背包视图（hide 时调用）。 */
    fun restore(player: Player)

    /** 挂接通道（注册包监听等），返回可释放句柄；[callbacks] 是通道向 overlay 的反向通知口。 */
    fun attach(callbacks: Callbacks): Disposable

    /** 通道 → overlay 的反向回调。实现方（overlay）负责线程策略；通道在自己的线程原地调用。 */
    interface Callbacks {
        fun isViewer(player: Player): Boolean
        fun onClick(player: Player, slot: Int, clickType: ClickType)
        fun onInteract(player: Player, slot: Int, action: OverlaySlotActionEvent.Interact.Action)
    }
}
