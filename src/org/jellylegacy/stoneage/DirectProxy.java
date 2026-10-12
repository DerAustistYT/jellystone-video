package org.jellylegacy.stoneage;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated HTTPS upstream; old native player reads HTTP only on loopback. */
public final class DirectProxy {
    public interface Opener { LegacyHttp.Response open(String range)throws IOException; }
    private final ServerSocket server;private final Opener opener;private final String key=UUID.randomUUID().toString();
    private final Set<Socket> clients=Collections.synchronizedSet(new HashSet<Socket>());
    private final Set<LegacyHttp.Response> responses=Collections.synchronizedSet(new HashSet<LegacyHttp.Response>());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(4));private volatile boolean closed;
    public DirectProxy(Opener source)throws IOException{opener=source;server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));new Thread(new Runnable(){public void run(){while(!closed)try{final Socket socket=server.accept();socket.setSoTimeout(15000);clients.add(socket);try{workers.execute(new Runnable(){public void run(){serve(socket);}});}catch(RejectedExecutionException full){clients.remove(socket);socket.close();}}catch(IOException ignored){if(closed)return;}}},"stone-direct-proxy").start();}
    public String url(){return"http://127.0.0.1:"+server.getLocalPort()+"/"+key+"/video.mp4";}
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=in.read())!=-1&&n!='\n'){if(out.size()>8192)throw new IOException("Header too large");if(n!='\r')out.write(n);}return out.toString("US-ASCII");}
    private void serve(Socket socket){LegacyHttp.Response r=null;try{InputStream in=new BufferedInputStream(socket.getInputStream());String[] request=line(in).split(" ");if(request.length<2||!(request[0].equals("GET")||request[0].equals("HEAD")))throw new IOException("Unsupported method");String path=request[1].startsWith("http://")?new URL(request[1]).getPath():request[1];if(!path.equals("/"+key+"/video.mp4"))throw new IOException("Unknown route");String range=null;int headers=0;for(;;){String l=line(in);if(l.length()==0)break;headers+=l.length();if(headers>16384)throw new IOException("Too many headers");if(l.toLowerCase(Locale.US).startsWith("range:"))range=l.substring(6).trim();}r=opener.open(range);responses.add(r);OutputStream out=new BufferedOutputStream(socket.getOutputStream(),16384);String head="HTTP/1.1 "+r.status+" "+(r.status==206?"Partial Content":r.status==200?"OK":"Error")+"\r\nContent-Type: video/mp4\r\nConnection: close\r\nAccept-Ranges: bytes\r\n";if(r.length>=0)head+="Content-Length: "+r.length+"\r\n";String contentRange=r.headers.get("content-range");if(contentRange!=null)head+="Content-Range: "+contentRange+"\r\n";out.write((head+"\r\n").getBytes("US-ASCII"));if(!request[0].equals("HEAD")&&r.status>=200&&r.status<300){byte[] b=new byte[16384];int n;while(!closed&&(n=r.read(b))!=-1){out.write(b,0,n);out.flush();}}out.flush();}catch(Exception ignored){}finally{if(r!=null){responses.remove(r);try{r.close();}catch(Exception ignored){}}clients.remove(socket);try{socket.close();}catch(Exception ignored){}}}
    public void close(){closed=true;try{server.close();}catch(Exception ignored){}synchronized(clients){for(Socket s:clients)try{s.close();}catch(Exception ignored){}clients.clear();}synchronized(responses){for(LegacyHttp.Response r:responses)try{r.close();}catch(Exception ignored){}responses.clear();}workers.shutdownNow();}
}
