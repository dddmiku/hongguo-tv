package cn.hongguo.tv.media;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import cn.hongguo.tv.cache.MediaCache;

/**
 * 一集的准备流程：下载密文 → spade 出 key → 解密 → 得到可播放明文。
 *
 * 为什么要单独一个类：
 *   播放器 UI 只关心「准备好了没 / 百分比」，不该知道 spade、CENC、senc
 *   这些细节。所有失败模式（下载断、key 算不出、解密无视频轨）都在这里
 *   收敛成一句话，UI 直接显示。
 *
 * 线程模型：run() 是阻塞的，调用方放到后台线程；进度通过回调回到调用线程
 * （由调用方保证回到 UI 线程）。
 */
public final class EpisodePreparer {

    private static final String TAG = "HgPrep";

    /** 进度回调。stage 是给人看的短句，pct 是 0..100。 */
    public interface Listener {
        void onProgress(String stage, int pct);

        void onDone(File plain);

        void onError(String message);
    }

    private final MediaCache cache;

    public EpisodePreparer(MediaCache cache) {
        this.cache = cache;
    }

    /**
     * 准备一集。
     *
     * @param vid        集 id
     * @param definition 清晰度名（用于缓存分桶）
     * @param url        密文直链
     * @param spadeA     encrypt_info.spade_a
     * @param encrypted  是否需要解密
     */
    public void prepare(String vid, String definition, String url, String spadeA,
                        boolean encrypted, Listener l) {
        prepare(vid, definition, url, spadeA, encrypted, new PreparationCancellation(), l);
    }

    /** Compatible preparation with explicit cancellation for obsolete loads and season prefetch. */
    public void prepare(String vid, String definition, String url, String spadeA,
                        boolean encrypted, PreparationCancellation cancellation, Listener l) {
        prepare(vid,definition,java.util.Collections.singletonList(url),spadeA,encrypted,cancellation,l);
    }

    public void prepare(String vid, TrackPicker.Track track, PreparationCancellation cancellation, Listener l) {
        prepare(vid,track.definition,track.urls,track.spadeA,track.encrypted,cancellation,l);
    }
    public interface Refresh { TrackPicker.Track read() throws Exception; }
    public void prepare(String vid, TrackPicker.Track track, PreparationCancellation cancellation, Listener l, Refresh refresh) {
        prepare(vid,track,cancellation,new Listener(){
            @Override public void onProgress(String stage,int pct){l.onProgress(stage,pct);}
            @Override public void onDone(File plain){l.onDone(plain);}
            @Override public void onError(String message){
                try {
                    cancellation.bindWorker();cancellation.check();
                    l.onProgress("重新连接片源",0);
                    TrackPicker.Track fresh=refresh.read();cancellation.check();
                    prepare(vid,fresh,cancellation,l);
                }catch(Exception error){if(!cancellation.cancelled()&&!Thread.currentThread().isInterrupted())l.onError("各播放线路暂时不可用，请重试当前集");}
            }
        });
    }

    private void prepare(String vid, String definition, java.util.List<String> urls, String spadeA,
                        boolean encrypted, PreparationCancellation cancellation, Listener l) {
        File plain = cache.plainFile(vid, definition);
        File cipher = cache.cipherFile(vid, definition);
        File staged = new File(plain.getPath() + ".part");
        try {
            cancellation.bindWorker();
            if (plain.isFile() && plain.length() > 0) {
                l.onProgress("已缓存", 100);
                cancellation.check();
                l.onDone(plain);
                return;
            }
            l.onProgress("下载中", 0);
            long total = 0;
            for (String url : urls) {
                cancellation.check();
                try { total = download(url, cipher, pct -> l.onProgress("下载中", pct), cancellation); if (total>0) break; }
                catch (Exception error) { cancellation.check(); cipher.delete(); }
            }
            cancellation.check();
            Log.i(TAG, "下载完成 " + total / 1024 + "KB");
            if (total <= 0) {
                l.onError("下载失败");
                return;
            }

            if (!encrypted) {
                if (!cipher.renameTo(staged)) {
                    copy(cipher, staged, cancellation);
                }
                cancellation.check();
                publish(staged, plain, cancellation);
                cancellation.check();
                l.onDone(plain);
                return;
            }

            l.onProgress("解密中", 90);
            byte[] key = Spade.contentKey(spadeA);
            cancellation.check();
            if (key == null) {
                l.onError("密钥解析失败");
                return;
            }
            Decryptor.Result r = Decryptor.decrypt(cipher, staged, key, cancellation);
            cancellation.check();
            Log.i(TAG, "解密完成 视频=" + r.videoSamples + " 音频=" + r.audioSamples);
            if (r.videoSamples <= 0) {
                l.onError("解密后无视频");
                return;
            }
            publish(staged, plain, cancellation);
            l.onProgress("就绪", 100);
            cancellation.check();
            l.onDone(plain);
        } catch (Throwable e) {
            if (!cancellation.cancelled() && !Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "准备失败类型=" + e.getClass().getSimpleName());
                l.onError("视频暂时加载失败，请稍后重试");
            }
        } finally {
            cipher.delete();
            staged.delete();
            cancellation.finished();
        }
    }

    private static void publish(File staged, File plain, PreparationCancellation cancellation) throws Exception {
        cancellation.check();
        if (!staged.renameTo(plain)) throw new java.io.IOException("缓存提交失败");
    }

    private interface Pct {
        void set(int pct);
    }

    /** 下载到文件，按字节数回报百分比（总长未知时只报 0）。 */
    private static long download(String url, File dst, Pct pct, PreparationCancellation cancellation) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        // 必须整集下载：moov 里的样本偏移是相对完整文件的，
        // 只取前面一部分会让大量样本越界、解出来只剩开头几帧。
        cancellation.bindConnection(c);
        long done = 0;
        long total = -1;
        int last = -1;
        try {
          cancellation.check();
          total = c.getContentLength();
          int status = c.getResponseCode();
          if (status != 200) throw new java.io.IOException("片源不可用");
          cancellation.check();
          try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            while (true) {
                cancellation.check();
                int n = in.read(buf);
                if (n == -1) break;
                cancellation.check();
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int p = (int) (done * 85 / total);   // 下载占进度条的 85%
                    if (p != last) {
                        last = p;
                        pct.set(p);
                    }
                }
            }
          }
          cancellation.check();
        } finally {
            c.disconnect();
            cancellation.clearConnection(c);
        }
        if (total > 0 && done != total) throw new java.io.IOException("片源下载不完整");
        return done;
    }

    private static void copy(File from, File to, PreparationCancellation cancellation) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            while (true) {
                cancellation.check();
                int n = in.read(buf);
                if (n == -1) break;
                cancellation.check();
                out.write(buf, 0, n);
            }
        }
    }
}
