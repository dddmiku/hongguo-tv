package cn.hongguo.tv.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.LibraryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Official rankings, with separate category and board controls for the remote. */
public class RankingsActivity extends TvActivity {
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final List<List<View>> focusRows = new ArrayList<>();
    private Api api;
    private LibraryRepository library;
    private LinearLayout categories, boards, results;
    private ScrollView resultsScroll;
    private TextView back, status;
    private String category = "series", board = "recommend";
    private int generation;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        cn.hongguo.tv.sign.RequestSigner.bind(this);
        api = new Api(this);
        library = LibraryRepository.get(this);
        if (saved != null) {
            category = saved.getString("rank-category", "series");
            board = saved.getString("rank-board", "recommend");
        }
        if (!"series".equals(category) && !"comic".equals(category)
                && !"short_play".equals(category) && !"ai_series".equals(category)) category = "series";
        if (!validBoard(category, board)) board = "recommend";
        buildUi();
        load();
    }

    private static boolean validBoard(String category, String board) {
        if ("series".equals(category)) return "recommend".equals(board) || "new".equals(board);
        return "recommend".equals(board) || "new".equals(board) || "hot".equals(board);
    }

    private void buildUi() {
        LinearLayout root = TvUi.column(this);
        root.setBackgroundColor(TvUi.BG);
        root.setPadding(dp(48), dp(24), dp(48), dp(20));
        LinearLayout header = TvUi.header(this, "排行榜");
        back = (TextView) header.getChildAt(0);
        back.setTag("rank-back");
        root.addView(header);
        categories = TvUi.row(this);
        categories.setPadding(0, dp(14), 0, dp(6));
        root.addView(categories);
        boards = TvUi.row(this);
        root.addView(boards);
        status = TvUi.text(this, "正在加载官方榜单…", 13, TvUi.DIM);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);
        resultsScroll = new ScrollView(this);
        resultsScroll.setFocusable(false);
        resultsScroll.setVerticalScrollBarEnabled(false);
        results = TvUi.column(this);
        results.setPadding(0, 0, 0, dp(12));
        resultsScroll.addView(results);
        root.addView(resultsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        renderControls();
        categories.findViewWithTag("rank-category:" + category).requestFocus();
    }

    private void renderControls() {
        View focused = getCurrentFocus();
        Object tag = focused == null ? null : focused.getTag();
        categories.removeAllViews();
        boards.removeAllViews();
        // Same category set as the desktop client: series, comic, short play, AI.
        String[][] types = {{"系列剧", "series"}, {"漫剧", "comic"},
                {"真人短剧", "short_play"}, {"AI 剧", "ai_series"}};
        for (String[] type : types) {
            TextView tab = TvUi.tab(this, type[0], category.equals(type[1]), () -> {
                if (category.equals(type[1])) return;
                category = type[1];
                if (!validBoard(category, board)) board = "recommend";
                renderControls();
                load();
            });
            tab.setTag("rank-category:" + type[1]);
            categories.addView(tab);
        }
        String[][] choices = "series".equals(category)
                ? new String[][]{{"推荐榜", "recommend"}, {"新季榜", "new"}}
                : new String[][]{{"推荐榜", "recommend"}, {"热播榜", "hot"}, {"新剧榜", "new"}};
        for (String[] choice : choices) {
            TextView tab = TvUi.tab(this, choice[0], board.equals(choice[1]), () -> {
                if (board.equals(choice[1])) return;
                board = choice[1];
                renderControls();
                load();
            });
            tab.setTag("rank-board:" + choice[1]);
            boards.addView(tab);
        }
        linkFocus();
        View restored = tag == null ? null : getWindow().getDecorView().findViewWithTag(tag);
        if (restored != null) restored.requestFocus();
        else if (focused != null) boards.findViewWithTag("rank-board:" + board).requestFocus();
    }

    private void load() {
        final int request = ++generation;
        final String selectedCategory = category, selectedBoard = board;
        boolean resultFocused = results.hasFocus();
        results.removeAllViews();
        if (resultFocused) boards.findViewWithTag("rank-board:" + board).requestFocus();
        resultsScroll.scrollTo(0, 0);
        status.setText(label() + " · 正在加载…");
        linkFocus();
        loader.execute(() -> {
            try {
                List<Api.Series> rows = requestRank(selectedCategory, selectedBoard);
                publish(request, () -> showRows(rows));
            } catch (Exception error) {
                publish(request, () -> showRetry("榜单暂时加载失败，请重试。"));
            }
        });
    }

    /** Debug fixtures replace transport while exercising the actual request-generation guard. */
    protected List<Api.Series> requestRank(String category, String board) throws Exception {
        return api.rank(category, board, 50);
    }

    private void publish(int request, Runnable action) {
        runOnUiThread(() -> {
            if (request != generation || isFinishing() || isDestroyed()) return;
            action.run();
        });
    }

    private String label() {
        String type = "series".equals(category) ? "系列剧"
                : "comic".equals(category) ? "漫剧"
                : "short_play".equals(category) ? "真人短剧" : "AI 剧";
        String selected = "new".equals(board) ? ("series".equals(category) ? "新季榜" : "新剧榜")
                : "hot".equals(board) ? "热播榜" : "推荐榜";
        return type + " · " + selected;
    }

    private void showRows(List<Api.Series> rows) {
        results.removeAllViews();
        if (rows.isEmpty()) {
            showRetry("这个榜单暂时没有内容，可稍后刷新。");
            return;
        }
        status.setText(label() + " · 官方排序 · " + rows.size() + " 部");
        for (Api.Series series : rows) results.addView(rankRow(series));
        linkFocus();
    }

    private void showRetry(String message) {
        results.removeAllViews();
        status.setText(label() + " · " + message);
        TextView retry = TvUi.button(this, "重新加载榜单", true, this::load);
        retry.setTag("rank-retry");
        results.addView(retry, new LinearLayout.LayoutParams(dp(180), dp(44)));
        linkFocus();
    }

    private View rankRow(Api.Series series) {
        LinearLayout row = TvUi.row(this);
        row.setId(View.generateViewId());
        row.setTag("rank-item:" + series.seriesId);
        row.setContentDescription("第 " + series.rank + " 名，" + series.title);
        row.setPadding(dp(14), dp(10), dp(18), dp(10));
        TvFocus.applyCard(row, this, 12);
        TextView rank = TvUi.heading(this, String.valueOf(series.rank), 25);
        rank.setGravity(Gravity.CENTER);
        rank.setTextColor(series.rank <= 3 ? TvUi.ACCENT : TvUi.DIM);
        row.addView(rank, new LinearLayout.LayoutParams(dp(46), -1));
        row.addView(TvUi.poster(this, series.cover, 64, 86));
        LinearLayout copy = TvUi.column(this);
        copy.setPadding(dp(20), 0, 0, 0);
        TextView title = TvUi.heading(this, series.title, 20);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(title);
        String detail = !series.category.isEmpty() ? series.category
                : series.episodeCount > 0 ? series.episodeCount + " 集" : "选集与分季";
        if (!series.hot.isEmpty()) detail += " · " + series.hot;
        TextView meta = TvUi.text(this, detail, 13, TvUi.ACCENT);
        meta.setPadding(0, dp(8), 0, dp(6));
        copy.addView(meta);
        TextView intro = TvUi.text(this, series.intro, 13, TvUi.DIM);
        intro.setSingleLine();
        intro.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(intro);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(view -> SeriesDialog.show(this, series, library));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(layout);
        return row;
    }

    private void linkFocus() {
        focusRows.clear();
        focusRows.add(java.util.Collections.singletonList(back));
        List<View> categoryRow = new ArrayList<>(), boardRow = new ArrayList<>();
        for (int index = 0; index < categories.getChildCount(); index++) categoryRow.add(categories.getChildAt(index));
        for (int index = 0; index < boards.getChildCount(); index++) boardRow.add(boards.getChildAt(index));
        if (!categoryRow.isEmpty()) focusRows.add(categoryRow);
        if (!boardRow.isEmpty()) focusRows.add(boardRow);
        for (int index = 0; index < results.getChildCount(); index++) focusRows.add(java.util.Collections.singletonList(results.getChildAt(index)));
        TvFocus.linkRows(focusRows);
        View selected = categories.findViewWithTag("rank-category:" + category);
        if (selected != null) back.setNextFocusDownId(selected.getId());
        View selectedBoard = boards.findViewWithTag("rank-board:" + board);
        if (selectedBoard != null && results.getChildCount() > 0)
            results.getChildAt(0).setNextFocusUpId(selectedBoard.getId());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("rank-category", category);
        state.putString("rank-board", board);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        generation++;
        loader.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) { return TvUi.dp(this, value); }
}
