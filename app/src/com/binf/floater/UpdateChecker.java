package com.binf.floater;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 查 GitHub Releases 有没有新版本，以及把 APK 下下来。
 *
 * 只用 HttpURLConnection（android.jar 里就有），不引 AndroidX ——
 * 这个项目是 aapt2 + javac + d8 手搓的，没有 Gradle 也没有 AndroidX，
 * 所以也用不了 WorkManager。检查时机就定成「打开主界面时顺手看一眼」，
 * 24 小时节流一次，不在后台常驻，不耗电、不打扰。
 *
 * 版本比较交给 VersionUtil（纯逻辑，有单测）。
 */
public final class UpdateChecker {

    /** 仓库网页地址。**仓库必须公开**，否则下面的请求会 404。 */
    public static final String REPO = "https://github.com/BlestHeart/BinFloater-Android";

    /**
     * 查最新版用**网页重定向**，不用 api.github.com。
     *
     * 这个地址对匿名请求 302 到 `.../releases/tag/<tag>`，把 tag 从 Location 里读出来就行：
     * 不限流、不用解析 JSON、不依赖 org.json。
     *
     * 早先的写法是打 `api.github.com/repos/.../releases/latest`，实测被限流得很惨
     * —— 未认证每小时只有 60 次且按 IP 算，共享出口（CGNAT / 代理）经常整个 IP 池
     * remaining=0，直接 403。功能是给用户用的，不能靠「运气好没被限流」。
     */
    public static final String LATEST_URL = REPO + "/releases/latest";

    /** Release 里挂的 APK 附件名。地址可以由 tag 直接拼出来，不必再查一次。 */
    public static final String APK_NAME = "BinFloater.apk";

    /** 同一个版本 24 小时内只自动查一次。 */
    public static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000L;

    /** 连不上时的超时，短一点：查更新不该让用户等。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

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
     * 由 tag 拼出三个地址。APK 的下载路径是固定的
     * `<repo>/releases/download/<tag>/<文件名>`，所以不用再查一次附件列表。
     */
    public static Info makeInfo(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        Info info = new Info();
        info.tag = tag;
        info.pageUrl = REPO + "/releases/tag/" + tag;
        info.apkUrl = REPO + "/releases/download/" + tag + "/" + APK_NAME;
        return info;
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
                try {
                    String url = LATEST_URL;
                    // 手动跟重定向：跟着跟着就走到 /releases/tag/<tag> 了。
                    // 多给几跳是因为 GitHub 偶尔会先 301 到带尾斜杠的规范地址。
                    for (int hop = 0; hop < 4; hop++) {
                        HttpURLConnection c = null;
                        int code;
                        String loc;
                        try {
                            c = (HttpURLConnection) new URL(url).openConnection();
                            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                            c.setReadTimeout(READ_TIMEOUT_MS);
                            // 必须自己接管重定向：默认会自动跟到最终页，那时就拿不到 Location 了
                            c.setInstanceFollowRedirects(false);
                            c.setRequestProperty("User-Agent", "BinFloater");
                            code = c.getResponseCode();
                            loc = c.getHeaderField("Location");
                        } finally {
                            if (c != null) c.disconnect();
                        }
                        if (code == 404) {
                            err = "没有找到 Release（仓库还没发过版）";
                            break;
                        }
                        if (code >= 200 && code < 300) {
                            // 直接 200 说明没跳转，可能被中间设备拦了
                            err = "意外的响应 " + code;
                            break;
                        }
                        if (code < 300 || code >= 400 || loc == null) {
                            err = "服务器返回 " + code;
                            break;
                        }
                        String tag = VersionUtil.parseTag(loc);
                        if (tag != null) {
                            info = makeInfo(tag);
                            break;
                        }
                        // 还没到 tag 那一跳，接着跟
                        url = loc.startsWith("http") ? loc : new URL(new URL(url), loc).toString();
                    }
                    if (info == null && err == null) err = "重定向太多次";
                } catch (Exception e) {
                    // 连不上、超时、DNS 失败都走这里。国内直连 github.com 经常超时，
                    // 所以自动检查必须静默失败，只有手动检查才提示。
                    err = e.getClass().getSimpleName();
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

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}