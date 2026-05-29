package com.lean.iptv;

/** Plain data holder for one channel. */
public class Channel {
    public final String name;
    public final String url;
    public final String group;
    public final String logo;

    public Channel(String name, String url, String group, String logo) {
        this.name = name;
        this.url = url;
        this.group = group;
        this.logo = logo;
    }
}
