package org.jellylegacy.stoneage;
import android.app.*;
import android.content.*;
import android.os.*;
import android.media.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MusicService extends Service {
    private final Handler ui=new Handler();private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private Api api;private MediaPlayer player;private ArrayList<String> ids=new ArrayList<String>(),names=new ArrayList<String>();
    private int index,version,repeat;private boolean ready,dead;private File temporary;private PowerManager.WakeLock wake;
    public void onCreate(){super.onCreate();api=new Api(this);wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"JellyMusicStoneAge");wake.setReferenceCounted(false);}
    public void onStart(Intent i,int startId){if(i==null)return;String action=i.getStringExtra("action");
        if("Play".equals(action)){ids=i.getStringArrayListExtra("ids");names=i.getStringArrayListExtra("names");index=i.getIntExtra("index",0);if(ids!=null&&ids.size()>0)play();}
        else if("Play/Pause".equals(action)&&ready){if(player.isPlaying()){player.pause();releaseWake();notifyState("Paused");}else{holdWake();player.start();notifyState("Playing");}}
        else if("Next".equals(action)&&index+1<ids.size()){index++;play();}
        else if("Prev".equals(action)){if(index>0){index--;play();}else if(ready)player.seekTo(0);}
        else if("Repeat".equals(action)){repeat=(repeat+1)%3;notifyState(repeat==0?"Repeat off":repeat==1?"Repeat one":"Repeat all");}
        else if("Stop".equals(action))stopSelf();
    }
    private void holdWake(){if(!wake.isHeld())wake.acquire();}private void releaseWake(){if(wake.isHeld())wake.release();}
    private void play(){final int token=++version;ready=false;holdWake();if(player!=null){player.release();player=null;}if(temporary!=null){temporary.delete();temporary=null;}final String id=ids.get(index);notifyState("Loading");
        jobs.execute(new Runnable(){public void run(){if(dead||token!=version)return;File f=new File(getFilesDir(),id+".mp3");boolean cached=f.exists();Exception failure=null;try{if(!cached){f=new File(getCacheDir(),"song-"+token+".mp3");api.download(api.music(id),f,null);}}catch(Exception e){failure=e;}final File file=f;final boolean keep=cached;final Exception error=failure;ui.post(new Runnable(){public void run(){if(dead||token!=version){if(!keep)file.delete();return;}if(error!=null){notifyState("Error: "+error.getMessage());releaseWake();return;}if(!keep)temporary=file;prepare(file,token);}});}});
    }
    private void prepare(File file,final int token){try{player=new MediaPlayer();player.setAudioStreamType(AudioManager.STREAM_MUSIC);player.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
        long offset=0;RandomAccessFile raf=new RandomAccessFile(file,"r");try{if(raf.length()>10){byte[] h=new byte[10];raf.readFully(h);if(h[0]=='I'&&h[1]=='D'&&h[2]=='3'){long n=10L+((h[6]&127)<<21)+((h[7]&127)<<14)+((h[8]&127)<<7)+(h[9]&127)+((h[5]&16)!=0?10:0);if(n<raf.length())offset=n;}}}finally{raf.close();}FileInputStream in=new FileInputStream(file);try{player.setDataSource(in.getFD(),offset,file.length()-offset);}finally{in.close();}
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){if(dead||token!=version)return;ready=true;p.start();notifyState("Playing");}});
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){ready=false;if(repeat==1)play();else if(index+1<ids.size()){index++;play();}else if(repeat==2){index=0;play();}else stopSelf();}});
        player.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){ready=false;notifyState("Playback error "+what+"/"+extra);releaseWake();return true;}});player.prepareAsync();
    }catch(Exception e){notifyState("Error: "+e.getMessage());releaseWake();}}
    private void notifyState(String state){String name=names!=null&&index<names.size()?names.get(index):"JellyMusic";Intent open=new Intent(this,MusicActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,0);Notification n=new Notification(android.R.drawable.ic_media_play,name,System.currentTimeMillis());try{Notification.class.getMethod("setLatestEventInfo",Context.class,CharSequence.class,CharSequence.class,PendingIntent.class).invoke(n,this,name,state,pi);try{Service.class.getMethod("startForeground",Integer.TYPE,Notification.class).invoke(this,Integer.valueOf(19),n);}catch(Exception old){try{Service.class.getMethod("setForeground",Boolean.TYPE).invoke(this,Boolean.TRUE);}catch(Exception ignoredOld){}((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(19,n);}}catch(Exception ignored){}}
    public IBinder onBind(Intent i){return null;}
    public void onDestroy(){dead=true;version++;if(player!=null)player.release();if(temporary!=null)temporary.delete();releaseWake();jobs.shutdownNow();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(19);super.onDestroy();}
}
