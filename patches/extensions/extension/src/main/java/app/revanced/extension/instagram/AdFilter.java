package app.revanced.extension.instagram;

/**
 * Runtime ad filter for Instagram patches.
 * Called from bytecode patches injected by HideAdsPatch.
 * Returns true to block ads, false to allow them through.
 */
public class AdFilter {

    /**
     * Whether ads should be blocked. Currently always true.
     * Future: read from SharedPreferences for a user toggle.
     */
    public static boolean shouldBlockAds() {
        return true;
    }
}
