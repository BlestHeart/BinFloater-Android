package com.binf.floater;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 查 GitHub Releases 有没有新版本，以及把 APK 下下来。
 *
 * 只用 HttpURLConnection + org.json（都在 android.jar 里），不引 AndroidX ——
 * 这个项目是 aapt2 + javac + d8 手搓的，没有 Gradle 也没有 AndroidX，
 * 所以也用不了 WorkManager。检查时机就定成「打开主界面时顺手看一眼」，
 * 24 小时节流一次，不在后台常驻，不耗电、不打扰。
 *
 * 版本比较交给 VersionUtil（纯逻辑，有单测）。
 */
public final class UpdateChecker {

    /**
     * 仓库地址。**仓库必须公开**，未认证访问才能读到 releases/latest；
     * 私有仓库匿名请求返回 404。仓库切换成 public 之前这个功能查不到东西。
     */
    public static final String API_URL =
            "https://api.github.com/repos/BlestHeart/BinFloater-Android/releases/latest";

    /** 同一个版本 24 小时内只自动查一次。 */
    public static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000L;

    /** 连不上时的超时，短一点：查更新不该让用户等。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    /** JSON 响应上限 512 KB，防止异常响应把内存吃掉。 */
    private static final int MAX_JSON_BYTES = 512 * 1024;

    /** APK 下载上限 64 MB，防止异常响应把存储写满。 */
    public static final long MAX_APK_BYTES = 64L * 1024 * 1024L;

    private UpdateChecker() {
    }

    /** 一次检查的结果。 */
    public static class Info {
        /** Release 的 tag，例如 `v2.5`。 */
        public String tag;
        /** .apk 附件的下载地址，可能为空（发布时忘了挂附件）。 */
        public String apkUrl;
        /** Release 网页地址，拿不到 apkUrl 时兜底用。 */
        public String pageUrl;
    }

    public interface Callback {
        /**
         * @param info  非空表示查到了（不代表一定有新版，用 VersionUtil 比）
         * @param error 非空表示失败原因
         */
        void onResult(Info info, String error);
    }

    public interface Progress {
        void onProgress(int percent);
    }

    /**
     * 解析 releases/latest 的 JSON。抽出来单独一个方法是为了能在没有网络的情况下测：
     * 把一段固定的 JSON 喂进来，看挑不挑得出 tag 和 .apk 地址。
     */
    public static Info parseRelease(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            Info info = new Info();
            info.tag = o.optString("tag_name", "");
            info.pageUrl = o.optString("html_url", "");
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    String url = a.optString("browser_download_url", "");
                    if (name.toLowerCase().endsWith(".apk") && !url.isEmpty()) {
                        info.apkUrl = url;
                        break;
                    }
                }
            }
            // 连 tag 都没有，这份 JSON 对我们没用
            if (info.tag.isEmpty()) return null;
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 后台线程查一次，回调派回主线程。
     * 不需要 Context：Handler 用主 Looper 就够。
     */
    public static void checkAsync(final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = null;
                String err = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(API_URL).openConnection();
                    c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    c.setReadTimeout(READ_TIMEOUT_MS);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    // GitHub API 要求带 User-Agent，不带会 403
                    c.setRequestProperty("User-Agent", "BinFloater");
                    int code = c.getResponseCode();
                    if (code == 404) {
                        err = "没有找到 Release（仓库还没公开，或者还没发过版）";
                    } else if (code == 403) {
                        err = "被限流了，过一会儿再试";
                    } else if (code != 200) {
                        err = "服务器返回 " + code;
                    } else {
                        info = parseRelease(readAll(c.getInputStream(), MAX_JSON_BYTES));
                        if (info == null) err = "返回内容看不懂";
                    }
                } catch (Exception e) {
                    // 连不上、超时、DNS 失败都走这里。国内直连 api.github.com 经常超时，
                    // 所以自动检查必须静默失败，只有手动检查才提示。
                    err = e.getClass().getSimpleName();
                } finally {
                    if (c != null) c.disconnect();
                }
                final Info fi = info;
                final String fe = err;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fi, fe);
                    }
                });
            }
        }).start();
    }

    /**
     * 下载 APK 到 {@link ApkProvider#file(Context)}。同步方法，调用方自己放后台线程。
     * 失败返回 null（已经删掉半截文件）。
     */
    public static File download(Context ctx, String url, Progress p) {
        HttpURLConnection c = null;
        File out = ApkProvider.file(ctx);
        FileOutputStream fos = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "BinFloater");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            long total = c.getContentLengthLong();
            if (total > MAX_APK_BYTES) return null;
            in = c.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            long done = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                done += n;
                if (done > MAX_APK_BYTES) {
                    // 长度头可以撒谎，边写边兜一次
                    closeQuietly(fos);
                    closeQuietly(in);
                    out.delete();
                    return null;
                }
                if (total > 0 && p != null) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        p.onProgress(pct);
                    }
                }
            }
            fos.flush();
            closeQuietly(fos);
            closeQuietly(in);
            // 空文件当失败处理：省得安装器报一个看不懂的解析错误
            if (out.length() <= 0) {
                out.delete();
                return null;
            }
            return out;
        } catch (Exception e) {
            closeQuietly(fos);
            closeQuietly(in);
            out.delete();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IllegalStateException("响应过大");
            bos.write(buf, 0, n);
        }
        closeQuietly(in);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
