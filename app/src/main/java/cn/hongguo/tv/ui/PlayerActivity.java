package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.MediaCache;
import cn.hongguo.tv.cache.PlayProgress;
import cn.hongguo.tv.media.EpisodePreparer;
import cn.hongguo.tv.media.TrackPicker;
import cn.hongguo.tv.media.SeasonPlaybackCoordinator;
import cn.hongguo.tv.media.PreparationCancellation;
import cn.hongguo.tv.cache.SeasonRepository;

/**
 * 播放页：连续播放 + 进度记忆 + 清晰度切换 + 倍速。
 *
 * 电视遥控器交互：
 *   确定 / 播放暂停 —— 暂停或继续
 *   左 / 右        —— 快退 / 快进 10 秒
 *   上 / 下        —— 上一集 / 下一集
 *   菜单键 / M     —— 清晰度切换（在可选档位间循环）
 *   返回           —— 退出（自动保存进度）
 *
 * 为什么用 MediaPlayer 而不是 ExoPlayer：
 *   包体约束。解密后已是标准 mp4（hvc1 + mp4a），系统解码器够用。
 *   老电视对 H.265 的支持依赖厂商，TrackPicker 已先问过系统解码能力。
 */
public class PlayerActivity extends TvActivity implements SurfaceHolder.Callback {

    private static final String TAG = "HgPlayer";

    public static final String EXTRA_SERIES_ID = "series_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_COVER = "cover";
    public static final String EXTRA_EPISODES = "episodes";
    public static final String EXTRA_START_INDEX = "start_index";
    private static final String STATE_IDENTITY = "playback_identity";
    private static final String STATE_POSITION = "start_position_ms";

    private static final int SEEK_MS = 10000;
    /** 进度自动保存间隔。 */
    private static final long SAVE_INTERVAL_MS = 5000;
    /** 可用倍速。 */
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};

    private SurfaceView surface;
    private TextView status;
    private TextView titleView;
    private TextView timeView;
    private TextView qualityView;
    /** 收藏按钮：显示当前是否已收藏，点击切换。 */
    private TextView favView;
    /** 当前剧是否在云端书架里；未登录或还没查过时为 false。 */
    private volatile boolean favorited;
    private boolean favoriteBusy;
    /** 是否已经成功查过一次书架状态（决定按钮文案能不能信）。 */
    private volatile boolean favoriteKnown;
    /** 系列封面（进度上报用，让手机端历史里也有图）。 */
    private String seriesCover = "";
    private ProgressBar bar;
    private LinearLayout overlay;

    private MediaPlayer player;
    private boolean prepared;
    private boolean surfaceReady;
    private File pendingFile;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Api api;
    private MediaCache cache;
    private PlayProgress progress;
    private EpisodePreparer preparer;
    /** 账号接口：登录后把进度同步到云端（手机「历史」页能看到）。 */
    private cn.hongguo.tv.account.AccountApi account;
    /** 云端已知的最新集号；0 表示还没读过。 */
    private volatile int cloudEpisode;
    /** 上一次上报的「集号|位置」，避免每个 tick 都打一次网络。 */
    private String lastReported = "";

    private String seriesId;
    private String seriesTitle;
    private final List<Ep> episodes = new ArrayList<>();
    private int index;
    private volatile int loadGeneration;
    private cn.hongguo.tv.cache.LibraryRepository library;
    private String playbackOwner;
    private long requestedStartPosition=-1;
    private boolean forceFlush;
    private boolean autoplay=true;
    private boolean foreground;
    private android.app.AlertDialog playbackMenu;
    private android.app.AlertDialog episodePicker;
    private android.app.AlertDialog seasonPicker;
    private TextView seasonsView;
    private SeasonRepository seasons;
    private SeasonRepository.Overview seasonOverview;
    private final SeasonPlaybackCoordinator seasonPlayback = new SeasonPlaybackCoordinator();
    /** EpisodePreparer writes fixed cache files; serialize preparation without another decoder. */
    private static final Object preparationLock = new Object();
    private PreparationCancellation episodePreparation, seasonPreparation;
    private boolean overviewBusy, showSeasonsWhenReady, nextPreparing, switchingSeason;
    private String overviewError = "", nextError = "";
    private SeasonTarget nextTarget;
    private SeasonRepository.Season retrySeason;
    private String playbackIdentity;
    private int favoriteGeneration;
    private boolean userPaused, seeking, armAfterSeekAdvance;
    private boolean playbackWanted = true;
    private int seekSettledPosition;

    private static final class SeasonTarget {
        SeasonRepository.Season season;
        List<Ep> episodes;
        File file;
        String definition;
        int cap, firstIndex;
    }


    /** 当前清晰度上限，用于切换。 */
    private int capIndex;
    private static final int[] CAPS = {1080, 720, 480};
    private static final String[] CAP_NAMES = {"1080p", "720p", "480p"};
    /** 当前倍速下标。 */
    private int speedIndex;

    /** 实际选中的清晰度名（TrackPicker 可能因设备能力降档）。 */
    private String actualDefinition = "";

    private final Runnable saveTick = new Runnable() {
        @Override
        public void run() {
            if (!foreground || isFinishing() || isDestroyed()) return;
            saveProgress();
            ui.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private static final class Ep {
        String vid;
        int num;
        String title;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String identity = cn.hongguo.tv.account.Account.load(this).sessionIdentity();
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_SERIES_ID)) {
            if (!identity.equals(savedInstanceState.getString(STATE_IDENTITY, ""))) { finish(); return; }
            restorePlaybackIntent(savedInstanceState);
        }
        String intentIdentity = getIntent().getStringExtra(STATE_IDENTITY);
        if (intentIdentity != null && !identity.equals(intentIdentity)) { finish(); return; }
        getIntent().putExtra(STATE_IDENTITY, identity);
        cn.hongguo.tv.sign.RequestSigner.bind(this);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
        seriesTitle = getIntent().getStringExtra(EXTRA_TITLE);
        seriesCover = getIntent().getStringExtra(EXTRA_COVER);
        if (seriesCover == null) {
            seriesCover = "";
        }
        parseEpisodes(getIntent().getStringExtra(EXTRA_EPISODES));
        if (episodes.isEmpty()) {
            finish();
            return;
        }

        api = new Api(this);
        cache = new MediaCache(this);
        progress = new PlayProgress(this);
        library = cn.hongguo.tv.cache.LibraryRepository.get(this);
        playbackOwner = library.owner();
        playbackIdentity = cn.hongguo.tv.account.Account.load(this).sessionIdentity();
        seasons = new SeasonRepository(api);
        seasonPlayback.enter(seriesId, playbackIdentity);
        requestedStartPosition = getIntent().getLongExtra("start_position_ms",-1);
        autoplay = getPreferences(0).getBoolean("autoplay",true);
        capIndex = Math.max(0,Math.min(CAPS.length-1,getPreferences(0).getInt("quality",0)));
        speedIndex = Math.max(0,Math.min(SPEEDS.length-1,getPreferences(0).getInt("speed",0)));
        preparer = new EpisodePreparer(cache);
        account = new cn.hongguo.tv.account.AccountApi(this);
        // 沿用上次选的画面比例模式
        aspectMode = progress.aspectMode();
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_SERIES_ID)) {
            capIndex = Math.max(0, Math.min(CAPS.length - 1, savedInstanceState.getInt("player_quality", capIndex)));
            speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, savedInstanceState.getInt("player_speed", speedIndex)));
            aspectMode = Math.max(0, Math.min(MODE_NAMES.length - 1, savedInstanceState.getInt("player_aspect", aspectMode)));
            autoplay = savedInstanceState.getBoolean("player_autoplay", autoplay);
            playbackWanted = savedInstanceState.getBoolean("player_wanted", true);
        }

        buildUi();

        // 续播：优先用 Intent 指定的集，否则用记忆里的集
        int start = getIntent().getIntExtra(EXTRA_START_INDEX, -1);
        if (start < 0) {
            start = indexOfEpisode(progress.episode(seriesId));
        }
        index = Math.max(0, Math.min(start, episodes.size() - 1));
        // 后台读一次云端最新集号，用于进度合并保护（不阻塞播放）
        library.uploadPending();
        // 后台查一次收藏状态（未登录会立刻返回，不发请求）
        loadFavoriteState();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Surface 包一层裁剪容器：「填充」模式下 Surface 会比屏幕大，
        // 不裁剪会溢出到别的界面之上。clipChildren 让超出部分被切掉，
        // 这正是「填充」想要的视觉效果（放大后裁边）。
        FrameLayout videoBox = new FrameLayout(this);
        videoBox.setClipChildren(true);
        videoBox.setClipToPadding(true);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        videoBox.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(videoBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 控制层放在**底部**。
        //
        // 为什么在下面：竖屏短剧的画面居中、上下都有黑边，信息放顶部会
        // 压在画面上方的大片黑边里，离画面太远看着散。放底部是播放器
        // 的通行做法（进度条本来就在底部，标题紧贴它更自然）。
        //
        // 外层用 FrameLayout 让内容贴底；内层 LinearLayout 排内容。
        FrameLayout bottomBar = new FrameLayout(this);
        // 不要给控制栏加渐变背景。
        //
        // 早先用黑色渐变「保证文字可读」，但它会永久压暗画面底部 ——
        // 控制栏淡出后那层渐变还在（alpha 动画作用在 overlay 上，
        // bottomBar 的背景不受影响），观感很差。
        // 现在只在控制栏实际显示时给 overlay 一个半透明底，
        // 隐藏后画面上不留任何遮挡。
        bottomBar.setBackgroundColor(Color.TRANSPARENT);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        // 半透明底只挂在 overlay 上，跟着 alpha 动画一起淡出。
        // 这样文字在亮画面上可读，而控制栏隐藏后画面完全不被遮挡。
        GradientDrawable ovBg = new GradientDrawable();
        ovBg.setColor(0xD00D1523);
        overlay.setBackground(ovBg);
        // 安全边距：电视 overscan 会裁边，信息必须留在 5%（约 48dp）以内。
        overlay.setPadding(dp(48), dp(20), dp(48), dp(28));
        FrameLayout.LayoutParams ovlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ovlp.gravity = Gravity.BOTTOM;
        bottomBar.addView(overlay, ovlp);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(22);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overlay.addView(titleView);

        // 状态 + 时间 + 清晰度 放同一行，横向排列更紧凑
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setPadding(0, dp(8), 0, dp(8));

        status = new TextView(this);
        status.setTextColor(0xFFCCCCCC);
        status.setTextSize(14);
        infoRow.addView(status);

        timeView = new TextView(this);
        timeView.setTextColor(0xFFAAAAAA);
        timeView.setTextSize(14);
        timeView.setPadding(dp(16), 0, 0, 0);
        infoRow.addView(timeView);

        // 清晰度标签：当前档位 + 点击切换入口。
        // 之前只能用遥控器按菜单键切，屏幕上没提示，用户不知道有这功能。
        //
        // 注意：这个 View 不能抢焦点。播放页的所有按键都由 Activity
        // 的 onKeyDown 统一处理，如果它变成 focusable，按确定会先落到
        // 它身上触发切换清晰度（实测踩过：按确定想暂停，结果换了档）。
        qualityView = new TextView(this);
        qualityView.setTextColor(Color.WHITE);
        qualityView.setTextSize(13);
        qualityView.setPadding(dp(10), dp(4), dp(10), dp(4));
        qualityView.setFocusable(false);
        qualityView.setFocusableInTouchMode(false);
        GradientDrawable qbg = new GradientDrawable();
        qbg.setCornerRadius(dp(10));
        qbg.setColor(0xDD263750);
        qualityView.setBackground(qbg);
        // 只响应触摸（手机/盒子用鼠标），遥控器走菜单键
        qualityView.setOnClickListener(v -> showPlaybackMenu());
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.setMargins(dp(16), 0, 0, 0);
        infoRow.addView(qualityView, qlp);

        TextView episodeView = overlayAction("选集", this::showEpisodePicker);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(dp(10), 0, 0, 0);
        infoRow.addView(episodeView, elp);
        seasonsView = overlayAction("全部季", this::showSeasonPicker);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(10), 0, 0, 0);
        infoRow.addView(seasonsView, slp);

        // 收藏按钮：遥控器按 F（菜单键已被清晰度占用），或鼠标点击。
        // 和 qualityView 一样不能 focusable —— 播放页所有按键由 onKeyDown
        // 统一处理，可聚焦的子 View 会先吃掉确定键。
        favView = new TextView(this);
        favView.setTextColor(Color.WHITE);
        favView.setTextSize(13);
        favView.setPadding(dp(10), dp(4), dp(10), dp(4));
        favView.setFocusable(false);
        favView.setFocusableInTouchMode(false);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setCornerRadius(dp(10));
        fbg.setColor(0xDD263750);
        favView.setBackground(fbg);
        favView.setOnClickListener(v -> toggleFavorite());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(dp(10), 0, 0, 0);
        infoRow.addView(favView, flp);
        updateFavoriteLabel();

        overlay.addView(infoRow);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(TvUi.ACCENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 4);
        overlay.addView(bar, blp);
        TextView hint=TvUi.text(this,"确定  暂停/继续    左右  快进退    上  播放设置    下  选集",13,TvUi.DIM);
        hint.setPadding(0,dp(12),0,0);overlay.addView(hint);

        // bottomBar 自己必须贴底。
        //
        // 早先只给内部的 overlay 设了 gravity=BOTTOM，但 bottomBar 本身
        // 是 WRAP_CONTENT 且没设 gravity，FrameLayout 默认把它放顶部 ——
        // 结果整块控制栏跑到屏幕上方，还压了一条黑渐变在画面上。
        // 外层 LayoutParams 的 gravity 才是决定位置的那个。
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, barLp);

        setContentView(root);
    }

    private TextView overlayAction(String label, Runnable action) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        view.setFocusable(false);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(0xDD263750);
        view.setBackground(background);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    // ------------------------------------------------------------ 播放控制

    private void play(int i) {
        if (!playbackUiActive() || i < 0 || i >= episodes.size()) {
            return;
        }
        // 切集前先存当前进度，否则切走后进度丢失
        flushProgress();
        cancelEpisodePreparation();
        cancelSeasonWork();
        nextError = "";
        retrySeason = null;
        index = i;
        Ep ep = episodes.get(i);
        final int request=++loadGeneration;
        final PreparationCancellation cancellation = new PreparationCancellation();
        episodePreparation = cancellation;
        releasePlayer();
        setStatus("第 " + ep.num + " 集 加载中…");
        titleView.setText(seriesTitle + "  第 " + ep.num + " 集");
        bar.setProgress(0);
        timeView.setText("");

        final int cap = CAPS[capIndex];
        new Thread(() -> {
            try {
                JSONObject j = api.videoModel(ep.vid);
                JSONObject vm = Api.findVideoModel(j, ep.vid);
                if (vm == null) {
                    postLoad(request, () -> fail("取流失败"));
                    return;
                }
                TrackPicker.Track t = TrackPicker.pick(vm, cap);
                Log.i(TAG, "第 " + ep.num + " 集 选中 " + t);
                if (request != loadGeneration) return;
                postLoad(request, () -> {
                    actualDefinition = t.definition;
                    updateQualityLabel();
                });
                // 缓存键要带清晰度，否则换档会命中旧档的文件
                String bucket = t.definition;
                synchronized (preparationLock) {
                if (request != loadGeneration || cancellation.cancelled()) return;
                preparer.prepare(ep.vid, t, cancellation, new EpisodePreparer.Listener() {
                            @Override
                            public void onProgress(String stage, int pct) {
                                postLoad(request, () -> setStatus("第 " + ep.num + " 集 "
                                        + stage + (pct > 0 && pct < 100 ? " " + pct + "%" : "")));
                            }

                            @Override
                            public void onDone(File plain) {
                                postLoad(request, () -> startPlayback(plain, ep.num));
                            }

                            @Override
                            public void onError(String message) {
                                postLoad(request, () -> fail(message));
                            }
                        }, () -> TrackPicker.pick(Api.findVideoModel(api.videoModel(ep.vid),ep.vid),cap));
                }
            } catch (Throwable e) {
                Log.e(TAG, "取流异常", e);
                String message = e.getMessage();
                postLoad(request, () -> fail(message == null || message.isEmpty() ? "取流失败，请稍后重试" : message));
            }
        }, "episode-load").start();
        checkSeasonTail();
    }

    /** Check lifetime and request generation together on the same thread that owns the views. */
    private void postLoad(int request, Runnable action) {
        ui.post(() -> {
            if (request == loadGeneration && playbackUiActive() && foreground && samePlaybackAccount()) action.run();
        });
    }

    private boolean playbackUiActive() {
        return status != null && !isFinishing() && !isDestroyed();
    }

    private boolean currentPlayer(MediaPlayer candidate, int request) {
        return candidate == player && request == loadGeneration && playbackUiActive()
                && foreground && samePlaybackAccount();
    }

    private void startPlayback(File f, int epNum) {
        if (!playbackUiActive() || !foreground || !samePlaybackAccount()) return;
        if (!surfaceReady) {
            pendingFile = f;
            return;
        }
        releasePlayer();
        final int request = loadGeneration;
        try {
            player = new MediaPlayer();
            player.setDisplay(surface.getHolder());
            player.setDataSource(f.getAbsolutePath());
            player.setOnVideoSizeChangedListener((mp, w, h) -> {
                if (!currentPlayer(mp, request)) return;
                // 按视频真实宽高比调整 Surface，不做拉伸。
                //
                // 红果的短剧原始素材是竖屏 9:16，但上游把它装进 16:9 容器
                // 再返回（实测 1920x1080 的片子里画面是竖的，两侧黑边）。
                // 这里用 MediaPlayer 报的真实宽高来定 Surface 尺寸，
                // 竖屏素材就能铺满屏幕高度而不是被压成中间一条。
                applyAspect(w, h);
            });
            player.setOnPreparedListener(mp -> {
                if (!currentPlayer(mp, request)) return;
                prepared = true;
                // 续播：同一集重进时回到上次位置
                long dur = mp.getDuration();
                long at = resumeTarget(epNum, dur);
                if (at > 0) {
                    mp.seekTo((int) at);
                }
                applySpeed();
                setStatus("播放中" + (at > 0 ? "（续播）" : ""));
                userPaused = !playbackWanted;
                seeking = false;
                armAfterSeekAdvance = false;
                if (!userPaused) {
                    seasonPlayback.armCompletion();
                    if(foreground)mp.start();
                } else setStatus("已暂停");
                startTicker();
                scheduleHideOverlay();
                checkSeasonTail();
            });
            player.setOnCompletionListener(mp -> {
                if (!currentPlayer(mp, request) || !seasonPlayback.completionArmed()
                        || userPaused || seeking || !completedNearEnd(mp)) return;
                flushProgress();
                if (!foreground || switchingSeason) return;
                if (autoplay && index + 1 < episodes.size()) {
                    play(index + 1);
                } else if (autoplay) {
                    seasonPlayback.completed();
                    continueAtSeasonEnd();
                } else {
                    setStatus(autoplay?"已是最后一集":"本集播放完毕");
                    showOverlay();
                }
            });
            player.setOnSeekCompleteListener(mp -> {
                if (!currentPlayer(mp, request) || !seeking) return;
                seeking = false;
                seekSettledPosition = mp.getCurrentPosition();
                armAfterSeekAdvance = !userPaused;
                if (armAfterSeekAdvance) ui.postDelayed(armSeekCompletion, 100);
                checkSeasonTail();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (!currentPlayer(mp, request)) return true;
                Log.e(TAG, "MediaPlayer 错误 what=" + what + " extra=" + extra);
                fail("播放失败（" + what + "/" + extra + "）");
                return true;
            });
            player.prepareAsync();
        } catch (Throwable e) {
            Log.e(TAG, "起播异常", e);
            fail("无法播放：" + e.getMessage());
        }
    }

    private boolean completedNearEnd(MediaPlayer decoder) {
        try {
            int duration = decoder.getDuration();
            return duration > 0 && decoder.getCurrentPosition() >= Math.max(0, duration - 750);
        } catch (IllegalStateException error) { return false; }
    }

    /**
     * 画面比例模式。
     *
     * 为什么需要手动切换：
     *   红果上游把竖屏素材（9:16）装进 16:9 容器再返回，MediaPlayer 上报的
     *   宽高是 1920x1080，但画面内容只占中间一条竖条。这种「容器比例 ≠
     *   内容比例」的情况，靠 API 拿不到真实内容比例，只能让用户选。
     *
     * 三种模式覆盖所有场景：
     *   适应（FIT）  —— 保持比例完整显示，可能有黑边。默认。
     *   填充（FILL） —— 铺满屏幕，超出部分裁掉。竖屏素材选这个才铺满。
     *   拉伸（STRETCH）—— 变形铺满。画面有人脸时不建议，但有些用户偏好。
     */
    private static final int MODE_FIT = 0;
    private static final int MODE_FILL = 1;
    private static final int MODE_STRETCH = 2;
    private static final String[] MODE_NAMES = {"适应", "填充", "拉伸"};

    private int aspectMode = MODE_FIT;
    private int lastVideoW;
    private int lastVideoH;

    /**
     * 按视频宽高比 + 当前模式设置 Surface 尺寸。
     *
     * 用 CENTER 定位而不是拉伸：拉伸会让人脸变形，竖屏短剧里特别明显。
     */
    private void applyAspect(int videoW, int videoH) {
        if (videoW <= 0 || videoH <= 0) {
            return;
        }
        lastVideoW = videoW;
        lastVideoH = videoH;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float videoRatio = (float) videoW / videoH;
        float screenRatio = (float) screenW / screenH;

        int w;
        int h;
        switch (aspectMode) {
            case MODE_FILL:
                // 铺满：取能覆盖屏幕的最大尺寸，超出部分由父容器裁掉
                if (videoRatio > screenRatio) {
                    h = screenH;
                    w = (int) (screenH * videoRatio);
                } else {
                    w = screenW;
                    h = (int) (screenW / videoRatio);
                }
                break;
            case MODE_STRETCH:
                w = screenW;
                h = screenH;
                break;
            case MODE_FIT:
            default:
                // 适应：完整显示，不裁不拉
                if (videoRatio > screenRatio) {
                    w = screenW;
                    h = (int) (screenW / videoRatio);
                } else {
                    h = screenH;
                    w = (int) (screenH * videoRatio);
                }
                break;
        }
        Log.i(TAG, "画面 " + videoW + "x" + videoH + " 模式=" + MODE_NAMES[aspectMode]
                + " -> surface " + w + "x" + h);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    /** 切换画面比例模式。选择会记住，下次播放自动沿用。 */
    private void cycleAspect() {
        aspectMode = (aspectMode + 1) % MODE_NAMES.length;
        progress.saveAspectMode(aspectMode);
        if (lastVideoW > 0) {
            applyAspect(lastVideoW, lastVideoH);
        }
        setStatus("画面：" + MODE_NAMES[aspectMode]);
    }

    /** 计算续播起点。duration 未知时按 0 处理。 */
    private long resumeTarget(int epNum, long durationMs) {
        if(requestedStartPosition>=0){long at=requestedStartPosition;requestedStartPosition=-1;return durationMs>0?Math.min(at,Math.max(0,durationMs-300)):at;}
        return progress.resumeAt(seriesId, epNum, durationMs);
    }

    /** 进度条刷新。 */
    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared) {
                try {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    if (armAfterSeekAdvance && !userPaused && player.isPlaying()
                            && pos > seekSettledPosition + 100) {
                        armAfterSeekAdvance = false;
                        seasonPlayback.armCompletion();
                    }
                    if (dur > 0) {
                        bar.setProgress((int) (pos * 1000L / dur));
                        timeView.setText(String.format(Locale.US, "%s / %s  %s×",
                                fmt(pos), fmt(dur), trimSpeed()));
                    }
                } catch (Throwable ignored) {
                }
                ui.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable armSeekCompletion = new Runnable() {
        @Override public void run() {
            if (!armAfterSeekAdvance || !foreground || userPaused || seeking || !prepared
                    || player == null || !samePlaybackAccount()) return;
            try {
                if (!player.isPlaying()) return;
                if (player.getCurrentPosition() > seekSettledPosition + 100) {
                    armAfterSeekAdvance = false;
                    seasonPlayback.armCompletion();
                } else ui.postDelayed(this, 100);
            } catch (IllegalStateException ignored) { }
        }
    };

    private static String fmt(int ms) {
        int s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private String trimSpeed() {
        float f = SPEEDS[speedIndex];
        return f == Math.round(f) ? String.valueOf((int) f)
                : String.valueOf(f).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** 应用当前倍速。低于 Android 6 的 setPlaybackParams 不可用，忽略即可。 */
    private void applySpeed() {
        if (player == null || !prepared) {
            return;
        }
        try {
            boolean wasPlaying=player.isPlaying();
            android.media.PlaybackParams p = player.getPlaybackParams();
            p.setSpeed(SPEEDS[speedIndex]);
            player.setPlaybackParams(p);
            if(!wasPlaying)player.pause();
        } catch (Throwable e) {
            Log.w(TAG, "倍速设置失败", e);
        }
    }

    private void saveProgress() {
        if(player==null||!prepared||library==null||!samePlaybackAccount())return;
        try {
            cn.hongguo.tv.cache.WatchRecord r=new cn.hongguo.tv.cache.WatchRecord();
            r.seriesId=seriesId;r.title=seriesTitle==null?"":seriesTitle;r.cover=seriesCover;
            r.episode=episodes.get(index).num;r.vid=episodes.get(index).vid;r.total=episodes.size();
            r.position=Math.max(0,player.getCurrentPosition());r.duration=Math.max(0,player.getDuration());r.watchedAt=System.currentTimeMillis();
            library.save(r,forceFlush);
        }catch(RuntimeException e){Log.w(TAG,"本机观看位置保存失败",e);}
    }

    private void flushProgress(){forceFlush=true;try{saveProgress();}finally{forceFlush=false;}}

    private boolean samePlaybackAccount() {
        return library != null && playbackOwner != null && playbackOwner.equals(library.owner())
                && playbackIdentity.equals(cn.hongguo.tv.account.Account.load(this).sessionIdentity());
    }

    private boolean currentSeasonWork(SeasonPlaybackCoordinator.Ticket ticket) {
        return playbackUiActive() && samePlaybackAccount()
                && seasonPlayback.accepts(ticket, seriesId, playbackIdentity, foreground);
    }

    /** Do not retain an automatic jump across pause, seek, episode choice or another season choice. */
    private void cancelSeasonWork() {
        seasonPlayback.cancel();
        if (seasonPreparation != null) { seasonPreparation.cancel(); seasonPreparation = null; }
        seeking = false;
        armAfterSeekAdvance = false;
        ui.removeCallbacks(armSeekCompletion);
        overviewBusy = false;
        showSeasonsWhenReady = false;
        nextPreparing = false;
        switchingSeason = false;
        updateSeasonLabel();
    }

    private void cancelEpisodePreparation() {
        if (episodePreparation != null) { episodePreparation.cancel(); episodePreparation = null; }
    }

    private void updateSeasonLabel() {
        if (seasonsView != null) seasonsView.setText(overviewBusy ? "全部季 · 查询中"
                : !overviewError.isEmpty() ? "全部季 · 重试" : "全部季");
    }

    private void checkSeasonTail() {
        if (foreground && !episodes.isEmpty() && index >= Math.max(0, episodes.size() - 3))
            loadSeasonOverview(false, false);
    }

    private void loadSeasonOverview(boolean refresh, boolean showPicker) {
        if (!foreground || !playbackUiActive() || !samePlaybackAccount()) return;
        if (!refresh && seasonOverview != null && seriesId.equals(seasonOverview.seriesId)) {
            if (showPicker) displaySeasonPicker();
            prepareNextSeason();
            return;
        }
        if (overviewBusy) { showSeasonsWhenReady |= showPicker; return; }
        overviewBusy = true;
        showSeasonsWhenReady = showPicker;
        overviewError = "";
        updateSeasonLabel();
        final SeasonPlaybackCoordinator.Ticket ticket = seasonPlayback.ticket();
        new Thread(() -> {
            try {
                SeasonRepository.Overview result = seasons.overview(ticket.seriesId, refresh);
                ui.post(() -> {
                    if (!currentSeasonWork(ticket)) return;
                    overviewBusy = false;
                    seasonOverview = result;
                    updateSeasonLabel();
                    if (showSeasonsWhenReady) { showSeasonsWhenReady = false; displaySeasonPicker(); }
                    prepareNextSeason();
                    if (seasonPlayback.ended()) continueAtSeasonEnd();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (!currentSeasonWork(ticket)) return;
                    overviewBusy = false;
                    overviewError = "暂时读取不到全部季，请重试";
                    updateSeasonLabel();
                    if (showSeasonsWhenReady || seasonPlayback.ended()) {
                        showSeasonsWhenReady = false;
                        setStatus(overviewError);
                        showOverlay();
                    }
                });
            }
        }, "season-overview").start();
    }

    private void showSeasonPicker() {
        if (!foreground || !playbackUiActive()) return;
        showOverlay();
        if (seasonPicker != null && seasonPicker.isShowing()) return;
        loadSeasonOverview(!overviewError.isEmpty(), true);
    }

    private void displaySeasonPicker() {
        if (seasonOverview == null || seasonOverview.items.isEmpty()
                || seasonPicker != null && seasonPicker.isShowing()) return;
        List<SeasonRepository.Season> items = seasonOverview.items;
        String[] labels = new String[items.size()];
        int selected = -1;
        for (int i = 0; i < items.size(); i++) {
            SeasonRepository.Season season = items.get(i);
            boolean current = seriesId.equals(season.series.seriesId);
            if (current) selected = i;
            labels[i] = "第 " + season.number + " 季 · " + season.series.title + " · "
                    + season.series.episodeCount + " 集" + (current ? " · 正在播放" : "");
        }
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
                .setTitle("全部季")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    selectSeason(items.get(which));
                }).setNegativeButton("返回播放", null)
                .setNeutralButton("刷新", (dialog, which) -> loadSeasonOverview(true, true));
        if (!nextError.isEmpty() && retrySeason != null)
            builder.setPositiveButton("重试切季", (dialog, which) -> selectSeason(retrySeason));
        seasonPicker = builder.show();
    }

    private void prepareNextSeason() {
        if (!foreground || !prepared || switchingSeason || episodes.isEmpty() || index != episodes.size() - 1
                || seasonOverview == null || seasonOverview.next == null || nextPreparing
                || !nextError.isEmpty()) return;
        SeasonRepository.Season next = seasonOverview.next;
        if (nextTarget != null && nextTarget.cap == CAPS[capIndex]
                && next.series.seriesId.equals(nextTarget.season.series.seriesId)) return;
        nextPreparing = true;
        final SeasonPlaybackCoordinator.Ticket ticket = seasonPlayback.ticket();
        prepareSeasonTarget(next, ticket, false);
    }

    private void selectSeason(SeasonRepository.Season season) {
        if (!foreground || !samePlaybackAccount()) return;
        if (seriesId.equals(season.series.seriesId)) {
            requestedStartPosition = 0;
            play(indexOfEpisode(season.firstEpisode));
            return;
        }
        if (!prepared) {
            cancelEpisodePreparation();
            ++loadGeneration;
            releasePlayer();
        }
        cancelSeasonWork();
        switchingSeason = true;
        nextError = "";
        retrySeason = null;
        setStatus("正在准备第 " + season.number + " 季…");
        showOverlay();
        SeasonPlaybackCoordinator.Ticket ticket = seasonPlayback.ticket();
        if (nextTarget != null && nextTarget.cap == CAPS[capIndex]
                && season.series.seriesId.equals(nextTarget.season.series.seriesId)) {
            switchToSeason(nextTarget, ticket);
        } else prepareSeasonTarget(season, ticket, true);
    }

    /** Resolve and decrypt the first episode using the same cache path as normal episode playback. */
    private void prepareSeasonTarget(SeasonRepository.Season season,
                                     SeasonPlaybackCoordinator.Ticket ticket, boolean manual) {
        final int cap = CAPS[capIndex];
        final PreparationCancellation cancellation = new PreparationCancellation();
        seasonPreparation = cancellation;
        new Thread(() -> {
            try {
                JSONArray json = Api.parseEpisodes(api.episodes(season.series.seriesId), season.series.seriesId);
                List<Ep> targetEpisodes = readEpisodes(json.toString());
                if (targetEpisodes.isEmpty()) throw new java.io.IOException("下一季暂无可播放集数");
                if (!seasonPlayback.isCurrent(ticket)) return;
                int first = 0;
                for (int i = 0; i < targetEpisodes.size(); i++)
                    if (targetEpisodes.get(i).num == season.firstEpisode) { first = i; break; }
                Ep episode = targetEpisodes.get(first);
                JSONObject model = Api.findVideoModel(api.videoModel(episode.vid), episode.vid);
                if (model == null) throw new java.io.IOException("下一季取流失败");
                TrackPicker.Track track = TrackPicker.pick(model, cap);
                SeasonTarget target = new SeasonTarget();
                target.season = season; target.episodes = targetEpisodes; target.definition = track.definition;
                target.cap = cap; target.firstIndex = first;
                synchronized (preparationLock) {
                    if (!seasonPlayback.isCurrent(ticket) || cancellation.cancelled()) return;
                    // The UI validates again before adopting this cache file. No player is created here.
                    preparer.prepare(episode.vid, track, cancellation, new EpisodePreparer.Listener() {
                        @Override public void onProgress(String stage, int percent) {
                            if (manual) ui.post(() -> {
                                if (currentSeasonWork(ticket)) setStatus("第 " + season.number + " 季 · " + stage);
                            });
                        }
                        @Override public void onDone(File file) {
                            target.file = file;
                            ui.post(() -> {
                                if (!currentSeasonWork(ticket)) return;
                                nextPreparing = false;
                                nextError = "";
                                if (manual) switchToSeason(target, ticket);
                                else {
                                    nextTarget = target;
                                    if (seasonPlayback.ended()) continueAtSeasonEnd();
                                }
                            });
                        }
                        @Override public void onError(String message) { seasonTargetFailed(ticket, manual, season); }
                    }, () -> TrackPicker.pick(Api.findVideoModel(api.videoModel(episode.vid),episode.vid),cap));
                }
            } catch (Exception error) { seasonTargetFailed(ticket, manual, season); }
        }, manual ? "season-switch" : "season-prefetch").start();
    }

    private void seasonTargetFailed(SeasonPlaybackCoordinator.Ticket ticket, boolean manual,
                                    SeasonRepository.Season season) {
        ui.post(() -> {
            if (!currentSeasonWork(ticket)) return;
            nextPreparing = false;
            switchingSeason = false;
            nextError = "下一季暂时加载失败，可在全部季中重试";
            retrySeason = season;
            if (manual || seasonPlayback.ended()) {
                setStatus(nextError);
                showOverlay();
            }
        });
    }

    private void continueAtSeasonEnd() {
        if (!seasonPlayback.canContinue(seasonPlayback.ticket(), seriesId, playbackIdentity,
                foreground && playbackUiActive() && samePlaybackAccount(), autoplay) || switchingSeason) return;
        if (seasonOverview == null || !seriesId.equals(seasonOverview.seriesId)) {
            setStatus(overviewError.isEmpty() ? "本季播放完毕 · 正在查找下一季…" : overviewError);
            if (overviewError.isEmpty()) loadSeasonOverview(false, false);
        } else if (seasonOverview.next == null) {
            seasonPlayback.consumed();
            setStatus("全部季已播放完毕");
            showOverlay();
        } else if (!nextError.isEmpty()) {
            setStatus(nextError);
            showOverlay();
        } else if (nextTarget != null && nextTarget.cap == CAPS[capIndex]
                && seasonOverview.next.series.seriesId.equals(nextTarget.season.series.seriesId)) {
            switchToSeason(nextTarget, seasonPlayback.ticket());
        } else {
            setStatus("本季播放完毕 · 正在准备下一季…");
            prepareNextSeason();
        }
    }

    private void switchToSeason(SeasonTarget target, SeasonPlaybackCoordinator.Ticket ticket) {
        if (!currentSeasonWork(ticket) || target.file == null || !target.file.isFile()) return;
        // Save using the old season fields before replacing any progress or favorite ownership.
        flushProgress();
        ++loadGeneration;
        ++favoriteGeneration;
        if (episodePicker != null) { episodePicker.dismiss(); episodePicker = null; }
        if (seasonPicker != null) { seasonPicker.dismiss(); seasonPicker = null; }
        if (playbackMenu != null) { playbackMenu.dismiss(); playbackMenu = null; }
        releasePlayer();
        seriesId = target.season.series.seriesId;
        seriesTitle = target.season.series.title;
        seriesCover = target.season.series.cover == null ? "" : target.season.series.cover;
        episodes.clear(); episodes.addAll(target.episodes);
        index = target.firstIndex;
        requestedStartPosition = 0;
        updatePlaybackIntent(0);
        actualDefinition = target.definition;
        seasonPlayback.enter(seriesId, playbackIdentity);
        seasonOverview = null; overviewError = ""; nextError = ""; nextTarget = null;
        retrySeason = null;
        overviewBusy = false; nextPreparing = false; switchingSeason = false;
        favorited = false; favoriteKnown = false; favoriteBusy = false;
        titleView.setText(seriesTitle + "  第 " + episodes.get(index).num + " 集");
        timeView.setText(""); bar.setProgress(0);
        updateQualityLabel(); updateFavoriteLabel(); updateSeasonLabel();
        setStatus("第 " + target.season.number + " 季准备就绪");
        startPlayback(target.file, episodes.get(index).num);
        loadFavoriteState();
        checkSeasonTail();
    }

    // ------------------------------------------------------------ Control visibility

    private static final long OVERLAY_HIDE_MS = 3000;

    private final Runnable hideOverlay = () -> {
        if (overlay != null) {
            overlay.animate().alpha(0f).setDuration(250).start();
        }
    };

    /** 播放开始后隐藏叠加层；按任意键重新显示。 */
    private void scheduleHideOverlay() {
        ui.removeCallbacks(hideOverlay);
        // 暂停时不自动隐藏：用户暂停通常就是想看进度/切清晰度，
        // 3 秒后把界面藏起来反而碍事。
        if (player != null && prepared) {
            try {
                if (!player.isPlaying()) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        ui.postDelayed(hideOverlay, OVERLAY_HIDE_MS);
    }

    /** 显示叠加层并重置隐藏计时。 */
    private void showOverlay() {
        if (overlay != null) {
            overlay.animate().alpha(1f).setDuration(150).start();
        }
        scheduleHideOverlay();
    }

    private void fail(String msg) {
        setStatus("✗ " + msg);
    }

    private void setStatus(String s) {
        if (!playbackUiActive()) return;
        status.setText(s);
        Log.i(TAG, s);
    }

    private void releasePlayer() {
        prepared = false;
        pendingFile = null;
        ui.removeCallbacks(ticker);
        MediaPlayer previous = player;
        player = null;
        if (previous != null) {
            previous.setOnPreparedListener(null);
            previous.setOnCompletionListener(null);
            previous.setOnErrorListener(null);
            previous.setOnVideoSizeChangedListener(null);
            previous.setOnSeekCompleteListener(null);
            previous.release();
        }
    }

    // ------------------------------------------------------------ 清晰度 / 倍速

    /** 切下一档清晰度并从当前位置续播。 */
    private void cycleQuality() {
        capIndex = (capIndex + 1) % CAPS.length;
        getPreferences(0).edit().putInt("quality",capIndex).apply();
        long at = 0;
        try {
            if (player != null && prepared) {
                at = player.getCurrentPosition();
            }
        } catch (Throwable ignored) {
        }
        setStatus("切换到 " + CAP_NAMES[capIndex] + "…");
        updateQualityLabel();
        // 换档后缓存键变了，会重新下载解密；位置靠进度记忆带过去
        requestedStartPosition=at;
        play(index);
    }

    /** 刷新清晰度标签。actualDefinition 是实际选中的档位（可能因设备能力降级）。 */
    private void updateQualityLabel() {
        if (qualityView == null) {
            return;
        }
        String text = actualDefinition != null && !actualDefinition.isEmpty()
                ? actualDefinition : CAP_NAMES[capIndex];
        qualityView.setText("清晰度 " + text);
    }

    /** 切下一档倍速。 */
    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        getPreferences(0).edit().putInt("speed",speedIndex).apply();
        applySpeed();
        setStatus("倍速 " + trimSpeed() + "×");
    }

    // ------------------------------------------------------------ 收藏

    /**
     * 切换收藏状态。
     *
     * 未登录时直接提示去账号页登录 —— 收藏是云端行为，没有账号就没有
     * 可写的地方，静默失败会让用户以为收藏成功了。
     */
    private void toggleFavorite() {
        showOverlay();
        cn.hongguo.tv.account.Account a = cn.hongguo.tv.account.Account.load(this);
        if (!a.loggedIn()) {
            setStatus("登录后才能收藏");
            return;
        }
        if(favoriteBusy)return;
        if(!favoriteKnown){loadFavoriteState();setStatus("正在确认追剧状态…");return;}
        favoriteBusy=true;
        updateFavoriteLabel();
        final boolean target = !favorited;
        setStatus(target ? "正在加入收藏…" : "正在取消收藏…");
        Api.Series item=new Api.Series();item.seriesId=seriesId;item.title=seriesTitle;item.cover=seriesCover;item.episodeCount=episodes.size();
        final int request = ++favoriteGeneration;
        final String sid = seriesId;
        library.favorite(item,target,result->postFavorite(request,sid,()->{favoriteBusy=false;favoriteKnown=result.known;if(result.known)favorited=result.enabled;updateFavoriteLabel();setStatus(result.ok()?(result.enabled?"已加入追剧":"已取消追剧"):result.error);}));
    }

    /** 读一次云端书架，确定当前剧是否已收藏。失败保持「收藏」文案（不谎报已收藏）。 */
    private void loadFavoriteState() {
        cn.hongguo.tv.account.Account a = cn.hongguo.tv.account.Account.load(this);
        if (!a.loggedIn()) {
            favoriteKnown = true;
            favorited = false;
            updateFavoriteLabel();
            return;
        }
        if(favoriteBusy)return;
        favoriteBusy=true;updateFavoriteLabel();
        Api.Series item=new Api.Series();item.seriesId=seriesId;
        final int request = ++favoriteGeneration;
        final String sid = seriesId;
        library.loadFavorite(item,result->postFavorite(request,sid,()->{favoriteBusy=false;favoriteKnown=result.known;if(result.known)favorited=result.enabled;updateFavoriteLabel();}));
    }

    private void postFavorite(int request, String sid, Runnable action) {
        ui.post(() -> {
            if (request == favoriteGeneration && sid.equals(seriesId) && foreground
                    && playbackUiActive() && samePlaybackAccount()) action.run();
        });
    }

    private void updateFavoriteLabel() {
        if (favView == null || isFinishing() || isDestroyed()) {
            return;
        }
        favView.setText(favoriteBusy?"确认中…":!favoriteKnown?"重试追剧状态":favorited?"已追剧":"追剧");
        favView.setEnabled(!favoriteBusy);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(4));
        // 已收藏用品牌红，未收藏用中性灰 —— 一眼能看出状态
        g.setColor(favorited ? 0xCC735B35 : 0xDD263750);
        favView.setBackground(g);
    }

    // ------------------------------------------------------------ 遥控器

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 任何按键都先唤出叠加层：用户操作时要能看见进度和状态
        showOverlay();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showPlaybackMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showEpisodePicker();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_M:
                showPlaybackMenu();
                return true;
            case KeyEvent.KEYCODE_S:
                cycleSpeed();
                return true;
            case KeyEvent.KEYCODE_F:
                toggleFavorite();
                return true;
            case KeyEvent.KEYCODE_A:
                // 画面比例：适应 / 填充 / 拉伸
                cycleAspect();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void showPlaybackMenu(){
        if (!playbackUiActive()) return;
        if(playbackMenu!=null&&playbackMenu.isShowing())return;
        String[] items={"选集 · 当前第 "+episodes.get(index).num+" 集","全部季","清晰度 · "+(actualDefinition.isEmpty()?CAP_NAMES[capIndex]:actualDefinition),"倍速 · "+trimSpeed()+"×","画面 · "+MODE_NAMES[aspectMode],autoplay?"关闭自动连播":"开启自动连播",favorited?"取消追剧":"加入追剧"};
        playbackMenu=new android.app.AlertDialog.Builder(this).setTitle("播放设置").setItems(items,(d,which)->{switch(which){case 0:showEpisodePicker();break;case 1:showSeasonPicker();break;case 2:cycleQuality();break;case 3:cycleSpeed();break;case 4:cycleAspect();break;case 5:toggleAutoplay();break;case 6:toggleFavorite();break;}}).setNegativeButton("返回播放",null).show();
    }

    private void toggleAutoplay() {
        autoplay = !autoplay;
        getPreferences(0).edit().putBoolean("autoplay", autoplay).apply();
        if (!autoplay) {
            cancelSeasonWork();
            // Disabling continuation must still allow the current episode's natural end UI.
            try { if (prepared && player != null && player.isPlaying() && !userPaused && !seeking)
                seasonPlayback.armCompletion(); } catch (IllegalStateException ignored) { }
        } else checkSeasonTail();
        setStatus(autoplay ? "自动连播已开启" : "自动连播已关闭");
    }
    private void showEpisodePicker(){
        if (!playbackUiActive() || episodePicker != null && episodePicker.isShowing()) return;
        String[] labels=new String[episodes.size()];for(int i=0;i<labels.length;i++)labels[i]="第 "+episodes.get(i).num+" 集";
        episodePicker=new android.app.AlertDialog.Builder(this).setTitle("选集").setSingleChoiceItems(labels,index,(d,which)->{d.dismiss();requestedStartPosition=0;play(which);}).setNegativeButton("返回播放",null).show();
    }

    private void togglePause() {
        if (player == null || !prepared) {
            return;
        }
        try {
            cancelSeasonWork();
            if (player.isPlaying()) {
                playbackWanted = false;
                userPaused = true;
                player.pause();
                setStatus("已暂停");
                flushProgress();
            } else {
                playbackWanted = true;
                userPaused = false;
                player.start();
                seasonPlayback.armCompletion();
                setStatus("播放中");
                checkSeasonTail();
            }
        } catch (Throwable e) {
            Log.w(TAG, "暂停切换失败", e);
        }
    }

    private void seekBy(int deltaMs) {
        if (player == null || !prepared) {
            return;
        }
        try {
            cancelSeasonWork();
            seeking = true;
            armAfterSeekAdvance = false;
            int pos = player.getCurrentPosition();
            int dur = player.getDuration();
            int target = Math.max(0, Math.min(dur, pos + deltaMs));
            player.seekTo(target);
            // 只更新时间那一行，不要覆盖 status。
            // 早先这里调 setStatus(时间)，会和 timeView 的时间叠在一起，
            // 出现「1:22 / 1:29  1:24 / 1:29」这种重复显示。
            timeView.setText(String.format(Locale.US, "%s / %s  %s×",
                    fmt(target), fmt(dur), trimSpeed()));
        } catch (Throwable e) {
            Log.w(TAG, "seek 失败", e);
        }
    }

    // ------------------------------------------------------------ Surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!playbackUiActive()) return;
        surfaceReady = true;
        if (pendingFile != null) {
            File f = pendingFile;
            pendingFile = null;
            startPlayback(f, episodes.get(index).num);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        cancelSeasonWork();
    }

    // ------------------------------------------------------------ 生命周期

    private String serializedEpisodes() {
        JSONArray items = new JSONArray();
        for (Ep episode : episodes) {
            JSONObject item = new JSONObject();
            try {
                item.put("vid", episode.vid); item.put("num", episode.num); item.put("title", episode.title);
                items.put(item);
            } catch (org.json.JSONException error) { throw new IllegalStateException("集列表保存失败"); }
        }
        return items.toString();
    }

    private long currentPlaybackPosition() {
        try { if (player != null && prepared) return Math.max(0, player.getCurrentPosition()); }
        catch (IllegalStateException ignored) { }
        if (requestedStartPosition >= 0) return requestedStartPosition;
        if (progress != null && !episodes.isEmpty() && progress.episode(seriesId) == episodes.get(index).num)
            return progress.position(seriesId);
        return 0;
    }

    private void updatePlaybackIntent(long position) {
        Intent current = new Intent(getIntent());
        current.putExtra(EXTRA_SERIES_ID, seriesId).putExtra(EXTRA_TITLE, seriesTitle)
                .putExtra(EXTRA_COVER, seriesCover).putExtra(EXTRA_EPISODES, serializedEpisodes())
                .putExtra(EXTRA_START_INDEX, index).putExtra(STATE_POSITION, Math.max(0, position))
                .putExtra(STATE_IDENTITY, playbackIdentity);
        setIntent(current);
    }

    private void restorePlaybackIntent(Bundle state) {
        Intent current = new Intent(getIntent());
        current.putExtra(EXTRA_SERIES_ID, state.getString(EXTRA_SERIES_ID))
                .putExtra(EXTRA_TITLE, state.getString(EXTRA_TITLE))
                .putExtra(EXTRA_COVER, state.getString(EXTRA_COVER))
                .putExtra(EXTRA_EPISODES, state.getString(EXTRA_EPISODES))
                .putExtra(EXTRA_START_INDEX, state.getInt(EXTRA_START_INDEX))
                .putExtra(STATE_POSITION, state.getLong(STATE_POSITION))
                .putExtra(STATE_IDENTITY, state.getString(STATE_IDENTITY));
        setIntent(current);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (episodes.isEmpty() || !samePlaybackAccount()) return;
        long position = currentPlaybackPosition();
        updatePlaybackIntent(position);
        state.putString(EXTRA_SERIES_ID, seriesId); state.putString(EXTRA_TITLE, seriesTitle);
        state.putString(EXTRA_COVER, seriesCover); state.putString(EXTRA_EPISODES, serializedEpisodes());
        state.putInt(EXTRA_START_INDEX, index); state.putLong(STATE_POSITION, position);
        state.putString(STATE_IDENTITY, playbackIdentity);
        state.putInt("player_quality", capIndex); state.putInt("player_speed", speedIndex);
        state.putInt("player_aspect", aspectMode); state.putBoolean("player_autoplay", autoplay);
        state.putBoolean("player_wanted", playbackWanted);
    }

    @Override
    protected void onPause() {
        foreground=false;
        cancelEpisodePreparation();
        cancelSeasonWork();
        userPaused = true;
        seeking = false;
        armAfterSeekAdvance = false;
        ++favoriteGeneration;
        if (favoriteBusy) favoriteKnown = false;
        favoriteBusy = false;
        if (!prepared) ++loadGeneration;
        super.onPause();
        flushProgress();
        ui.removeCallbacks(saveTick);
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideOverlay);
        if (player != null && prepared) {
            try {
                player.pause();
            } catch (Throwable ignored) {
            }
        } else if (player != null) {
            releasePlayer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground=true;
        if(library!=null&&!samePlaybackAccount()){finish();return;}
        ui.removeCallbacks(saveTick);
        ui.postDelayed(saveTick, SAVE_INTERVAL_MS);
        if (player != null && prepared) {
            startTicker();
        } else if (!episodes.isEmpty() && status != null) play(index);
        checkSeasonTail();
        if (!favoriteKnown) loadFavoriteState();
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        cancelEpisodePreparation();
        ++favoriteGeneration;
        cancelSeasonWork();
        foreground = false;
        flushProgress();
        ui.removeCallbacksAndMessages(null);
        if (overlay != null) overlay.animate().cancel();
        if (playbackMenu != null) playbackMenu.dismiss();
        if (episodePicker != null) episodePicker.dismiss();
        if (seasonPicker != null) seasonPicker.dismiss();
        releasePlayer();
        if (cache != null) new Thread(() -> { synchronized (preparationLock) { cache.trim(); } }, "cache-trim").start();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 入参

    private void parseEpisodes(String json) {
        episodes.addAll(readEpisodes(json));
    }

    private static List<Ep> readEpisodes(String json) {
        List<Ep> parsed = new ArrayList<>();
        if (json == null) {
            return parsed;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Ep e = new Ep();
                e.vid = o.optString("vid");
                e.num = o.optInt("num", i + 1);
                e.title = o.optString("title");
                if (!e.vid.isEmpty()) {
                    parsed.add(e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "集列表解析失败", e);
        }
        return parsed;
    }

    /** 集号 → 列表下标。找不到返回 0。 */
    private int indexOfEpisode(int num) {
        for (int i = 0; i < episodes.size(); i++) {
            if (episodes.get(i).num == num) {
                return i;
            }
        }
        return 0;
    }

    /** 供其它页面跳转用。 */
    public static Intent intent(android.content.Context ctx, String seriesId,
                                String title, String episodesJson) {
        return intent(ctx, seriesId, title, "", episodesJson);
    }

    /** 带封面的跳转：云端历史有封面，手机端列表才有图。 */
    public static Intent intent(android.content.Context ctx, String seriesId,
                                String title, String cover, String episodesJson) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(EXTRA_SERIES_ID, seriesId);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_COVER, cover == null ? "" : cover);
        i.putExtra(EXTRA_EPISODES, episodesJson);
        return i;
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
