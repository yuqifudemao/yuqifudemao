package cn.bookpdf.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.print.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final int INK=Color.rgb(27,49,47),GREEN=Color.rgb(20,125,114);
    private LinearLayout root,previewBox; private WebView web;
    private TextView status,fileName; private Button pick,export,refresh;
    private Spinner size,paper,margin,encoding;
    private Uri selected; private String selectedName="",content="";
    private boolean busy=false,ready=false;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private int generation=0;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);build();}
    private int dp(int v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    private TextView text(String s,int sp,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);return t;}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void build(){
        getWindow().setStatusBarColor(Color.rgb(244,247,243));getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(14),dp(20),dp(8));root.setBackgroundColor(Color.rgb(244,247,243));
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(20),dp(14)+insets.getSystemWindowInsetTop(),dp(20),dp(8)+insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        setContentView(root);
        TextView title=text("书页 PDF",28,INK);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        TextView sub=text("把电子书，变成随处可读的 PDF",13,0xff657773);sub.setPadding(0,dp(5),0,dp(16));root.addView(sub);
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(16),dp(12),dp(16),dp(12));card.setBackground(bg(Color.WHITE,16));root.addView(card);
        fileName=text("还没有选择书籍",16,INK);fileName.setSingleLine(true);fileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);card.addView(fileName);
        TextView formats=text("EPUB · TXT · HTML · DOCX  /  最大 40MB",11,0xff657773);formats.setPadding(0,dp(5),0,dp(6));card.addView(formats);
        pick=button("＋  选择本地书籍",GREEN,Color.WHITE);card.addView(pick);pick.setOnClickListener(v->choose());
        LinearLayout settings=new LinearLayout(this);settings.setOrientation(0);settings.setPadding(0,dp(12),0,0);root.addView(settings);
        size=setting(settings,"字号",new String[]{"12 pt","14 pt","16 pt","18 pt"},1);
        paper=setting(settings,"纸张",new String[]{"A4","A5"},0);
        margin=setting(settings,"页边距",new String[]{"窄","标准","宽"},1);
        encoding=setting(settings,"文本编码",new String[]{"自动","UTF-8","GB18030","UTF-16LE"},0);
        refresh=button("应用设置 / 刷新预览",0xffe1eeea,GREEN);root.addView(refresh);refresh.setOnClickListener(v->{if(selected!=null)load();});
        status=text("文件只在手机本地处理，无需联网。",12,0xff657773);status.setPadding(0,dp(8),0,dp(8));root.addView(status);
        previewBox=new LinearLayout(this);previewBox.setOrientation(1);previewBox.setBackground(bg(Color.WHITE,12));root.addView(previewBox,new LinearLayout.LayoutParams(-1,0,1));
        web=new WebView(this);web.setBackgroundColor(Color.WHITE);
        web.getSettings().setJavaScriptEnabled(false);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setBlockNetworkLoads(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public void onPageFinished(WebView v,String url){if(!content.isEmpty()&&!busy){ready=true;export.setEnabled(true);status.setText("预览已就绪 · 导出时选择“另存为 PDF”");}}
        });
        previewBox.addView(web,new LinearLayout.LayoutParams(-1,-1));
        web.loadDataWithBaseURL(null,"<html><body style='color:#78908a;text-align:center;padding-top:70px;font-family:sans-serif'><h2>每一本书，都值得留存</h2><p>选一本书，预览会显示在这里</p></body></html>","text/html","UTF-8",null);
        export=button("导出 PDF",GREEN,Color.WHITE);export.setEnabled(false);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(52));ep.topMargin=dp(10);root.addView(export,ep);export.setOnClickListener(v->print());
    }
    private Button button(String label,int color,int fg){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(fg);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));return b;}
    private Spinner setting(LinearLayout row,String label,String[]choices,int position){LinearLayout cell=new LinearLayout(this);cell.setOrientation(1);row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));cell.addView(text(label,11,0xff657773));Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);s.setAdapter(a);s.setSelection(position);cell.addView(s);return s;}
    private void choose(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");try{startActivityForResult(i,10);}catch(ActivityNotFoundException e){error("手机缺少系统文件选择器。");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==10&&result==RESULT_OK&&data!=null){Uri uri=data.getData();if(uri==null)return;String name="";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}if(name.isEmpty()){error("无法读取文件名，请从“文件管理”中重新选择。");return;}selected=uri;selectedName=name;fileName.setText(name);load();}}
    private void load(){if(busy)return;busy=true;ready=false;content="";export.setEnabled(false);pick.setEnabled(false);refresh.setEnabled(false);status.setText("正在解析和排版，请稍候…");final int task=++generation;final String enc=encoding.getSelectedItem().toString();final Uri uri=selected;final String name=selectedName;
        worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法打开文件。");String parsed=BookParser.parse(in,name,enc);if(name.toLowerCase(Locale.ROOT).matches(".*\\.(html|htm|xhtml)$")){String plain=Html.fromHtml(parsed,Html.FROM_HTML_MODE_LEGACY).toString();parsed="<div style='white-space:pre-wrap'>"+BookParser.escape(plain)+"</div>";}final String result=parsed;runOnUiThread(()->{if(task!=generation||isFinishing()||isDestroyed())return;busy=false;pick.setEnabled(true);refresh.setEnabled(true);content=result;render();});}catch(Exception | OutOfMemoryError e){runOnUiThread(()->{if(task!=generation||isFinishing()||isDestroyed())return;busy=false;pick.setEnabled(true);refresh.setEnabled(true);error(e instanceof OutOfMemoryError?"书籍过大，请拆分后转换。":e.getMessage());});}});
    }
    private void render(){int font=new int[]{12,14,16,18}[size.getSelectedItemPosition()];String html="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:serif;color:#172a27;font-size:"+font+"pt;line-height:1.75;overflow-wrap:anywhere;margin:20px}p{margin:.6em 0}h1,h2,h3{line-height:1.3;page-break-after:avoid}img{max-width:100%;max-height:85vh;object-fit:contain}table{border-collapse:collapse;width:100%;font-size:.85em}td,th{border:1px solid #bbc9c4;padding:4px}pre{white-space:pre-wrap}.chapter+.chapter{break-before:page;page-break-before:always}@media print{body{margin:0;color:#000}}</style></head><body>"+content+"</body></html>";web.loadDataWithBaseURL(null,html,"text/html","UTF-8",null);}
    private void print(){if(!ready||busy)return;try{PrintManager manager=(PrintManager)getSystemService(PRINT_SERVICE);int m=new int[]{250,590,790}[margin.getSelectedItemPosition()];PrintAttributes attrs=new PrintAttributes.Builder().setMediaSize(paper.getSelectedItemPosition()==0?PrintAttributes.MediaSize.ISO_A4:PrintAttributes.MediaSize.ISO_A5).setMinMargins(new PrintAttributes.Margins(m,m,m,m)).setResolution(new PrintAttributes.Resolution("pdf","PDF",300,300)).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build();String title=selectedName.replaceFirst("\\.[^.]+$","");manager.print(title,web.createPrintDocumentAdapter(title),attrs);}catch(Exception ex){error("无法打开系统打印服务："+ex.getMessage());}}
    private void error(String message){String msg=message==null?"文件无法解析，请检查格式或文件是否损坏。":message;status.setText("处理失败，可重新选择文件。");new AlertDialog.Builder(this).setTitle("暂时无法转换").setMessage(msg).setPositiveButton("知道了",null).show();}
    @Override protected void onDestroy(){generation++;worker.shutdownNow();if(web!=null){previewBox.removeView(web);web.destroy();}super.onDestroy();}
}
