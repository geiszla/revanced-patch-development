# X (Twitter) Premium Features — Client-Side Patchability Report

Scope: decompiled X APK at `workspace/twitter/decompiled/`. Code is heavily obfuscated — every hook is anchored on stable strings (entitlement tier names, feature-switch keys) rather than class/method names. Each patchable feature below lists the gate, the file anchor, and a proposed ReVanced patch approach. A single "universal" patch at the end unlocks every Category 1 feature in one shot; per-feature narrower alternatives are also listed.

## Revisions from review

Two rounds of code review caught issues with the patch proposals. All fixes are reflected below; the key corrections:

**Round 1**
- **Narrow Tab Customization and Reader Mode matchers previously used `returnType("Z")`** — wrong. The only methods that reference `subscriptions_feature_1008` and `subscriptions_feature_1005` return `List<EnumC44854c>` (`C56522a.mo51630a`) and `Intent` (deeplink handlers). Matchers below anchor on the actual return types.
- **`parameterTypes("Ljava/lang/String;", null, null)` is invalid syntax** — this project uses `"L"` as the single-char wildcard for any object type. All matchers now use `"L"` or concrete descriptors.
- **The broad FS getter hook was unsafe.** `boolean (String, boolean)` matches `C61000x.m54096a`, `C61338e.getBoolean`, `C77697e0.getBoolean`, and even `org.bouncycastle.util.Properties.isOverrideSetTo`. The static/instance mix also breaks the `p1` register assumption. That hook is removed; deeplink FS checks are patched at each call site instead. Reactive FS consumers (e.g. `C43081v` subscribing to `featureSwitches.m54106k("subscriptions_feature_1001")` at `C43081v.java:64`) are acknowledged as a gap — they return an RxJava `Observable<Optional<T>>` and aren't easily hijacked.
- **Narrow Undo patch was incomplete.** Undo runs through three call paths: `C56388z.a.m51554a` (send-time), `C56388z.m51551c` (VM-time via `UndoSendViewModel`), and the XLite `SubscriptionsFeatures.mo55215g` path that goes through `C56353c`/`C56354d` → `AbstractC56352b.m51528b` → `C56355e.mo51527a`. All three are now addressed.
- **`subscriptions_feature_1007`** was described as "Articles (published long-form)" — actually gates the Top Articles popular-feed deeplink (`TopArticleTimelineDeeplinks_deepLinkToTopArticleTimeline`). Still Category 3; table corrected.

**Round 2**
- **`perFeatureGateMatch` was still returning `true` unconditionally**, which also unlocks Category 2/3 IDs that pass through the same gate (1002 Bookmark Folders deeplink, 1007 Top Articles, 1014 NoteTweets, `labs_1004` Edit, `highlights`, etc.). The patch now **gates `m51607d` with a whitelist extension** — returns `true` only for {1003, 1005, 1008}. All other feature IDs fall through to the original method body.
- **Global `m51602b` patch removed.** Forcing "user has any-tier premium" to true has broad side effects — changes Premium Hub button copy, verified-name callouts, ad feature-switch reads, upsell banner selection, and analytics payloads. For Video Download, patch `C56482g.a.m51603c` instead: it's a narrower helper used by this-user premium-check call sites, with a small enumerated side-effect set.
- **App Icon and Tab Customization deeplinks now delegate to an in-APK unconditional route.** The class `AppIconDeepLinks_GeneratedStaticProxyDeepLinkHandlers` already ships with two methods that open the same activities **without any premium check**:
  - `SubscriptionsDeepLinks_deepLinkToCustomAppIcons(Context)` (line 110) — invoked by URIs `twitter.com/settings/app_icon`.
  - `SubscriptionsDeepLinks_deepLinkToCustomNavigation(Context, Bundle)` (line 115) — invoked by URIs `twitter.com/settings/custom_navigation`.

  Rather than calling obfuscated accessors via a custom extension helper, the gated handlers are patched to just `invoke-static` their unconditional sibling in the same class. Less fragile, no extension code needed, uses public APK methods.

## Gating architecture

Two gate functions funnel nearly all premium checks in the app:

- **`C56482g.a.m51602b(prefs)`** — "user is any-tier premium". Reads the `subscriptions` `StringSet` out of `SharedPreferences` and returns `true` if it contains any of `feature/twitter_blue`, `feature/premium_basic`, `feature/twitter_blue_verified`, `feature/premium_plus`. File: `com/twitter/subscriptions/features/api/C56482g.java:59`.
- **`C56482g.a.m51607d(featureId, FS, prefs)`** — per-feature gate. Returns `true` iff `client_feature_switch/<featureId>/true` is in the user's `subscriptions` StringSet AND (`subscriptions_enabled` or `subscriptions_gating_bypass` is on) AND the `<featureId>` feature switch is on. File: `com/twitter/subscriptions/features/api/C56482g.java:115`.

The XLite code path (`com/twitter/x/lite/impl/C62010e0`) uses an equivalent gate `C90850f.m67355j(str)` in `com/p819x/subscriptions/C90850f.java:98`.

Known feature-ID strings discovered:

| ID | Feature |
|---|---|
| `subscriptions_feature_1001` | Custom App Icon |
| `subscriptions_feature_1002` | Bookmark Folders |
| `subscriptions_feature_1003` | Undo Post / Undo Reply |
| `subscriptions_feature_1005` | Thread Reader Mode (deeplink entry) |
| `subscriptions_feature_1007` | Top Articles feed (popular articles timeline) |
| `subscriptions_feature_1008` | Tab Customization (bottom-nav) |
| `subscriptions_feature_1009` | Early Access settings entry |
| `subscriptions_feature_1011` | 1080p video upload quality |
| `subscriptions_feature_1014` | NoteTweets / longer posts (25k chars) |
| `subscriptions_feature_labs_1004` | Edit Post |
| `subscriptions_feature_highlights` | Highlights (mark a post) |
| `subscriptions_per_post_in_thread` | Per-post in thread / post storm |
| `subscriptions_inapp_grok` | Grok AI |

---

## Summary

| Feature | Category | One-line reason |
|---|---|---|
| Undo Post / Undo Reply | 1 | Pure local send-delay timer |
| Custom App Icon | 1 | Uses Android `setComponentEnabledSetting` — no server call |
| Tab Customization | 1 | Tab list read from local prefs when gate passes |
| Thread Reader Mode (deeplink) | 1 | Reader is client-side composition over already-fetched timeline |
| Video Download | 1 | Video URL already in tweet data; gate just guards download call |
| Edit Post | 2 | UI opens; `EditTweet` mutation server-validates `is_edit_eligible` |
| NoteTweets / Longer Posts | 2 | Composer accepts chars; `CreateTweet` mutation server-rejects |
| 1080p Video Upload | 2 | UI setting appears; server re-encodes / rejects |
| Post Storm / Per-post in thread | 2 | Client check bypassable; server enforces monetization eligibility |
| Highlights (mark post) | 2 | UI action appears; mutation server-gated |
| Bookmark Folders | 3 | Folder CRUD uses `bookmark_collection_*` GraphQL mutations |
| Top Articles feed | 3 | Server-returned timeline (`top_articles_timeline` GraphQL) |
| Account Analytics / Radar | 3 | WebViews & server-rendered timelines |
| Grok AI | 3 | All Grok endpoints re-auth against entitlement |
| Blue Verified checkmark (visible to others) | 3 | Per-user `is_blue_verified` returned by server |
| Post Analytics dashboard | 3 | WebView to `twitter.com/i/.../analytics` |
| Premium Hub / signup | 3 | Google Play Billing + server receipt |

---

## Category 1 — Features that would actually work after a client-side patch

### 1.1 Undo Post / Undo Reply

**Where**: Undo runs through three distinct gate paths, all of which must be flipped:

- `C56388z.a.m51554a(prefs, draft, z)` at `com/twitter/subscriptions/api/C56388z.java:67` — send-time decision in the outgoing-tweet pipeline. Uses `C56482g.a.m51604e(..., "subscriptions_feature_1003", prefs, 2)` + `allow_undo_tweet`/`allow_undo_replies` user prefs. Hits `C56482g.a.m51607d` internally.
- `C56388z.m51551c(self, z)` at `com/twitter/subscriptions/api/C56388z.java:133` — VM-time decision called from `UndoSendViewModel.invokeSuspend` at `com/twitter/subscriptions/core/UndoSendViewModel.java:129`. Same internal dispatch through `m51604e` → `m51607d`.
- `AbstractC56352b.m51528b` (shared base) at `com/twitter/subscriptions/api/featuregating/AbstractC56352b.java:28` — used by XLite's `SubscriptionsFeatures.mo55215g(isReply)` via `C56353c`/`C56354d`. Requires `m51602b` + `mo51527a`. The concrete `mo51527a` in `C56355e` at `com/twitter/subscriptions/api/featuregating/C56355e.java:24` does **not** go through `m51607d` — it reads the FS and StringSet directly, so patching `m51607d` alone does not cover this path.

The feature itself is a local send-delay: `C72817j.mo58872a` at `com/p819x/composer/work/C72817j.java:248` schedules `UndoNudgeWork` on WorkManager for a window (default 20s, controlled by `undo_tweet_timer` user pref ∈ {5, 10, 20, 30, 60}). No server call happens during the undo window.

**Proposed patches** — minimal set covering all three paths, with the shared gate scoped via a whitelist so that non-Category-1 feature IDs (1002, 1007, 1014, labs_1004, highlights, etc.) fall through and keep their server-backed rejection behaviour:

```kotlin
// Matching.kt

// C56482g.a.m51607d — shared per-feature gate. Anchored on its two unique FS
// keys + the "client_feature_switch/" literal also present in its body.
// The signature (String, L, L → Z) disambiguates from other boolean getters.
val BytecodePatchContext.perFeatureGateMatch by composingFirstMethod(
    "client_feature_switch/",
) {
    strings("subscriptions_enabled", "subscriptions_gating_bypass")
    returnType("Z")
    parameterTypes("Ljava/lang/String;", "L", "L")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
}

// C56355e.mo51527a — the undo FS+claim gate that bypasses m51607d. Anchored
// on "subscriptions_feature_1003" + the claim-key suffix, scoped to a
// zero-arg boolean method (C56355e only exposes one public override).
val BytecodePatchContext.undoFeatureGateImplMatch by composingFirstMethod(
    "subscriptions_feature_1003",
) {
    strings("client_feature_switch/subscriptions_feature_1003/true", "subscriptions")
    returnType("Z")
    parameterTypes()
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
}
```

```kotlin
// UndoSendUnlockPatch.kt
val undoSendUnlockPatch = bytecodePatch(name = "Unlock undo send") {
    compatibleWith("com.twitter.android")
    apply {
        // Scoped whitelist: return true only for client-safe feature IDs.
        // Other IDs (1002, 1007, 1014, labs_1004, highlights, ...) fall
        // through to the original method body and keep their server-rejection
        // behaviour, so this patch doesn't expose broken Category 2/3 UI.
        perFeatureGateMatch.method.addInstructions(0, """
            invoke-static { p1 }, Lapp/revanced/extension/twitter/PremiumGateWhitelist;->isClientSafe(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :fall_through
            const/4 v0, 0x1
            return v0
            :fall_through
        """)
        // The XLite undo base gate doesn't pass through m51607d, so it
        // needs its own short-circuit. Feature-specific (undo only), so
        // no whitelist needed.
        undoFeatureGateImplMatch.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)
    }
}
```

```java
// extensions/extension/src/main/java/app/revanced/extension/twitter/PremiumGateWhitelist.java
package app.revanced.extension.twitter;

/**
 * Scoped override for C56482g.a.m51607d. Returns true only for the four
 * feature IDs whose features work end-to-end on the client:
 *   1003 — undo post / undo reply
 *   1005 — thread reader deeplink
 *   1008 — tab customization list builder
 *   highlights-client — placeholder in case the whitelist grows
 *
 * All other IDs (1002, 1007, 1011, 1014, labs_1004, "highlights",
 * "inapp_grok", ...) fall through to the original method, so Category 2/3
 * UI stays hidden and server rejection paths are not triggered.
 */
public final class PremiumGateWhitelist {
    private PremiumGateWhitelist() {}

    public static boolean isClientSafe(String featureId) {
        if (featureId == null) return false;
        return "subscriptions_feature_1003".equals(featureId)
            || "subscriptions_feature_1005".equals(featureId)
            || "subscriptions_feature_1008".equals(featureId);
    }
}
```

`perFeatureGateMatch` is the same hook reused by §1.3 / §1.4, so the whitelist covers all three features with one patch. Also recommended: set `allow_undo_tweet` / `allow_undo_replies` user prefs to `true` via an extension `onCreate` hook so the per-user toggle is on.

### 1.2 Custom App Icon

**Where**: two entry points, each with its own gate:

- Deeplink: `AppIconDeepLinks_deepLinkToAppIconSettings` at `com/twitter/feature/subscriptions/settings/appicon/deeplinks/AppIconDeepLinks_GeneratedStaticProxyDeepLinkHandlers.java:36`. Checks `m51597d() && C60991o.m54086b().m54096a("subscriptions_feature_1001", false)`. If both true, returns an `AppIconSettingActivityContentViewArgs`; otherwise falls through to the signup flow. Wired to URIs `twitter://subscriptions/settings/app-icon?referring_page={referrer}`.
- Settings screen: `C43081v` at `com/twitter/feature/subscriptions/settings/appicon/C43081v.java:64` subscribes to `featureSwitches.m54106k("subscriptions_feature_1001")` (an RxJava `Observable<Optional<Boolean>>`) and shows/hides the preference entry based on the emitted value.

The app-icon switcher itself (`com/twitter/subscriptions/appicon/implementation/C56394c.java:113`) calls `PackageManager.setComponentEnabledSetting` on activity-aliases declared in the manifest. No server involvement once the UI is open.

**Key finding**: the same class also ships `SubscriptionsDeepLinks_deepLinkToCustomAppIcons(Context)` at `AppIconDeepLinks_GeneratedStaticProxyDeepLinkHandlers.java:110`, wired to URIs `twitter.com/settings/app_icon`, which opens the **same** `AppIconSettingActivityContentViewArgs` with **no premium check**. So the APK itself has a working unconditional route. The patch simply makes the gated handler delegate to the unconditional one.

**Known gap (unchanged)**: the reactive `m54106k` path in `C43081v` returns an observable wrapping an `Optional<T>` container (`C60993q`). Hijacking that observable to always emit `Optional.of(true)` would let the Settings → Premium preference row show up. Not patched here; users reach App Icon via `twitter://settings/app_icon` (or the redirected gated URI) instead of through Settings → Premium.

**Proposed patch**:

```kotlin
// Matching.kt

// AppIconDeepLinks_deepLinkToAppIconSettings — the gated handler.
// Anchored on the feature-ID string and Intent return; "referring_page"
// bundle key pins it to this handler (several handlers in the same
// class read the same FS, but only this one reads "referring_page").
val BytecodePatchContext.appIconDeeplinkMatch by composingFirstMethod(
    "subscriptions_feature_1001",
) {
    strings("referring_page")
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Landroid/os/Bundle;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
}
```

```kotlin
// AppIconUnlockPatch.kt — redirect the gated handler to its in-class
// unconditional sibling. No extension helpers required.
val appIconUnlockPatch = bytecodePatch(name = "Unlock custom app icon") {
    compatibleWith("com.twitter.android")
    apply {
        val handlerClass = appIconDeeplinkMatch.classDef.type
        appIconDeeplinkMatch.method.addInstructions(0, """
            invoke-static { p0 }, ${handlerClass}->SubscriptionsDeepLinks_deepLinkToCustomAppIcons(Landroid/content/Context;)Landroid/content/Intent;
            move-result-object v0
            return-object v0
        """)
    }
}
```

`SubscriptionsDeepLinks_deepLinkToCustomAppIcons` is a `public static` method in the same class (`AppIconDeepLinks_GeneratedStaticProxyDeepLinkHandlers`) that ships unconditional and uses `ReferringPage.Deeplink.INSTANCE`. No FQCN drift risk — the method is on the same class the matcher already resolved.

### 1.3 Tab Customization (custom bottom-nav ordering)

**Where**: two sites.

- `C56522a.mo51630a` at `com/twitter/subscriptions/tabcustomization/implementation/C56522a.java:95` — the method that builds the tab list. Return type is `List<EnumC44854c>`. If `m51607d("subscriptions_feature_1008", ...)` returns `false`, returns `EmptyList` (falls back to default nav). Otherwise reads local `tabCustomizationPreferences` and maps entries to enum values.
- `TabCustomizationDeepLinks_deepLinkToTabCustomization` at `com/twitter/feature/twitterblue/settings/tabcustomization/deeplinks/TabCustomizationDeepLinks_GeneratedStaticProxyDeepLinkHandlers.java:21` — `Intent`-returning deeplink handler that also checks the FS.

Zero server dependency for the tab-ordering itself. **Caveat** (from second opinion): destinations within the selected tabs (Grok, Spaces, Communities) retain their own server gates. Tab ordering works; some destinations may still be non-functional.

**Key finding**: the same deeplinks class ships `SubscriptionsDeepLinks_deepLinkToCustomNavigation(Context, Bundle)` at `AppIconDeepLinks_GeneratedStaticProxyDeepLinkHandlers.java:115`, wired to URIs `twitter.com/settings/custom_navigation`, which opens `TabCustomizationContentViewArgs` with **no premium check**. The patch delegates the gated handler to this unconditional sibling.

`C56522a.mo51630a` (the list builder) is handled separately by the scoped `perFeatureGateMatch` from §1.1 — because `1008` is on the whitelist, the shared gate returns `true` for that ID.

**Proposed patch**:

```kotlin
// Matching.kt

// TabCustomizationDeepLinks_deepLinkToTabCustomization — the gated handler.
// Lives in the same deeplinks class as the App Icon handlers above.
val BytecodePatchContext.tabCustomizationDeeplinkMatch by composingFirstMethod(
    "subscriptions_feature_1008",
) {
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Landroid/os/Bundle;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
}
```

```kotlin
val tabCustomizationUnlockPatch = bytecodePatch(name = "Unlock tab customization") {
    compatibleWith("com.twitter.android")
    apply {
        val handlerClass = tabCustomizationDeeplinkMatch.classDef.type
        tabCustomizationDeeplinkMatch.method.addInstructions(0, """
            invoke-static { p0, p1 }, ${handlerClass}->SubscriptionsDeepLinks_deepLinkToCustomNavigation(Landroid/content/Context;Landroid/os/Bundle;)Landroid/content/Intent;
            move-result-object v0
            return-object v0
        """)
    }
}
```

### 1.4 Thread Reader Mode (deeplink entry)

**Where**: `ReaderModeDeeplinks_deeplinkToReaderModeTimeline` at `com/twitter/longform/threadreader/deeplink/C44787xc4e1d6a7.java:18`. The handler is `Intent`-returning; it parses `bundle.getString("id")`, then if `m51607d("subscriptions_feature_1005", ...)` is true, returns an intent for `C44781d` (the reader activity). Otherwise falls back to the home intent. Reader rendering itself (`com/twitter/longform/threadreader/implementation/**`) is pure client composition over the same timeline data already fetched for the regular tweet-detail view.

Caveat: reader mode is also offered through the normal `toolbar_thread_reader_switch` entry in `com/twitter/tweetdetail/C58198q0.java:458`, gated by `readerModeConfig` in the timeline metadata — that entry is server-driven and not affected by this patch. The deeplink patch gives reliable reader access from pasted URLs.

**Proposed patch** — `perFeatureGateMatch` from §1.1 already unlocks this, since `C44787xc4e1d6a7` uses the same `m51607d` gate. No additional matcher is needed.

(If a narrow alternative is preferred, anchor on the Intent-returning handler method instead of a non-existent boolean getter:

```kotlin
val BytecodePatchContext.readerDeeplinkMatch by composingFirstMethod(
    "subscriptions_feature_1005",
) {
    strings("extra_tweet_id")
    returnType("Landroid/content/Intent;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
}
```

and rewrite the method to always construct the reader intent. In practice the shared-gate patch is cleaner.)

### 1.5 Video Download (save video from other users' posts)

**Where**: `C58828r.mo52758a` at `com/twitter/tweetview/core/p748ui/mediaoptionssheet/C58828r.java:37` —

```java
if (!C56482g.a.m51603c(C56482g.Companion)) {
    this.f291981b.m53072d(new PremiumUpsellBottomSheetArgs(...));
    return false;
}
// picks highest-bitrate variant URL from mediaEntity, hands to InterfaceC42168b.mo45137a
```

`m51603c` is a static helper that reads the current user's prefs and calls `m51602b`. If it returns `true`, the method picks the highest-bitrate variant URL out of `mediaEntity.f258097s.f253102c` (data already present in the timeline response) and hands it to the file downloader. The CDN URL is public.

Caveat: only works for media where the tweet exposes downloadable variants. Restricted/DRM'd media won't have the URLs in `f253102c`.

**Proposed patch** — patch `C56482g.a.m51603c` to always return `true`. This is narrower than patching `m51602b` globally: `m51603c` is only consulted where "does *this* user have a premium claim" matters for a client-side decision. Call sites beyond Video Download:

- `C42717x`, `C42325b` — immersive video player native-PIP gate (UX-only, fine if enabled)
- `C53763d.m50980d` — Spaces tab visibility (will show; server-gated entry otherwise)
- `C32906f` — "Get verified" profile upsell (will hide — fine)
- `C56388z`, `C33442l` — subscription settings entry points (UI-only)
- `C33756e` — Birdwatch pinned-tab fetch trigger (server returns empty — fine)
- `C35398d`, `C27961c0` — creator-call UI + verification education (entry cosmetic; action server-gated)

None of these cause a user-visible broken state in the way that patching `m51602b` does to Premium Hub copy, subscription-tier checks, and analytics payloads.

```kotlin
// Matching.kt

// C56482g.a.m51603c — static helper that resolves the current user's
// UserIdentifier and calls m51602b. Anchored structurally: the class-a
// inner Companion (C56482g.a) contains the 4-string tier array (via
// m51602b) and the "client_feature_switch/" literal (via m51607d).
// m51603c itself is a tiny static helper with signature (a → Z) and no
// string literals — match via class anchor + signature.
val BytecodePatchContext.currentUserIsPremiumMatch by composingFirstMethod {
    // The enclosing class has the 4 tier strings plus the per-feature
    // literals; use those to pin the class, then pick the single
    // static-synthetic method that returns Z and takes one L param.
    strings(
        "feature/twitter_blue_verified",
        "feature/premium_plus",
        "client_feature_switch/",
    )
    returnType("V")  // match the class initializer to resolve the class;
                      // the actual method is selected below by signature
    // Placeholder — see VideoDownloadUnlockPatch.kt for the full class-
    // walk that picks the specific method.
}
```

In practice the matcher-plus-class-walk pattern (as used by `patchJsonMapperToReturnNull` in `patches/patches/src/main/kotlin/app/revanced/patches/twitter/Matching.kt:156`) is cleaner than trying to anchor `m51603c` directly:

```kotlin
val videoDownloadUnlockPatch = bytecodePatch(name = "Unlock video download") {
    compatibleWith("com.twitter.android")
    apply {
        // Anchor the enclosing class via the 4 tier strings (unique to
        // C56482g.a.m51602b). Then pick the one public-static-synthetic
        // method with signature (L → Z) that is not m51602b itself — that
        // is m51603c, the "current user is premium" helper.
        val gateCompanionClass = classDefs.firstOrNull { classDef ->
            classDef.methods.any { method ->
                val strings = method.implementation?.instructions
                    ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? StringReference }
                    ?.map { it.string }
                    ?: return@any false
                setOf(
                    "feature/twitter_blue",
                    "feature/premium_basic",
                    "feature/twitter_blue_verified",
                    "feature/premium_plus",
                ).all { it in strings }
            }
        } ?: return@apply

        val m51603c = gateCompanionClass.methods.singleOrNull { method ->
            method.returnType == "Z" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].startsWith("L") &&
                method.accessFlags and AccessFlags.STATIC.value != 0 &&
                method.accessFlags and AccessFlags.SYNTHETIC.value != 0
        } ?: return@apply

        classDefs.getOrReplaceMutable(gateCompanionClass).methods.first {
            it.name == m51603c.name && it.parameterTypes == m51603c.parameterTypes
        }.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)
    }
}
```

This structural resolution avoids baking the obfuscated method name (`m51603c`) into the patch. If X re-obfuscates between builds, the class-walk still finds the right helper.

---

## Category 2 — Partial (UI unlocks, server rejects action)

Patching these gates makes buttons appear and flows start, but the underlying GraphQL mutation / REST call re-checks entitlement server-side and returns an error. Net UX: broken, not functional.

| Feature | Client gate | Server touchpoint |
|---|---|---|
| Edit Post | `C42249j.mo45160p` → `subscriptions_feature_labs_1004` + `edit_tweet_*` FS | Tweet's `edit_controls.is_edit_eligible` from server; `EditTweet`/`CreateTweet` mutation re-validates |
| NoteTweets / Longer Posts | `C56482g.a.m51608h` → `subscriptions_feature_1014` or `longform_notetweets_composition_without_claims_enabled` | `CreateTweet` mutation rejects >280-char payload |
| 1080p Video Upload | `C44885p.m46183a` + `DataSettingsActivity.f223113O` (`subscriptions_feature_1011`) | Upload API downscales / rejects |
| Post Storm / Per-post in thread | `C57171l.m51982a` / `C90850f.mo55210b` | Server validates monetization eligibility |
| Highlights (mark a post) | `C58019d0:398` (`subscriptions_feature_highlights`) | Highlight mutation server-gated |

Don't ship patches for these as "premium unlocks" — they'll appear to work then fail at send-time.

---

## Category 3 — Server-only (client patching cannot help)

- **Bookmark Folders** — `BookmarkDeeplinks_GeneratedStaticProxyDeepLinkHandlers.java:20` gates on `subscriptions_feature_1002`, but folder CRUD goes through `bookmark_collection_create`/`update`/`delete`/`timeline` GraphQL mutations in `com/twitter/api/graphql/C28079d.java:349`. Patching the deeplink just opens an empty folders view; all mutations server-reject.
- **Articles** (publish) — `C44719xfd48f7e3.java:29` gates deeplink; real publishing is server-side. Profile Articles tab is also tier-checked at the server (`com/twitter/app/profiles/timeline/C32999g.java:82`).
- **Top Articles**, **Account Analytics** (`com/twitter/feature/subscriptions/analytics/C42999a.java:67`), **Radar** (`com/twitter/feature/subscriptions/radar/C43053a.java:67`) — all open server-rendered timelines or webviews; client-side gates only guard the entry point.
- **Grok AI** — `C56482g.m51595a` at `C56482g.java:245` gates UI; Grok model endpoints re-auth server-side, so conversations return 403/error.
- **Blue / Premium checkmark visibility to other users** — `UserVerificationInfo.isBlueVerified` is populated per-user from the server-returned profile. You can render yourself verified locally, but nobody else sees it.
- **Post Analytics dashboard** — `TweetAnalyticsWebViewActivity` is just a WebView to `twitter.com/i/.../analytics`; the page itself enforces entitlement on load.
- **Premium Hub / signup / paywall** — Google Play Billing + server activation receipt.
- **Prioritized replies ("reply boost")** — timeline ranking decision, server-side only.
- **Timeline ad reduction** — timeline ads are injected by the GraphQL timeline endpoint, not client-filterable via premium flags. (The unrelated SSP client-only-integration fallback ad path in `C25329a.m32724a` is only a minor slice; see ad-surfaces-report for handling real ads.)

---

## Appendix — Consolidated patch strategy

Deliberately scoped. The patch:

- **does** flip the per-feature gate (`m51607d`) to `true` for a whitelist of four IDs — 1003 / 1005 / 1008 (and its XLite twin) — covering Undo, Reader Mode deeplink, and Tab Customization.
- **does not** flip `m51602b` (any-tier premium) globally — too much cross-contamination with Premium Hub copy, verified-name callouts, ad gating, analytics, etc.
- **does** delegate the two gated deeplink handlers (App Icon, Tab Customization) to their unconditional siblings already in the APK.
- **does** patch `C56482g.a.m51603c` (the narrower "current user has premium" helper) for Video Download, with acknowledged minor cosmetic side effects.

### Shared matchers

```kotlin
// patches/twitter/premium/Matching.kt
package app.revanced.patches.twitter.premium

import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import com.android.tools.smali.dexlib2.AccessFlags

// C56482g.a.m51607d — shared per-feature gate. See §1.1.
val BytecodePatchContext.perFeatureGateMatch by composingFirstMethod(
    "client_feature_switch/",
) {
    strings("subscriptions_enabled", "subscriptions_gating_bypass")
    returnType("Z")
    parameterTypes("Ljava/lang/String;", "L", "L")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
}

// C90850f.m67355j — XLite equivalent of m51607d.
val BytecodePatchContext.xliteFeatureGateMatch by composingFirstMethod(
    "client_feature_switch/",
) {
    strings("/true")
    returnType("Z")
    parameterTypes("Ljava/lang/String;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
}

// C56355e.mo51527a — undo's XLite-path FS+claim gate that bypasses m51607d.
// See §1.1.
val BytecodePatchContext.undoFeatureGateImplMatch by composingFirstMethod(
    "subscriptions_feature_1003",
) {
    strings("client_feature_switch/subscriptions_feature_1003/true", "subscriptions")
    returnType("Z")
    parameterTypes()
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
}

// AppIconDeepLinks_deepLinkToAppIconSettings — the gated deeplink. See §1.2.
val BytecodePatchContext.appIconDeeplinkMatch by composingFirstMethod(
    "subscriptions_feature_1001",
) {
    strings("referring_page")
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Landroid/os/Bundle;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
}

// TabCustomizationDeepLinks_deepLinkToTabCustomization — gated deeplink.
// See §1.3.
val BytecodePatchContext.tabCustomizationDeeplinkMatch by composingFirstMethod(
    "subscriptions_feature_1008",
) {
    returnType("Landroid/content/Intent;")
    parameterTypes("Landroid/content/Context;", "Landroid/os/Bundle;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
}

// Anchor for the C56482g.a class: the only class with all 4 tier strings.
// Used to find the m51603c helper structurally inside §1.5's patch.
val BytecodePatchContext.gateCompanionAnchorMatch by composingFirstMethod {
    strings(
        "feature/twitter_blue",
        "feature/premium_basic",
        "feature/twitter_blue_verified",
        "feature/premium_plus",
    )
    returnType("Z")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
}
```

### Consolidated patch

```kotlin
// patches/twitter/premium/UnlockClientPremiumPatch.kt
package app.revanced.patches.twitter.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

val unlockClientPremiumPatch = bytecodePatch(
    name = "Unlock client-side premium gates",
    description = "Unlocks five premium features that work fully on-device: " +
        "undo send, custom app icon, tab customization, thread reader " +
        "deeplink, and video download. Scoped whitelist prevents exposure " +
        "of Category 2 server-rejected UI.",
) {
    compatibleWith("com.twitter.android")
    extendWith("extensions/extension.rve")

    apply {
        // (a) Scoped whitelist on the shared per-feature gate.
        perFeatureGateMatch.method.addInstructions(0, """
            invoke-static { p1 }, Lapp/revanced/extension/twitter/PremiumGateWhitelist;->isClientSafe(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :fall_through
            const/4 v0, 0x1
            return v0
            :fall_through
        """)

        // (b) Same whitelist semantics for the XLite twin.
        xliteFeatureGateMatch.method.addInstructions(0, """
            invoke-static { p1 }, Lapp/revanced/extension/twitter/PremiumGateWhitelist;->isClientSafe(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :fall_through
            const/4 v0, 0x1
            return v0
            :fall_through
        """)

        // (c) Undo's XLite-path FS gate — feature-specific, so no whitelist.
        undoFeatureGateImplMatch.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)

        // (d) Redirect gated deeplinks to their in-class unconditional siblings.
        val appIconHandlerClass = appIconDeeplinkMatch.classDef.type
        appIconDeeplinkMatch.method.addInstructions(0, """
            invoke-static { p0 }, ${appIconHandlerClass}->SubscriptionsDeepLinks_deepLinkToCustomAppIcons(Landroid/content/Context;)Landroid/content/Intent;
            move-result-object v0
            return-object v0
        """)

        val tabHandlerClass = tabCustomizationDeeplinkMatch.classDef.type
        tabCustomizationDeeplinkMatch.method.addInstructions(0, """
            invoke-static { p0, p1 }, ${tabHandlerClass}->SubscriptionsDeepLinks_deepLinkToCustomNavigation(Landroid/content/Context;Landroid/os/Bundle;)Landroid/content/Intent;
            move-result-object v0
            return-object v0
        """)

        // (e) Video Download: patch C56482g.a.m51603c structurally. Find the
        //     gate-companion class (C56482g.a) via the 4 tier strings, then
        //     pick its single static-synthetic (L → Z) helper — that is
        //     m51603c. Short-circuit to true.
        val gateCompanionClass = gateCompanionAnchorMatch.classDef
        val m51603c = gateCompanionClass.methods.singleOrNull { method ->
            method.returnType == "Z" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].startsWith("L") &&
                method.accessFlags and AccessFlags.STATIC.value != 0 &&
                method.accessFlags and AccessFlags.SYNTHETIC.value != 0
        } ?: return@apply

        classDefs.getOrReplaceMutable(gateCompanionClass).methods.first {
            it.name == m51603c.name && it.parameterTypes == m51603c.parameterTypes
        }.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)
    }
}
```

### Extension helper

Just one, for the scoped whitelist:

```java
// extensions/extension/src/main/java/app/revanced/extension/twitter/PremiumGateWhitelist.java
package app.revanced.extension.twitter;

public final class PremiumGateWhitelist {
    private PremiumGateWhitelist() {}

    public static boolean isClientSafe(String featureId) {
        if (featureId == null) return false;
        return "subscriptions_feature_1003".equals(featureId)  // undo post/reply
            || "subscriptions_feature_1005".equals(featureId)  // reader mode deeplink
            || "subscriptions_feature_1008".equals(featureId); // tab customization
    }
}
```

### Validation

After applying, verify:

1. **Undo Post** — compose a tweet; confirm the send countdown appears without a subscription. Test replies too (separate `allow_undo_replies` pref).
2. **Custom App Icon** — open `twitter://settings/app_icon`; the picker should list alternates; tap one and confirm launcher icon changes. The original gated URI `twitter://subscriptions/settings/app-icon` should now also open the picker directly (patch (d) redirects it).
3. **Tab Customization** — open `twitter://settings/custom_navigation` (or the gated `twitter://settings/tab_customization`); the editor should open; reorder and confirm the bottom nav reflects the local order.
4. **Thread Reader** — open `twitter://i/threadreader?id=<tweet_id>`; confirm the reader view renders instead of the signup gate.
5. **Video Download** — long-press a video in any timeline; "Save video" should trigger the download rather than the Premium upsell sheet. Verify the file lands in downloads.

Category 2/3 UI that **should stay hidden** after this patch (confirm these are still gated):

- Edit button on own posts (`labs_1004` → falls through whitelist → original gate rejects)
- Compose char counter above 280 (`1014` → same)
- 1080p upload preference (`1011` → same)
- Highlights caret action (`highlights` → same)
- Bookmark Folders deeplink (`1002` → same)
- Top Articles deeplink (`1007` → same)

### Risks / known limitations

- **Reactive FS observable gap (§1.2)**: the Settings → Premium → App Icon preference row may remain hidden if the server hasn't marked `subscriptions_feature_1001` on. Deeplink entry still works. If this becomes user-visible, future work could hook `C60993q` (the `Optional<T>` wrapper) construction or replace `C43081v`'s preference-row gating method outright.
- **Video Download side effects (§1.5)**: patching `m51603c` to `true` also changes the visible state of: Spaces tab eligibility, native-PIP in immersive video, the "get verified" profile upsell (hides it), creator-call UI entry, and Birdwatch pinned-tab fetch trigger. All either cosmetic or silently fall back to server-side rejection; none break the user flow. Documented so they're not a surprise during QA.
- **Gate-function renaming**: `perFeatureGateMatch` / `xliteFeatureGateMatch` anchor on `"client_feature_switch/"` + `"subscriptions_enabled"` + `"subscriptions_gating_bypass"`, all long-lived literals in the subscription SDK. `gateCompanionAnchorMatch` depends on the 4 tier strings coexisting in one method — if X adds/removes a tier (e.g. a new "Premium Ultra") the matcher fails closed and needs updating.
- **In-class deeplink siblings**: the redirect target methods `SubscriptionsDeepLinks_deepLinkToCustomAppIcons` and `SubscriptionsDeepLinks_deepLinkToCustomNavigation` are on the same ProGuard-retained deeplink-handler class that holds the gated methods. If future builds split those handlers across multiple classes, the `${handlerClass}->Subscriptions...` forwards need to resolve the sibling class, not just reuse the matched one.
- Do **not** force `subscriptions_enabled` on globally. It feeds `C56482g.m51596b` (upsell banner selector) and several analytics-reporting paths; forcing it changes what the app thinks about its own subscription state.
