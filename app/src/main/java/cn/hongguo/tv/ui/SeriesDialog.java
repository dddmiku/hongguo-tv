package cn.hongguo.tv.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.account.AccountApi;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.LibraryRepository;
import cn.hongguo.tv.cache.SeasonRepository;
import cn.hongguo.tv.cache.WatchRecord;

/** A remote-accessible detail sheet. Opening a sheet never records a viewing. */
final class SeriesDialog {
    static SeriesDialog show(TvActivity activity,Api.Series series,LibraryRepository library){SeriesDialog sheet=new SeriesDialog(activity,series,library);sheet.show(null);return sheet;}
    static void showAfterMissingResume(TvActivity activity, Api.Series series, LibraryRepository library,
                                       SeriesEpisodes available) {
        new SeriesDialog(activity, series, library).show(available);
    }
    private final TvActivity activity;
    private Api.Series series;
    private final LibraryRepository library;
    private final ExecutorService loader=Executors.newFixedThreadPool(2);
    private final Api api;
    private final SeasonRepository seasons;
    private SeasonRepository.Overview overview;
    private String seasonError="";
    private boolean seasonsLoading;
    private int generation;
    private AlertDialog dialog;
    private LinearLayout body;
    private TextView status;
    private JSONArray episodes=new JSONArray();
    private volatile boolean dismissed;
    private boolean favoriteBusy;
    private boolean favoriteKnown;
    private boolean saved;
    private boolean missingResume;
    private String intro="";
    private final Runnable updated=this::render;
    private SeriesDialog(TvActivity a,Api.Series s,LibraryRepository l){activity=a;series=s;library=l;intro=s.intro;api=new Api(a);seasons=new SeasonRepository(api);}
    private void show(SeriesEpisodes available){
        missingResume = available != null;
        LinearLayout page=TvUi.column(activity);page.setBackground(TvFocus.round(activity,20,TvUi.BG,0xFF3D4D64,1));page.setPadding(dp(28),dp(24),dp(28),dp(24));
        LinearLayout top=TvUi.row(activity);top.addView(TvUi.heading(activity,"故事详情",22),new LinearLayout.LayoutParams(0,-2,1));top.addView(TvUi.button(activity,"关闭",false,()->dialog.dismiss()));page.addView(top);
        status=TvUi.text(activity,"正在加载剧集…",15,TvUi.DIM);status.setPadding(0,dp(12),0,dp(12));page.addView(status);
        ScrollView scroll=new ScrollView(activity);scroll.setFocusable(false);scroll.setVerticalScrollBarEnabled(false);body=TvUi.column(activity);scroll.addView(body);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog=new AlertDialog.Builder(activity).create();dialog.setView(page,0,0,0,0);dialog.setOnDismissListener(d->{dismissed=true;generation++;library.unobserve(updated);loader.shutdownNow();});dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(dp(800),Math.min(dp(480),(int)(activity.getResources().getDisplayMetrics().heightPixels*.9f)));}
        library.observe(updated);render();
        loadCurrent(available);
    }
    private boolean active(int request,String sid){return !dismissed&&!activity.isFinishing()&&!activity.isDestroyed()&&request==generation&&series.seriesId.equals(sid);}
    private void loadCurrent(SeriesEpisodes available){
        final int request=++generation;final Api.Series selected=series;final String sid=selected.seriesId;
        status.setText("正在加载这一季…");favoriteBusy=false;favoriteKnown=false;saved=false;
        library.loadFavorite(selected,result->{if(!active(request,sid))return;favoriteKnown=result.known;saved=result.enabled;render();});
        loadOverview(false,request,sid);
        loader.execute(()->{
            try{
                SeriesEpisodes result = available == null ? SeriesEpisodes.load(api::episodes, selected) : available;
                Account snapshot=Account.load(activity);
                if(snapshot.loggedIn()){
                    try{WatchRecord remote=new AccountApi(activity).resume(snapshot,sid);if(remote!=null&&snapshot.sessionIdentity().equals(Account.load(activity).sessionIdentity()))library.store().merge(snapshot.libraryKey(),remote,false);}catch(Exception ignored){ }
                }
                activity.runOnUiThread(() -> {
                    if (!active(request, sid)) return;
                    episodes = result.rows;
                    intro = result.intro;
                    series.episodeCount = episodes.length();
                    if (!result.title.isEmpty()) series.title = result.title;
                    if (!result.cover.isEmpty()) series.cover = result.cover;
                    if (episodes.length() == 0) status.setText(missingResume ? "上次观看的集数暂不可播放，暂无其他可选集数。" : "暂时没有可播放的剧集。");
                    else if (missingResume) status.setText("上次观看的集数暂不可播放，请重新选集。");
                    else status.setText("选择任意一集，或切换其他季继续看。");
                    render();
                });
            }catch(Exception e){activity.runOnUiThread(()->{if(active(request,sid))status.setText("这一季暂时加载失败，可以切换其他季或关闭后重试。");});}
        });
    }
    private void loadOverview(boolean refresh,int request,String sid){
        if(dismissed||loader.isShutdown())return;seasonsLoading=true;seasonError="";
        loader.execute(()->{try{SeasonRepository.Overview result=seasons.overview(sid,refresh);activity.runOnUiThread(()->{if(!active(request,sid))return;overview=result;seasonsLoading=false;render();});}catch(Exception error){activity.runOnUiThread(()->{if(!active(request,sid))return;seasonsLoading=false;seasonError="季信息暂时未能加载，当前剧集可正常播放。";render();});}});
    }
    private void selectSeason(Api.Series selected){
        if(dismissed||selected.seriesId.equals(series.seriesId))return;
        missingResume=false;series=selected;intro=selected.intro;episodes=new JSONArray();render();loadCurrent(null);
    }
    private void render(){
        if(dismissed||body==null)return;View old=dialog.getCurrentFocus();Object tag=old==null?null:old.getTag();body.removeAllViews();
        LinearLayout hero=TvUi.row(activity);hero.addView(TvUi.poster(activity,series.cover,100,138));LinearLayout copy=TvUi.column(activity);copy.setPadding(dp(24),0,0,0);
        TextView title=TvUi.heading(activity,series.title,27);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(title);TextView meta=TvUi.text(activity,(series.episodeCount>0?"全 "+series.episodeCount+" 集":"剧集信息")+(!series.category.isEmpty()?"  ·  "+series.category:""),15,TvUi.ACCENT);meta.setPadding(0,dp(10),0,dp(10));copy.addView(meta);
        TextView plot=TvUi.text(activity,intro.isEmpty()?"从第一集开始，看看这个故事。":intro,16,TvUi.DIM);plot.setMaxLines(3);plot.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(plot);hero.addView(copy,new LinearLayout.LayoutParams(0,-2,1));body.addView(hero);
        List<List<View>> focus=new ArrayList<>();LinearLayout actions=TvUi.row(activity);actions.setPadding(0,dp(20),0,dp(22));List<View> buttons=new ArrayList<>();WatchRecord record=library.store().find(library.owner(),series.seriesId);
        boolean showResume = record != null && !missingResume;
        if(showResume){TextView resume=TvUi.button(activity,"继续第 "+record.episode+" 集 · "+TvUi.time(record.position),true,()->play(record.episode,record.position));resume.setTag("detail-resume");resume.setEnabled(episodes.length()>0);actions.addView(resume);buttons.add(resume);}
        JSONObject firstEpisode = episodes.optJSONObject(0);
        if (!missingResume) {
            int firstNumber = firstEpisode == null ? 1 : firstEpisode.optInt("num", 1);
            String firstLabel = record == null ? "立即播放" : firstNumber == 1 ? "从第一集看" : "从第 " + firstNumber + " 集看";
            TextView first=TvUi.button(activity,firstLabel,record==null,()->play(firstNumber,0));first.setTag("detail-first");first.setEnabled(firstEpisode!=null);TvUi.margins(first,activity,showResume?12:0,0,12,0);actions.addView(first);buttons.add(first);
        }
        TextView favorite=TvUi.button(activity,!favoriteKnown?"确认追剧状态":saved?"已追剧":"加入追剧",false,()->favorite(!saved));favorite.setTag("detail-favorite");favorite.setEnabled(!favoriteBusy);actions.addView(favorite);buttons.add(favorite);body.addView(actions);focus.add(buttons);
        renderSeasons(focus);
        TextView heading=TvUi.heading(activity,"选集",19);heading.setPadding(0,0,0,dp(12));body.addView(heading);
        for(int from=0;from<episodes.length();from+=10){LinearLayout row=TvUi.row(activity);List<View> cells=new ArrayList<>();for(int i=0;i<10;i++){JSONObject episode=episodes.optJSONObject(from+i);if(episode==null)break;int number=episode.optInt("num",from+i+1);TextView button=TvUi.tab(activity,String.format(java.util.Locale.US,"%02d",number),record!=null&&record.episode==number,()->play(number,0));button.setPadding(0,0,0,0);button.setTag("detail-ep:"+number);button.setContentDescription("播放第 "+number+" 集");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(0,0,dp(8),dp(8));row.addView(button,p);cells.add(button);}for(int i=cells.size();i<10;i++)row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));body.addView(row);focus.add(cells);}
        TvFocus.linkRows(focus);
        View restored = tag == null ? null : body.findViewWithTag(tag);
        if (restored != null) restored.requestFocus();
        else if (missingResume && firstEpisode != null) {
            View firstSelection = body.findViewWithTag("detail-ep:" + firstEpisode.optInt("num", 1));
            if (firstSelection != null) firstSelection.requestFocus();
        } else if (episodes.length() > 0 && !buttons.isEmpty()) buttons.get(0).requestFocus();
    }
    private void renderSeasons(List<List<View>> focus){
        if(!seasonsLoading&&seasonError.isEmpty()&&(overview==null||"single".equals(overview.status)))return;
        TextView label=TvUi.heading(activity,"全部季",19);label.setTag("detail-seasons-heading");label.setPadding(0,0,0,dp(12));body.addView(label);
        if(!seasonError.isEmpty()){
            LinearLayout row=TvUi.row(activity);TextView error=TvUi.text(activity,seasonError,14,TvUi.DIM);row.addView(error,new LinearLayout.LayoutParams(0,-2,1));TextView retry=TvUi.button(activity,"重试季信息",false,()->{loadOverview(true,generation,series.seriesId);render();});retry.setTag("detail-seasons-retry");row.addView(retry);body.addView(row);focus.add(java.util.Collections.singletonList(retry));
        }else if(overview!=null&&"ambiguous".equals(overview.status)){body.addView(TvUi.text(activity,"存在不同版本，暂未合并为同一季列表。",14,TvUi.DIM));}
        else if(overview!=null&&overview.items.size()>1){
            for(int from=0;from<overview.items.size();from+=2){LinearLayout row=TvUi.row(activity);List<View> cells=new ArrayList<>();
                for(int i=from;i<Math.min(from+2,overview.items.size());i++){SeasonRepository.Season season=overview.items.get(i);boolean selected=season.series.seriesId.equals(series.seriesId);
                    String text="第 "+season.number+" 季"+(selected?" · 当前季":"")+"\n"+season.series.title+"\n"+season.series.episodeCount+" 集";
                    TextView card=TvUi.tab(activity,text,selected,()->selectSeason(season.series));card.setTag("detail-season:"+season.series.seriesId);card.setContentDescription("查看第 "+season.number+" 季，"+season.series.title+"，共 "+season.series.episodeCount+" 集");card.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);card.setPadding(dp(16),dp(10),dp(16),dp(10));card.setTextSize(14);card.setMaxLines(4);
                    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(104),1);params.setMargins(0,0,dp(10),dp(12));row.addView(card,params);cells.add(card);
                }if(cells.size()==1)row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));body.addView(row);focus.add(cells);
            }
        }else{TextView waiting=TvUi.text(activity,"正在查找其他季及各季集数…",14,TvUi.DIM);waiting.setPadding(0,0,0,dp(16));body.addView(waiting);}
    }
    private void favorite(boolean enabled){
        if(!Account.load(activity).loggedIn()){status.setText("登录后可以追剧，观看记录仍会保存在本机。");return;}
        if(favoriteBusy)return;final int request=generation;final String sid=series.seriesId;
        if(!favoriteKnown){favoriteBusy=true;status.setText("正在确认追剧状态…");library.loadFavorite(series,result->{if(!active(request,sid))return;favoriteBusy=false;favoriteKnown=result.known;saved=result.enabled;status.setText(result.ok()?"追剧状态已确认":result.error);render();});return;}
        favoriteBusy=true;render();status.setText("正在更新追剧…");
        library.favorite(series,enabled,result->{if(!active(request,sid))return;favoriteBusy=false;favoriteKnown=result.known;if(result.known)saved=result.enabled;status.setText(result.ok()?(result.enabled?"已加入追剧":"已取消追剧"):result.error);render();});
    }
    private void play(int episode,long position){
        Intent intent = SeriesEpisodes.playback(activity, series, episodes, episode, position);
        if(intent==null){status.setText("这一集暂时不可播放，请重新选集。");return;}
        dialog.dismiss();activity.startActivity(intent);
    }
    private int dp(int value){return TvUi.dp(activity,value);}
}
