package org.jellylegacy.stoneage;
import java.io.*;

/** Read complete MP4 fragments until enough video is buffered; never hold media in RAM. */
public final class ClipReader {
    public static final class Result {public long ticks,bytes;public boolean end;}
    private static final class Box {int offset,size,header;String type;int data(){return offset+header;}}
    private static int number(byte[] b,int n)throws IOException{if(n<0||n+4>b.length)throw new IOException("Incomplete MP4 metadata");return((b[n]&255)<<24)|((b[n+1]&255)<<16)|((b[n+2]&255)<<8)|(b[n+3]&255);}
    private static long wide(byte[] b,int n)throws IOException{return((long)number(b,n)<<32)|(number(b,n+4)&0xffffffffL);}
    private static Box box(byte[] b,int at,int limit)throws IOException{Box x=new Box();x.offset=at;long size=number(b,at)&0xffffffffL;x.header=8;if(size==1){size=wide(b,at+8);x.header=16;}if(size<x.header||size>Integer.MAX_VALUE||at+size>limit)throw new IOException("Invalid MP4 metadata box");x.size=(int)size;x.type=new String(b,at+4,4,"US-ASCII");return x;}
    private static int track(byte[] moov,int[] settings)throws IOException{
        int video=0;Box root=box(moov,0,moov.length);
        for(int at=root.data();at<root.size;){Box tr=box(moov,at,root.size);at+=tr.size;if(tr.type.equals("trak")){int id=0,scale=0;boolean isVideo=false;
            for(int p=tr.data();p<tr.offset+tr.size;){Box c=box(moov,p,tr.offset+tr.size);p+=c.size;if(c.type.equals("tkhd"))id=number(moov,c.data()+((moov[c.data()]&255)==1?20:12));if(c.type.equals("mdia"))for(int q=c.data();q<c.offset+c.size;){Box d=box(moov,q,c.offset+c.size);q+=d.size;if(d.type.equals("mdhd"))scale=number(moov,d.data()+((moov[d.data()]&255)==1?20:12));if(d.type.equals("hdlr"))isVideo="vide".equals(new String(moov,d.data()+8,4,"US-ASCII"));}}
            if(isVideo){video=id;settings[0]=scale;}
        }}
        for(int at=root.data();at<root.size;){Box c=box(moov,at,root.size);at+=c.size;if(c.type.equals("mvex"))for(int p=c.data();p<c.offset+c.size;){Box t=box(moov,p,c.offset+c.size);p+=t.size;if(t.type.equals("trex")&&number(moov,t.data()+4)==video)settings[1]=number(moov,t.data()+12);}}
        return video;
    }
    private static long duration(byte[] moof,int track,int fallback)throws IOException{
        long duration=0;Box root=box(moof,0,moof.length);
        for(int at=root.data();at<root.size;){Box traf=box(moof,at,root.size);at+=traf.size;if(!traf.type.equals("traf"))continue;int id=0,defaultDuration=fallback;
            for(int p=traf.data();p<traf.offset+traf.size;){Box t=box(moof,p,traf.offset+traf.size);p+=t.size;if(t.type.equals("tfhd")){int flags=number(moof,t.data())&0xffffff;id=number(moof,t.data()+4);int q=t.data()+8;if((flags&1)!=0)q+=8;if((flags&2)!=0)q+=4;if((flags&8)!=0)defaultDuration=number(moof,q);}}
            if(id!=track)continue;
            for(int p=traf.data();p<traf.offset+traf.size;){Box t=box(moof,p,traf.offset+traf.size);p+=t.size;if(!t.type.equals("trun"))continue;int flags=number(moof,t.data())&0xffffff,count=number(moof,t.data()+4),q=t.data()+8;if(count<0||count>100000)throw new IOException("Invalid fragment sample count");if((flags&1)!=0)q+=4;if((flags&4)!=0)q+=4;for(int n=0;n<count;n++){int d=defaultDuration;if((flags&0x100)!=0){d=number(moof,q);q+=4;}if(d<=0)throw new IOException("Missing frame duration");duration+=d;if((flags&0x200)!=0)q+=4;if((flags&0x400)!=0)q+=4;if((flags&0x800)!=0)q+=4;}}
        }return duration;
    }
    public static Result read(InputStream input,File file,long targetTicks)throws IOException{
        Result result=new Result();FileOutputStream output=new FileOutputStream(file);byte[] copy=new byte[16384];int video=0;int[] settings=new int[2];long clock=0;boolean complete=false;
        try{for(;;){if(Thread.currentThread().isInterrupted())throw new IOException("Buffering cancelled");byte[] header=new byte[16];int first=input.read();if(first<0){result.end=true;break;}header[0]=(byte)first;fill(input,header,1,7);long size=number(header,0)&0xffffffffL;int headerSize=8;if(size==1){fill(input,header,8,8);size=wide(header,8);headerSize=16;}String type=new String(header,4,4,"US-ASCII");if(size==0)throw new IOException("Server MP4 is not fragmented");if(size<headerSize||size>268435456L)throw new IOException("MP4 box exceeds buffer limits");output.write(header,0,headerSize);result.bytes+=headerSize;
            if(type.equals("moov")||type.equals("moof")){if(size>1048576L)throw new IOException("MP4 metadata too large");byte[] metadata=new byte[(int)size];System.arraycopy(header,0,metadata,0,headerSize);fill(input,metadata,headerSize,(int)size-headerSize);output.write(metadata,headerSize,(int)size-headerSize);result.bytes+=size-headerSize;if(type.equals("moov")){video=track(metadata,settings);if(video==0||settings[0]<=0)throw new IOException("Video track missing");}else clock+=duration(metadata,video,settings[1]);}
            else{long remaining=size-headerSize;while(remaining>0){if(Thread.currentThread().isInterrupted())throw new IOException("Buffering cancelled");int n=input.read(copy,0,(int)Math.min(copy.length,remaining));if(n<0)throw new EOFException("Incomplete MP4 fragment");output.write(copy,0,n);remaining-=n;result.bytes+=n;}}
            if(result.bytes>33554432L)throw new IOException("Video chunk exceeds 32 MB");result.ticks=settings[0]>0?clock*10000000L/settings[0]:0;
            if(type.equals("mdat")&&result.ticks>=targetTicks)break;
        }if(result.ticks<=0)throw new IOException("Server returned no playable video fragments");complete=true;return result;}finally{output.close();if(!complete)file.delete();}
    }
    private static void fill(InputStream in,byte[] b,int offset,int count)throws IOException{while(count>0){int n=in.read(b,offset,count);if(n<0)throw new EOFException("Incomplete MP4");offset+=n;count-=n;}}
    public static boolean fastStart(byte[] prefix){try{for(int at=0;at+8<=prefix.length;){long size=number(prefix,at)&0xffffffffL;String type=new String(prefix,at+4,4,"US-ASCII");if(type.equals("moov"))return true;if(type.equals("mdat")||type.equals("moof"))return false;if(size<8||size>prefix.length-at)return false;at+=(int)size;}return false;}catch(Exception e){return false;}}
}
