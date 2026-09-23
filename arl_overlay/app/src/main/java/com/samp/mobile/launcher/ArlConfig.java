package com.samp.mobile.launcher;

public final class ArlConfig {
    private ArlConfig() {}

    public static final String DEFAULT_HOST = "play.arl-samprpg.site";
    public static final int DEFAULT_PORT = 7777;
    public static final String CLIENT_VERSION = "0.3.7-R3";

    /* Isolated Phase 11 base config; older APKs continue using launcher-phase11.json. */
    public static final String REMOTE_CONFIG =
        "https://raw.githubusercontent.com/gunikee1508/ARL-Android/phase11-zero-setup-base/launcher-phase11-base.json";

    public static final String DISCORD = "https://discord.gg/7X6swkqHDt";
    public static final String INSTAGRAM =
        "https://www.instagram.com/amazingreallifesamp";
    public static final String FORUM = "https://arl-samprpg.forumeiros.com";

    // Official Android GTA SA package/store fallback used when no base is present.
    public static final String GTA_PACKAGE = "com.rockstargames.gtasa";
    public static final String GTA_PLAY_STORE =
        "https://play.google.com/store/apps/details?id=" + GTA_PACKAGE;
}
