package com.binf.floater;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 查有没有新版本，以及把 APK 下下来。
 *
 * 只用 HttpURLConnection（android.jar 里就有），不引 AndroidX ——
 * 这个项目是 aapt2 + javac + d8 手搓的，没有 Gradle 也没有 AndroidX，
 * 所以也用不了 WorkManager。检查时机就定成「打开主界面时顺手看一眼」，
 * 24 小时节流一次，不在后台常驻，不耗电、不打扰。
 *
 * ## 为什么不再直连 github.com
 *
 * 老版本靠 {@code github.com/.../releases/latest} 的 302 重定向抠 tag。
 * 这条路在没挂梯子的国内网络里**必然超时**（github.com、api.github.com、
 * raw.githubusercontent.com 全是黑洞），用户看到的永远是「检查失败」。
 *
 * 现在改成读仓库根目录的 {@code version.json}（一个几十字节的静态文件），
 * 并且**依次试一串国内可达的镜像**：
 *
 * - jsDelivr 的三个入口（testingcf / gcore / b-cdn）在国内都能连，
 *   分别走不同的 CDN 节点，实测 1.3-4.3s；
 * - {@code gh-proxy.com} 前缀能连但慢（5-10s），当第二梯队；
 * - 直连 raw.githubusercontent.com 和原来的 302 重定向放在最后，
 *   给能上 GitHub 的用户用。
 *
 * 任何一个源返回合法的 version.json 就停，所以只要有一个通就能查到。
 *
 * ## APK 也从镜像下
 *
 * 下载候选同样是「镜像优先、GitHub 兜底」：jsDelivr 支持按 tag 取仓库里的
 * 文件（{@code @v2.6/dist/BinFloater.apk}），tag 是不可变的，缓存永远不会
 * 给出旧包；取不到再退回 Releases 附件（直连或 gh-proxy）。
 *
 * 版本比较交给 VersionUtil（纯逻辑，有单测）。
 */
public final class UpdateChecker {

    /** 仓库网页地址。**仓库必须公开**，否则下面的请求会 404。 */
    public static final String REPO = "https://github.com/BlestHeart/BinFloater-Android";

    /** 仓库的 owner/name，拼镜像地址用。 */
    private static final String OWNER_REPO = "BlestHeart/BinFloater-Android";

    /** Release 里挂的 APK 附件名。地址可以由 tag 直接拼出来，不必再查一次。 */
    public static final String APK_NAME = "BinFloater.apk";

    /** 仓库里那份 APK 副本的路径（给 jsDelivr 用；没有也不影响，会退回 Releases）。 */
    private static final String DEFAULT_APK_PATH = "dist/BinFloater.apk";

    /** 版本清单文件名。每次发版由 build.sh 重新生成，内容就几行。 */
    private static final String VERSION_JSON = "version.json";

    private static final String RAW_VERSION_JSON =
            "https://raw.githubusercontent.com/" + OWNER_REPO + "/main/" + VERSION_JSON;

    /**
     * version.json 的镜像候选，**按顺序试，第一个解析成功的算数**。
     *
     * 顺序是按国内实测的「能通 + 快」排的：jsDelivr 三个入口在前，
     * gh-proxy 其次，直连最后。jsDelivr 对分支有约 12 小时缓存，
     * 所以刚发版那半天可能还读到上一版 —— 24 小时才自动查一次，
     * 这个延迟可以接受；急着确认的用户会手动点「检查更新」，
     * 那时如果 jsDelivr 还是旧的，后面的 gh-proxy 会给出最新的。
     */
    public static final String[] VERSION_URLS = {
            "https://testingcf.jsdelivr.net/gh/" + OWNER_REPO + "@main/" + VERSION_JSON,
            "https://gcore.jsdelivr.net/gh/" + OWNER_REPO + "@main/" + VERSION_JSON,
            "https://jsdelivr.b-cdn.net/gh/" + OWNER_REPO + "@main/" + VERSION_JSON,
            "https://gh-proxy.com/" + RAW_VERSION_JSON,
            RAW_VERSION_JSON,
    };

    /**
     * 最后的兜底：老办法，跟 {@code releases/latest} 的 302 抠 tag。
     * 只在 version.json 全都没取到时才走，给能直连 GitHub 的网络用。
     */
    public static final String LATEST_URL = REPO + "/releases/latest";

    /** 同一个版本 24 小时内只自动查一次。 */
    public static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000L;

    /** 连不上时的超时，短一点：查更新不该让用户等。 */
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 6000;

    /** APK 下载上限 64 MB，防止异常响应把存储写满。 */
    public static final long MAX_APK_BYTES = 64L * 1024 * 1024L;

    /** version.json 不该超过这个大小，超了当异常响应。 */
    private static final int MAX_JSON_BYTES = 64 * 1024;

    private UpdateChecker() {
    }

    /** 一次检查的结果。 */
    public static class Info {
        /** Release 的 tag，例如 `v2.5`。 */
        public String tag;
        /** 清单里写的 versionName，可能为空（老兜底路径拿不到）。 */
        public String versionName;
        /** 清单里写的 versionCode，0 表示不知道。 */
        public int versionCode;
        /** 首选下载地址（UI 判空用），等于 apkUrls[0]。 */
        public String apkUrl;
        /** 全部下载候选，按顺序试。 */
        public String[] apkUrls;
        /** Release 网页地址，一个都下不来时兜底用。 */
        public String pageUrl;
        /** 版本说明，可空。 */
        public String notes;
        /** 这份结果是哪个源给的，排查用。 */
        public String source;
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

    /** 老签名：只有 tag 时也能拼出地址。 */
    public static Info makeInfo(String tag) {
        return makeInfo(tag, 0, null, DEFAULT_APK_PATH);
    }

    /** 由 tag / 版本号 / 仓库内 APK 路径拼出 Info 和全部下载候选。 */
    public static Info makeInfo(String tag, int versionCode, String versionName, String apkPath) {
        if (tag == null || tag.isEmpty()) return null;
        Info info = new Info();
        info.tag = tag;
        info.versionCode = versionCode;
        info.versionName = versionName;
        info.pageUrl = REPO + "/releases/tag/" + tag;
        info.apkUrls = apkUrlsFor(tag, apkPath);
        info.apkUrl = info.apkUrls[0];
        return info;
    }

    /**
     * APK 的下载候选：jsDelivr 按 tag 取仓库副本（不可变、国内快），
     * 取不到再走 Releases 附件（gh-proxy 前缀 + 直连）。
     */
    public static String[] apkUrlsFor(String tag, String apkPath) {
        String p = (apkPath == null || apkPath.isEmpty()) ? DEFAULT_APK_PATH : apkPath;
        String dl = REPO + "/releases/download/" + tag + "/" + APK_NAME;
        return new String[]{
                "https://testingcf.jsdelivr.net/gh/" + OWNER_REPO + "@" + tag + "/" + p,
                "https://gcore.jsdelivr.net/gh/" + OWNER_REPO + "@" + tag + "/" + p,
                "https://jsdelivr.b-cdn.net/gh/" + OWNER_REPO + "@" + tag + "/" + p,
                "https://gh-proxy.com/" + dl,
                dl,
        };
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
                for (String u : VERSION_URLS) {
                    try {
                        Info got = parseVersionJson(getText(u), u);
                        if (got != null) {
                            info = got;
                            break;
                        }
                        err = "版本清单格式不对";
                    } catch (Exception e) {
                        // 超时 / DNS / SSL / 404 都走这里，继续试下一个源
                        err = e.getClass().getSimpleName();
                    }
                }
                if (info == null) {
                    // 镜像全挂：退回老的重定向路径，能直连 GitHub 的网络还能查到
                    Info legacy = checkByRedirect();
                    if (legacy != null) {
                        info = legacy;
                        err = null;
                    } else if (err == null) {
                        err = "镜像和 GitHub 都没连上";
                    }
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
     * 把 version.json 解析成 Info；字段不合法返回 null。
     *
     * 解析用 {@link VersionUtil#jsonString} / {@link VersionUtil#jsonInt}：
     * 那个文件是 build.sh 生成的四行固定格式，手写抠字段就够，
     * 顺带让「解析」这段逻辑能在桌面 JVM 上跑单测（不依赖 org.json）。
     */
    static Info parseVersionJson(String body, String source) {
        if (body == null) return null;
        String tag = VersionUtil.jsonString(body, "tag");
        if (tag != null) tag = tag.trim();
        int code = VersionUtil.jsonInt(body, "versionCode");
        String name = VersionUtil.jsonString(body, "versionName");
        if (name != null) name = name.trim();
        if (tag == null || tag.isEmpty()) {
            // 没写 tag 就由 versionName 推
            if (code <= 0 || name == null || name.isEmpty()) return null;
            tag = "v" + name;
        }
        if (!tag.startsWith("v")) tag = "v" + tag;
        if (name == null || name.isEmpty()) name = tag.substring(1);
        String apkPath = VersionUtil.jsonString(body, "apkPath");
        Info info = makeInfo(tag, code, name, apkPath);
        if (info == null) return null;
        String notes = VersionUtil.jsonString(body, "notes");
        info.notes = notes == null ? "" : notes;
        info.source = source;
        return info;
    }

    /** 读一个小文本文件（version.json），超过 {@link #MAX_JSON_BYTES} 当异常。 */
    private static String getText(String url) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "BinFloater");
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_JSON_BYTES) throw new IllegalStateException("响应过大");
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            closeQuietly(in);
            if (c != null) c.disconnect();
        }
    }

    /**
     * 老办法：跟 {@code releases/latest} 的 302，从 Location 里抠 tag。
     * 镜像全挂时的兜底，只在能直连 github.com 的网络里有效。
     */
    private static Info checkByRedirect() {
        String url = LATEST_URL;
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
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
            if (code >= 200 && code < 300) return null;          // 被中间设备拦了
            if (code < 300 || code >= 400 || loc == null) return null;
            String tag = VersionUtil.parseTag(loc);
            if (tag != null) return makeInfo(tag);
            try {
                url = loc.startsWith("http") ? loc : new URL(new URL(url), loc).toString();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 按 Info 里的候选列表依次下载，第一个成功的算数。
     * 同步方法，调用方自己放后台线程。全失败返回 null。
     */
    public static File download(Context ctx, Info info, Progress p) {
        if (info == null) return null;
        String[] urls = info.apkUrls;
        if (urls == null || urls.length == 0) {
            return info.apkUrl == null ? null : download(ctx, info.apkUrl, p);
        }
        for (String u : urls) {
            File f = download(ctx, u, p);
            if (f != null) return f;
        }
        return null;
    }

    /**
     * 从单个地址下载 APK 到 {@link ApkProvider#file(Context)}。
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
