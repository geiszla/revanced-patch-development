package app.revanced.patches.twitter

import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

// Class-name constants — used for classes whose names X retains via
// ProGuard rules (typically reflection-used JSON mappers). These are
// stable across builds even as surrounding code gets reobfuscated.
internal const val JSON_TIMELINE_RTB_IMAGE_AD =
    "Lcom/twitter/model/json/timeline/urt/JsonTimelineRtbImageAd;"
internal const val JSON_PROMOTED_TREND_METADATA =
    "Lcom/twitter/model/json/timeline/urt/JsonPromotedTrendMetadata;"
internal const val JSON_PREROLL_METADATA =
    "Lcom/twitter/model/json/timeline/urt/promoted/JsonPrerollMetadata;"

// Subscription module types — also stable because the subscriptions SDK
// surfaces them via kotlinx.serialization and relies on the FQCN.
internal const val UPSELL_CONTENT_TYPE =
    "Lcom/twitter/subscriptions/upsell/UpsellContent;"
internal const val UPSELL_CONFIG_TYPE =
    "Lcom/twitter/subscriptions/upsell/UpsellConfig;"

// -- Hide ads matches --

/**
 * CompactPromotedTweet binder's item-type matcher (`mo33961a`). The
 * matcher checks the tweet's subtype string against "CompactPromotedTweet"
 * and also requires a non-null promoted-content field. Returning false
 * makes this binder decline the item.
 *
 * Anchored on the "CompactPromotedTweet" literal + matcher signature
 * (PUBLIC FINAL, Z return, one L param). The signature disambiguates
 * from the synthetic bridge method (which doesn't reference the string
 * directly).
 */
internal val BytecodePatchContext.compactPromotedTweetMatcherMatch by composingFirstMethod(
    "CompactPromotedTweet",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("L")
}

/**
 * EmphasizedPromotedTweet binder's item-type matcher. Same pattern as
 * the Compact matcher above — checks the tweet's subtype string against
 * "EmphasizedPromotedTweet".
 */
internal val BytecodePatchContext.emphasizedPromotedTweetMatcherMatch by composingFirstMethod(
    "EmphasizedPromotedTweet",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("L")
}


/**
 * Sponsored live-event tab customizer's `mo33765a`. Inflates the sponsored
 * tab layout when the live event's customization info is present. Patched
 * to return null so no sponsored tab view is produced.
 *
 * Anchored on the "customization_info" bundle-key string plus the View-
 * returning single-L-param signature, which disambiguates from the other
 * files that reference the same string but don't inflate views.
 */
internal val BytecodePatchContext.sponsoredLiveEventTabMatch by composingFirstMethod(
    "customization_info",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Landroid/view/View;")
    parameterTypes("L")
}

/**
 * UpsellConfigManager class (C56382g). The match resolves to the class's
 * constructor (the only method that references the unique prefs storage
 * key "subscriptions_upsells_config"); the patch consumes `classDef` and
 * walks its fields/methods to find the empty-UpsellContent sentinel and
 * the resolver method.
 */
internal val BytecodePatchContext.upsellConfigManagerClassMatch by composingFirstMethod(
    "subscriptions_upsells_config",
) {
    returnType("V")
}

/**
 * RTB DSP enum class (EnumC47257e). Its `<clinit>` emits "Taboola" and
 * "LocalGoogleInjection" as literal strings while initializing the enum
 * values; "Google" and "Unknown" go through other paths, so this pair
 * uniquely identifies the enum. The match resolves to the clinit and the
 * patch takes its `classDef` as the handle for the DSP enum type.
 */
internal val BytecodePatchContext.rtbDspEnumClinitMatch by composingFirstMethod(
    "LocalGoogleInjection",
    "Taboola",
) {
    returnType("V")
    parameterTypes()
}

// -- Also block ad fetch matches --

/**
 * Primary SSP ad feature-switch resolver (C25329a.m32727d). Reads keys
 * like `ssp_ads_home_enabled`, `ssp_ads_spotlight`, `ssp_ads_profile`,
 * `ssp_ads_immersive` from the mobile-config to decide whether the
 * client-side ad pipeline should run. Patched to return false.
 *
 * Anchored on the home key ("ssp_ads_home_enabled"), which is unique to
 * this method's switch.
 */
internal val BytecodePatchContext.sspAdsFeatureSwitchMatch by composingFirstMethod(
    "ssp_ads_home_enabled",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Z")
}

/**
 * Client-only-integration SSP ad feature-switch resolver (C25329a.m32724a).
 * Sister method of m32727d; checks the Premium-user exclusion + the
 * `*_client_only_integration` keys. Patched to return false as well, so
 * both entry points into ad feature gating short-circuit.
 *
 * Anchored on the "ssp_ads_home_client_only_integration" key, which
 * only m32724a reads.
 */
internal val BytecodePatchContext.sspAdsFeatureSwitchPremiumMatch by composingFirstMethod(
    "ssp_ads_home_client_only_integration",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Z")
}

/**
 * C47258f's Earned-disclosure check (`m47695a`) — the method that calls
 * `"Earned".equalsIgnoreCase(disclosureType)`. Anchored on the "Earned"
 * literal. The match yields both the envelope class (via `classDef`) and
 * the Earned-check method name (via `method.name`), which the shared
 * pre-binder filter in Hide ads uses to build its envelope pre-check.
 */
internal val BytecodePatchContext.earnedDisclosureCheckMatch by composingFirstMethod(
    "Earned",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
}

// -- Disable force-upgrade matches --

/**
 * ClientShutdown state class (C56883p). The match resolves to its
 * constructor — the only method that reads both the `"is_shutdown"` and
 * `"shutdown_min_version"` shared-preference keys (the setters each
 * touch only one). The patch takes `classDef` from the match and looks
 * up the `isShutdown()` method by its preserved name.
 */
internal val BytecodePatchContext.shutdownStateClassMatch by composingFirstMethod(
    "is_shutdown",
    "shutdown_min_version",
) {
    accessFlags(AccessFlags.PUBLIC)
    returnType("V")
    parameterTypes("L")
}

/**
 * XLite ForceUpdateChecker's coroutine body (`m$a.invokeSuspend`). Reads
 * the `"min_android_xlite_version"` feature switch and, if the installed
 * version code is below it, dispatches the ForceUpdate UI event that
 * routes the main navigator to the "install the latest version" screen.
 * Runs once at app start, independently of the ClientShutdown path.
 *
 * Anchored on `"min_android_xlite_version"` — unique to this method
 * across the binary — plus the coroutine invokeSuspend signature
 * (`(Ljava/lang/Object;)Ljava/lang/Object;`, public final).
 */
internal val BytecodePatchContext.xliteForceUpdateCheckMatch by composingFirstMethod(
    "min_android_xlite_version",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Ljava/lang/Object;")
    parameterTypes("Ljava/lang/Object;")
}

// -- Unlock Premium features matches --

/**
 * C56482g.a.m51607d — shared per-feature gate. Returns true iff the user's
 * prefs StringSet contains `client_feature_switch/<featureId>/true`, the
 * subscriptions-enabled (or gating-bypass) FS is on, and the per-feature
 * FS is on.
 *
 * Anchored on its two unique FS keys — they are co-referenced by the
 * method body's combinator `(subscriptions_enabled || subscriptions_gating_bypass && ...)`.
 * Signature (String, L, L → Z) disambiguates from the synthetic bridges
 * m51604e / m51605f / m51606g on the same class, which also contain one
 * or both anchor strings but take different parameter counts.
 */
internal val BytecodePatchContext.perFeatureGateMatch by composingFirstMethod(
    "subscriptions_enabled",
    "subscriptions_gating_bypass",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("Ljava/lang/String;", "L", "L")
}

/**
 * C90850f.m67355j — XLite equivalent of C56482g.a.m51607d. Same role,
 * different impl path (XLite builds use C90850f instead of C56482g.a).
 *
 * Anchored on the `"client_feature_switch/"` prefix plus the `"/true"`
 * suffix that the method's StringBuilder concatenates. The single-String
 * parameter signature distinguishes from m51607d, which also references
 * both strings but takes three parameters.
 */
internal val BytecodePatchContext.xliteFeatureGateMatch by composingFirstMethod(
    "client_feature_switch/",
    "/true",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("Ljava/lang/String;")
}

/**
 * C56355e.mo51527a — undo's XLite-path FS+claim gate. Reads the FS and
 * StringSet directly rather than going through m51607d, so it needs its
 * own hook — the per-feature gate patch does not cover it.
 *
 * Anchored on the full claim-key literal `"client_feature_switch/subscriptions_feature_1003/true"`,
 * which is unique to this method. Zero-parameter boolean signature is the
 * only public override of AbstractC56352b.mo51527a on this class.
 */
internal val BytecodePatchContext.undoFeatureGateImplMatch by composingFirstMethod(
    "client_feature_switch/subscriptions_feature_1003/true",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
}

/**
 * AppIconDeepLinks_deepLinkToAppIconSettings — the Premium-gated deeplink
 * handler for `twitter://subscriptions/settings/app-icon`. Checks
 * m51597d() + FS("subscriptions_feature_1001") and either returns the
 * app-icon-picker intent or falls through to the signup flow.
 *
 * Anchored on the FS key `"subscriptions_feature_1001"` plus the
 * `"referring_page"` bundle key — only this handler in the deeplink
 * class references both. Matched so the patch can redirect its body to
 * the in-class unconditional sibling
 * `SubscriptionsDeepLinks_deepLinkToCustomAppIcons`.
 */
internal val BytecodePatchContext.appIconDeeplinkMatch by composingFirstMethod(
    "subscriptions_feature_1001",
    "referring_page",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Landroid/os/Bundle;")
}

/**
 * TabCustomizationDeepLinks_deepLinkToTabCustomization — the Premium-gated
 * deeplink handler. Lives in a different class from its unconditional
 * sibling, which is on the App Icon deeplinks class.
 *
 * Anchored on the FS key `"subscriptions_feature_1008"` plus the Intent
 * return type and (Context, Bundle) parameter signature. C56522a.mo51630a
 * also references the same FS key but returns List<EnumC44854c>.
 */
internal val BytecodePatchContext.tabCustomizationDeeplinkMatch by composingFirstMethod(
    "subscriptions_feature_1008",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Landroid/os/Bundle;")
}

/**
 * C56482g.a.m51602b — "user has any-tier premium claim". Constructs the
 * 4-string array `{twitter_blue, premium_basic, twitter_blue_verified,
 * premium_plus}` and delegates to m51605f.
 *
 * Used only as a class anchor: the 4 tier strings uniquely coexist in
 * this method, pinning the enclosing class (C56482g.a). The patch then
 * walks that class to find m51603c structurally (the single static
 * method taking a Companion-type parameter and returning Z), avoiding
 * a direct name-based match on the obfuscated m51603c identifier.
 */
internal val BytecodePatchContext.gateCompanionAnchorMatch by composingFirstMethod(
    "feature/twitter_blue",
    "feature/premium_basic",
    "feature/twitter_blue_verified",
    "feature/premium_plus",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Z")
}

// -- Shared helpers --

/**
 * Finds a ProGuard-retained JSON mapper class by FQCN, locates its single
 * non-constructor method that builds the domain object, and patches that
 * method to return null. The mapper's abstract parent (AbstractC47780l/k<T>)
 * declares a single abstract builder method, so every concrete mapper has
 * exactly one override matching "no params + object return." Used by both
 * Hide ads (for preroll, promoted-trend, and RTB mappers) and Also block
 * ad fetch (also for the RTB mapper — defense in depth).
 *
 * Fails fast: throws if the class is missing or the shape has drifted so
 * that zero or more-than-one builder method matches. A silent no-op would
 * let the patch appear to apply while ads reappear in a newer X build.
 */
internal fun BytecodePatchContext.patchJsonMapperToReturnNull(mapperType: String) {
    val mapperClass = classDefs.first { it.type == mapperType }

    val builderMethod = mapperClass.methods.single { method ->
        !method.name.startsWith("<") &&
            method.parameterTypes.isEmpty() &&
            method.returnType.startsWith("L")
    }

    classDefs.getOrReplaceMutable(mapperClass).methods.first {
        it.name == builderMethod.name && it.parameterTypes == builderMethod.parameterTypes
    }.addInstructions(
        0,
        """
            const/4 v0, 0x0
            return-object v0
        """,
    )
}
