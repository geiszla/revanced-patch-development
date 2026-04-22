package app.revanced.patches.twitter

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Unlocks the five X (Twitter) Premium features that actually work
 * end-to-end on-device, without exposing Category 2 UI that server-rejects
 * at action time. Premium gating in this APK funnels through two shared
 * functions plus a few deeplink-handler FS reads; this patch narrows them
 * surgically.
 *
 * Unlocked features (also listed in the patch `description`):
 *  1. Undo Post / Undo Reply — the local send-delay countdown that holds
 *     an outgoing tweet in the WorkManager queue so the user can cancel
 *     it. No server call happens during the undo window.
 *  2. Custom App Icon — the in-settings alternate-icon picker. The
 *     switcher calls PackageManager.setComponentEnabledSetting on
 *     manifest-declared activity-aliases; entirely local.
 *  3. Tab Customization — the custom bottom-nav ordering editor. Tab
 *     order is read from local tabCustomizationPreferences and mapped to
 *     nav entries; no server involvement (though destinations like Grok
 *     or Spaces retain their own server gates).
 *  4. Thread Reader Mode (deeplink entry) — opening a tweet thread in
 *     the alternate reader layout via twitter://i/threadreader?id=….
 *     Reader rendering is pure client composition over the timeline data
 *     already fetched for the regular tweet-detail view.
 *  5. Video Download — the "Save video" option in the media-options
 *     sheet on any tweet that exposes downloadable variants. The CDN URL
 *     is public; the gate just guards the download call.
 *
 * Patch shape (each bullet corresponds to one Patch below):
 *  (a) Scoped whitelist on C56482g.a.m51607d (shared per-feature gate):
 *      short-circuit to true only when the feature ID is one of
 *      {1003, 1005, 1008}. All other IDs fall through to the original
 *      method body, preserving server-rejection behaviour for Category 2/3.
 *  (b) Same whitelist on C90850f.m67355j (XLite twin of the gate).
 *  (c) C56355e.mo51527a (undo XLite-path FS+claim gate that bypasses
 *      m51607d entirely) — unconditional true. Feature-specific to undo,
 *      so short-circuiting unconditionally does not leak other features.
 *  (d) Redirect the gated App Icon and Tab Customization deeplink
 *      handlers to in-APK unconditional siblings that already ship inside
 *      AppIconDeepLinks_GeneratedStaticProxyDeepLinkHandlers. No custom
 *      extension code required.
 *  (e) Short-circuit C56482g.a.m51603c (the "current user has premium"
 *      helper) to true. Narrower than m51602b: only affects this-user
 *      premium-check call sites, not every upsell banner / verified-name
 *      callout / analytics payload. Located structurally via the 4
 *      tier-string anchor on the companion class.
 *
 * Explicitly does NOT patch C56482g.a.m51602b globally — that would
 * change Premium Hub button copy, verified-name callouts, ad-feature
 * switch reads, and upsell banner selection. Likewise does not force any
 * subscriptions_feature_* feature switch on, because that would expose
 * the Category 2 UI listed above.
 *
 * Known limitation: the Settings → Premium → App Icon preference row
 * subscribes to featureSwitches.m54106k("subscriptions_feature_1001") via
 * an RxJava Observable<Optional<Boolean>>. The reactive path is not
 * patched here; users reach the App Icon picker via the deeplink
 * (twitter://settings/app_icon or the redirected gated URI). The picker
 * itself functions normally once opened.
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock Premium features",
    description = "Enables Undo Post / Undo Reply, Custom App Icon, Tab Customization, Thread Reader Mode deeplink, and Video Download.",
) {
    compatibleWith("com.twitter.android")

    apply {
        // -- Patch (a): Scoped whitelist on the shared per-feature gate --
        //
        // C56482g.a.m51607d is the choke point for undo (via m51604e
        // wrapper → m51607d), reader-mode deeplink, and tab-customization
        // list builder. We short-circuit to true only for the three
        // feature IDs whose features work client-side; other IDs fall
        // through to the original body so Category 2/3 UI stays hidden.
        //
        // The inline smali avoids a runtime extension helper. `p1` is
        // the featureId String; `v0` is used as scratch and does not
        // collide with the original body, which re-initialises v0 at
        // its first instruction after :fall_through.
        perFeatureGateMatch.method.addInstructions(0, buildWhitelistGuard())

        // -- Patch (b): Same whitelist on the XLite twin --
        //
        // C90850f.m67355j is the XLite-build equivalent of m51607d.
        // XLite's SubscriptionsFeatures impl (C62010e0) can route
        // through either gate depending on DI wiring, so both need the
        // whitelist to keep the three features working across build
        // variants.
        xliteFeatureGateMatch.method.addInstructions(0, buildWhitelistGuard())

        // -- Patch (c): Undo's XLite-path FS gate unconditional true --
        //
        // C56355e.mo51527a reads the FS and the subscriptions StringSet
        // directly rather than going through m51607d, so Patch (a)
        // doesn't reach it. Feature-specific to undo, so we can
        // short-circuit unconditionally without leaking any other
        // features.
        undoFeatureGateImplMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        // -- Patch (d): Redirect gated App Icon deeplink to in-class sibling --
        //
        // AppIconDeepLinks_deepLinkToAppIconSettings checks `m51597d() &&
        // FS("subscriptions_feature_1001")` and either returns the
        // settings intent or falls through to the signup flow. The same
        // class ships SubscriptionsDeepLinks_deepLinkToCustomAppIcons
        // (wired to URIs *.com/settings/app_icon) which constructs the
        // same settings intent with no premium check. We forward the
        // gated handler straight to the unconditional one.
        //
        // Both methods are in the same ProGuard-retained deeplink class,
        // so the method-name tokens are stable across builds and the
        // class type resolves from the match itself (no hardcoded FQCN).
        val appIconHandlerClass = appIconDeeplinkMatch.classDef.type
        appIconDeeplinkMatch.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $appIconHandlerClass->SubscriptionsDeepLinks_deepLinkToCustomAppIcons(Landroid/content/Context;)Landroid/content/Intent;
                move-result-object v0
                return-object v0
            """,
        )

        // -- Patch (d cont.): Redirect gated Tab Customization deeplink --
        //
        // The gated handler
        // (TabCustomizationDeepLinks_deepLinkToTabCustomization) lives in
        // a different class from its unconditional sibling. The sibling
        // SubscriptionsDeepLinks_deepLinkToCustomNavigation is in the App
        // Icon deeplinks class (wired to URIs
        // *.com/settings/custom_navigation). We reuse the App Icon
        // handler class (resolved just above) as the forward target.
        tabCustomizationDeeplinkMatch.method.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $appIconHandlerClass->SubscriptionsDeepLinks_deepLinkToCustomNavigation(Landroid/content/Context;Landroid/os/Bundle;)Landroid/content/Intent;
                move-result-object v0
                return-object v0
            """,
        )

        // -- Patch (e): Short-circuit C56482g.a.m51603c for Video Download --
        //
        // m51603c is a static helper on the gate-companion class that
        // resolves the current user's prefs and delegates to m51602b.
        // C58828r.mo52758a (the video-download entry) consults this
        // helper as its only premium gate; once it returns true, the
        // method picks the highest-bitrate variant URL and hands it to
        // the file downloader.
        //
        // Locate m51603c structurally: on the gate-companion class
        // (resolved via the 4 tier-string anchor), it is the unique
        // static method returning Z and taking one parameter whose type
        // is the companion class itself. That signature distinguishes
        // it from m51602b (param type InterfaceC61347n), m51604e
        // (4 params), and m51605f (2 params).
        //
        // Side effects of forcing this helper true are cosmetic:
        //  - Spaces tab eligibility, native-PIP in immersive video,
        //    "Get verified" profile upsell (hides it), creator-call UI
        //    entry, Birdwatch pinned-tab fetch trigger. None break the
        //    user flow; server-gated actions downstream still reject.
        val gateCompanionClass = gateCompanionAnchorMatch.classDef
        val m51603c = gateCompanionClass.methods.single { method ->
            method.returnType == "Z" &&
                method.parameterTypes.toList() == listOf(gateCompanionClass.type) &&
                method.accessFlags and AccessFlags.STATIC.value != 0
        }

        classDefs.getOrReplaceMutable(gateCompanionClass).methods.first {
            it.name == m51603c.name && it.parameterTypes == m51603c.parameterTypes
        }.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}

/**
 * Inline smali guard for the per-feature gate whitelist. Prepends a check
 * that returns true if p1 matches one of the three client-safe feature
 * IDs (1003 undo, 1005 reader deeplink, 1008 tab customization);
 * otherwise falls through to the original method body.
 *
 * Uses only v0 as scratch, which the original body re-initialises at its
 * first instruction. Kept as a shared builder so Patches (a) and (b) —
 * standard and XLite gates — stay in lockstep.
 */
private fun buildWhitelistGuard(): String =
    """
        if-eqz p1, :fall_through
        const-string v0, "subscriptions_feature_1003"
        invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :return_true
        const-string v0, "subscriptions_feature_1005"
        invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :return_true
        const-string v0, "subscriptions_feature_1008"
        invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :fall_through
        :return_true
        const/4 v0, 0x1
        return v0
        :fall_through
    """
