package client.nilore.modules.impl.combat;

import net.minecraft.world.entity.LivingEntity;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.SprintEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;

public class Critical extends Module {
    public static Critical INSTANCE;

    /* ======================== Settings ======================== */

    public final BooleanSetting controlSprintKey = new BooleanSetting("Control Sprint Key", true);
    public final NumberSetting hurtTime = new NumberSetting("Hurt Time", 2.0, 0.0, 10.0, 1.0);

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    /* ======================== Events ======================== */

    @EventTarget
    public void onSprint(SprintEvent event) {
        if (mc.player == null) return;
        if (KillAura.INSTANCE == null || !KillAura.INSTANCE.isEnabled()) return;
        if (!(KillAura.target instanceof LivingEntity target)) return;

        // 与 LiquidBounce Criticals/StopSprint 相同：下落 + 疾跑 + KillAura 锁定目标且目标在受击窗口内 → 松疾跑
        boolean falling = mc.player.getDeltaMovement().y <= -0.08
                && !mc.player.onGround()
                && !mc.player.isInWater()
                && !mc.player.isInLava()
                && !mc.player.onClimbable()
                && mc.player.isSprinting();

        if (falling && target.hurtTime <= this.hurtTime.getValue().intValue()) {
            if (this.controlSprintKey.getValue()) {
                // 模拟松开疾跑键 → 客户端停止疾跑并自然发 STOP_SPRINTING 包，本次攻击走服务器暴击分支
                mc.options.keySprint.setDown(false);
            } else if (event.getSource() == SprintEvent.Source.INPUT) {
                event.setSprint(false);
            }
        }
    }
}
