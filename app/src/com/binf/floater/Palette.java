package com.binf.floater;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
/** 主界面和悬浮窗共享的视觉配置。默认经典，三种新风格可独立保留。 */
public final class Palette {
 public static final int MODE_SYSTEM=0, MODE_LIGHT=1, MODE_DARK=2;
 public static final int STYLE_CLASSIC=0, STYLE_CARDS=1, STYLE_TECH=2, STYLE_MATERIAL=3;
 public static final String PREF_UI_STYLE="ui_style";
 public static final int MAX_STYLE=3;
 public final int style,bg,card,cardHi,stroke,text,muted,accent,teal,ok,warn,err;
 /** 卡片与悬浮面板圆角，单位 dp。 */
 public final int cornerDp;
 public final boolean tech,material;
 private Palette(int bg,int card,int cardHi,int stroke,int text,int muted,int accent,int teal,int ok,int warn,int err){
  this(STYLE_CLASSIC,bg,card,cardHi,stroke,text,muted,accent,teal,ok,warn,err,14,false,false);
 }
 private Palette(int style,int bg,int card,int cardHi,int stroke,int text,int muted,int accent,int teal,int ok,int warn,int err,int cornerDp,boolean tech,boolean material){
  this.style=style;this.bg=bg;this.card=card;this.cardHi=cardHi;this.stroke=stroke;this.text=text;this.muted=muted;
  this.accent=accent;this.teal=teal;this.ok=ok;this.warn=warn;this.err=err;this.cornerDp=cornerDp;this.tech=tech;this.material=material;
 }
 /** 经典色板原样保留。 */
 public static final Palette DARK=new Palette(0xFF0F1117,0xFF171B24,0xFF1D2230,0xFF2A3142,0xFFE6EBF5,0xFF8992A7,0xFF7AA2FF,0xFF5EEAD4,0xFF4ADE80,0xFFFBBF24,0xFFF87171);
 public static final Palette LIGHT=new Palette(0xFFF4F6FB,0xFFFFFFFF,0xFFEDF1F8,0xFFD7DEEA,0xFF1B2230,0xFF5C6679,0xFF2F6BEA,0xFF0E8C7C,0xFF15803D,0xFFB45309,0xFFDC2626);
 public static int mode(Context c){
  try{int m=c.getSharedPreferences("binf",Context.MODE_PRIVATE).getInt("theme_mode",MODE_SYSTEM);
   if(m==MODE_LIGHT||m==MODE_DARK)return m;
  }catch(Exception ignored){}
  int night=c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK;
  return night==Configuration.UI_MODE_NIGHT_YES?MODE_DARK:MODE_LIGHT;
 }
 public static int systemMode(Context c){return mode(c);}
 public static int clampStyle(int s){return s>=STYLE_CARDS&&s<=MAX_STYLE?s:STYLE_CLASSIC;}
 public static int style(Context c){
  try{return clampStyle(c.getSharedPreferences("binf",Context.MODE_PRIVATE).getInt(PREF_UI_STYLE,STYLE_CLASSIC));}
  catch(Exception ignored){return STYLE_CLASSIC;}
 }
 public static String styleLabel(int s){
  switch(clampStyle(s)){case STYLE_CARDS:return "简洁卡片";case STYLE_TECH:return "深色科技";case STYLE_MATERIAL:return "Material You";default:return "经典";}
 }
 public static Palette get(Context c){
  switch(style(c)){
   case STYLE_CARDS:return new Palette(STYLE_CARDS,0xFFF4F7FB,0xFFFFFFFF,0xFFF0F4FA,0xFFDCE4EF,0xFF192332,0xFF647082,0xFF2A6CBE,0xFF0E8C7C,0xFF15803D,0xFFB45309,0xFFDC2626,20,false,false);
   case STYLE_TECH:return new Palette(STYLE_TECH,0xFF090E1A,0xFF131D30,0xFF1B2D43,0xFF274B65,0xFFE0EEFF,0xFF829DBE,0xFF50DCDC,0xFF70E8C0,0xFF4ADE80,0xFFFBBF24,0xFFF87171,10,true,false);
   case STYLE_MATERIAL:return new Palette(STYLE_MATERIAL,0xFFF6F2FA,0xFFFFFCFF,0xFFF0E9F7,0xFFD8CDE1,0xFF23192D,0xFF695C73,0xFF6750A4,0xFF006A60,0xFF146C43,0xFF995A00,0xFFBA1A1A,26,false,true);
   default:return mode(c)==MODE_DARK?DARK:LIGHT;
  }
 }
 public static String modeLabel(int m){return m==MODE_LIGHT?"浅色":m==MODE_DARK?"深色":"跟随系统";}
 public boolean isDark(){return this==DARK||tech;}
 /** 不能比较对象地址：新风格每次读取都会创建新实例。 */
 public boolean sameAppearance(Palette p){return p!=null&&style==p.style&&isDark()==p.isDark();}
 public int primaryColor(){return style==STYLE_CLASSIC&&this==DARK?0xFF2B3A5E:accent;}
 public int primaryText(){return tech?0xFF081920:(style==STYLE_CLASSIC&&this==DARK?0xFFCFE0FF:0xFFFFFFFF);}
 public int panelBackground(){return style==STYLE_CLASSIC?bg:card;}
 public int pressed(int normal){return blend(normal,isDark()?0xFFFFFFFF:0xFF000000,0.16f);}
 /** 经典按钮保持原矩形；新风格统一使用圆角按下态。 */
 public Drawable pressedBg(int normal){
  StateListDrawable s=new StateListDrawable();
  if(style==STYLE_CLASSIC){
   s.addState(new int[]{android.R.attr.state_pressed},new ColorDrawable(pressed(normal)));
   s.addState(new int[]{},new ColorDrawable(normal));
  }else{
   s.addState(new int[]{android.R.attr.state_pressed},roundedColor(pressed(normal),10));
   s.addState(new int[]{},roundedColor(normal,10));
  }
  return s;
 }
 public Drawable roundedBg(int normal,float radiusDp){
  StateListDrawable s=new StateListDrawable();
  s.addState(new int[]{android.R.attr.state_pressed},roundedColor(pressed(normal),radiusDp));
  s.addState(new int[]{},roundedColor(normal,radiusDp));
  return s;
 }
 public static GradientDrawable roundedColor(int color,float radiusDp){
  GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radiusDp);return g;
 }
 public Drawable cardBackground(){
  GradientDrawable g=roundedColor(card,14);
  if(style!=STYLE_CLASSIC)g.setStroke(1,stroke);
  return g;
 }
 public static int blend(int a,int b,float t){
  float k=t<0?0:(t>1?1:t);
  int aa=(a>>>24)&255,ar=(a>>16)&255,ag=(a>>8)&255,ab=a&255;
  int ba=(b>>>24)&255,br=(b>>16)&255,bg=(b>>8)&255,bb=b&255;
  return (Math.round(aa+(ba-aa)*k)<<24)|(Math.round(ar+(br-ar)*k)<<16)|(Math.round(ag+(bg-ag)*k)<<8)|Math.round(ab+(bb-ab)*k);
 }
}