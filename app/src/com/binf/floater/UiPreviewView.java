package com.binf.floater;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
final class UiPreviewView {
 private UiPreviewView(){}
 static View create(Activity a,int style){return new Preview(a,style).build();}
 private static final class Preview {
  final Activity a; final int style,bg,card,text,muted,accent; final LinearLayout content;
  Preview(Activity a,int style){
   this.a=a;this.style=style;
   if(style==1){bg=Color.rgb(9,14,26);card=Color.rgb(19,29,48);text=Color.rgb(224,238,255);muted=Color.rgb(130,157,190);accent=Color.rgb(80,220,220);}
   else if(style==2){bg=Color.rgb(246,242,250);card=Color.rgb(255,252,255);text=Color.rgb(35,25,45);muted=Color.rgb(105,92,115);accent=Color.rgb(103,80,164);}
   else{bg=Color.rgb(244,247,251);card=Color.WHITE;text=Color.rgb(25,35,50);muted=Color.rgb(100,112,130);accent=Color.rgb(42,108,190);}
   content=new LinearLayout(a);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(18),dp(20),dp(28));
  }
  View build(){
   a.getWindow().setStatusBarColor(bg);a.getWindow().setNavigationBarColor(bg);
   ScrollView s=new ScrollView(a);s.setBackgroundColor(bg);s.setClipToPadding(false);s.addView(content,new ScrollView.LayoutParams(-1,-2));
   content.addView(text("BINFLOATER  /  UI PREVIEW",11,accent));
   TextView title=text(style==1?"控制面板":style==2?"你的转换工具":"二进制悬浮窗",29,text);title.setTypeface(Typeface.DEFAULT_BOLD);content.addView(title);
   content.addView(text(style==1?"FLOATING UTILITY  ·  ONLINE":"不离开当前页面，点一下完成转换",13,muted));
   LinearLayout hero=card();TextView status=text(FloatingService.isRunning()?"●  悬浮窗正在运行":"○  悬浮窗尚未开启",17,accent);status.setTypeface(Typeface.DEFAULT_BOLD);hero.addView(status);
   TextView hint=text("保留经典功能，只更换这层外观。开启后点击桌面悬浮球即可使用。",12,muted);hint.setPadding(0,dp(8),0,dp(12));hero.addView(hint);
   Switch sw=new Switch(a);sw.setText("启用悬浮窗");sw.setTextColor(text);sw.setChecked(FloatingService.isRunning());
   sw.setOnCheckedChangeListener((v,on)->{if(on&&!Settings.canDrawOverlays(a)){v.setChecked(false);try{a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+a.getPackageName())));}catch(Exception e){toast("请手动开启悬浮窗权限");}return;}try{if(on)a.startForegroundService(new Intent(a,FloatingService.class));else a.stopService(new Intent(a,FloatingService.class));}catch(Exception e){toast("系统拒绝了启动，请检查后台弹出设置");}});
   hero.addView(sw);content.addView(hero);
   section("快速操作");LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);addAction(row,"权限设置",v->openPermission());addAction(row,"界面外观",v->toast("外观仍在经典界面设置，预览不会改变原配置"));content.addView(row);
   section("三种视觉方案");addStyle("简洁卡片风","明亮、清爽，信息按卡片分组",0);addStyle("深色科技风","深色底、青色强调，突出工具感",1);addStyle("Material You 风格","柔和紫色调，大圆角与舒展留白",2);
   section("返回");Button old=button("回到经典界面");old.setOnClickListener(v->a.finish());content.addView(old);return s;
  }
  void section(String label){TextView t=text(label,13,muted);t.setTypeface(Typeface.DEFAULT_BOLD);t.setPadding(0,dp(24),0,dp(8));content.addView(t);}
  void addStyle(String title,String desc,int target){LinearLayout c=card();TextView t=text((target==style?"✓  ":"")+title,17,text);t.setTypeface(Typeface.DEFAULT_BOLD);c.addView(t);TextView d=text(desc,12,muted);d.setPadding(0,dp(5),0,dp(10));c.addView(d);Button b=button(target==style?"当前预览":"查看这个风格");b.setOnClickListener(v->{if(target!=style)UiPreviewActivity.open(a,target);});c.addView(b);content.addView(c);}
  void addAction(LinearLayout row,String label,View.OnClickListener l){Button b=button(label);b.setOnClickListener(l);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1f);p.setMargins(dp(3),0,dp(3),0);row.addView(b,p);}
  void openPermission(){try{a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+a.getPackageName())));}catch(Exception e){toast("请手动打开系统设置");}}
  LinearLayout card(){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(16),dp(18),dp(16));GradientDrawable g=new GradientDrawable();g.setColor(card);g.setCornerRadius(dp(style==2?28:style==1?12:20));if(style==1)g.setStroke(dp(1),Color.rgb(39,75,101));c.setBackground(g);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);c.setLayoutParams(p);return c;}
  Button button(String label){Button b=new Button(a);b.setText(label);b.setTextSize(13);b.setTextColor(style==1?Color.rgb(8,25,32):Color.WHITE);b.setAllCaps(false);GradientDrawable g=new GradientDrawable();g.setColor(accent);g.setCornerRadius(dp(style==2?24:12));b.setBackground(g);return b;}
  TextView text(String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1f);return t;}
  int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
  void toast(String s){Toast.makeText(a,s,Toast.LENGTH_SHORT).show();}
 }
}