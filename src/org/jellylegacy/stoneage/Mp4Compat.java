package org.jellylegacy.stoneage;

import java.io.*;
import java.util.*;

/** Repack fragmented server MP4 into classic local MP4 without decoding video.
 * Sample metadata is spooled to disk; media copies use one 16 KB buffer. */
public final class Mp4Compat {
    private static final class Box {long at,size;int header;String type;long data(){return at+header;}long end(){return at+size;}}
    private static final class Track {
        int id,scale,count,defaultDuration,defaultSize,defaultFlags;long duration,chunk;
        Box box;File indexFile;RandomAccessFile index;
    }
    private final RandomAccessFile in;private RandomAccessFile out;private final File output;
    private final LinkedHashMap<Integer,Track> tracks=new LinkedHashMap<Integer,Track>();
    private final byte[] buffer=new byte[16384];private Box ftyp,moov;private int movieScale;
    private Mp4Compat(File input,File target)throws IOException{in=new RandomAccessFile(input,"r");output=target;}
    public static boolean convert(File input,File target)throws IOException{
        Mp4Compat m=new Mp4Compat(input,target);boolean finished=false;try{boolean converted=m.run();finished=true;return converted;}
        finally{m.in.close();if(m.out!=null)m.out.close();for(Track t:m.tracks.values()){if(t.index!=null)t.index.close();if(t.indexFile!=null)t.indexFile.delete();}if(!finished)target.delete();}
    }
    private Box box(long at,long limit)throws IOException{in.seek(at);Box b=new Box();b.at=at;b.size=in.readInt()&0xffffffffL;byte[] type=new byte[4];in.readFully(type);b.type=new String(type,"US-ASCII");b.header=8;if(b.size==1){b.size=in.readLong();b.header=16;}else if(b.size==0)b.size=limit-at;if(b.size<b.header||at+b.size>limit)throw new IOException("Invalid MP4 box");return b;}
    private ArrayList<Box> children(Box parent)throws IOException{ArrayList<Box> boxes=new ArrayList<Box>();for(long at=parent.data();at<parent.end();){Box b=box(at,parent.end());boxes.add(b);at=b.end();if(boxes.size()>128)throw new IOException("Too many MP4 boxes");}return boxes;}
    private int integer(long at)throws IOException{in.seek(at);return in.readInt();}
    private int version(Box b)throws IOException{in.seek(b.data());return in.readUnsignedByte();}
    private void loadTrack(Box trak)throws IOException{
        Track t=new Track();t.box=trak;
        for(Box b:children(trak)){if(b.type.equals("tkhd"))t.id=integer(b.data()+(version(b)==1?20:12));if(b.type.equals("mdia"))for(Box md:children(b))if(md.type.equals("mdhd"))t.scale=integer(md.data()+(version(md)==1?20:12));}
        if(t.id<=0||t.scale<=0)throw new IOException("Invalid MP4 track");
        t.indexFile=new File(output.getParentFile(),"mp4-"+t.id+"-"+UUID.randomUUID()+".index");t.index=new RandomAccessFile(t.indexFile,"rw");tracks.put(t.id,t);
    }
    private boolean run()throws IOException{
        boolean fragmented=false;
        for(long at=0;at<in.length();){Box b=box(at,in.length());if(b.type.equals("ftyp"))ftyp=b;if(b.type.equals("moov"))moov=b;if(b.type.equals("moof"))fragmented=true;at=b.end();}
        if(!fragmented)return false;if(ftyp==null||moov==null)throw new IOException("Missing MP4 headers");
        for(Box b:children(moov)){if(b.type.equals("trak"))loadTrack(b);if(b.type.equals("mvhd"))movieScale=integer(b.data()+(version(b)==1?20:12));}
        if(tracks.size()>3||movieScale<=0)throw new IOException("Unsupported MP4 track layout");
        for(Box b:children(moov))if(b.type.equals("mvex"))for(Box x:children(b))if(x.type.equals("trex")){Track t=tracks.get(integer(x.data()+4));if(t!=null){t.defaultDuration=integer(x.data()+12);t.defaultSize=integer(x.data()+16);t.defaultFlags=integer(x.data()+20);}}
        for(long at=0;at<in.length();){Box b=box(at,in.length());if(b.type.equals("moof"))fragment(b);at=b.end();}
        long mediaBytes=0;for(Track t:tracks.values()){if(t.count==0)throw new IOException("Empty MP4 track");t.index.seek(0);for(int n=0;n<t.count;n++){t.index.readLong();mediaBytes+=t.index.readInt();t.index.skipBytes(12);}}
        if(mediaBytes+8>0xffffffffL)throw new IOException("MP4 too large");
        out=new RandomAccessFile(output,"rw");out.setLength(0);copy(ftyp.at,ftyp.size);out.writeInt((int)(mediaBytes+8));out.writeBytes("mdat");
        for(Track t:tracks.values()){t.chunk=out.getFilePointer();t.index.seek(0);for(int n=0;n<t.count;n++){long location=t.index.readLong();int size=t.index.readInt();t.index.skipBytes(12);copy(location,size);}}
        writeMoov();return true;
    }
    private void fragment(Box moof)throws IOException{
        for(Box traf:children(moof))if(traf.type.equals("traf")){
            Track track=null;long base=moof.at,dataEnd=-1;int dur=0,size=0,flags=0;
            ArrayList<Box> boxes=children(traf);
            for(Box b:boxes)if(b.type.equals("tfhd")){in.seek(b.data());int fields=in.readInt()&0xffffff;track=tracks.get(in.readInt());if(track==null)throw new IOException("Unknown MP4 track");dur=track.defaultDuration;size=track.defaultSize;flags=track.defaultFlags;if((fields&1)!=0)base=in.readLong();if((fields&2)!=0)in.readInt();if((fields&8)!=0)dur=in.readInt();if((fields&16)!=0)size=in.readInt();if((fields&32)!=0)flags=in.readInt();}
            if(track==null)throw new IOException("Missing track fragment header");
            for(Box b:boxes)if(b.type.equals("trun")){
                in.seek(b.data());int fields=in.readInt()&0xffffff,count=in.readInt();if(count<0||count>100000)throw new IOException("Invalid sample count");long data=(fields&1)!=0?base+in.readInt():dataEnd;
                if(data<0)throw new IOException("Missing MP4 sample offset");int firstFlags=(fields&4)!=0?in.readInt():flags;
                for(int n=0;n<count;n++){int d=(fields&0x100)!=0?in.readInt():dur,s=(fields&0x200)!=0?in.readInt():size,f=(fields&0x400)!=0?in.readInt():n==0?firstFlags:flags,composition=(fields&0x800)!=0?in.readInt():0;
                    if(d<=0||s<=0||data<0||data+s>in.length()||composition!=0)throw new IOException("Unsupported MP4 sample; use baseline video without B frames");
                    track.index.writeLong(data);track.index.writeInt(s);track.index.writeInt(d);track.index.writeInt(composition);track.index.writeInt(f);track.count++;track.duration+=d;data+=s;
                }dataEnd=data;
            }
        }
    }
    private void copy(long at,long length)throws IOException{in.seek(at);while(length>0){if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled MP4 preparation");int n=in.read(buffer,0,(int)Math.min(buffer.length,length));if(n<0)throw new EOFException();out.write(buffer,0,n);length-=n;}}
    private long begin(String type)throws IOException{long at=out.getFilePointer();out.writeInt(0);out.writeBytes(type);return at;}
    private void end(long at)throws IOException{long position=out.getFilePointer();if(position-at>0xffffffffL)throw new IOException("MP4 box too large");out.seek(at);out.writeInt((int)(position-at));out.seek(position);}
    private void full(String type)throws IOException{out.writeInt(0);out.writeBytes(type);out.writeInt(0);}
    private void timed(Box b,long duration)throws IOException{
        int version=version(b);int offset=b.type.equals("tkhd")?(version==1?28:20):(version==1?24:16);long at=out.getFilePointer();copy(b.at,b.size);long finish=out.getFilePointer();out.seek(at+b.header+offset);if(version==1)out.writeLong(duration);else{if(duration>0xffffffffL)throw new IOException("MP4 duration too large");out.writeInt((int)duration);}out.seek(finish);
    }
    private void writeMoov()throws IOException{
        long at=begin("moov"),duration=0;for(Track t:tracks.values())duration=Math.max(duration,t.duration*movieScale/t.scale);
        for(Box b:children(moov)){if(b.type.equals("mvex"))continue;if(b.type.equals("mvhd"))timed(b,duration);else if(b.type.equals("trak")){Track track=null;for(Track t:tracks.values())if(t.box.at==b.at)track=t;rewrite(b,track);}else copy(b.at,b.size);}end(at);
    }
    private void rewrite(Box box,Track t)throws IOException{
        if(box.type.equals("tkhd")){timed(box,t.duration*movieScale/t.scale);return;}if(box.type.equals("mdhd")){timed(box,t.duration);return;}if(box.type.equals("stbl")){sampleTable(box,t);return;}
        if(box.type.equals("trak")||box.type.equals("mdia")||box.type.equals("minf")){long at=begin(box.type);for(Box b:children(box))rewrite(b,t);end(at);}else copy(box.at,box.size);
    }
    private void sampleTable(Box old,Track t)throws IOException{
        long stbl=begin("stbl");for(Box b:children(old))if(b.type.equals("stsd"))copy(b.at,b.size);
        int runs=0,last=-1;for(int n=0;n<t.count;n++){t.index.seek(n*24L+12);int duration=t.index.readInt();if(duration!=last){runs++;last=duration;}}
        long stts=begin("stts");out.writeInt(0);out.writeInt(runs);int run=0;last=-1;
        for(int n=0;n<t.count;n++){t.index.seek(n*24L+12);int d=t.index.readInt();if(d!=last&&run>0){out.writeInt(run);out.writeInt(last);run=0;}last=d;run++;}if(run>0){out.writeInt(run);out.writeInt(last);}end(stts);
        long stsc=begin("stsc");out.writeInt(0);out.writeInt(1);out.writeInt(1);out.writeInt(t.count);out.writeInt(1);end(stsc);
        long stsz=begin("stsz");out.writeInt(0);out.writeInt(0);out.writeInt(t.count);for(int n=0;n<t.count;n++){t.index.seek(n*24L+8);out.writeInt(t.index.readInt());}end(stsz);
        long stco=begin("stco");out.writeInt(0);out.writeInt(1);out.writeInt((int)t.chunk);end(stco);
        int keys=0;for(int n=0;n<t.count;n++){t.index.seek(n*24L+20);if((t.index.readInt()&0x10000)==0)keys++;}
        if(keys<t.count){long stss=begin("stss");out.writeInt(0);out.writeInt(keys);for(int n=0;n<t.count;n++){t.index.seek(n*24L+20);if((t.index.readInt()&0x10000)==0)out.writeInt(n+1);}end(stss);}end(stbl);
    }
}
