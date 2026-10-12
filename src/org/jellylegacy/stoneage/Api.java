package org.jellylegacy.stoneage;
import android.content.*;
import org.json.*;
import java.util.*;
import java.io.*;
import java.net.URLEncoder;

public class Api {
    private final SharedPreferences prefs;private final LegacyHttp http;private volatile LegacyHttp.Response activeClip;public void cancelClip(){LegacyHttp.Response r=activeClip;if(r!=null)r.abort();}
    public Api(Context c){prefs=c.getSharedPreferences("session",0);http=new LegacyHttp(c);}
    public String server(){return prefs.getString("server","");}
    public boolean signedIn(){return prefs.getString("token","").length()>0;}
    public String device(){String id=prefs.getString("device","");if(id.length()==0){id=UUID.randomUUID().toString();prefs.edit().putString("device",id).commit();}return id;}
    public void server(String s){s=s.trim();while(s.endsWith("/"))s=s.substring(0,s.length()-1);if(!s.startsWith("http://")&&!s.startsWith("https://"))s="https://"+s;prefs.edit().putString("server",s).remove("token").remove("user").commit();}
    public void logout(){prefs.edit().remove("token").remove("user").commit();}
    public Map<String,String> headers(){HashMap<String,String> h=new HashMap<String,String>();String token=prefs.getString("token","");String auth="MediaBrowser Client=\"JellyStone Video\", Device=\"Android legacy\", DeviceId=\""+device()+"\", Version=\"0.3.0\"";if(token.length()>0){auth+=", Token=\""+token.replace("\\","").replace("\"","")+"\"";h.put("X-Emby-Token",token);}h.put("Authorization",auth);h.put("X-Emby-Authorization",auth);return h;}
    public JSONObject json(String method,String path,JSONObject body)throws Exception{
        LegacyHttp.Response response=http.open(method,server()+path,headers(),body==null?null:body.toString().getBytes("UTF-8"));
        try{if(response.status<200||response.status>=300)throw new IOException("Server returned HTTP "+response.status);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=response.read(b))!=-1){if(out.size()+n>524288)throw new IOException("Server response too large; use smaller pages");out.write(b,0,n);}String text=out.toString("UTF-8");return text.length()==0?new JSONObject():new JSONObject(text);}finally{response.close();}
    }
    public JSONObject begin()throws Exception{return json("POST","/QuickConnect/Initiate",new JSONObject());}
    public boolean check(String secret)throws Exception{return json("GET","/QuickConnect/Connect?secret="+enc(secret),null).optBoolean("Authenticated");}
    public void finish(String secret)throws Exception{JSONObject result=json("POST","/Users/AuthenticateWithQuickConnect",new JSONObject().put("Secret",secret));String token=result.optString("AccessToken");JSONObject user=result.optJSONObject("User");if(token.length()==0||user==null)throw new IOException("Missing login session");prefs.edit().putString("token",token).putString("user",user.optString("Id")).commit();}
    public String user(){return prefs.getString("user","");}
    public ArrayList<Item> list(String type,String parent,String filter,String search,int page)throws Exception{
        String path="/Users/"+enc(user())+"/Items?Recursive="+(parent==null?"true":"false")+"&IncludeItemTypes="+enc(type)+"&StartIndex="+(page*30)+"&Limit=30&EnableTotalRecordCount=false&Fields=RunTimeTicks&SortBy="+((type.equals("Episode")||type.equals("Season")||parent!=null&&type.equals("Audio"))?"ParentIndexNumber,IndexNumber":"SortName")+"&SortOrder=Ascending";
        if(parent!=null)path+="&ParentId="+enc(parent);if(filter!=null)path+="&Filters="+enc(filter);if(search!=null&&search.length()>0)path+="&SearchTerm="+enc(search);
        JSONArray a=json("GET",path,null).optJSONArray("Items");ArrayList<Item> out=new ArrayList<Item>();if(a!=null)for(int n=0;n<a.length();n++)out.add(parse(a.getJSONObject(n)));return out;
    }
    public Item item(String id)throws Exception{return parse(json("GET","/Users/"+enc(user())+"/Items/"+enc(id),null));}
    private Item parse(JSONObject o){Item i=new Item();i.id=o.optString("Id");i.name=o.optString("Name","Untitled");i.type=o.optString("Type");i.duration=o.optLong("RunTimeTicks");i.overview=o.optString("Overview");i.info=i.type;JSONArray a=o.optJSONArray("Artists");if(a!=null&&a.length()>0)i.info=a.optString(0)+" · "+o.optString("Album");if(i.type.equals("Episode"))i.info=o.optString("SeriesName")+" · S"+o.optInt("ParentIndexNumber")+" E"+o.optInt("IndexNumber");if(i.duration>0)i.info+=" · "+(i.duration/600000000L)+" min";JSONObject u=o.optJSONObject("UserData");if(u!=null){i.resume=u.optLong("PlaybackPositionTicks");i.favorite=u.optBoolean("IsFavorite");i.watched=u.optBoolean("Played");}return i;}
    public void favorite(String id,boolean value)throws Exception{json(value?"POST":"DELETE","/Users/"+enc(user())+"/FavoriteItems/"+enc(id),null);}
    public void watched(String id,boolean value)throws Exception{json(value?"POST":"DELETE","/Users/"+enc(user())+"/PlayedItems/"+enc(id),null);}
    public String music(String id)throws Exception{return server()+"/Audio/"+enc(id)+"/universal?UserId="+enc(user())+"&DeviceId="+enc(device())+"&Container=mp3&AudioCodec=mp3&AudioBitRate=96000&MaxAudioChannels=2&MaxAudioSampleRate=44100&EnableRedirection=false";}
    public String video(String id)throws Exception{return server()+"/Videos/"+enc(id)+"/stream.mp4?DeviceId="+enc(device())+"&VideoCodec=h264&AudioCodec=aac&VideoBitRate=250000&AudioBitRate=48000&MaxWidth=320&MaxHeight=240&MaxFramerate=15&MaxAudioChannels=1&Profile=baseline&Level=30&AllowVideoStreamCopy=false&AllowAudioStreamCopy=false&EnableAutoStreamCopy=false&MaxVideoBitDepth=8&MaxRefFrames=1&Static=false";}
    public String directVideo(String id)throws Exception{
        JSONArray sources=json("GET","/Items/"+enc(id)+"/PlaybackInfo?UserId="+enc(user()),null).optJSONArray("MediaSources");
        if(sources==null)return null;
        for(int n=0;n<sources.length();n++){
            JSONObject source=sources.getJSONObject(n);String container=source.optString("Container").toLowerCase(Locale.US);
            if(!(container.equals("mp4")||container.equals("m4v")||container.equals("3gp")))continue;
            JSONArray streams=source.optJSONArray("MediaStreams");if(streams==null)continue;boolean video=false,audio=true;
            for(int j=0;j<streams.length();j++){JSONObject stream=streams.getJSONObject(j);String type=stream.optString("Type"),codec=stream.optString("Codec").toLowerCase(Locale.US),profile=stream.optString("Profile").toLowerCase(Locale.US);
                if(type.equals("Video")){int w=stream.optInt("Width"),h=stream.optInt("Height");video=w>0&&h>0&&w<=480&&h<=360&&stream.optDouble("RealFrameRate",30)<=30&&stream.optInt("BitDepth",8)<=8&&((codec.equals("h264")&&profile.indexOf("baseline")>=0)||(codec.equals("mpeg4")&&profile.indexOf("simple")>=0));}
                if(type.equals("Audio"))audio=audio&&codec.equals("aac")&&stream.optInt("Channels",2)<=2&&stream.optInt("SampleRate",48000)<=48000;
            }
            if(!video||!audio||source.optInt("Bitrate",1000001)>1000000)continue;
            String url=server()+"/Videos/"+enc(id)+"/stream.mp4?Static=true&MediaSourceId="+enc(source.optString("Id"));
            LegacyHttp.Response response=openMedia(url,"bytes=0-4095");try{if(response.status!=200&&response.status!=206)continue;ByteArrayOutputStream prefix=new ByteArrayOutputStream();byte[] b=new byte[1024];while(prefix.size()<4096){int count=response.read(b,0,Math.min(b.length,4096-prefix.size()));if(count<0)break;prefix.write(b,0,count);}if(ClipReader.fastStart(prefix.toByteArray()))return url;}finally{response.close();}
        }return null;
    }
    public LegacyHttp.Response openMedia(String address,String range)throws IOException{Map<String,String> h=headers();if(range!=null)h.put("Range",range);return http.open("GET",address,h,null);}
    public static class Clip {public File file;public long ticks;public boolean end;public long bytes;}
    public Clip clip(String id,long start,long length,File directory)throws Exception{
        String session=UUID.randomUUID().toString();String address=video(id)+"&StartTimeTicks="+start+"&PlaySessionId="+session;
        File raw=File.createTempFile("buffer-",".mp4",directory),classic=File.createTempFile("ready-",".mp4",directory);LegacyHttp.Response response=null;boolean success=false;
        try{response=openMedia(address,null);activeClip=response;if(response.status<200||response.status>=300)throw new IOException("Video buffering returned HTTP "+response.status);ClipReader.Result read=ClipReader.read(response,raw,length);response.close();response=null;if(!Mp4Compat.convert(raw,classic))throw new IOException("Server did not return fragmented MP4");Clip clip=new Clip();clip.file=classic;clip.ticks=read.ticks;clip.end=read.end;clip.bytes=read.bytes;success=true;return clip;}
        finally{activeClip=null;if(response!=null)try{response.close();}catch(Exception ignored){}raw.delete();if(!success)classic.delete();try{json("DELETE","/Videos/ActiveEncodings?DeviceId="+enc(device())+"&PlaySessionId="+enc(session),null);}catch(Exception ignored){}}
    }
    public long download(String address,File file,final Progress progress)throws Exception{
        LegacyHttp.Response r=http.open("GET",address,headers(),null);FileOutputStream out=null;long bytes=0;boolean complete=false;
        try{if(r.status<200||r.status>=300)throw new IOException("Download returned HTTP "+r.status);out=new FileOutputStream(file);byte[] b=new byte[8192];int n;while((n=r.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Download cancelled");bytes+=n;if(bytes>268435456L)throw new IOException("256 MB download limit reached; use the Android 4 edition for streaming video");out.write(b,0,n);if(progress!=null)progress.update(bytes,r.length);}complete=true;return bytes;}finally{if(out!=null)out.close();r.close();if(!complete)file.delete();}
    }
    public void report(String event,String id,long ticks,boolean paused)throws Exception{JSONObject body=new JSONObject().put("ItemId",id).put("PositionTicks",ticks).put("IsPaused",paused).put("PlayMethod","Transcode").put("CanSeek",true);json("POST","/Sessions/Playing"+(event.equals("start")?"":event.equals("stop")?"/Stopped":"/Progress"),body);}
    public interface Progress {void update(long bytes,long length);}
    private String enc(String value)throws Exception{return URLEncoder.encode(value==null?"":value,"UTF-8");}
}
