package com.binf.floater;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
public final class UiPreviewActivity extends Activity {
 public static final String EXTRA_STYLE="preview_style";
 public static final int STYLE_CARDS=0, STYLE_TECH=1, STYLE_MATERIAL=2;
 @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(UiPreviewView.create(this,getIntent().getIntExtra(EXTRA_STYLE,STYLE_CARDS)));}
 public static void open(Activity a,int style){a.startActivity(new Intent(a,UiPreviewActivity.class).putExtra(EXTRA_STYLE,style));}
}