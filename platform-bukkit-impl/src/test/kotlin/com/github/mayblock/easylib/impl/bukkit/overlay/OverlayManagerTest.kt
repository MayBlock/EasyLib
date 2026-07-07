package com.github.mayblock.easylib.impl.bukkit.overlay

import com.github.mayblock.easylib.api.scheduler.TaskScheduler
import io.mockk.mockk
import kotlin.test.Test

class OverlayManagerTest {

    // 说明：create{} 会构造 PacketPlayerOverlay → 触发 PacketEvents/单例，单测环境不可跑，
    // 故此处仅冒烟覆盖生命周期（空态 close 幂等不抛）；覆盖层创建与渲染的正确性由手动验证兜底（spec §6）。
    @Test
    fun `空态 close 幂等且不抛`() {
        val mgr = OverlayManager(mockk<TaskScheduler>(relaxed = true))
        mgr.close()
        mgr.close()
    }
}
