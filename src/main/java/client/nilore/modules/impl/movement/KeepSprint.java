package client.nilore.modules.impl.movement;

import client.nilore.event.EventTarget;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import net.minecraft.world.phys.HitResult;

public class KeepSprint extends Module {
    private static final double GRIM2_MOTION_XZ = 0.8D;
    private static final double PREDICTION2_CLOSE_MOTION_XZ = 0.8D;

    public final ModeSetting mode = new ModeSetting("Mode",
            "Vanilla", "Prediction", "Grim", "Grim2", "Prediction2");

    public final BooleanSetting fullSprint = new BooleanSetting("Full Sprint", false,
            () -> mode.is("Grim"));

    public final BooleanSetting prediction = new BooleanSetting("Prediction", false,
            () -> mode.is("Prediction2"));

    public final NumberSetting slowdown = new NumberSetting("Slowdown", 0.0F, 0.0F, 100.0F, 1.0F,
            () -> mode.is("Prediction2"));

    public final BooleanSetting groundOnly = new BooleanSetting("Ground Only", false,
            () -> mode.is("Prediction2"));

    public final BooleanSetting reachOnly = new BooleanSetting("Reach Only", false,
            () -> mode.is("Prediction2"));

    private int wTapTicks;
    private boolean restoreForward;
    private boolean restoreSprint;
    private boolean attackForward;
    private boolean attackSprinting;
    private boolean canSprint;

    public KeepSprint() {
        super("KeepSprint", Category.MOVEMENT);
    }

    @Override
    public String getSuffix() {
        return mode.getValue();
    }

    @Override
    public void onDisable() {
        resetWTap();
        this.canSprint = false;
        super.onDisable();
    }

    // --- Prediction2: motion tracking ---

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (!mode.is("Prediction2")) {
            this.canSprint = false;
            return;
        }

        if (event.isPre()) {
            this.canSprint = false;
        } else {
            this.canSprint = true;
        }
    }

    // --- Grim2: w-tap attack logic (EntityRemoveEvent ~= EventAttack) ---

    @EventTarget
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!mode.is("Grim2") || mc.player == null || mc.level == null || event.entity() == null) {
            return;
        }
        // dead == false → pre-attack
        if (!event.dead()) {
            this.attackForward = mc.options.keyUp.isDown();
            this.attackSprinting = mc.player.isSprinting() || mc.options.keySprint.isDown();
            return;
        }

        // dead == true → post-attack
        this.wTapTicks = 2;
        this.restoreForward = this.attackForward || mc.options.keyUp.isDown();
        this.restoreSprint = this.attackSprinting || mc.options.keySprint.isDown();
    }

    // --- Grim2: w-tap move input (StrafeEvent ~= EventMoveInput) ---

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!mode.is("Grim2")) {
            resetWTap();
            return;
        }
        if (mc.player == null || this.wTapTicks <= 0) {
            return;
        }

        if (this.wTapTicks == 2) {
            event.setForward(0.0F);
            if (mc.player.isSprinting()) {
                mc.player.setSprinting(false);
            }
        } else {
            if (this.restoreForward && mc.options.keyUp.isDown()) {
                event.setForward(1.0F);
            }
            if (this.restoreSprint && canRestoreSprint()) {
                mc.player.setSprinting(true);
            }
        }

        --this.wTapTicks;
        if (this.wTapTicks <= 0) {
            resetWTap();
        }
    }

    // --- Tick (EventUpdate ~= TickEvent) ---

    @EventTarget
    public void onTick(TickEvent event) {
        if (!mode.is("Grim2") || mc.player == null) {
            resetWTap();
        }
        if (!mode.is("Prediction2")) {
            this.canSprint = false;
        }
    }

    // ==================== 私有工具方法 ====================

    private boolean canRestoreSprint() {
        return mc.player != null
                && mc.options.keyUp.isDown()
                && !mc.player.isUsingItem()
                && !mc.player.isCrouching();
    }

    private void resetWTap() {
        this.wTapTicks = 0;
        this.restoreForward = false;
        this.restoreSprint = false;
        this.attackForward = false;
        this.attackSprinting = false;
    }

    private boolean shouldKeepSprint() {
        if (mc.player == null) {
            return false;
        }
        if (this.prediction.getValue() && !this.canSprint) {
            return false;
        }
        if (this.groundOnly.getValue() && !mc.player.onGround()) {
            return false;
        }
        return true;
    }

    // --- 辅助: reach / motionXZ（供 EventAttackSlowdown 接入后使用） ---

    private boolean isReachHit() {
        if (mc.player == null || mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS) {
            return false;
        }
        if (mc.getCameraEntity() == null) {
            return mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition(1.0F)) > 3.0D;
        }
        return mc.hitResult.getLocation().distanceTo(mc.getCameraEntity().getEyePosition(1.0F)) > 3.0D;
    }

    private double getMotionXZ() {
        double s = Math.max(0.0D, Math.min(100.0D, this.slowdown.getValue().doubleValue())) / 100.0D;
        return 1.0D - 0.4D * s;
    }
}
