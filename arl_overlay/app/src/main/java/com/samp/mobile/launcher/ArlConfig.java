package com.samp.mobile.launcher;

public final class ArlConfig {
    private ArlConfig() {}

    public static final String DEFAULT_HOST = "play.arl-samprpg.site";
    public static final int DEFAULT_PORT = 7777;
    public static final String CLIENT_VERSION = "0.3.7-R3";

    /* Phase 10 experimental config. Production/main stays untouched until promotion. */
    public static final String REMOTE_CONFIG =
        "https://raw.githubusercontent.com/gunikee1508/ARL-Android/phase10-cdn-differential-updater/launcher-phase10.json";

    public static final String DISCORD = "https://discord.gg/7X6swkqHDt";
    public static final String INSTAGRAM =
        "https://www.instagram.com/amazingreallifesamp";
    public static final String FORUM = "https://arl-samprpg.forumeiros.com";
}
