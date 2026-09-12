package client.nilore.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.misc.MusicPlayer;
import client.nilore.modules.impl.misc.music.AudioPlayer;
import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.MusicHttp;
import client.nilore.modules.impl.misc.music.NeteaseApi;
import client.nilore.modules.impl.misc.music.SongInfo;
import client.nilore.modules.impl.misc.music.provider.MusicSource;
import client.nilore.modules.impl.misc.music.provider.MusicSources;
import client.nilore.modules.impl.misc.music.provider.NeteaseOfficialApi;
import client.nilore.modules.impl.misc.music.provider.QrCode;
import client.nilore.modules.impl.render.LyricsModule;
import client.nilore.render.CoverBackdrop;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.Rectangle;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.render.Texture;
import client.nilore.utils.animation.SmoothAnimationTimer;
import client.nilore.utils.math.Easings;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.MonetPalette;

public class MusicPlayerScreen extends Screen {
    private static final float DESIGN_W = 760.32f;
    private static final float DESIGN_H = 459.36f;
    private static final float SIDEBAR_W = 80.96f;
    private static final float BOTTOM_H = 72.16f;
    private static final float PAD = 22.88f;
    private static final float RADIUS = 19.36f;

    // 配色不再是写死的常量：每首歌按封面动态提取（Material You / Monet），换歌时平滑过渡。
    // 这里从 static final 改成可变静态字段，是为了让下面上百处引用一行都不用动。
    // 字面值只是占位，静态块里会用兜底配色覆盖一遍（见本类末尾的 static 块）。
    private static int SCRIM = 0xC30B0807;
    private static int SHELL = 0xFF1B1215;
    private static int SIDEBAR = 0xFF24171C;
    private static int SURFACE = 0xFF2D1E23;
    private static int RAISED = 0xFF3A272E;
    private static int BERRY = 0xFF81344F;
    private static int BERRY_HOVER = 0xFF98405F;
    private static int ACCENT = 0xFFFFA6C3;
    private static int ACCENT_STRONG = 0xFFFF8FB5;
    private static int ACCENT_HOVER = 0xFFFFB9CE;
    private static int CREAM = 0xFFF8EEF1;
    private static int MUTED = 0xFFC1AAB2;
    private static int DIM = 0xFF77646B;
    private static int NAV_ACTIVE = 0xFF60404B;
    private static int ROW_ACTIVE = 0xFF52313D;
    private static int DIVIDER = 0xFF3B2B31;

    // --- 动态配色状态 ---
    // 配色是全局共享的（上面那些字段是 static），所以状态也放 static。
    // 同一时刻只会开一个播放器界面，不会互相干扰。
    private static MonetPalette.Scheme themeCurrent = MonetPalette.fallback();
    private static MonetPalette.Scheme themeFrom = themeCurrent;
    private static MonetPalette.Scheme themeTarget = themeCurrent;
    private static float themeProgress = 1.0f;
    /** 换歌时配色过渡时长（秒）。 */
    private static final float THEME_FADE_SECONDS = 0.85f;

    private static long frameLastNanos = 0L;

    /**
     * 把一套 Monet 配色套到上面那组字段上。
     *
     * <p>tone 按 Material 3 深色方案取：表面层级 surfaceLowest(5) → surfaceHighest(21)，
     * 主色块 primaryContainer(30)，强调色 primary(80)，正文 onSurface(92)，描边 outline(60)。
     */
    private static void applyScheme(MonetPalette.Scheme scheme) {
        SCRIM = MonetPalette.withAlphaOf(scheme.surfaceLowest(), 0xC3);
        SHELL = scheme.surfaceLowest();
        SIDEBAR = scheme.surfaceContainer();
        SURFACE = scheme.surfaceHigh();
        RAISED = scheme.surfaceHighest();
        BERRY = scheme.primaryContainer();
        BERRY_HOVER = scheme.primary.at(36);
        ACCENT = scheme.primary();
        ACCENT_STRONG = scheme.primary.at(88);
        ACCENT_HOVER = scheme.primary.at(92);
        CREAM = scheme.onSurface();
        MUTED = scheme.onSurfaceVariant();
        DIM = scheme.outline();
        NAV_ACTIVE = scheme.secondaryContainer();
        ROW_ACTIVE = scheme.secondaryContainer();
        DIVIDER = scheme.outlineVariant();
    }

    /** 本帧与上一帧的间隔（秒），夹在 [0, 0.1] 内，避免卡顿后动画整段跳过去。 */
    private static float frameDelta() {
        long now = System.nanoTime();
        float delta = frameLastNanos == 0L ? 0.0f : (now - frameLastNanos) / 1.0e9f;
        frameLastNanos = now;
        return Math.min(Math.max(delta, 0.0f), 0.1f);
    }

    /** 推进配色过渡。过渡结束后直接返回，不做事。 */
    private static void tickTheme(float delta) {
        if (themeProgress >= 1.0f) {
            return;
        }
        themeProgress = Math.min(1.0f, themeProgress + delta / THEME_FADE_SECONDS);
        themeCurrent = MonetPalette.lerp(themeFrom, themeTarget, themeProgress);
        applyScheme(themeCurrent);
    }

    /**
     * 切到新配色。
     *
     * <p>起点用「当前显示的颜色」而不是上一个目标色，这样连续换歌时不会跳变。
     */
    private static void setTheme(MonetPalette.Scheme next) {
        if (next == null) {
            return;
        }
        themeFrom = themeCurrent;
        themeTarget = next;
        themeProgress = 0.0f;
    }

    static {
        // 上面那些字面色值只是占位。开局统一走一遍取色管线，
        // 保证「打开界面时」和「换歌后」走的是同一套逻辑，避免第一首歌加载完时颜色突跳。
        applyScheme(themeCurrent);
    }

    private static final FontRenderer DISPLAY_FONT = FontPresets.poppinsBold(33.0f);
    private static final FontRenderer TITLE_FONT = FontPresets.pingfang(29.0f);
    private static final FontRenderer HEADING_FONT = FontPresets.pingfang(25.0f);
    private static final FontRenderer BODY_FONT = FontPresets.pingfang(23.0f);
    private static final FontRenderer SMALL_FONT = FontPresets.pingfang(21.0f);
    private static final FontRenderer NAV_FONT = FontPresets.productSans(20.0f);
    private static final FontRenderer LYRIC_FONT = FontPresets.pingfang(26.0f);
    private static final FontRenderer LYRIC_ACTIVE_FONT = FontPresets.pingfang(33.0f);
    private static final FontRenderer ICON_FONT = FontPresets.materialIcons(28.0f);
    private static final FontRenderer ICON_LARGE = FontPresets.materialIcons(38.0f);
    private static final FontRenderer USERNAME_FONT = FontPresets.pingfang(33.0f);

    private static final String ICON_PREV = "";
    private static final String ICON_PLAY = "";
    private static final String ICON_PAUSE = "";
    private static final String ICON_NEXT = "";
    private static final String ICON_VOLUME = "";
    private static final String ICON_SEARCH = "";
    private static final String ICON_QUEUE = "";
    private static final String ICON_PLAYLIST = "";
    private static final String ICON_ADD = "";
    private static final String ICON_REMOVE = "";
    private static final String ICON_HOME = "";
    private static final String ICON_BACK = "";
    private static final String ICON_MUSIC = "";
    private static final String ICON_INFO = "";
    private static final String ICON_CLOSE = "";

    private Page page = Page.HOME;
    private Page playerReturnPage = Page.HOME;
    private final List<ClickArea> clickAreas = new ArrayList<>();
    private final Map<Long, List<LyricLine>> lyricsCache = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> lyricsRequested = new ConcurrentHashMap<>();
    private final AtomicLong searchSeq = new AtomicLong();
    private final AtomicLong playRequestSeq = new AtomicLong();

    private String searchText = "";
    private boolean searchFocused;
    private boolean searchDirty;
    private boolean searchSelectAll;
    private volatile List<SongInfo> searchResults = List.of();
    private volatile boolean searching;

    private final List<SongInfo> playQueue = new ArrayList<>();
    private int queueIndex = -1;
    private boolean playlistAutoAdvance;

    private float searchScroll;
    private float maxSearchScroll;
    private float queueScroll;
    private float maxQueueScroll;
    private float playlistScroll;
    private float maxPlaylistScroll;
    private float lyricScroll;
    private long lyricSongId = -1;

    private Bounds searchViewport;
    private Bounds queueViewport;
    private Bounds playlistViewport;
    private DragTarget dragTarget = DragTarget.NONE;
    private Rectangle lastProgressRect;
    private Rectangle lastVolumeRect;
    private float pendingVolume = -1.0f;
    private float pendingProgress = -1.0f;

    private float layoutOriginX;
    private float layoutOriginY;
    private float layoutScale = 1.0f;

    /** 流体色雾是否已就绪。true 时 renderRoot 会把流体画进圆角背景板。 */
    private boolean fluidActive;

    private final SmoothAnimationTimer openAnim = new SmoothAnimationTimer();
    private volatile Texture albumTexture;
    private long albumSongId = -1;
    private volatile boolean albumLoading;
    private volatile byte[] albumBytes;
    private int albumRetryCount;
    // 背景用的是封面压出来的 32×32 色雾（见 CoverBackdrop.prepareFluidPalette），不是封面本身。
    // 单独记一份：albumTexture 在换歌加载期间会被清空，背景不能跟着闪回原版背景。
    private volatile Texture fluidTexture;
    private volatile Texture fluidPrev;
    /** 背景在新旧色雾之间的插值，0 = 旧，1 = 新。 */
    private float backdropFade = 1.0f;
    private static final float BACKDROP_FADE_SECONDS = 0.7f;
    /** 有流体背景时的遮罩透明度。默认的 0xC3 是给世界画面兜底用的，压在自发光背景上会把光压没。 */
    private static final int SCRIM_ALPHA_FLUID = 0x66;

    // 持久化：网易官方登录态（Cookie 串）、当前播放渠道。
    // 路径放在 .minecraft/nilore/ 下，和其它 mod 配置区分开。
    private final Path sessionFile = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("nilore").resolve("netease_session.txt");
    private final Path sourceFile = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("nilore").resolve("music_source.txt");

    // 二维码登录弹层状态。点 About 页里的登录卡片打开；用户扫码成功后自动关闭。
    private boolean loginDialogOpen = false;
    private QrCode.RenderableQr loginQr;
    // loginQrKey / loginQrStatus 在 CompletableFuture 回调线程里赋值、渲染线程读，
    // 不加 volatile 渲染线程可能一直读到旧值（表现为扫码成功后界面毫无反应）。
    private volatile String loginQrKey;
    private volatile NeteaseOfficialApi.QrStatus loginQrStatus = NeteaseOfficialApi.QrStatus.WAITING;
    private long loginQrNextPollMs;
    /** CONFIRMED 那一刻的时间戳，用于延迟 1.2 秒再自动关弹层。0 表示不在确认流程里。 */
    private long lastLoginConfirmMs;

    // 弹层自己的点击区。必须和主界面的 clickAreas 分开：
    // 主界面走设计坐标（有 translate/scale 变换），弹层在变换之外、用屏幕像素定位，
    // 混在同一个列表里会因为坐标系不同而永远匹配不上（表现为关闭按钮点不掉）。
    private final List<ClickArea> dialogClickAreas = new ArrayList<>();

    public MusicPlayerScreen() {
        super(Component.literal("Music Player"));
        MusicPlayer.AUDIO_PLAYER.setNearEndListener(this::requestPreloadForNext);
        MusicPlayer.AUDIO_PLAYER.setOnCrossfadeTrackListener(this::onCrossfadeTrack);
        // 启动恢复：上次选的播放渠道 + 网易官方登录态（如果登录过）。
        // 两边都在 catch 里吞异常，缺文件/坏数据都不影响正常使用。
        MusicSources.load(sourceFile);
        NeteaseOfficialApi.loadSession(sessionFile);
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().level == null) {
            MusicPlayer.AUDIO_PLAYER.stop();
        }
        // 二维码登录轮询：每 1.5 秒问一次。状态变 CONFIRMED/EXPIRED/FAILED 后不再轮询。
        if (loginDialogOpen && loginQrKey != null
                && loginQrStatus != NeteaseOfficialApi.QrStatus.CONFIRMED
                && loginQrStatus != NeteaseOfficialApi.QrStatus.EXPIRED
                && loginQrStatus != NeteaseOfficialApi.QrStatus.FAILED
                && System.currentTimeMillis() >= loginQrNextPollMs) {
            loginQrNextPollMs = System.currentTimeMillis() + 1500L;
            NeteaseOfficialApi.pollQr(loginQrKey, sessionFile).thenAccept(s -> loginQrStatus = s);
        }
    }

    @Override
    public void onClose() {
        // 退出时落盘当前播放渠道。Cookie 文件由 NeteaseOfficialApi 自己在登录成功时存。
        MusicSources.save(sourceFile);
        releaseLoginQr();
        super.onClose();
    }

    private void releaseLoginQr() {
        if (loginQr != null) {
            // 必须从 TextureManager 注销，否则 GL 纹理 ID 泄漏。
            // TextureManager.release 只清掉引用，DynamicTexture 的 NativeImage 靠 GC finalize 释放。
            Minecraft.getInstance().getTextureManager().release(loginQr.location());
            loginQr = null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        clickAreas.clear();
        dialogClickAreas.clear();
        searchViewport = null;
        queueViewport = null;
        playlistViewport = null;
        lastProgressRect = null;
        lastVolumeRect = null;

        float delta = frameDelta();
        tickTheme(delta);
        if (backdropFade < 1.0f) {
            backdropFade = Math.min(1.0f, backdropFade + delta / BACKDROP_FADE_SECONDS);
        }
        // 流体是否就绪。真正的绘制在 renderRoot 里做 —— 要裁剪到圆角背景板内。
        // 色雾还没算出来时只有主菜单退回原版背景；游戏内退回原版背景会把世界画面糊掉，
        // 所以那种情况直接不画，让世界照常透出来（和原来的行为一致）。
        Texture fluid = fluidTexture;
        fluidActive = fluid != null && fluid.getGlId() > 0;
        if (!fluidActive && Minecraft.getInstance().level == null) {
            renderBackground(graphics);
        }

        openAnim.animate(1.0, 0.3, Easings.EASE_OUT_QUAD);
        openAnim.tick();
        LayoutTransform transform = calculateTransform();
        layoutOriginX = transform.originX();
        layoutOriginY = transform.originY();
        layoutScale = transform.scale();
        float designMouseX = toDesignX(mouseX);
        float designMouseY = toDesignY(mouseY);

        // 有流体背景时用更轻的遮罩，否则自发光背景会被自己的遮罩压成一坨死黑。
        final int scrimColor = fluidActive
                ? MonetPalette.withAlphaOf(SCRIM, SCRIM_ALPHA_FLUID)
                : SCRIM;

        Renderer.render(graphics, ctx -> {
            float alpha = openAnim.getValueF();
            ctx.drawRectXYWH(0, 0, width, height, new Paint().setColor(withAlpha(scrimColor, alpha)));
            ctx.save();
            ctx.translate(layoutOriginX, layoutOriginY);
            ctx.scale(layoutScale, layoutScale);
            renderRoot(ctx, designMouseX, designMouseY, alpha);
            ctx.restore();
            // 登录弹层在 ctx.restore() 之后画 —— 栈已经退回到屏幕坐标，弹层用屏幕像素定位。
            if (loginDialogOpen) {
                renderLoginDialog(ctx, mouseX, mouseY);
            }
        });
    }

    private LayoutTransform calculateTransform() {
        float margin = 15.84f;
        float scale = Math.min(1.0f, Math.min((width - margin * 2.0f) / DESIGN_W, (height - margin * 2.0f) / DESIGN_H));
        scale = Math.max(0.45f, scale);
        return new LayoutTransform((width - DESIGN_W * scale) * 0.5f, (height - DESIGN_H * scale) * 0.5f, scale);
    }

    private void renderRoot(DrawContext ctx, float mouseX, float mouseY, float alpha) {
        // 背景板（不透明底色）。流体要画在它上面、被它裁出圆角。
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(0, 0, DESIGN_W, DESIGN_H, RADIUS),
                new Paint().setColor(withAlpha(SHELL, alpha)));

        // 发光流体：只填在背景板这一块圆角矩形里，不是铺满整屏。
        // CoverBackdrop 是直接在裁剪空间画全屏四边形的、不走 DrawContext，
        // 所以 GUI 层的 clipRect 对它无效 —— 把面板的屏幕坐标矩形传进去，
        // 由着色器自己做圆角 SDF 裁剪。
        if (fluidActive) {
            Texture fluid = fluidTexture;
            Texture previous = fluidPrev;
            CoverBackdrop.render(fluid.getGlId(),
                    previous == null ? fluid.getGlId() : previous.getGlId(),
                    backdropFade, width, height, 0.0f,
                    new CoverBackdrop.PanelRect(
                            layoutOriginX, layoutOriginY,
                            DESIGN_W * layoutScale, DESIGN_H * layoutScale,
                            RADIUS * layoutScale));
        }

        if (page == Page.PLAYER) {
            renderPlayerPage(ctx, mouseX, mouseY);
            return;
        }

        float contentH = DESIGN_H - BOTTOM_H;
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHRadii(0, 0, SIDEBAR_W, contentH,
                new float[]{RADIUS, 0, 0, 0}), new Paint().setColor(withAlpha(SIDEBAR, 0.85f)));
        renderSidebar(ctx, 0, 0, SIDEBAR_W, contentH, mouseX, mouseY);
        renderCurrentPage(ctx, SIDEBAR_W, 0, DESIGN_W - SIDEBAR_W, contentH, mouseX, mouseY);
        renderPlaybackBar(ctx, 0, contentH, DESIGN_W, BOTTOM_H, mouseX, mouseY);
    }

    private void renderCurrentPage(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        switch (page) {
            case HOME -> renderHomePage(ctx, x, y, w, h, mouseX, mouseY);
            case SEARCH -> renderSearchPage(ctx, x, y, w, h, mouseX, mouseY);
            case QUEUE -> renderQueue(ctx, x, y, w, h, mouseX, mouseY);
            case PLAYLIST -> renderPlaylist(ctx, x, y, w, h, mouseX, mouseY);
            case ABOUT -> renderAbout(ctx, x, y, w, h, mouseX, mouseY);
            default -> { }
        }
    }

    private void renderSidebar(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x + 15.84f, y + 20.84f, 49.28f, 42.24f, 14.08f), new Paint().setColor(BERRY));
        drawCentered(ICON_MUSIC, x + 15.84f, y + 37.56f, 49.28f, ICON_LARGE, ACCENT);

        float navY = y + 85.08f;
        navItem(ctx, x + 8.8f, navY, w - 17.6f, Page.HOME, ICON_HOME, "Home", mouseX, mouseY);
        navItem(ctx, x + 8.8f, navY + 63.36f, w - 17.6f, Page.SEARCH, ICON_SEARCH, "Search", mouseX, mouseY);
        navItem(ctx, x + 8.8f, navY + 126.72f, w - 17.6f, Page.QUEUE, ICON_QUEUE, "Queue", mouseX, mouseY);
        navItem(ctx, x + 8.8f, navY + 190.08f, w - 17.6f, Page.PLAYLIST, ICON_PLAYLIST, "Library", mouseX, mouseY);
        navItem(ctx, x + 8.8f, h - 53.08f, w - 17.6f, Page.ABOUT, ICON_INFO, "About", mouseX, mouseY);
    }

    private void navItem(DrawContext ctx, float x, float y, float w, Page target, String icon, String label,
                         float mouseX, float mouseY) {
        float h = 50.16f;
        boolean active = page == target;
        boolean hover = contains(mouseX, mouseY, x, y, w, h);
        if (active || hover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 3.0f, w, h, 15.84f),
                    new Paint().setColor(active ? NAV_ACTIVE : withAlpha(RAISED, 0.82f)));
        }
        drawCentered(icon, x, y + 14.08f, w, ICON_FONT, active ? ACCENT : hover ? CREAM : MUTED);
        drawCentered(label, x, y + 31.68f, w, NAV_FONT, active ? CREAM : MUTED);
        clickAreas.add(new ClickArea(x, y, w, h, () -> openPage(target)));
    }

    private void renderHomePage(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        float innerX = x + PAD;
        float innerW = w - PAD * 2.2f;
        String username = Minecraft.getInstance().player == null
                ? "Player"
                : Minecraft.getInstance().player.getGameProfile().getName();
        String greetingLine = greeting() + ", ";
        GlHelper.drawText(greetingLine, innerX, y + 22.88f, DISPLAY_FONT, CREAM);
        GlHelper.drawText(ellipsize(username, USERNAME_FONT, 376), innerX + measure(greetingLine, DISPLAY_FONT),
                y + 22.88f, USERNAME_FONT, CREAM);
        GlHelper.drawText("Music picked for this moment", innerX, y + 51.04f, BODY_FONT, MUTED);
        renderHeaderActions(ctx, x + w - PAD - 130.24f, y + 23.36f, mouseX, mouseY);

        float heroY = y + 73.04f;
        float heroH = 138.16f;
        renderDailyMix(ctx, innerX, heroY, innerW, heroH, mouseX, mouseY);

        float cardsTitleY = heroY + heroH + 16;
        GlHelper.drawText("Made for you", innerX, cardsTitleY, TITLE_FONT, CREAM);
        String seeAll = "See all";
        float seeAllW = measure(seeAll, BODY_FONT);
        GlHelper.drawText(seeAll, innerX + innerW - seeAllW, cardsTitleY + 2, BODY_FONT, ACCENT);
        clickAreas.add(new ClickArea(innerX + innerW - seeAllW - 8, cardsTitleY - 5, seeAllW + 16, 25,
                () -> openPage(Page.PLAYLIST)));

        float gridY = cardsTitleY + 26.0f;
        renderRecommendationGrid(ctx, recommendations(), innerX, gridY, innerW,
                h - gridY - 14.0f, mouseX, mouseY);
    }

    private void renderHeaderActions(DrawContext ctx, float x, float y, float mouseX, float mouseY) {
        float w = 130.24f;
        float h = 36.96f;
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 18.48f), new Paint().setColor(SURFACE));
        float actionW = 31.68f;
        float gap = 9.6f;
        float startX = x + (w - actionW * 3.0f - gap * 2.0f) * 0.5f;
        headerAction(ctx, startX, y + 7, ICON_SEARCH, mouseX, mouseY, () -> openPage(Page.SEARCH));
        headerAction(ctx, startX + actionW + gap, y + 7, ICON_INFO, mouseX, mouseY, () -> openPage(Page.ABOUT));
        headerAction(ctx, startX + (actionW + gap) * 2.0f, y + 7, ICON_CLOSE, mouseX, mouseY, this::onClose);
    }

    private void headerAction(DrawContext ctx, float x, float y, String icon, float mouseX, float mouseY, Runnable action) {
        boolean hover = contains(mouseX, mouseY, x, y, 31.68f, 28.16f);
        if (hover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 3.0f, 31.68f, 28.16f, 14.08f), new Paint().setColor(RAISED));
        }
        drawCentered(icon, x, y + 12, 31.68f, ICON_FONT, hover ? ACCENT : MUTED);
        clickAreas.add(new ClickArea(x, y, 31.68f, 28.16f, action));
    }

    private void renderDailyMix(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        List<SongInfo> songs = recommendations();
        SongInfo hero = songs.isEmpty() ? null : songs.get(0);
        // 背景和下面 "Made for you" 推荐卡片同款：SURFACE 半透明，hover 时 RAISED。
        boolean cardHover = contains(mouseX, mouseY, x, y, w, h);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 25),
                new Paint().setColor(cardHover ? RAISED : withAlpha(SURFACE, 0.682f)));

        float art = h - 33.44f;
        float artX = x + 19.36f;
        float artY = y + 16.72f;
        drawAlbum(ctx, hero, artX, artY, art, 14.96f);
        float textX = artX + art + 24.64f;
        GlHelper.drawText("YOUR DAILY SOUNDTRACK", textX, y + 24, SMALL_FONT, 0xFFFFC4D4);
        GlHelper.drawText(hero == null ? "Build your mix" : "Daily Mix", textX, y + 48, DISPLAY_FONT, CREAM);
        String description = hero == null ? "Search for music to create your first mix"
                : ellipsize(hero.name + " · " + hero.artist, BODY_FONT, w - (textX - x) - 29.92f);
        GlHelper.drawText(description, textX, y + 74.96f, BODY_FONT, 0xFFF0C1CF);

        float buttonY = y + h - 41.36f;
        float buttonW = hero == null ? 109.12f : 98.56f;
        boolean hover = contains(mouseX, mouseY, textX, buttonY, buttonW, 28.16f);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(textX, buttonY, buttonW, 28.16f, 14.08f),
                new Paint().setColor(hover ? ACCENT_HOVER : ACCENT));
        GlHelper.drawText(hero == null ? ICON_SEARCH : ICON_PLAY, textX + 12.32f, buttonY + 15, ICON_FONT, 0xFF431A28);
        GlHelper.drawText(hero == null ? "Find music" : "Listen now", textX + 32,
                buttonY + (hero == null ? 9.68f : 11.68f), SMALL_FONT, 0xFF431A28);

        if (hero == null) {
            clickAreas.add(new ClickArea(textX, buttonY, buttonW, 32, () -> openPage(Page.SEARCH)));
        } else {
            clickAreas.add(new ClickArea(x, y, w, h, () -> playSongAndOpen(hero, songs, 0, true, Page.HOME)));
        }
    }

    private void renderRecommendationGrid(DrawContext ctx, List<SongInfo> songs, float x, float y, float w, float h,
                                          float mouseX, float mouseY) {
        if (songs.isEmpty()) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, Math.max(96, h), 18), new Paint().setColor(SURFACE));
            GlHelper.drawText("Your saved music will appear here.", x + 20, y + 24, BODY_FONT, MUTED);
            GlHelper.drawText("Open Search to add your first track.", x + 20, y + 50, SMALL_FONT, DIM);
            clickAreas.add(new ClickArea(x, y, w, Math.max(96, h), () -> openPage(Page.SEARCH)));
            return;
        }

        int columns = Math.max(3, Math.min(5, (int) (w / 130.24f)));
        float gap = 18.48f;
        float cardW = (w - gap * (columns - 1)) / columns;
        float artSize = Math.min(cardW, h - 43.0f);
        int count = Math.min(columns, songs.size());
        for (int i = 0; i < count; i++) {
            SongInfo song = songs.get(i);
            float cardX = x + i * (cardW + gap);
            boolean hover = contains(mouseX, mouseY, cardX, y, cardW, artSize + 38);
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cardX - 6, y - 6, cardW + 12, artSize + 49, 16),
                    new Paint().setColor(hover ? RAISED : withAlpha(SURFACE, 0.682f)));
            drawAlbum(ctx, song, cardX, y, artSize, 13);
            GlHelper.drawText(ellipsize(song.name, SMALL_FONT, cardW), cardX, y + artSize + 9, SMALL_FONT,
                    hover ? CREAM : MUTED);
            GlHelper.drawText(ellipsize(song.artist, SMALL_FONT, cardW), cardX, y + artSize + 25, SMALL_FONT, DIM);
            int index = i;
            clickAreas.add(new ClickArea(cardX, y, cardW, artSize + 38,
                    () -> playSongAndOpen(song, songs, index, true, Page.HOME)));
        }
    }

    private void renderSearchPage(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        float innerX = x + PAD;
        float innerW = w - PAD * 2.2f;
        GlHelper.drawText("Search", innerX, y + 24.64f, DISPLAY_FONT, CREAM);
        GlHelper.drawText("Find tracks, artists and albums", innerX, y + 53.68f, BODY_FONT, MUTED);
        renderSearchInput(ctx, innerX, y + 80.08f, innerW, mouseX, mouseY);
        renderSearchResults(ctx, innerX, y + 132.88f, innerW, h - 150.48f, mouseX, mouseY);
    }

    private void renderSearchInput(DrawContext ctx, float x, float y, float w, float mouseX, float mouseY) {
        float h = 38.72f;
        boolean hover = contains(mouseX, mouseY, x, y, w, h);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 16),
                new Paint().setColor(searchFocused ? RAISED : hover ? withAlpha(RAISED, 0.902f) : SURFACE));
        GlHelper.drawText(ICON_SEARCH, x + 15, y + 20, ICON_FONT, searchFocused ? ACCENT : MUTED);

        float textX = x + 45;
        float textW = w - 86;
        float textY = y + 17;
        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(textX, y + 4, textW, h - 8), true);
        if (searchSelectAll && !searchText.isEmpty()) {
            float selectionW = Math.min(textW, measure(searchText, BODY_FONT) + 5);
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(textX - 2, y + 10, selectionW, 22, 5),
                    new Paint().setColor(withAlpha(BERRY_HOVER, 0.792f)));
        }
        String shown = searchText.isEmpty() ? "Search music..." : searchText;
        GlHelper.drawText(shown, textX, textY, BODY_FONT, searchText.isEmpty() ? DIM : CREAM);
        if (searchFocused && !searchSelectAll) {
            float cursorX = textX + Math.min(textW - 1, measure(searchText, BODY_FONT) + 1);
            float blink = (float) Math.abs(Math.sin(System.currentTimeMillis() / 220.0));
            ctx.drawLine(cursorX, y + 10, cursorX, y + 33,
                    new Paint().setColor(withAlpha(ACCENT, blink)).setStrokeWidth(1.54f));
        }
        ctx.restore();
        GlHelper.drawText("Enter", x + w - 49, y + 17, SMALL_FONT, searchText.isEmpty() ? DIM : ACCENT);
        clickAreas.add(new ClickArea(x, y, w, h, () -> {
            searchFocused = true;
            searchSelectAll = false;
        }));
    }

    private void renderSearchResults(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        searchViewport = new Bounds(x, y, w, h);
        if (searching) {
            GlHelper.drawText("Searching...", x + 8, y + 18, BODY_FONT, MUTED);
            return;
        }
        if (searchResults.isEmpty()) {
            String title = searchText.isEmpty() ? "Start with a song or artist" : "No results found";
            String detail = searchText.isEmpty() ? "Press Enter to search NetEase Music." : "Try another title or artist.";
            GlHelper.drawText(title, x + 8, y + 18, HEADING_FONT, MUTED);
            GlHelper.drawText(detail, x + 8, y + 47, SMALL_FONT, DIM);
            return;
        }

        float rowH = 60.5f;
        float gap = 4.4f;
        maxSearchScroll = Math.max(0, searchResults.size() * (rowH + gap) - gap - h);
        searchScroll = clamp(searchScroll, 0, maxSearchScroll);
        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(x, y, w, h), true);
        for (int i = 0; i < searchResults.size(); i++) {
            SongInfo song = searchResults.get(i);
            float rowY = y + i * (rowH + gap) - searchScroll;
            if (rowY + rowH <= y || rowY >= y + h) {
                continue;
            }
            boolean fullyInteractive = rowY >= y && rowY + rowH <= y + h;
            boolean hover = fullyInteractive && contains(mouseX, mouseY, x, rowY, w, rowH);
            boolean playing = isCurrentSong(song);
            if (hover || playing) {
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, rowY, w, rowH, 13),
                        new Paint().setColor(playing ? ROW_ACTIVE : RAISED));
            }
            GlHelper.drawText(playing ? "♪" : String.valueOf(i + 1), x + 14, rowY + 19, SMALL_FONT,
                    playing ? ACCENT : DIM);
            GlHelper.drawText(ellipsize(song.name, BODY_FONT, w - 185), x + 44, rowY + 11, BODY_FONT,
                    playing ? CREAM : MUTED);
            GlHelper.drawText(ellipsize(song.artist, SMALL_FONT, w - 185), x + 44, rowY + 32, SMALL_FONT, DIM);
            String duration = song.formatDuration();
            GlHelper.drawText(duration, x + w - measure(duration, SMALL_FONT) - 52, rowY + 20, SMALL_FONT, MUTED);
            GlHelper.drawText(ICON_ADD, x + w - 30, rowY + 26, ICON_FONT, hover ? ACCENT : MUTED);
            if (fullyInteractive) {
                int index = i;
                clickAreas.add(new ClickArea(x + w - 43, rowY, 43, rowH,
                        () -> MusicPlayer.PLAYLIST.add(searchResults.get(index))));
                clickAreas.add(new ClickArea(x, rowY, w - 48, rowH,
                        () -> playSong(searchResults.get(index), searchResults, index, true)));
            }
        }
        ctx.restore();
    }

    private void renderQueue(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        queueViewport = renderListPage(ctx, "Queue", "Songs in your current session", playQueue,
                x, y, w, h, mouseX, mouseY, false, false);
    }

    private void renderPlaylist(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        playlistViewport = renderListPage(ctx, "Library", "Tracks saved to General Playlist", MusicPlayer.PLAYLIST.getSongs(),
                x, y, w, h, mouseX, mouseY, true, true);
    }

    private Bounds renderListPage(DrawContext ctx, String title, String subtitle, List<SongInfo> songs,
                                  float x, float y, float w, float h, float mouseX, float mouseY,
                                  boolean removable, boolean playlist) {
        float innerX = x + PAD;
        float innerW = w - PAD * 2.2f;
        GlHelper.drawText(title, innerX, y + 25.52f, DISPLAY_FONT, CREAM);
        GlHelper.drawText(subtitle, innerX, y + 53.68f, BODY_FONT, MUTED);
        if (songs.isEmpty()) {
            GlHelper.drawText(removable ? "Add songs from Search to build your library." : "Play a song to create a queue.",
                    innerX, y + 99.44f, BODY_FONT, DIM);
            return null;
        }

        float listY = y + 82.72f;
        float listH = h - 100.32f;
        float rowH = 50.16f;
        float gap = 3.52f;
        float maxScroll = Math.max(0, songs.size() * (rowH + gap) - gap - listH);
        float scroll = playlist ? clamp(playlistScroll, 0, maxScroll) : clamp(queueScroll, 0, maxScroll);
        if (playlist) {
            maxPlaylistScroll = maxScroll;
            playlistScroll = scroll;
        } else {
            maxQueueScroll = maxScroll;
            queueScroll = scroll;
        }

        Bounds viewport = new Bounds(innerX, listY, innerW, listH);
        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(innerX, listY, innerW, listH), true);
        for (int i = 0; i < songs.size(); i++) {
            SongInfo song = songs.get(i);
            float rowY = listY + i * (rowH + gap) - scroll;
            if (rowY + rowH <= listY || rowY >= listY + listH) {
                continue;
            }
            boolean fullyInteractive = rowY >= listY && rowY + rowH <= listY + listH;
            boolean hover = fullyInteractive && contains(mouseX, mouseY, innerX, rowY, innerW, rowH);
            boolean playing = isCurrentSong(song);
            if (hover || playing) {
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, rowY, innerW, rowH, 13),
                        new Paint().setColor(playing ? ROW_ACTIVE : RAISED));
            }
            if (playing) {
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX + 4, rowY + 12, 3, rowH - 24, 1.65f),
                        new Paint().setColor(ACCENT));
            }
            GlHelper.drawText(String.valueOf(i + 1), innerX + 16, rowY + 20, SMALL_FONT,
                    playing ? ACCENT : DIM);
            GlHelper.drawText(ellipsize(song.name, BODY_FONT, innerW - 150), innerX + 48, rowY + 12, BODY_FONT,
                    playing ? CREAM : MUTED);
            GlHelper.drawText(ellipsize(song.artist, SMALL_FONT, innerW - 150), innerX + 48, rowY + 33, SMALL_FONT, DIM);
            if (removable) {
                GlHelper.drawText(ICON_REMOVE, innerX + innerW - 30, rowY + 27, ICON_FONT, hover ? ACCENT : MUTED);
            }
            if (fullyInteractive) {
                int index = i;
                if (removable) {
                    clickAreas.add(new ClickArea(innerX + innerW - 44, rowY, 44, rowH,
                            () -> MusicPlayer.PLAYLIST.remove(index)));
                }
                clickAreas.add(new ClickArea(innerX, rowY, innerW - (removable ? 49 : 0), rowH,
                        () -> playSongAndOpen(songs.get(index), songs, index, true,
                                playlist ? Page.PLAYLIST : Page.QUEUE)));
            }
        }
        ctx.restore();
        return viewport;
    }

    private void renderAbout(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        float innerX = x + PAD;
        GlHelper.drawText("About", innerX, y + 25.52f, DISPLAY_FONT, CREAM);
        GlHelper.drawText("A focused player for your Minecraft sessions", innerX, y + 54.56f, BODY_FONT, MUTED);

        float cardY = y + 82.72f;

        // 第一张：可点击的播放渠道切换器。点击循环切到下一个 source。
        MusicSource current = MusicSources.current();
        MusicSource next = nextSource(current);
        boolean sourceHover = contains(mouseX, mouseY, innerX, cardY, w - PAD * 2, 63.36f);
        aboutCard(ctx, innerX, cardY, w - PAD * 2, 63.36f,
                "Music source",
                current.displayName() + "   →   click to switch to " + next.displayName(),
                sourceHover ? RAISED : SURFACE);
        clickAreas.add(new ClickArea(innerX, cardY, w - PAD * 2, 63.36f, this::cycleMusicSource));

        // 第二张：网易官方登录卡片。
        // 只有当前 source 是官方时才显示 —— gdstudio 不需要登录、登录也没用。
        float secondCardY = cardY + 68;
        if ("NetEase Official".equals(current.displayName())) {
            String loginText = NeteaseOfficialApi.isLoggedIn()
                    ? "Signed in. Open login dialog to re-authorize on another device."
                    : "Sign in to play VIP-only tracks. QR-code login, no password leaves this device.";
            boolean loginHover = contains(mouseX, mouseY, innerX, secondCardY, w - PAD * 2, 63.36f);
            aboutCard(ctx, innerX, secondCardY, w - PAD * 2, 63.36f, "NetEase Account", loginText,
                    loginHover ? RAISED : SURFACE);
            clickAreas.add(new ClickArea(innerX, secondCardY, w - PAD * 2, 63.36f, this::openLoginDialog));
            secondCardY += 68;
        }

        aboutCard(ctx, innerX, secondCardY, w - PAD * 2, 63.36f, "Library",
                "Saved tracks are stored locally in your Nilore config.");
        aboutCard(ctx, innerX, secondCardY + 68, w - PAD * 2, 63.36f, "Playback",
                "Music keeps playing after this screen is closed.");

        boolean hover = contains(mouseX, mouseY, innerX, h - 50.16f, 116, 29.92f);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, h - 50.16f, 116, 29.92f, 14.96f),
                new Paint().setColor(hover ? BERRY_HOVER : BERRY));
        GlHelper.drawText(ICON_SEARCH, innerX + 12.32f, h - 35.08f, ICON_FONT, ACCENT);
        GlHelper.drawText("Search music", innerX + 36.96f, h - 39, SMALL_FONT, CREAM);
        clickAreas.add(new ClickArea(innerX, h - 50.16f, 116, 29.92f, () -> openPage(Page.SEARCH)));
    }

    private void aboutCard(DrawContext ctx, float x, float y, float w, float h, String title, String text, int bg) {
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 17), new Paint().setColor(bg));
        GlHelper.drawText(title, x + 18, y + 18, HEADING_FONT, CREAM);
        GlHelper.drawText(ellipsize(text, BODY_FONT, w - 36), x + 18, y + 45, BODY_FONT, MUTED);
    }

    // 老的双参重载：内部默认 SURFACE 颜色
    private void aboutCard(DrawContext ctx, float x, float y, float w, float h, String title, String text) {
        aboutCard(ctx, x, y, w, h, title, text, SURFACE);
    }

    /** 当前 source 列表里下一个 source，用于 "click to switch to ..." 提示。 */
    private static MusicSource nextSource(MusicSource current) {
        List<MusicSource> all = MusicSources.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).displayName().equals(current.displayName())) {
                return all.get((i + 1) % all.size());
            }
        }
        return all.get(0);
    }

    private void cycleMusicSource() {
        MusicSources.setCurrent(nextSource(MusicSources.current()).displayName());
        MusicSources.save(sourceFile);
    }

    private void openLoginDialog() {
        loginDialogOpen = true;
        loginQrStatus = NeteaseOfficialApi.QrStatus.WAITING;
        loginQrNextPollMs = 0L; // 立刻拉一次
        NeteaseOfficialApi.requestQrKey().thenAccept(key -> {
            if (key == null) {
                loginQrStatus = NeteaseOfficialApi.QrStatus.FAILED;
                return;
            }
            loginQrKey = key;
            // 生成二维码 + 上传纹理都是 GL 操作，必须在渲染线程跑。
            // 直接在 CompletableFuture 的回调里做会在 GL 线程外调 upload() 而失败，
            // 且异常被回调吞掉 —— 表现就是二维码永远停在 "Generating QR..."。
            RenderSystem.recordRenderCall(() -> {
                try {
                    releaseLoginQr();
                    QrCode.RenderableQr renderable =
                            QrCode.render(NeteaseOfficialApi.qrContent(key), 240);
                    Minecraft.getInstance().getTextureManager()
                            .register(renderable.location(), renderable.texture());
                    loginQr = renderable;
                } catch (Throwable t) {
                    // 用 Throwable 而不是 Exception：zxing 缺依赖时抛的是
                    // NoClassDefFoundError（Error 不是 Exception），不接住会直接崩游戏。
                    // 接住后只显示 Failed，不再整个客户端崩掉。
                    ClientBase.logger.error("二维码生成失败（多半是 zxing 依赖没进 classpath）", t);
                    loginQrStatus = NeteaseOfficialApi.QrStatus.FAILED;
                }
            });
        }).exceptionally(e -> {
            // 网络层已经把异常吞成 null 了，这里兜住「请求本身抛异常」的情况
            ClientBase.logger.error("申请二维码 key 失败", e);
            loginQrStatus = NeteaseOfficialApi.QrStatus.FAILED;
            return null;
        });
    }

    private void closeLoginDialog() {
        loginDialogOpen = false;
        releaseLoginQr();
    }

    /**
     * 渲染网易云扫码登录弹层。
     *
     * <p>布局：屏幕居中面板（深底 + 圆角），上方标题与关闭按钮，中间二维码（240×240），
     * 下方状态文字跟随 {@code loginQrStatus} 切换。
     */
    private void renderLoginDialog(DrawContext ctx, int mouseX, int mouseY) {
        // 背景遮罩（让背后内容变暗但不完全黑）
        ctx.drawRectXYWH(0, 0, width, height, new Paint().setColor(withAlpha(0xFF000000, 0.55f)));

        float panelW = 304.0f;
        float panelH = 372.0f;
        float px = (width - panelW) * 0.5f;
        float py = (height - panelH) * 0.5f;
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(px, py, panelW, panelH, 18),
                new Paint().setColor(SHELL));

        // ---- 顶部：标题 + 关闭按钮，两者垂直居中对齐 ----
        float headerY = py + 20;
        float closeSize = 28;
        float closeX = px + panelW - closeSize - 20;
        // 标题 y 由按钮中心反推，保证文字垂直居中和按钮同一条中线
        float titleH = HEADING_FONT.getHeight();
        float titleY = headerY + (closeSize - titleH) * 0.5f;
        GlHelper.drawText("Sign in with NetEase Music", px + 24, titleY, HEADING_FONT, CREAM);

        boolean closeHover = contains(mouseX, mouseY, closeX, headerY, closeSize, closeSize);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(closeX, headerY, closeSize, closeSize, closeSize * 0.5f),
                new Paint().setColor(closeHover ? RAISED : SIDEBAR));
        // 关闭图标用 Material Icons 的 close（ICON_CLOSE，和顶部 headerAction 同一个），
        // 之前用 "×" 字符 + HEADING_FONT，苹方里没有这个字形，画出来是空白。
        float crossW = ICON_FONT.getWidth(ICON_CLOSE);
        float crossH = ICON_FONT.getHeight();
        GlHelper.drawText(ICON_CLOSE,
                closeX + (closeSize - crossW) * 0.5f,
                headerY + (closeSize - crossH) * 0.5f,
                ICON_FONT, closeHover ? ACCENT : MUTED);
        // 弹层走独立列表（屏幕坐标），加到主 clickAreas 会因为坐标系不同点不中
        dialogClickAreas.add(new ClickArea(closeX, headerY, closeSize, closeSize, this::closeLoginDialog));

        // ---- 中间：二维码 ----
        int qrSize = 240;
        float qrX = px + (panelW - qrSize) * 0.5f;
        float qrY = py + 72;
        if (loginQr != null) {
            // 二维码是黑白的，不要被主题色染色 —— Paint 用纯白
            Texture tex = new Texture(loginQr.location(), qrSize, qrSize);
            ctx.drawTexture(tex,
                    Rectangle.ofXYWH(0, 0, qrSize, qrSize),
                    Rectangle.ofXYWH(qrX, qrY, qrSize, qrSize),
                    new Paint().setColor(0xFFFFFFFF));
        } else {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(qrX, qrY, qrSize, qrSize, 8),
                    new Paint().setColor(RAISED));
            drawCentered("Generating QR...", qrX, qrY + qrSize * 0.5f - 8, qrSize, BODY_FONT, MUTED);
        }

        // ---- 底部：状态文字 ----
        String statusText;
        int statusColor = MUTED;
        switch (loginQrStatus) {
            case WAITING -> {
                statusText = "Scan with the NetEase Music app";
                statusColor = MUTED;
            }
            case SCANNED -> {
                statusText = "Confirm sign-in on your phone";
                statusColor = ACCENT;
            }
            case CONFIRMED -> {
                statusText = "Signed in";
                statusColor = ACCENT_STRONG;
            }
            case EXPIRED -> {
                statusText = "QR expired — close & reopen to refresh";
                statusColor = 0xFFFF8080;
            }
            default -> {
                statusText = "Failed — close & reopen to retry";
                statusColor = 0xFFFF8080;
            }
        }
        drawCentered(statusText, px, qrY + qrSize + 18, panelW, BODY_FONT, statusColor);

        // 登录成功后 1.2 秒自动关弹层，给用户看到 ✓ 的时间
        if (loginQrStatus == NeteaseOfficialApi.QrStatus.CONFIRMED) {
            loginQrNextPollMs = Long.MAX_VALUE; // 停掉轮询
            long since = System.currentTimeMillis() - lastLoginConfirmMs;
            if (lastLoginConfirmMs == 0L) {
                lastLoginConfirmMs = System.currentTimeMillis();
            } else if (since > 1200L) {
                closeLoginDialog();
                lastLoginConfirmMs = 0L;
            }
        }
    }

    private void renderPlaybackBar(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo song = player.getCurrentSong();
        ensureAlbum(song);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, w, h, new float[]{0, 0, RADIUS, RADIUS}),
                new Paint().setColor(withAlpha(DIVIDER, 0.9f)));
        ctx.drawRectXYWH(x, y, w, 1, new Paint().setColor(RAISED));

        float progress = dragTarget == DragTarget.PROGRESS && pendingProgress >= 0
                ? pendingProgress : player.getProgress();
        drawSplitProgress(ctx, x, y, w, 3.3f, progress, false);
        lastProgressRect = Rectangle.ofXYWH(x, y - 7, w, 17);
        clickAreas.add(new ClickArea(x, y - 7, w, 17, () -> dragTarget = DragTarget.PROGRESS));

        float art = 40;
        float artX = x + 15.84f;
        float artY = y + 16;
        drawAlbum(ctx, song, artX, artY, art, 7.92f);
        float textX = artX + art + 12.32f;
        float textW = 184;
        GlHelper.drawText(ellipsize(song == null ? "No track playing" : song.name, HEADING_FONT, textW),
                textX, y + 20.24f, HEADING_FONT, CREAM);
        GlHelper.drawText(ellipsize(song == null ? "Open Search to start" : song.artist, SMALL_FONT, textW),
                textX, y + 44.88f, SMALL_FONT, MUTED);
        clickAreas.add(new ClickArea(artX, artY, art + textW + 12.32f, art, () -> {
            if (MusicPlayer.AUDIO_PLAYER.getCurrentSong() != null) {
                openPlayer(page);
            }
        }));

        float center = x + w * 0.5f;
        float sideOffset = 50.0f;
        drawControl(ctx, center - sideOffset - 13.64f, y + 20.24f, 27.28f, 29.92f, ICON_PREV, false, mouseX, mouseY, this::prevSong);
        drawControl(ctx, center - 19.36f, y + 14.96f, 38.72f, 38.72f,
                player.getState() == AudioPlayer.State.PLAYING ? ICON_PAUSE : ICON_PLAY,
                true, mouseX, mouseY, player.getState() == AudioPlayer.State.LOADING ? null : player::togglePause);
        drawControl(ctx, center + sideOffset - 13.64f, y + 20.24f, 27.28f, 29.92f, ICON_NEXT, false, mouseX, mouseY, this::nextSong);

        String current = timestamp(player.getCurrentPositionMs());
        GlHelper.drawText(current, x + w - 131, y + 34, SMALL_FONT, MUTED);
        GlHelper.drawText(ICON_VOLUME, x + w - 93, y + 37, ICON_FONT, MUTED);
        float volumeX = x + w - 70;
        float volume = dragTarget == DragTarget.VOLUME && pendingVolume >= 0 ? pendingVolume : player.getVolume();
        drawSlider(ctx, volumeX, y + 35, 54, volume);
        lastVolumeRect = Rectangle.ofXYWH(volumeX, y + 27, 54, 16);
        clickAreas.add(new ClickArea(volumeX, y + 27, 54, 16, () -> dragTarget = DragTarget.VOLUME));
    }

    private void renderPlayerPage(DrawContext ctx, float mouseX, float mouseY) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo song = player.getCurrentSong();
        ensureAlbum(song);

        boolean backHover = contains(mouseX, mouseY, 22.88f, 19.36f, 38.72f, 32);
        if (backHover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(22.88f, 19.36f, 38.72f, 32, 16), new Paint().setColor(RAISED));
        }
        drawCentered(ICON_BACK, 22.88f, 34.32f, 38.72f, ICON_LARGE, backHover ? ACCENT : CREAM);
        clickAreas.add(new ClickArea(22.88f, 19.36f, 38.72f, 32, this::returnFromPlayer));

        float cover = 194.48f;
        float coverX = 82.0f;
        float coverY = 54.0f;
        drawAlbum(ctx, song, coverX, coverY, cover, 16.72f);
        float infoY = coverY + cover + 15.84f;
        GlHelper.drawText(ellipsize(song == null ? "No track playing" : song.name, TITLE_FONT, cover),
                coverX, infoY, TITLE_FONT, CREAM);
        GlHelper.drawText(ellipsize(song == null ? "Search for a song to start" : song.artist, BODY_FONT, cover),
                coverX, infoY + 31, BODY_FONT, MUTED);
        if (song != null && song.albumName != null && !song.albumName.isEmpty()) {
            GlHelper.drawText(ellipsize(song.albumName, SMALL_FONT, cover), coverX, infoY + 54, SMALL_FONT, DIM);
        }

        float lyricX = 376.0f;
        float lyricY = 37.0f;
        float lyricW = DESIGN_W - lyricX - 38.0f;
        float lyricH = 292.0f;
        renderLyrics(ctx, song, lyricX, lyricY, lyricW, lyricH);
        renderPlayerTransport(ctx, song, player, mouseX, mouseY);
    }

    private void renderLyrics(DrawContext ctx, SongInfo song, float x, float y, float w, float h) {
        if (song == null) {
            GlHelper.drawText("Play a song to see lyrics", x, y + h * 0.495f, HEADING_FONT, DIM);
            return;
        }

        List<LyricLine> lines = lyricsFor(song);
        if (lines.isEmpty()) {
            String message = lyricsCache.containsKey(song.id) ? "Lyrics unavailable" : "Loading lyrics...";
            GlHelper.drawText(message, x, y + h * 0.495f, HEADING_FONT, DIM);
            return;
        }

        if (lyricSongId != song.id) {
            lyricSongId = song.id;
            lyricScroll = 0;
        }
        long position = MusicPlayer.AUDIO_PLAYER.getCurrentPositionMs();
        int current = findCurrentLyricLine(lines, position);
        float lineH = 46.64f;
        float totalHeight = lines.size() * lineH;
        // 歌词不足一屏时整体垂直居中，否则按当前行滚动（target 夹在可滚动范围内，防止滚过头）。
        float topOffset;
        if (totalHeight < h) {
            lyricScroll = 0;
            topOffset = (h - totalHeight) * 0.5f;
        } else {
            float maxScroll = totalHeight - h;
            float target = clamp(current * lineH - h * 0.264f, 0.0f, maxScroll);
            lyricScroll += (target - lyricScroll) * 0.198f;
            topOffset = 0;
        }

        Rectangle viewport = Rectangle.ofXYWH(x, y, w, h);
        ctx.save();
        ctx.clipRect(viewport, true);
        for (int i = 0; i < lines.size(); i++) {
            float rowY = y + topOffset + i * lineH - lyricScroll;
            if (rowY < y - lineH || rowY > y + h) {
                continue;
            }
            int distance = Math.abs(i - current);
            boolean active = i == current;
            FontRenderer font = active ? LYRIC_ACTIVE_FONT : LYRIC_FONT;
            int color = active ? ACCENT_STRONG : distance == 1 ? MUTED : distance == 2 ? withAlpha(MUTED, 0.605f) : DIM;
            String raw = lines.get(i).text();
            String text = ellipsize(raw == null || raw.isBlank() ? "···" : raw, font, w);
            if (active) {
                drawKaraokeLine(text, x, rowY, font, lineProgress(lines, current, position));
            } else {
                GlHelper.drawText(text, x, rowY, font, color);
            }
        }
        ctx.restore();
    }

    /**
     * 当前行唱到哪儿了，返回 0..1。
     *
     * <p>优先按 {@link LyricLine#words()} 的逐字时间精确算（直连网易能拿到 yrc）；
     * 没拿到逐字时间就退回「本行起点 → 下一行起点」线性分摊。
     */
    private static float lineProgress(List<LyricLine> lines, int index, long positionMs) {
        if (index < 0 || index >= lines.size()) {
            return 0.0f;
        }
        LyricLine line = lines.get(index);
        // 1) 有逐字数据：当前字的时间窗（wordStart, wordStart+wordDur）内插值
        List<LyricLine.Word> words = line.words();
        if (words != null && !words.isEmpty()) {
            for (int w = 0; w < words.size(); w++) {
                LyricLine.Word word = words.get(w);
                long wStart = word.startMs();
                long wEnd = wStart + Math.max(word.durationMs(), 1L);
                if (positionMs < wEnd) {
                    // 字内进度 + 字之前所有字的累计宽度占比
                    float within = clamp((positionMs - wStart) / (float) (wEnd - wStart), 0.0f, 1.0f);
                    return (w + within) / words.size();
                }
            }
            return 1.0f;
        }
        // 2) 没逐字：按行时长分摊
        long start = line.timeMs();
        long end = index + 1 < lines.size() ? lines.get(index + 1).timeMs() : start + 6000L;
        if (end <= start) {
            return 1.0f;
        }
        return clamp((positionMs - start) / (float) (end - start), 0.0f, 1.0f);
    }

    /**
     * 画当前歌词行：未唱部分压暗，已唱部分提亮。
     *
     * <p>不做任何发光效果（发光那套 FBO 模糊在这台机器上出雪花噪点，已回滚）。
     * 只按已唱宽度取完整前缀画亮色，分界逐字推进，能看清唱到哪一句的哪个字。
     */
    private void drawKaraokeLine(String text, float x, float rowY, FontRenderer font, float progress) {
        // 整行先画暗色
        GlHelper.drawText(text, x, rowY, font, MUTED);

        float sungWidth = font.getWidth(text) * progress;
        if (sungWidth < 1.0f) {
            return;
        }
        String sung = prefixFitting(text, font, sungWidth);
        // 已唱前缀提亮，无发光
        GlHelper.drawText(sung, x, rowY, font, ACCENT_STRONG);
    }

    private void renderPlayerTransport(DrawContext ctx, SongInfo song, AudioPlayer player, float mouseX, float mouseY) {
        float progressX = 25.0f;
        float progressY = 370.0f;
        float progressW = DESIGN_W - 50.0f;
        float progress = dragTarget == DragTarget.PROGRESS && pendingProgress >= 0
                ? pendingProgress : player.getProgress();
        drawSplitProgress(ctx, progressX, progressY, progressW, 7.7f, progress, true);
        lastProgressRect = Rectangle.ofXYWH(progressX, progressY - 10, progressW, 27);
        clickAreas.add(new ClickArea(progressX, progressY - 10, progressW, 27, () -> dragTarget = DragTarget.PROGRESS));

        String current = timestamp(player.getCurrentPositionMs());
        String total = timestamp(song == null ? 0 : song.duration);
        GlHelper.drawText(current, progressX, progressY + 15, SMALL_FONT, MUTED);
        GlHelper.drawText(total, progressX + progressW - measure(total, SMALL_FONT), progressY + 15, SMALL_FONT, MUTED);

        float controlsY = 399.0f;
        float center = DESIGN_W * 0.5f;
        drawControl(ctx, center - 74, controlsY + 3, 34, 38, ICON_PREV, false, mouseX, mouseY, this::prevSong);
        drawControl(ctx, center - 25, controlsY - 4, 50, 50,
                player.getState() == AudioPlayer.State.PLAYING ? ICON_PAUSE : ICON_PLAY,
                true, mouseX, mouseY, player.getState() == AudioPlayer.State.LOADING ? null : player::togglePause);
        drawControl(ctx, center + 40, controlsY + 3, 34, 38, ICON_NEXT, false, mouseX, mouseY, this::nextSong);

        GlHelper.drawText(ICON_VOLUME, DESIGN_W - 154, controlsY + 22, ICON_FONT, MUTED);
        float volumeX = DESIGN_W - 124;
        float volume = dragTarget == DragTarget.VOLUME && pendingVolume >= 0 ? pendingVolume : player.getVolume();
        drawSlider(ctx, volumeX, controlsY + 20, 88, volume);
        lastVolumeRect = Rectangle.ofXYWH(volumeX, controlsY + 10, 88, 20);
        clickAreas.add(new ClickArea(volumeX, controlsY + 10, 88, 20, () -> dragTarget = DragTarget.VOLUME));

        if (player.getState() == AudioPlayer.State.LOADING) {
            GlHelper.drawText("Loading", center - measure("Loading", SMALL_FONT) * 0.5f, controlsY - 22, SMALL_FONT, DIM);
        }
    }

    private void drawControl(DrawContext ctx, float x, float y, float w, float h, String icon, boolean primary,
                             float mouseX, float mouseY, Runnable action) {
        boolean enabled = action != null;
        boolean hover = enabled && contains(mouseX, mouseY, x, y, w, h);
        if (primary || hover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, h * 0.5f),
                    new Paint().setColor(primary ? enabled ? BERRY_HOVER : RAISED : RAISED));
        }
        drawCentered(icon, x, y + (h - ICON_LARGE.getMetrics().capHeight()) * 0.5f + 8.0f, w, ICON_LARGE,
                enabled ? primary ? CREAM : hover ? ACCENT : CREAM : DIM);
        if (enabled) {
            clickAreas.add(new ClickArea(x, y, w, h, action));
        }
    }

    private void drawSplitProgress(DrawContext ctx, float x, float y, float w, float height, float value, boolean thumb) {
        float progress = clamp(value, 0, 1);
        float radius = height * 0.5f;
        if (progress <= 0.001f) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, height, radius), new Paint().setColor(RAISED));
            return;
        }
        if (progress >= 0.999f) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, height, radius), new Paint().setColor(ACCENT));
            return;
        }
        float splitX = x + w * progress;
        float gap = thumb ? 11.0f : 3.0f;
        float playedW = Math.max(0, splitX - x - gap * 0.5f);
        float remainingX = splitX + gap * 0.5f;
        float remainingW = Math.max(0, x + w - remainingX);
        if (playedW > 0) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, playedW, height, radius), new Paint().setColor(ACCENT));
        }
        if (remainingW > 0) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(remainingX, y, remainingW, height, radius), new Paint().setColor(RAISED));
        }
        if (thumb) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(splitX - 4, y - 2, 8, height + 4, 4), new Paint().setColor(CREAM));
        }
    }

    private void drawSlider(DrawContext ctx, float x, float y, float w, float value) {
        float progress = clamp(value, 0, 1);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 1, w, 4, 2), new Paint().setColor(RAISED));
        if (progress > 0) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 1, w * progress, 4, 2), new Paint().setColor(ACCENT));
        }
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x + w * progress - 4, y - 3, 8, 8, 4), new Paint().setColor(CREAM));
    }

    private void drawAlbum(DrawContext ctx, SongInfo song, float x, float y, float size, float radius) {
        if (song != null && song.id == albumSongId && albumTexture != null) {
            org.joml.Matrix4f pose = ctx.getPoseStack().last().pose();
            DrawContext.getRoundedRectShader().drawTextured(pose, x, y, x + size, y + size,
                    radius, radius, radius, radius, 0xFFFFFFFF, albumTexture.getGlId(), 0, 0, 1, 1);
            return;
        }
        // 封面还没加载出来时的占位。用主题色而不是写死的土黄，这样跟着换歌配色一起变。
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, size, size, radius), new Paint().setColor(BERRY));
        float iconY = y + (size - ICON_LARGE.getMetrics().capHeight()) * 0.5f + 8.0f;
        drawCentered(ICON_MUSIC, x, iconY, size, ICON_LARGE, ACCENT);
    }

    private List<SongInfo> recommendations() {
        List<SongInfo> result = new ArrayList<>();
        SongInfo current = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        addUnique(result, current);
        for (SongInfo song : MusicPlayer.PLAYLIST.getSongs()) {
            addUnique(result, song);
        }
        for (SongInfo song : playQueue) {
            addUnique(result, song);
        }
        return result;
    }

    private static void addUnique(List<SongInfo> songs, SongInfo candidate) {
        if (candidate != null && songs.stream().noneMatch(song -> song.id == candidate.id)) {
            songs.add(candidate);
        }
    }

    private boolean isCurrentSong(SongInfo song) {
        SongInfo current = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        return current != null && song != null && current.id == song.id;
    }

    private void openPage(Page target) {
        page = target;
        searchFocused = target == Page.SEARCH && searchFocused;
        if (target != Page.SEARCH) {
            searchFocused = false;
            searchSelectAll = false;
        }
    }

    private void openPlayer(Page returnPage) {
        if (returnPage != Page.PLAYER) {
            playerReturnPage = returnPage;
        }
        searchFocused = false;
        searchSelectAll = false;
        page = Page.PLAYER;
    }

    private void returnFromPlayer() {
        page = playerReturnPage == Page.PLAYER ? Page.HOME : playerReturnPage;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        // 登录弹层在最上层：用原始屏幕坐标优先匹配，命中就吞掉点击（不穿透到下层 UI）。
        // 弹层是在 translate/scale 变换之外画的，所以这里不能走 toDesignX/Y。
        if (loginDialogOpen) {
            for (int i = dialogClickAreas.size() - 1; i >= 0; i--) {
                ClickArea area = dialogClickAreas.get(i);
                if (area.contains(mouseX, mouseY)) {
                    area.action().run();
                    return true;
                }
            }
            return true;
        }
        double designX = toDesignX(mouseX);
        double designY = toDesignY(mouseY);
        for (int i = clickAreas.size() - 1; i >= 0; i--) {
            ClickArea area = clickAreas.get(i);
            if (!area.contains(designX, designY)) {
                continue;
            }
            area.action().run();
            if (dragTarget == DragTarget.PROGRESS) {
                updateProgress(designX);
            } else if (dragTarget == DragTarget.VOLUME) {
                updateVolume(designX);
            }
            return true;
        }
        searchFocused = false;
        searchSelectAll = false;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double designX = toDesignX(mouseX);
        if (button == 0 && dragTarget == DragTarget.PROGRESS) {
            updateProgress(designX);
            return true;
        }
        if (button == 0 && dragTarget == DragTarget.VOLUME) {
            updateVolume(designX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragTarget == DragTarget.PROGRESS && pendingProgress >= 0) {
            commitProgress(pendingProgress);
            pendingProgress = -1;
        }
        if (button == 0 && dragTarget == DragTarget.VOLUME && pendingVolume >= 0) {
            MusicPlayer.AUDIO_PLAYER.setVolume(pendingVolume);
            pendingVolume = -1;
        }
        dragTarget = DragTarget.NONE;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        float designX = toDesignX(mouseX);
        float designY = toDesignY(mouseY);
        if (page == Page.SEARCH && searchViewport != null && searchViewport.contains(designX, designY)) {
            searchScroll = clamp(searchScroll - (float) delta * 30, 0, maxSearchScroll);
            return true;
        }
        if (page == Page.QUEUE && queueViewport != null && queueViewport.contains(designX, designY)) {
            queueScroll = clamp(queueScroll - (float) delta * 30, 0, maxQueueScroll);
            return true;
        }
        if (page == Page.PLAYLIST && playlistViewport != null && playlistViewport.contains(designX, designY)) {
            playlistScroll = clamp(playlistScroll - (float) delta * 30, 0, maxPlaylistScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!searchFocused || codePoint < 32 || codePoint == 127) {
            return super.charTyped(codePoint, modifiers);
        }
        searchText = searchSelectAll ? String.valueOf(codePoint) : searchText + codePoint;
        searchSelectAll = false;
        searchDirty = true;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                startSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                searchText = searchSelectAll ? "" : searchText.isEmpty() ? "" : searchText.substring(0, searchText.length() - 1);
                searchSelectAll = false;
                searchDirty = true;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                searchText = "";
                searchSelectAll = false;
                searchDirty = true;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                searchSelectAll = false;
                return true;
            }
            if (Screen.isPaste(keyCode)) {
                String paste = Minecraft.getInstance().keyboardHandler.getClipboard();
                searchText = searchSelectAll ? paste : searchText + paste;
                searchSelectAll = false;
                searchDirty = true;
                return true;
            }
            if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_A) {
                searchSelectAll = !searchText.isEmpty();
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && page == Page.PLAYER) {
            returnFromPlayer();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void startSearch() {
        searchDirty = false;
        String query = searchText.trim();
        long sequence = searchSeq.incrementAndGet();
        if (query.isEmpty()) {
            searchResults = List.of();
            searching = false;
            return;
        }
        searching = true;
        NeteaseApi.search(query, 20).whenComplete((results, error) -> {
            if (searchSeq.get() == sequence) {
                searchResults = results == null ? List.of() : results;
                searching = false;
                searchScroll = 0;
            }
        });
    }

    private void playSongAndOpen(SongInfo song, List<SongInfo> queue, int index, boolean autoAdvance, Page returnPage) {
        playSong(song, queue, index, autoAdvance);
        openPlayer(returnPage);
    }

    private void playSong(SongInfo song, List<SongInfo> queue, int index, boolean autoAdvance) {
        long request = playRequestSeq.incrementAndGet();
        playlistAutoAdvance = autoAdvance;
        queueIndex = index;
        List<SongInfo> queueSnapshot = new ArrayList<>(queue);
        playQueue.clear();
        playQueue.addAll(queueSnapshot);
        lyricSongId = -1;

        NeteaseApi.getLyrics(song.id).thenAccept(lines -> {
            List<LyricLine> safeLines = lines == null ? List.of() : lines;
            lyricsCache.put(song.id, safeLines);
            lyricsRequested.put(song.id, true);
            try {
                LyricsModule module = NiloreClient.getInstance().getModuleManager().getModule(LyricsModule.class);
                if (module != null) {
                    module.setLyrics(song.id, safeLines);
                }
            } catch (Exception ignored) { }
        });
        if (MusicPlayer.AUDIO_PLAYER.isPreloadedFor(song)) {
            String preloadedUrl = MusicPlayer.AUDIO_PLAYER.getPreloadedUrl(song);
            if (preloadedUrl != null) {
                startPlayback(song, preloadedUrl, request, autoAdvance, true);
                return;
            }
        }
        NeteaseApi.getSongUrl(song.id).thenAccept(result -> {
            if (result == null || playRequestSeq.get() != request) {
                return;
            }
            String url = result.url();
            if (url == null || url.isBlank()) {
                System.err.println("[MusicPlayer] No playable URL for " + song.name);
                return;
            }
            if (song.duration <= 0 && result.size() > 0) {
                song.duration = result.size() * 1000L / 40000;
            }
            startPlayback(song, url, request, autoAdvance);
        });
    }

    private void startPlayback(SongInfo song, String url, long request, boolean autoAdvance) {
        startPlayback(song, url, request, autoAdvance, false);
    }

    private void startPlayback(SongInfo song, String url, long request, boolean autoAdvance, boolean usePreload) {
        if (playRequestSeq.get() == request) {
            MusicPlayer.AUDIO_PLAYER.play(song, url, autoAdvance ? () -> playNextFromPlaylist(request) : null, usePreload);
        }
    }

    private void playNextFromPlaylist(long request) {
        if (playRequestSeq.get() != request || !playlistAutoAdvance || playQueue.isEmpty()) {
            return;
        }
        int next = (queueIndex + 1) % playQueue.size();
        playSong(playQueue.get(next), playQueue, next, true);
    }

    private void onCrossfadeTrack() {
        SongInfo song = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        if (song == null) {
            return;
        }
        for (int i = 0; i < playQueue.size(); i++) {
            if (playQueue.get(i).id == song.id) {
                queueIndex = i;
                break;
            }
        }
        lyricSongId = -1;
        if (lyricsCache.containsKey(song.id)) {
            return;
        }
        NeteaseApi.getLyrics(song.id).thenAccept(lines -> {
            List<LyricLine> safeLines = lines == null ? List.of() : lines;
            lyricsCache.put(song.id, safeLines);
            lyricsRequested.put(song.id, true);
            try {
                LyricsModule module = NiloreClient.getInstance().getModuleManager().getModule(LyricsModule.class);
                if (module != null) {
                    module.setLyrics(song.id, safeLines);
                }
            } catch (Exception ignored) { }
        });
    }

    private void requestPreloadForNext() {
        if (!MusicPlayer.AUDIO_PLAYER.isMelodifyEnabled() || !playlistAutoAdvance
                || playQueue.isEmpty() || queueIndex < 0) {
            return;
        }
        int next = (queueIndex + 1) % playQueue.size();
        SongInfo nextSong = playQueue.get(next);
        if (nextSong == null || MusicPlayer.AUDIO_PLAYER.isPreloadedFor(nextSong)) {
            return;
        }
        NeteaseApi.getSongUrl(nextSong.id).thenAccept(result -> {
            if (result == null || result.url() == null || result.url().isBlank()
                    || MusicPlayer.AUDIO_PLAYER.isPreloadedFor(nextSong)) {
                return;
            }
            MusicPlayer.AUDIO_PLAYER.preloadNext(nextSong, result.url());
        });
    }

    private void nextSong() {
        if (playQueue.isEmpty() || queueIndex < 0) {
            return;
        }
        int next = (queueIndex + 1) % playQueue.size();
        playSong(playQueue.get(next), playQueue, next, playlistAutoAdvance);
    }

    private void prevSong() {
        if (playQueue.isEmpty() || queueIndex < 0) {
            return;
        }
        int previous = (queueIndex - 1 + playQueue.size()) % playQueue.size();
        playSong(playQueue.get(previous), playQueue, previous, playlistAutoAdvance);
    }

    private void updateProgress(double mouseX) {
        if (lastProgressRect == null) {
            return;
        }
        SongInfo song = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        if (song == null || song.duration <= 0) {
            return;
        }
        pendingProgress = clamp((float) ((mouseX - lastProgressRect.getX()) / lastProgressRect.getWidth()), 0, 1);
    }

    private void commitProgress(float progress) {
        SongInfo song = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        if (song != null && song.duration > 0) {
            MusicPlayer.AUDIO_PLAYER.seekToMs((long) (clamp(progress, 0, 1) * song.duration));
        }
    }

    private void updateVolume(double mouseX) {
        if (lastVolumeRect == null) {
            return;
        }
        float volume = clamp((float) ((mouseX - lastVolumeRect.getX()) / lastVolumeRect.getWidth()), 0, 1);
        pendingVolume = volume;
        MusicPlayer.AUDIO_PLAYER.setVolume(volume);
        MusicPlayer module = NiloreClient.getInstance().getModuleManager().getModule(MusicPlayer.class);
        if (module != null) {
            module.setVolumeSetting(volume);
        }
    }

    private void ensureAlbum(SongInfo song) {
        if (song == null) {
            return;
        }
        if (albumBytes != null) {
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(albumBytes));
                // 取色和压色雾都必须排在 DynamicTexture 前面：它上传后会接管并关闭这张图
                setTheme(MonetPalette.fromImage(image));
                NativeImage palette = CoverBackdrop.prepareFluidPalette(image);
                DynamicTexture texture = new DynamicTexture(image);
                albumTexture = new Texture(texture.getId(), image.getWidth(), image.getHeight());
                // 背景交叉淡化：旧色雾 → 新色雾
                Texture fluid = CoverBackdrop.uploadPalette(palette);
                if (fluid != null) {
                    fluidPrev = fluidTexture;
                    fluidTexture = fluid;
                    backdropFade = fluidPrev == null ? 1.0f : 0.0f;
                }
            } catch (Exception e) {
                System.err.println("[MusicPlayerScreen] Album art failed: " + e.getMessage());
            }
            albumBytes = null;
        }
        if (albumLoading || (song.id == albumSongId && albumTexture != null)
                || (song.id == albumSongId && albumRetryCount >= 2)) {
            return;
        }
        if (song.id != albumSongId) {
            albumRetryCount = 0;
        }
        albumSongId = song.id;
        albumTexture = null;
        albumBytes = null;
        albumLoading = true;
        albumRetryCount++;
        NeteaseApi.getAlbumPicUrl(song).thenAccept(url -> {
            if (url == null || url.isEmpty()) {
                albumLoading = false;
                return;
            }
            try {
                byte[] bytes = MusicHttp.getBytes(URI.create(url));
                if (bytes != null && bytes.length > 100) {
                    albumBytes = bytes;
                } else {
                    albumSongId = -1;
                }
            } catch (Exception e) {
                albumSongId = -1;
            } finally {
                albumLoading = false;
            }
        }).exceptionally(error -> {
            albumLoading = false;
            albumSongId = -1;
            return null;
        });
    }

    private List<LyricLine> lyricsFor(SongInfo song) {
        List<LyricLine> cached = lyricsCache.get(song.id);
        if (cached != null) {
            return cached;
        }
        if (lyricsRequested.putIfAbsent(song.id, true) == null) {
            NeteaseApi.getLyrics(song.id).thenAccept(lines ->
                    lyricsCache.put(song.id, lines == null ? List.of() : lines));
        }
        return List.of();
    }

    private int findCurrentLyricLine(List<LyricLine> lines, long position) {
        int current = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs() > position) {
                break;
            }
            current = i;
        }
        return current;
    }

    private float toDesignX(double screenX) {
        return (float) ((screenX - layoutOriginX) / layoutScale);
    }

    private float toDesignY(double screenY) {
        return (float) ((screenY - layoutOriginY) / layoutScale);
    }

    private static String greeting() {
        int hour = LocalTime.now().getHour();
        return hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
    }

    private static void drawCentered(String text, float x, float y, float width, FontRenderer font, int color) {
        GlHelper.drawText(text, x + (width - GlHelper.getStringWidth(text, font)) * 0.5f, y, font, color);
    }

    /**
     * 取能放进给定宽度的最长前缀。
     *
     * <p>和 {@link #ellipsize} 的区别是**不加省略号** —— 卡拉OK已唱部分只该取字，
     * 补个 "..." 会在扫光头部多出三个点。
     */
    private static String prefixFitting(String value, FontRenderer font, float maxWidth) {
        if (value == null || maxWidth <= 0.0f) {
            return "";
        }
        int end = value.length();
        while (end > 0 && measure(value.substring(0, end), font) > maxWidth) {
            end--;
        }
        return value.substring(0, end);
    }

    private static int withAlpha(int color, float alpha) {
        int sourceAlpha = color >>> 24 & 255;
        return ColorUtil.fromARGB(color >>> 16 & 255, color >>> 8 & 255, color & 255,
                (int) (sourceAlpha * clamp(alpha, 0, 1)));
    }

    private static float measure(String text, FontRenderer font) {
        return GlHelper.getStringWidth(text == null ? "" : text, font);
    }

    private static String ellipsize(String value, FontRenderer font, float maxWidth) {
        if (value == null || maxWidth <= 0) {
            return "";
        }
        if (measure(value, font) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int end = value.length();
        while (end > 0 && measure(value.substring(0, end) + suffix, font) > maxWidth) {
            end--;
        }
        return value.substring(0, end) + suffix;
    }

    private static String timestamp(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean contains(double mouseX, double mouseY, float x, float y, float w, float h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record LayoutTransform(float originX, float originY, float scale) { }

    private record Bounds(float x, float y, float width, float height) {
        private boolean contains(double mouseX, double mouseY) {
            return MusicPlayerScreen.contains(mouseX, mouseY, x, y, width, height);
        }
    }

    private enum Page { HOME, PLAYER, SEARCH, QUEUE, PLAYLIST, ABOUT }

    private enum DragTarget { NONE, PROGRESS, VOLUME }

    private record ClickArea(float x, float y, float width, float height, Runnable action) {
        private boolean contains(double mouseX, double mouseY) {
            return MusicPlayerScreen.contains(mouseX, mouseY, x, y, width, height);
        }
    }
}
