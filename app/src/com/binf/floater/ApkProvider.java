package com.binf.floater;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider：只做一件事 —— 把缓存目录里的 update.apk 交给系统安装器。
 *
 * 为什么不用 FileProvider：那是 AndroidX 的类，而这个项目是 aapt2 + javac + d8
 * 手搓构建的，没有 Gradle、没有 AndroidX 依赖，为一个安装动作拉进整个 AndroidX 不值。
 * FileProvider 本身也不神秘，就是把 content:// 的 openFile 映射到私有目录里的某个文件，
 * 这里照着写一个够用的最小版本。
 *
 * 为什么不能用 file://：targetSdk 24 起系统直接抛 FileUriExposedException。
 */
public class ApkProvider extends ContentProvider {

    /** 必须和 AndroidManifest.xml 里 provider 的 authorities 一致。 */
    public static final String AUTHORITY = "com.binf.floater.apk";
    /** 下载落盘的文件名，同时也是 URI 的最后一段。 */
    public static final String FILE_NAME = "update.apk";

    /** 给安装器用的 content:// URI。 */
    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);
    }

    /**
     * APK 落盘位置。下载（UpdateChecker）和读取（这里）必须是同一份，
     * 所以统一从这里取，不要在别处再 new 一个。
     */
    public static File file(Context ctx) {
        return new File(ctx.getCacheDir(), FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException("只提供 " + FILE_NAME);
        }
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("没有 Context");
        File f = file(ctx);
        if (!f.exists()) throw new FileNotFoundException("APK 还没下载好");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
