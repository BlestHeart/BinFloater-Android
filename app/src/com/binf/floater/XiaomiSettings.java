package com.binf.floater;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

/** 小米 / 红米（MIUI、澎湃 HyperOS）的系统设置入口。
 *
 * 这些页面不是 Android SDK 的公开接口，不声明、不伪造授权结果；只负责尽量把用户
 * 带到正确页面。跳转失败或返回首页后，仍由系统 API 和实际窗口状态判断能否运行。
 */
public final class XiaomiSettings {
    private XiaomiSettings() {}

    /** 只认小米、红米，POCO 也可能使用同一套设置入口。 */
    public static boolean isXiaomiFamily() {
        return contains(Build.MANUFACTURER, "xiaomi")
                || contains(Build.MANUFACTURER, "redmi")
                || contains(Build.MANUFACTURER, "poco")
                || contains(Build.BRAND, "xiaomi")
                || contains(Build.BRAND, "redmi")
                || contains(Build.BRAND, "poco");
    }

    private static boolean contains(String value, String word) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(word);
    }

    /** 只在确实是小米/红米设备时返回专属入口；其他设备不冒险发厂商隐式 Intent。 */
    public static Intent[] overlayIntents(Context context) {
        if (!isXiaomiFamily()) return new Intent[0];
        return new Intent[]{
                explicit("miui.intent.action.APP_PERM_EDITOR",
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                        context),
                explicit("miui.intent.action.APP_PERM_EDITOR",
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                        context)
        };
    }

    /** 后台弹出界面：部分 MIUI / HyperOS 版本单独管控，授权页没有时交给系统详情页。 */
    public static Intent[] backgroundStartIntents(Context context) {
        if (!isXiaomiFamily()) return new Intent[0];
        return new Intent[]{
                explicit("miui.intent.action.APP_PERM_EDITOR",
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                        context),
                appDetails(context)
        };
    }

    /** 自启动入口。不存在时返回空数组，由调用方回退到应用详情页。 */
    public static Intent[] autoStartIntents(Context context) {
        if (!isXiaomiFamily()) return new Intent[0];
        return new Intent[]{
                explicit(null, "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity", context),
                appDetails(context)
        };
    }

    private static Intent explicit(String action, String pkg, String cls, Context context) {
        Intent i = new Intent();
        if (action != null) i.setAction(action);
        i.setClassName(pkg, cls);
        // 文档和不同 ROM 版本使用的 extra 不一致，三个都带上，由目标页自己挑。
        i.putExtra("extra_pkgname", context.getPackageName());
        i.putExtra("extra_package_uid", context.getApplicationInfo().uid);
        i.putExtra("package", context.getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent appDetails(Context context) {
        return new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
