package client.nilore.hud;

import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.ModuleToggleEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.SoundUtil;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.TextureUtil;

public class NotificationHud extends HudElement {

    private static final float SOUTHSIDE_WIDTH = 171.0f;
    private static final float SOUTHSIDE_HEIGHT = 45.0f;
    private static final float SOUTHSIDE_RADIUS = 5.4f;
    private static final float SOUTHSIDE_PADDING = 7.2f;
    private static final float SOUTHSIDE_BAR_HEIGHT = 2.7f;
    private static final float SOUTHSIDE_SPACING = 5.4f;
    private static final float SOUTHSIDE_ICON_SIZE = 21.6f;
    private static final float SIMPLE_HEIGHT = 30.0f;
    private static final float SIMPLE_RADIUS = 5.0f;
    private static final float SIMPLE_PADDING = 7.0f;
    private static final float SIMPLE_ICON_SIZE = 16.0f;
    private static final float SIMPLE_ICON_GAP = 6.0f;
    private static final float SIMPLE_SPACING = 4.0f;
    private static final int BG_COLOR = 0xFF111615;
    private static final int BAR_COLOR = 0xFFFFFFFF;
    private static final int BAR_BG_COLOR = 0xFF3A3A3A;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final NumberSetting margin = new NumberSetting("Margin", 8.0f, 0.0f, 100.0f, 1.0f);
    private final NumberSetting duration = new NumberSetting("Duration (ms)", 800, 500, 10000, 100);
    private final NumberSetting maxNotifications = new NumberSetting("Max Notifications", 7, 1, 10, 1);
    private final BooleanSetting needSound = new BooleanSetting("Sound", true);
    private final ModeSetting whichSound = new ModeSetting("Type", "Sigma", "Lever").withDefault("Lever");
    private final ModeSetting style = new ModeSetting("Style", "southside", "simple", "naven").withDefault("southside");
    private final NumberSetting navenTextXOff = new NumberSetting("Naven Text X Off", 7, 0, 20, 0.5f);
    private final NumberSetting navenTextYOff = new NumberSetting("Naven Text Y Off", 11.5, 0, 20, 0.5f);
    private final NumberSetting navenWidthPad = new NumberSetting("Naven Width Pad", 10, 0, 20, 1);
    private final NumberSetting navenCardH = new NumberSetting("Naven Card Height", 19, 12, 30, 1);
    private final NumberSetting navenRadius = new NumberSetting("Naven Radius", 6, 0, 10, 0.5f);
    private final NumberSetting navenSlideMs = new NumberSetting("Naven Slide (ms)", 120, 20, 300, 10);

    private final List<NotificationEntry> notifications = new ArrayList<>();

    private DynamicTexture enabledIcon;
    private DynamicTexture disabledIcon;

    public NotificationHud() {
        super("Notification");
        this.setWidth(SOUTHSIDE_WIDTH);
        this.setHeight(SOUTHSIDE_HEIGHT);
        this.setEnabled(true);
    }

    @Override
    public void registerSettings() {
        this.registerSetting(margin, duration, maxNotifications, needSound, whichSound, style, navenTextXOff, navenTextYOff, navenWidthPad, navenCardH, navenRadius, navenSlideMs);
    }

    @EventTarget
    public void onModuleToggle(ModuleToggleEvent event) {
        if (event.module() == this) {
            return;
        }
        loadTextures();
        boolean simple = isSimpleStyle();
        boolean naven = isNavenStyle();
        FontRenderer navenFont = FontPresets.openSans(18f);
        String displayText = event.module().getName() + (event.enabled() ? " Enabled" : " Disabled");
        float width = naven
                ? navenFont.getWidth(displayText) + navenWidthPad.getValue().floatValue()
                : simple
                ? SIMPLE_PADDING * 2.0f + SIMPLE_ICON_SIZE + SIMPLE_ICON_GAP
                + GlHelper.getStringWidth(displayText, FontPresets.pingfang(18.0f)) + 3.0f
                : SOUTHSIDE_WIDTH;
        float height = naven ? navenCardH.getValue().floatValue() + 4f : simple ? SIMPLE_HEIGHT : SOUTHSIDE_HEIGHT;
        float spacing = naven ? 4f : simple ? SIMPLE_SPACING : SOUTHSIDE_SPACING;
        notifications.add(new NotificationEntry(event.module().getName(), event.enabled(), System.currentTimeMillis(),
                simple || naven, displayText, width, height, spacing));
        while (notifications.size() > maxNotifications.getValue().intValue()) {
            notifications.remove(0);
        }
        if (this.isEnabled() && needSound.getValue()) {
            if ("Lever".equals(whichSound.getValue())) {
                if (mc.player != null) {
                    if (event.enabled()) mc.player.playSound(SoundEvents.LEVER_CLICK, 1f, 0.6f);
                    else mc.player.playSound(SoundEvents.LEVER_CLICK, 1f, 0.5f);
                }
            } else {
                String soundPath = event.enabled() ? "/assets/nilore/notifications/Enabled.wav" : "/assets/nilore/notifications/Disabled.wav";
                SoundUtil.playResourceSound(soundPath, 0.0f);
            }
        }
    }

    @Override
    public void onRender2D(Render2DEvent event, float px, float py) {
        if (mc.getWindow() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long dur = duration.getValue().longValue();
        float screenW = mc.getWindow().getGuiScaledWidth();
        float screenH = mc.getWindow().getGuiScaledHeight();
        float marginVal = margin.getValue().floatValue();
        this.setWidth(isSimpleStyle() || isNavenStyle() ? 0.0f : SOUTHSIDE_WIDTH);
        this.setHeight(isNavenStyle() ? navenCardH.getValue().floatValue() + 4f : isSimpleStyle() ? SIMPLE_HEIGHT : SOUTHSIDE_HEIGHT);

        Iterator<NotificationEntry> it = notifications.iterator();
        while (it.hasNext()) {
            NotificationEntry entry = it.next();
            long elapsed = now - entry.time;
            float targetX = screenW - entry.width - marginVal;
            float offscreenX = screenW + 10.0f;

            if (elapsed < dur) {
                if (!entry.entranceStarted) {
                    entry.entranceStarted = true;
                    entry.entranceTime = now;
                    entry.x = offscreenX;
                }
                float entranceElapsed = (now - entry.entranceTime) / 1000f;
                float slideSec = isNavenStyle() ? navenSlideMs.getValue().floatValue() / 1000f : 0.08f;
                float t = Math.min(1.0f, entranceElapsed / slideSec);
                entry.x = offscreenX + (targetX - offscreenX) * t;
                entry.alpha = Math.min(1.0f, entry.alpha + 0.05f);
            } else if (!entry.exiting) {
                entry.exiting = true;
                entry.lastBarProgress = 0.0f;
                entry.exitStartTime = now;
            } else {
                float exitElapsed = (now - entry.exitStartTime) / 1000f;
                float exitSec = isNavenStyle() ? navenSlideMs.getValue().floatValue() / 1000f : 0.04f;
                float t = Math.min(1.0f, exitElapsed / exitSec);
                entry.x = targetX + (offscreenX - targetX) * t;
                entry.alpha = 1.0f - t;
                if (t >= 1.0f) {
                    it.remove();
                }
            }
        }

        if (notifications.isEmpty()) {
            return;
        }

        Renderer.render(event.guiGraphics(), drawContext -> {
            float nextY = screenH - marginVal;
            for (NotificationEntry entry : notifications) {
                float cardY = nextY - entry.height;
                nextY = cardY - entry.spacing;
                long elapsed = now - entry.time;
                float progress = entry.exiting ? entry.lastBarProgress
                        : 1.0f - Mth.clamp((float) elapsed / dur, 0.0f, 1.0f);
                if (!entry.exiting) {
                    entry.lastBarProgress = progress;
                }
                renderCard(drawContext, entry, entry.x, cardY, progress, Mth.clamp(entry.alpha, 0.0f, 1.0f));
            }
        });
    }

    private void renderCard(DrawContext drawContext, NotificationEntry entry, float x, float y, float progress, float alpha) {
        if (entry.simple && !isNavenStyle()) {
            renderSimpleCard(drawContext, entry, x, y, alpha);
        } else if (isNavenStyle()) {
            renderNavenCard(drawContext, entry, x, y, alpha);
        } else {
            renderSouthsideCard(drawContext, entry, x, y, progress, alpha);
        }
    }

    private void renderSouthsideCard(DrawContext drawContext, NotificationEntry entry,
                                     float x, float y, float progress, float alpha) {
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(BG_COLOR, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, entry.width, entry.height - 2f,
                    new float[]{SOUTHSIDE_RADIUS, SOUTHSIDE_RADIUS, 0, 0}), paint);
        }

        float barY = y + entry.height - SOUTHSIDE_BAR_HEIGHT;
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(BAR_BG_COLOR, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, barY, entry.width, SOUTHSIDE_BAR_HEIGHT,
                    new float[]{0, 0, SOUTHSIDE_RADIUS, SOUTHSIDE_RADIUS}), paint);
        }
        float barWidth = entry.width * progress;
        if (barWidth > 0.5f) {
            try (Paint paint = new Paint()) {
                paint.setColor(ColorUtil.withAlpha(BAR_COLOR, alpha));
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, barY, barWidth, SOUTHSIDE_BAR_HEIGHT,
                        new float[]{0, 0, SOUTHSIDE_RADIUS, SOUTHSIDE_RADIUS}), paint);
            }
        }

        float drawSize = entry.enabled ? SOUTHSIDE_ICON_SIZE * 0.9f : SOUTHSIDE_ICON_SIZE;
        drawIcon(drawContext, entry.enabled, x + SOUTHSIDE_PADDING,
                y + (entry.height - SOUTHSIDE_BAR_HEIGHT - drawSize) / 2.0f, drawSize, alpha);
        float textX = x + SOUTHSIDE_PADDING + SOUTHSIDE_ICON_SIZE + 5.4f;
        FontRenderer titleFont = FontPresets.pingfang(16.2f);
        FontRenderer descFont = FontPresets.pingfang(12.6f);
        GlHelper.drawText("Module", textX, y + SOUTHSIDE_PADDING + 6.05f, titleFont, ColorUtil.withAlpha(TEXT_COLOR, alpha));
        GlHelper.drawText("Toggled " + entry.name + " " + (entry.enabled ? "on" : "off"), textX,
                y + SOUTHSIDE_PADDING + 18.25f, descFont, ColorUtil.withAlpha(0xFFCCCCCC, alpha));
    }

    private void renderSimpleCard(DrawContext drawContext, NotificationEntry entry, float x, float y, float alpha) {
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(BG_COLOR, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, entry.width, entry.height, SIMPLE_RADIUS), paint);
        }
        float drawSize = entry.enabled ? SIMPLE_ICON_SIZE * 0.9f : SIMPLE_ICON_SIZE;
        drawIcon(drawContext, entry.enabled, x + SIMPLE_PADDING, y + (entry.height - drawSize) / 2.0f, drawSize, alpha);
        FontRenderer font = FontPresets.pingfang(18.0f);
        GlHelper.drawText(entry.displayText, x + SIMPLE_PADDING + SIMPLE_ICON_SIZE + SIMPLE_ICON_GAP,
                y + entry.height / 2.0f - 2f, font, ColorUtil.withAlpha(TEXT_COLOR, alpha));
    }

    private void drawIcon(DrawContext drawContext, boolean enabled, float x, float y, float size, float alpha) {
        DynamicTexture icon = enabled ? enabledIcon : disabledIcon;
        if (icon == null) {
            return;
        }
        int iconAlpha = Math.round(alpha * 255.0f);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, icon.getId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f pose = drawContext.getPoseStack().last().pose();
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(pose, x, y, 0.0f).uv(0.0f, 0.0f).color(255, 255, 255, iconAlpha).endVertex();
        bufferBuilder.vertex(pose, x, y + size, 0.0f).uv(0.0f, 1.0f).color(255, 255, 255, iconAlpha).endVertex();
        bufferBuilder.vertex(pose, x + size, y + size, 0.0f).uv(1.0f, 1.0f).color(255, 255, 255, iconAlpha).endVertex();
        bufferBuilder.vertex(pose, x + size, y, 0.0f).uv(1.0f, 0.0f).color(255, 255, 255, iconAlpha).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    @Override
    public void onGlRender(GlRenderEvent event, float x, float y) {
    }

    @Override
    public void onSettings() {
    }

    private boolean isSimpleStyle() {
        return "simple".equals(style.getValue());
    }

    private boolean isNavenStyle() {
        return "naven".equals(style.getValue());
    }

    private void renderNavenCard(DrawContext drawContext, NotificationEntry entry, float x, float y, float alpha) {
        int bgColor = entry.enabled ? 0xFF179626 : 0xFF942A2B;
        float cardW = entry.width;
        float cardH = navenCardH.getValue().floatValue();
        float radius = navenRadius.getValue().floatValue();
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(bgColor, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x + 2f, y + 4f, cardW, cardH, radius), paint);
        }
        FontRenderer font = FontPresets.openSans(18f);
        int textColor = ((int)(alpha * 255f) & 0xFF) << 24 | 0x00FFFFFF;
        GlHelper.drawText(entry.displayText, x + navenTextXOff.getValue().floatValue(), y + navenTextYOff.getValue().floatValue(), font, textColor);
    }

    private void loadTextures() {
        if (enabledIcon != null && disabledIcon != null) {
            return;
        }
        enabledIcon = TextureUtil.loadResourceTexture("/assets/nilore/notifications/Enabled.png");
        disabledIcon = TextureUtil.loadResourceTexture("/assets/nilore/notifications/Disabled.png");
    }

    private static class NotificationEntry {
        final String name;
        final boolean enabled;
        final long time;
        final boolean simple;
        final String displayText;
        final float width;
        final float height;
        final float spacing;
        float x = 9999f;
        float alpha;
        boolean entranceStarted;
        long entranceTime;
        boolean exiting;
        long exitStartTime;
        float lastBarProgress = 1.0f;

        NotificationEntry(String name, boolean enabled, long time, boolean simple, String displayText,
                          float width, float height, float spacing) {
            this.name = name;
            this.enabled = enabled;
            this.time = time;
            this.simple = simple;
            this.displayText = displayText;
            this.width = width;
            this.height = height;
            this.spacing = spacing;
        }
    }
}
