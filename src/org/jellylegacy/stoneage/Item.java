package org.jellylegacy.stoneage;
public class Item {
    public String id,name,type,info,overview;
    public long duration,resume;
    public boolean favorite,watched;
    public String toString(){return name+"\n"+info;}
}
