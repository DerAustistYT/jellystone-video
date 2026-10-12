package org.jellylegacy.stoneage;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.media.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

/** Progressive native playback when compatible; small transcoded chunks otherwise. */
public class VideoPlayer extends Activity implements SurfaceHolder.Callback {
    private final Handler ui=new Handler();
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private MediaPlayer player;private Api api;private DirectProxy proxy;
    private SurfaceView surface;private FrameLayout screen;private LinearLayout controls;
    private TextView status,time;private SeekBar seek;private Button toggle;
    private String id,name,directUrl;private File storage,whole;
    private Api.Clip current,next;
    private long total,offset,resume,nextOffset;
    private volatile boolean dead;private volatile int generation;
    private boolean ready,direct,waiting,pending,fullFallback,seeking,started,prefetchFailed;
    private int reportClock;
    private Future<?> task;

    public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        api=new Api(this);id=getIntent().getStringExtra("id");name=getIntent().getStringExtra("title");resume=getIntent().getLongExtra("start",0);
        screen=new FrameLayout(this);screen.setBackgroundColor(Color.BLACK);surface=new SurfaceView(this);screen.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setText("Checking efficient playback…");screen.addView(status,new FrameLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(this);controls.setOrientation(1);controls.setBackgroundColor(Color.argb(215,0,0,0));time=new TextView(this);time.setTextColor(Color.WHITE);time.setGravity(Gravity.CENTER);controls.addView(time);
        seek=new SeekBar(this);controls.addView(seek);toggle=new Button(this);toggle.setText("Play / Pause");controls.addView(toggle);screen.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(screen);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);surface.getHolder().addCallback(this);
        View.OnTouchListener taps=new View.OnTouchListener(){public boolean onTouch(View v,MotionEvent e){if(e.getAction()==MotionEvent.ACTION_UP){if(controls.getVisibility()==View.VISIBLE)hideControls();else showControls();}return true;}};surface.setOnTouchListener(taps);screen.setOnTouchListener(taps);
        toggle.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(!ready)return;if(player.isPlaying())player.pause();else player.start();showControls();report("progress",position(),!player.isPlaying());}});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){seeking=true;ui.removeCallbacks(hide);}public void onProgressChanged(SeekBar s,int p,boolean user){if(user)time.setText(format(p));}public void onStopTrackingTouch(SeekBar s){seeking=false;showControls();if(!ready)return;long at=s.getProgress()*10000000L;if(direct||fullFallback)player.seekTo(s.getProgress()*1000);else if(at>=offset&&at<offset+current.ticks)player.seekTo((int)((at-offset)/10000L));else startChunks(at);}});
        storage=getCacheDir();if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())){File sd=new File(Environment.getExternalStorageDirectory(),"Android/data/"+getPackageName()+"/cache");if(sd.exists()||sd.mkdirs())storage=sd;}
    }
    public void surfaceCreated(SurfaceHolder h){if(started)return;started=true;final int token=++generation;
        task=jobs.submit(new Runnable(){public void run(){String candidate=null;long duration=0;try{duration=api.item(id).duration;candidate=api.directVideo(id);}catch(Exception ignored){}final String url=candidate;final long runtime=duration;ui.post(new Runnable(){public void run(){if(dead||token!=generation)return;total=runtime;seek.setMax((int)Math.min(Integer.MAX_VALUE,total/10000000L));if(url!=null){directUrl=url;playDirect();}else startChunks(resume);}});}});
    }
    private long position(){long pos=offset;try{if(ready)pos=(direct||fullFallback?0:offset)+player.getCurrentPosition()*10000L;}catch(Exception ignored){}return total>0?Math.min(total,pos):pos;}
    private void clearPlayer(){ready=false;ui.removeCallbacks(tick);ui.removeCallbacks(hide);if(player!=null){player.release();player=null;}}
    private void startChunks(long at){
        generation++;if(task!=null)task.cancel(true);api.cancelClip();clearPlayer();if(proxy!=null){proxy.close();proxy=null;}direct=false;waiting=true;pending=false;fullFallback=false;prefetchFailed=false;offset=at;
        if(current!=null){current.file.delete();current=null;}if(next!=null){next.file.delete();next=null;}status.setVisibility(View.VISIBLE);status.setText("Buffering the first ~20 seconds…");toggle.setEnabled(false);
        fetch(at,200000000L,true,generation);
    }
    private void fetch(final long at,final long length,final boolean first,final int token){
        pending=true;task=jobs.submit(new Runnable(){public void run(){if(dead||token!=generation)return;Api.Clip clip=null;Exception error=null;try{clip=api.clip(id,at,length,storage);}catch(Exception e){error=e;}final Api.Clip result=clip;final Exception failure=error;
            ui.post(new Runnable(){public void run(){if(dead||token!=generation){if(result!=null)result.file.delete();return;}pending=false;if(failure!=null){if(first&&!fullFallback){downloadFallback();}else {prefetchFailed=true;if(waiting){resume=at;downloadFallback();}}return;}
                if(first){current=result;offset=at;waiting=false;playFile(result.file,false);}else{next=result;nextOffset=at;if(waiting)advance();}
            }});
        }});
    }
    private void playDirect(){try{
        direct=true;offset=resume;status.setText("Starting progressive playback…");proxy=new DirectProxy(new DirectProxy.Opener(){public LegacyHttp.Response open(String range)throws IOException{return api.openMedia(directUrl,range);}});open(proxy.url(),null);
    }catch(Exception e){startChunks(resume);}}
    private void playFile(File file,boolean full){fullFallback=full;direct=false;try{open(null,file);}catch(Exception e){status.setVisibility(View.VISIBLE);status.setText(e.getMessage());}}
    private void open(String url,File file)throws Exception{
        clearPlayer();player=new MediaPlayer();player.setAudioStreamType(AudioManager.STREAM_MUSIC);player.setDisplay(surface.getHolder());if(url!=null)player.setDataSource(url);else player.setDataSource(file.getAbsolutePath());player.setScreenOnWhilePlaying(true);
        final int token=generation;
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){if(dead||token!=generation)return;ready=true;fit(p.getVideoWidth(),p.getVideoHeight());if(total<=0&&fullFallback)total=p.getDuration()*10000L;seek.setMax((int)Math.min(Integer.MAX_VALUE,total/10000000L));if((direct||fullFallback)&&resume>0)p.seekTo((int)Math.min(Integer.MAX_VALUE,resume/10000L));p.start();toggle.setEnabled(true);status.setVisibility(View.GONE);if(controls.getVisibility()==View.VISIBLE)showControls();ui.post(tick);report("start",position(),false);
            if(!direct&&!fullFallback&&current!=null&&!current.end&&(total<=0||offset+current.ticks<total-1000000L)){nextOffset=offset+current.ticks;fetch(nextOffset,450000000L,false,generation);}
        }});
        player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener(){public void onVideoSizeChanged(MediaPlayer p,int w,int h){fit(w,h);}});
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){if(direct||fullFallback||current.end||(total>0&&offset+current.ticks>=total-1000000L)){report("stop",total>0?total:position(),false);ready=false;finish();}else{ready=false;waiting=true;ui.removeCallbacks(tick);if(next!=null)advance();else if(prefetchFailed){resume=offset+current.ticks;downloadFallback();}else{status.setVisibility(View.VISIBLE);status.setText("Buffering next part…");if(!pending)fetch(offset+current.ticks,450000000L,false,generation);}}}});
        player.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){long pos=position();ready=false;ui.removeCallbacks(tick);if(direct){resume=pos;startChunks(pos);}else if(!fullFallback){resume=pos;downloadFallback();}else{status.setVisibility(View.VISIBLE);status.setText("This phone could not decode the video ("+what+"/"+extra+").");}return true;}});
        player.prepareAsync();
    }
    private void advance(){if(next==null)return;clearPlayer();if(current!=null)current.file.delete();current=next;next=null;offset=nextOffset;waiting=false;playFile(current.file,false);}
    private void downloadFallback(){
        generation++;if(task!=null)task.cancel(true);api.cancelClip();clearPlayer();if(current!=null){current.file.delete();current=null;}if(next!=null){next.file.delete();next=null;}fullFallback=true;pending=false;direct=false;final int token=generation;
        status.setVisibility(View.VISIBLE);status.setText("Server/decoder requires compatibility download…");whole=new File(storage,"compatibility-video.mp4");
        task=jobs.submit(new Runnable(){public void run(){Exception error=null;try{api.download(api.video(id),whole,new Api.Progress(){long last;public void update(final long bytes,long length){long now=System.currentTimeMillis();if(now-last<1000)return;last=now;ui.post(new Runnable(){public void run(){if(!dead&&token==generation)status.setText("Compatibility download: "+bytes/1048576+" MB");}});}});File converted=new File(storage,"compatibility-classic.mp4");if(Mp4Compat.convert(whole,converted)){whole.delete();whole=converted;}}catch(Exception e){error=e;}final Exception failure=error;ui.post(new Runnable(){public void run(){if(dead||token!=generation){if(whole!=null)whole.delete();return;}if(failure!=null){status.setText(failure.getMessage());return;}playFile(whole,true);}});}});
    }
    private void fit(int w,int h){int width=screen.getWidth(),height=screen.getHeight();if(w<=0||h<=0||width<=0||height<=0)return;double scale=Math.min((double)width/w,(double)height/h);surface.setLayoutParams(new FrameLayout.LayoutParams((int)(w*scale),(int)(h*scale),Gravity.CENTER));}
    private String format(long seconds){return(seconds/3600>0?seconds/3600+":":"")+String.format(java.util.Locale.US,seconds/3600>0?"%02d:%02d":"%d:%02d",seconds/60%60,seconds%60);}
    private final Runnable tick=new Runnable(){public void run(){if(dead||!ready)return;long pos=position();if(!seeking)seek.setProgress((int)(pos/10000000L));time.setText(format(pos/10000000L)+" / "+format(total/10000000L));if(++reportClock%15==0)report("progress",pos,!player.isPlaying());ui.postDelayed(this,1000);}};
    private final Runnable hide=new Runnable(){public void run(){if(ready&&!seeking&&player!=null&&player.isPlaying())hideControls();}};
    private void showControls(){controls.setVisibility(View.VISIBLE);ui.removeCallbacks(hide);if(ready&&player.isPlaying())ui.postDelayed(hide,3000);}
    private void hideControls(){ui.removeCallbacks(hide);controls.setVisibility(View.GONE);}
    private void report(final String event,final long pos,final boolean paused){new Thread(new Runnable(){public void run(){try{api.report(event,id,pos,paused);}catch(Exception ignored){}}},"stone-video-report").start();}
    public void surfaceChanged(SurfaceHolder h,int format,int w,int ht){if(ready)fit(player.getVideoWidth(),player.getVideoHeight());}
    public void surfaceDestroyed(SurfaceHolder h){}
    protected void onStop(){super.onStop();if(!isFinishing())finish();}
    protected void onDestroy(){if(ready)report("stop",position(),true);dead=true;generation++;if(task!=null)task.cancel(true);api.cancelClip();clearPlayer();if(proxy!=null)proxy.close();jobs.shutdownNow();if(current!=null)current.file.delete();if(next!=null)next.file.delete();if(whole!=null)whole.delete();super.onDestroy();}
}
