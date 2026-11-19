package com.irisai.farm;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.util.*;

final class Overlay {
    interface Actions {void open();void close();void start(String brawler,boolean repeat);void stop();void exit();}
    private final Context c;private final WindowManager wm;private final Profiles profiles;private final Actions actions;
    private final ImageButton bubble;
    private ScrollView sheet;
    private TextView phase,rates;
    private Button run,choice;
    private CheckBox repeat;
    private boolean enabled;
    Overlay(Context c,Profiles p,Actions a){
        this.c=c;profiles=p;actions=a;wm=c.getSystemService(WindowManager.class);
        bubble=new ImageButton(c);bubble.setImageResource(R.drawable.gear);
        bubble.setContentDescription("IrisAI 설정");bubble.setBackground(Ui.surface(0xff193c2b,40,c));
        bubble.setPadding(Ui.dp(c,10),Ui.dp(c,10),Ui.dp(c,10),Ui.dp(c,10));bubble.setOnClickListener(v->actions.open());
        WindowManager.LayoutParams lp=params(Ui.dp(c,48),Ui.dp(c,48),false);
        lp.gravity=Gravity.TOP|Gravity.START;lp.x=Ui.dp(c,12);lp.y=Ui.dp(c,24);lp.setTitle("IrisAI 설정 버튼");
        if(BuildConfig.DEBUG)bubble.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            if(bubble.getVisibility()!=View.VISIBLE)return;
            int[] origin=new int[2];bubble.getLocationOnScreen(origin);
            android.util.Log.d("IrisExternal","gear_center="+(origin[0]+bubble.getWidth()/2)+","+(origin[1]+bubble.getHeight()/2));
        });
        wm.addView(bubble,lp);
    }
    private WindowManager.LayoutParams params(int w,int h,boolean focus){
        int flags=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if(!focus)flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,PixelFormat.TRANSLUCENT);
        lp.preferredRefreshRate=120f;return lp;
    }
    boolean opened(){return sheet!=null;}
    void open(boolean playing,String last,String stats){
        if(sheet!=null)return;
        enabled=playing;sheet=new ScrollView(c);sheet.setBackground(Ui.surface(Ui.BG,20,c));
        LinearLayout body=Ui.column(c,16);body.setFocusableInTouchMode(true);sheet.addView(body);body.requestFocus();
        LinearLayout bar=new LinearLayout(c);bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(c,"플레이 설정",22,true);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=Ui.button(c,"닫기",false);close.setOnClickListener(v->actions.close());bar.addView(close);Ui.add(body,bar,0);
        Ui.add(body,Ui.text(c,"게임에서 선택한 브롤러",14,true),8);
        LinearLayout picker=Ui.column(c,0);picker.setVisibility(View.GONE);
        choice=Ui.button(c,profiles.selected(),false);choice.setContentDescription("브롤러 선택");
        ListView list=new ListView(c);EditText search=new EditText(c);search.setSingleLine();search.setHint("브롤러 검색 (영문)");
        picker.addView(search);picker.addView(list,new LinearLayout.LayoutParams(-1,Ui.dp(c,116)));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(c,android.R.layout.simple_list_item_1,new ArrayList<>(profiles.names));list.setAdapter(adapter);
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int n,int f){}
            public void onTextChanged(CharSequence s,int a,int b,int n){adapter.getFilter().filter(s);}
            public void afterTextChanged(android.text.Editable e){}
        });
        choice.setOnClickListener(v->picker.setVisibility(picker.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        list.setOnItemClickListener((parent,v,pos,id)->{String n=adapter.getItem(pos);choice.setText(n);picker.setVisibility(View.GONE);
            android.view.inputmethod.InputMethodManager im=c.getSystemService(android.view.inputmethod.InputMethodManager.class);im.hideSoftInputFromWindow(search.getWindowToken(),0);});
        Ui.add(body,choice,2);Ui.add(body,picker,0);
        repeat=new CheckBox(c);repeat.setText("결과 화면에서 다음 경기 진행");repeat.setChecked(profiles.repeat());Ui.add(body,repeat,3);
        phase=Ui.text(c,last,14,true);rates=Ui.text(c,stats,13,false);Ui.add(body,phase,4);Ui.add(body,rates,2);
        run=Ui.button(c,enabled?"플레이 정지":"선택한 브롤러로 시작",true);Ui.add(body,run,7);
        run.setOnClickListener(v->{if(enabled)actions.stop();else actions.start(choice.getText().toString(),repeat.isChecked());});
        Button end=Ui.button(c,"화면 공유 종료",false);end.setOnClickListener(v->actions.exit());Ui.add(body,end,6);
        android.graphics.Rect bounds=wm.getMaximumWindowMetrics().getBounds();
        WindowManager.LayoutParams lp=params(Math.min(Ui.dp(c,440),bounds.width()-Ui.dp(c,24)),
                Math.min(Ui.dp(c,460),bounds.height()-Ui.dp(c,28)),true);
        lp.gravity=Gravity.CENTER;lp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;lp.setTitle("IrisAI 플레이 설정");
        bubble.setVisibility(View.GONE);wm.addView(sheet,lp);
    }
    void status(boolean playing,String message,String fps){
        enabled=playing;if(sheet==null)return;choice.setEnabled(!playing);repeat.setEnabled(!playing);phase.setText(message);rates.setText(fps);run.setText(playing?"플레이 정지":"선택한 브롤러로 시작");
    }
    void close(){if(sheet!=null){wm.removeViewImmediate(sheet);sheet=null;bubble.setVisibility(View.VISIBLE);}}
    void destroy(){close();wm.removeViewImmediate(bubble);}
}
