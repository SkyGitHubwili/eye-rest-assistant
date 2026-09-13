package com.eyerest.app.ledger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Compact, calendar-week report. All displayed numbers come from WeeklyReport. */
public final class WeeklyReportView extends LinearLayout {
    public interface Actions {
        void changeWeek(int direction);
        void selectDay(String date);
    }

    public static final int BACKGROUND = 0xff079ad4;
    private static final int ACCENT = 0xff0b9cd2, SELECTED = 0xff087da9;
    private static final int[] COLORS = {0xffd82c43,0xff27cdb8,0xff79acbe,0xff896bda,0xffefae4f,0xff37bbc9};
    private final WeeklyReport report;
    private final Actions actions;
    private final boolean dark;
    private final int card, ink, muted, line;
    private final Map<String,String> names = new HashMap<>();
    private final Map<String,Drawable> icons = new HashMap<>();
    private final Map<String,Integer> colors = new HashMap<>();
    private final String selectedDate;

    public WeeklyReportView(Context context, WeeklyReport report, boolean dark, String selectedDate, Actions actions) {
        super(context);
        this.report = report;
        this.actions = actions;
        this.dark = dark;
        this.selectedDate = selectedDate;
        card = dark ? 0xff1a2536 : Color.WHITE;
        ink = dark ? 0xffedf1fb : 0xff55585c;
        muted = dark ? 0xffb2bfd2 : 0xff92989d;
        line = dark ? 0xff36465b : 0xffe8ebed;
        setOrientation(VERTICAL);
        for (int i = 0; i < report.current.byTime.size(); i++) {
            colors.put(report.current.byTime.get(i).pkg, COLORS[i % COLORS.length]);
        }
        header();
        LinearLayout usage = card("◔  使用比", report.comparison(false));
        usage.addView(new Ring(), new LayoutParams(-1, dp(290)));
        addView(usage);
        space(this, 16);
        LinearLayout daily = card("▥  日均", report.comparison(true));
        daily.addView(new WeekChart(), new LayoutParams(-1, dp(238)));
        TextView coverage = text(report.coverage(), 11, muted);
        coverage.setPadding(0, dp(6), 0, dp(4));
        daily.addView(coverage, new LayoutParams(-1, -2));
        addView(daily);
        space(this, 16);
        ranking("◷  TOP 6 使用时长", report.current.byTime, false);
        space(this, 16);
        ranking("▣  TOP 6 启动次数", report.current.byOpens, true);
        space(this, 16);
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private float sp(float n) { return n * getResources().getDisplayMetrics().scaledDensity; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false);
        return view;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(getContext()), new LayoutParams(1, dp(height))); }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); return row;
    }
    private void header() {
        LinearLayout titleRow = row(); titleRow.setPadding(dp(6),dp(10),dp(6),dp(10));
        TextView title = text("时间周报",28,Color.WHITE);
        titleRow.addView(title,new LayoutParams(0,-2,1));
        titleRow.addView(weekButton("‹",-1));
        titleRow.addView(weekButton("›",1));
        addView(titleRow);
        TextView date = text(report.start.format(DateTimeFormatter.ofPattern("MM.dd"))+" - "+report.start.plusDays(6).format(DateTimeFormatter.ofPattern("MM.dd")),13,Color.WHITE);
        date.setPadding(dp(15),dp(6),dp(15),dp(6)); date.setBackground(background(0x40ffffff,14));
        LayoutParams lp = new LayoutParams(-2,-2); lp.leftMargin=dp(6); addView(date,lp); space(this,16);
    }
    private TextView weekButton(String title, int direction) {
        TextView button=text(title,26,Color.WHITE); button.setGravity(Gravity.CENTER);
        button.setLayoutParams(new LayoutParams(dp(44),dp(44)));
        button.setContentDescription(direction<0?"上一周":"下一周");
        boolean enabled=direction<0 || report.start.isBefore(WeeklyReport.monday(report.today));
        button.setEnabled(enabled); button.setAlpha(enabled?1f:.35f);
        if(enabled)button.setOnClickListener(v->actions.changeWeek(direction)); return button;
    }
    private LinearLayout card(String heading, String note) {
        LinearLayout box=new LinearLayout(getContext()); box.setOrientation(VERTICAL);
        box.setPadding(dp(14),dp(14),dp(14),dp(10)); box.setBackground(background(card,12));
        LinearLayout headingRow=row();
        TextView title=text(heading,17,ink); title.setSingleLine(true);
        headingRow.addView(title,new LayoutParams(-2,-2));
        TextView subtitle=text(note,11,muted); subtitle.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        subtitle.setPadding(dp(8),0,0,0); subtitle.setMaxLines(2);
        headingRow.addView(subtitle,new LayoutParams(0,-2,1));
        box.addView(headingRow,new LayoutParams(-1,dp(30)));
        space(box,6); View divider=new View(getContext()); divider.setBackgroundColor(line); box.addView(divider,new LayoutParams(-1,dp(1)));
        return box;
    }
    private String name(String pkg) {
        if(!names.containsKey(pkg)){
            String name=pkg; try{PackageManager pm=getContext().getPackageManager();name=pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){}
            names.put(pkg,name);
        } return names.get(pkg);
    }
    private Drawable icon(String pkg) {
        if(!icons.containsKey(pkg)){
            Drawable icon=AppIcons.load(getContext(),pkg);
            icons.put(pkg,icon);
        } return icons.get(pkg);
    }
    private Drawable listIcon(String pkg) {
        Drawable cached=icon(pkg);
        Drawable.ConstantState state=cached.getConstantState();
        if(state!=null)return state.newDrawable(getResources()).mutate();
        return AppIcons.load(getContext(),pkg);
    }
    private int color(String pkg) { Integer color=colors.get(pkg); return color==null?COLORS[0]:color; }
    private void ranking(String title, List<UsageEngine.App> apps, boolean opens) {
        LinearLayout box=card(title,"");
        if(apps.isEmpty()) {TextView empty=text("暂无记录",14,muted);empty.setPadding(0,dp(24),0,dp(24));box.addView(empty);}
        long maximum=apps.isEmpty()?1:(opens?apps.get(0).opens:apps.get(0).millis);
        long total=report.current.total;
        if(opens){total=0;for(UsageEngine.App app:report.current.byOpens)total+=app.opens;}
        for(int i=0;i<Math.min(6,apps.size());i++){
            UsageEngine.App app=apps.get(i); LinearLayout row=row();row.setPadding(0,dp(15),0,dp(15));
            ImageView icon=new ImageView(getContext());icon.setImageDrawable(listIcon(app.pkg));icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setPadding(dp(2),dp(2),dp(2),dp(2));
            row.addView(icon,new LayoutParams(dp(24),dp(24)));
            LinearLayout detail=new LinearLayout(getContext());detail.setOrientation(VERTICAL);detail.setPadding(dp(12),0,0,0);
            TextView name=text(name(app.pkg),14,ink);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);detail.addView(name,new LayoutParams(-1,-2));
            long value=opens?app.opens:app.millis;
            String share=(opens?"启动占比 ":"时长占比 ")+WeeklyReport.percentage(value,total);
            TextView percentage=text(share,11,muted);percentage.setPadding(0,dp(5),0,0);detail.addView(percentage,new LayoutParams(-1,-2));
            detail.addView(new RankingBar(value,maximum,opens?value+"次":WeeklyReport.duration(value),color(app.pkg)),new LayoutParams(-1,dp(23)));
            row.addView(detail,new LayoutParams(0,-2,1));
            row.setContentDescription(name(app.pkg)+"，"+(opens?value+"次启动":WeeklyReport.duration(value))+"，"+share);
            row.setClickable(false);row.setFocusable(false);box.addView(row);
        }
        addView(box);
    }
    private final class RankingBar extends View {
        final long value,max;final String label;final int color;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RankingBar(long value,long max,String label,int color){super(WeeklyReportView.this.getContext());this.value=value;this.max=Math.max(1,max);this.label=label;this.color=color;}
        protected void onDraw(Canvas canvas){
            paint.setTextSize(sp(12));paint.setTypeface(Typeface.DEFAULT);paint.setStyle(Paint.Style.FILL);
            float reserve=paint.measureText(WeeklyReport.duration(report.current.total));
            reserve=Math.max(reserve,paint.measureText(label))+dp(9);
            float width=Math.max(dp(12),getWidth()-reserve),length=Math.max(dp(2),width*value/max),y=getHeight()/2f;
            paint.setColor(color);canvas.drawRoundRect(0,y-dp(2),length,y+dp(2),dp(2),dp(2),paint);
            paint.setColor(ink);paint.setTextAlign(Paint.Align.LEFT);canvas.drawText(label,length+dp(8),y-(paint.ascent()+paint.descent())/2,paint);
        }
    }

    private final class Ring extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Ring(){super(WeeklyReportView.this.getContext());setContentDescription("本周使用总时长："+WeeklyReport.duration(report.current.total));
            for(int i=0;i<Math.min(6,report.current.byTime.size());i++)icon(report.current.byTime.get(i).pkg);}
        protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,cy=getHeight()/2f;
            int iconSize=dp(18),edge=dp(3);
            float radius=RingIconLayout.radius(getWidth(),getHeight(),Math.min(getWidth(),getHeight())/2f-dp(45),dp(24),iconSize,edge);
            float stroke=dp(9);RectF oval=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(line);canvas.drawOval(oval,paint);
            List<UsageEngine.App> apps=report.current.byTime;int count=Math.min(6,apps.size());
            long remaining=report.current.total;float angle=-90;
            List<PointF> occupied=new ArrayList<>();
            for(int i=0;i<count+(apps.size()>6?1:0);i++){
                boolean other=i==count;long amount=other?remaining:apps.get(i).millis;remaining-=amount;
                float sweep=report.current.total==0?0:360f*amount/report.current.total;
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.BUTT);paint.setColor(other?0xffb3b8bc:color(apps.get(i).pkg));
                float gap=Math.min(2,sweep*.15f);canvas.drawArc(oval,angle+gap/2,Math.max(0,sweep-gap),false,paint);
                double mid=Math.toRadians(angle+sweep/2);float orbit=radius+dp(24);
                PointF center=new PointF(cx+(float)Math.cos(mid)*orbit,cy+(float)Math.sin(mid)*orbit);
                // Move crowded tiny-segment icons farther out without clipping their bounds.
                for(PointF prior:occupied)if(Math.hypot(center.x-prior.x,center.y-prior.y)<dp(25)){
                    orbit+=dp(20);center.set(cx+(float)Math.cos(mid)*orbit,cy+(float)Math.sin(mid)*orbit);
                }
                center.x=RingIconLayout.clampCenter(center.x,getWidth(),iconSize,edge);center.y=RingIconLayout.clampCenter(center.y,getHeight(),iconSize,edge);occupied.add(center);
                if(other){paint.setStyle(Paint.Style.FILL);paint.setColor(muted);for(int k=-1;k<=1;k++)canvas.drawCircle(center.x+k*dp(5),center.y,dp(1.7f),paint);}
                else {Drawable drawable=listIcon(apps.get(i).pkg);int left=Math.round(center.x-iconSize/2f),top=Math.round(center.y-iconSize/2f);drawable.setBounds(left,top,left+iconSize,top+iconSize);drawable.draw(canvas);}
                angle+=sweep;
            }
            paint.setStyle(Paint.Style.FILL);paint.setColor(ink);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(sp(23));
            String total=report.current.knownDays==0?"暂无记录":WeeklyReport.duration(report.current.total);
            while(paint.measureText(total)>radius*1.72f && paint.getTextSize()>sp(12))paint.setTextSize(paint.getTextSize()-1);
            canvas.drawText(total,cx,cy-(paint.ascent()+paint.descent())/2,paint);
        }
    }

    private final class WeekChart extends FrameLayout {
        final WeeklyReport.Scale scale=new WeeklyReport.Scale(report.days);
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Plot plot;
        final View[] targets=new View[7];
        int selected=-1;
        float left,right,top,bottom,step;
        WeekChart(){
            super(WeeklyReportView.this.getContext());setClipChildren(false);
            plot=new Plot();addView(plot,new FrameLayout.LayoutParams(-1,-1));
            for(int i=0;i<7;i++){
                final int index=i;UsageEngine.Day day=report.days.get(i);
                if(day.date.equals(selectedDate))selected=i;
                View target=new View(getContext());target.setContentDescription(dateLabel(i)+"，"+report.dayValue(i));target.setFocusable(true);
                target.setOnClickListener(v->{selected=index;actions.selectDay(day.date);plot.invalidate();target.announceForAccessibility(target.getContentDescription());});
                targets[i]=target;addView(target,new FrameLayout.LayoutParams(1,1));
            }
        }
        String dateLabel(int index){return LocalDate.parse(report.days.get(index).date).format(DateTimeFormatter.ofPattern("MM/dd"));}
        void geometry(){
            paint.setTextSize(sp(10));left=paint.measureText((scale.maximum/WeeklyReport.HOUR)+"时")+dp(7);right=getWidth()-dp(3);
            top=dp(42);bottom=getHeight()-dp(30);step=(right-left)/7;
        }
        protected void onMeasure(int widthSpec,int heightSpec){
            super.onMeasure(widthSpec,heightSpec);
            paint.setTextSize(sp(10));
            float inset=paint.measureText((scale.maximum/WeeklyReport.HOUR)+"时")+dp(7);
            int columnWidth=Math.max(1,(int)Math.ceil((getMeasuredWidth()-dp(3)-inset)/7f));
            int columnHeight=Math.max(1,getMeasuredHeight()-dp(42));
            for(View target:targets)target.measure(MeasureSpec.makeMeasureSpec(columnWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(columnHeight,MeasureSpec.EXACTLY));
        }
        protected void onLayout(boolean changed,int l,int t,int r,int b){
            super.onLayout(changed,l,t,r,b);geometry();
            for(int i=0;i<7;i++){
                targets[i].layout(Math.round(left+i*step),Math.round(top),Math.round(left+(i+1)*step),getHeight());
            }
        }
        public boolean dispatchTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                geometry();
                if(WeeklyReport.Scale.hit(event.getX(),left,right,7)<0)return false;
            }
            return super.dispatchTouchEvent(event);
        }
        final class Plot extends View {
            Plot(){super(WeeklyReportView.this.getContext());setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
            protected void onDraw(Canvas canvas){
                geometry();paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(sp(10));paint.setStyle(Paint.Style.FILL);paint.setPathEffect(null);
                for(long tick:scale.ticks){
                    float y=scale.y(tick,top,bottom);paint.setTextAlign(Paint.Align.RIGHT);paint.setColor(muted);
                    canvas.drawText(tick/WeeklyReport.HOUR+"时",left-dp(6),y-(paint.ascent()+paint.descent())/2,paint);
                    paint.setColor(line);paint.setStrokeWidth(dp(.6f));paint.setPathEffect(tick==0?null:new DashPathEffect(new float[]{dp(3),dp(2)},0));
                    canvas.drawLine(left,y,right,y,paint);paint.setPathEffect(null);
                }
                for(int i=0;i<7;i++){
                    UsageEngine.Day day=report.days.get(i);float center=left+(i+.5f)*step;
                    boolean future=LocalDate.parse(day.date).isAfter(report.today),known=WeeklyReport.known(day);
                    paint.setStyle(Paint.Style.FILL);paint.setColor(i==selected?SELECTED:ACCENT);
                    float y=scale.y(day.total,top,bottom),padding=dp(2.5f);
                    if(!future && known && day.total>0)canvas.drawRoundRect(left+i*step+padding,y,left+(i+1)*step-padding,bottom,dp(2),dp(2),paint);
                    paint.setTextSize(sp(9));paint.setTextAlign(Paint.Align.CENTER);paint.setColor(i==selected?SELECTED:muted);
                    if(future || !known)canvas.drawText(future?"未到":"无记录",center,bottom-dp(7),paint);
                    paint.setTextSize(sp(10));canvas.drawText(dateLabel(i),center,bottom+dp(19),paint);
                }
                RectF bubble=selected<0?null:bubbleBounds();
                if(report.current.knownDays>0){
                    float y=scale.y(report.current.average,top,bottom);paint.setColor(muted);paint.setStrokeWidth(dp(1));paint.setPathEffect(new DashPathEffect(new float[]{dp(3),dp(2)},0));
                    canvas.drawLine(left,y,right,y,paint);paint.setPathEffect(null);
                    String label="日均 "+WeeklyReport.duration(report.current.average);paint.setTextSize(sp(10));paint.setTextAlign(Paint.Align.RIGHT);
                    float labelWidth=paint.measureText(label)+dp(6),baseline=y-dp(5);
                    RectF rect=new RectF(right-labelWidth,baseline+paint.ascent()-dp(2),right,baseline+paint.descent()+dp(2));
                    if(bubble!=null && RectF.intersects(rect,bubble)){
                        rect.offsetTo(left,rect.top);
                        if(RectF.intersects(rect,bubble)){
                            baseline=top-dp(7);rect.offsetTo(right-labelWidth,baseline+paint.ascent()-dp(2));
                        }
                    }
                    paint.setColor(card);canvas.drawRoundRect(rect,dp(2),dp(2),paint);paint.setColor(muted);canvas.drawText(label,rect.right-dp(2),baseline,paint);
                }
                if(bubble!=null){
                    paint.setStyle(Paint.Style.FILL);paint.setColor(card);canvas.drawRoundRect(bubble,dp(9),dp(9),paint);
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.7f));paint.setColor(line);canvas.drawRoundRect(bubble,dp(9),dp(9),paint);
                    paint.setStyle(Paint.Style.FILL);paint.setColor(ink);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(sp(12));
                    canvas.drawText(report.dayValue(selected),bubble.centerX(),bubble.centerY()-(paint.ascent()+paint.descent())/2,paint);
                }
            }
            RectF bubbleBounds(){
                paint.setTextSize(sp(12));float width=Math.min(getWidth()-dp(4),paint.measureText(report.dayValue(selected))+dp(20));
                float x=left+(selected+.5f)*step-width/2;x=Math.max(dp(2),Math.min(getWidth()-dp(2)-width,x));
                float height=Math.max(dp(30),paint.descent()-paint.ascent()+dp(12)),barTop=scale.y(report.days.get(selected).total,top,bottom),y=Math.max(dp(4),barTop-height-dp(7));
                return new RectF(x,y,x+width,y+height);
            }
        }
    }
}
