package client.nilore.modules.impl.movement;

import client.nilore.event.EventTarget;
import client.nilore.event.impl.EntityHurtEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;

public class KeepSprint
extends Module {

    public final NumberSetting slowDownVelocity   = new NumberSetting("Hit Slow Down During Velocity", 0.6, 0, 1, 0.05);
    public final NumberSetting slowDownNormal     = new NumberSetting("Hit Slow Down Normal", 0.6, 0, 1, 0.05);
    public final NumberSetting bufferDecrease     = new NumberSetting("Buffer Decrease", 1.0, 0.1, 10, 0.1,
            () -> !this.bufferAbuse.getValue());
    public final NumberSetting maxBuffer          = new NumberSetting("Max Buffer", 5.0, 1, 10, 1,
            () -> !this.bufferAbuse.getValue());
    public final BooleanSetting sprintSlowDownVelocity = new BooleanSetting("Velocity Hit Sprint", false);
    public final BooleanSetting sprintSlowDownNormal   = new BooleanSetting("Normal Hit Sprint", false);
    public final BooleanSetting bufferAbuse      = new BooleanSetting("Buffer Abuse", false);
    public final BooleanSetting onlyInAir        = new BooleanSetting("Only In Air", false);

    private boolean resetting;
    private double combo;

    public KeepSprint() {
        super("KeepSprint", Category.MOVEMENT);
    }

    @EventTarget
    public void onEntityHurt(EntityHurtEvent event) {
        if (mc.player == null || mc.player.getHealth() <= 0f) return;
        if (event.damageSource().getEntity() != mc.player) return;

        if (mc.player.onGround() && this.onlyInAir.getValue()) return;

        if (this.bufferAbuse.getValue()) {
            if (this.combo < this.maxBuffer.getValue().intValue() && !this.resetting) {
                this.combo++;
            } else {
                if (this.combo > 0) {
                    this.combo = Math.max(0, this.combo - this.bufferDecrease.getValue().doubleValue());
                    this.resetting = true;
                    return;
                } else {
                    this.resetting = false;
                }
            }
        } else {
            this.combo = 0;
        }

        if (mc.player.hurtTime > 0) {
            if (this.sprintSlowDownVelocity.getValue()) {
                mc.player.setSprinting(true);
            }
        } else {
            if (this.sprintSlowDownNormal.getValue()) {
                mc.player.setSprinting(true);
            }
        }
    }
}
