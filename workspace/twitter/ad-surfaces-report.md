# Ad & Promoted-Content Surfaces in X (Twitter) Android — Report

Scope: decompiled X APK at `workspace/twitter/decompiled/`. Code is heavily obfuscated (`C25329a`, `C48152g2`), so every hook below is anchored on stable strings, JSON keys, GraphQL enum values, or resource names — not class names. Each surface lists trigger, file anchors, and a proposed ReVanced patch approach. A separate section at the end lists surfaces that do not need patches because the user opts in or is already inside an ad context.

## A. Single highest-leverage hooks

Before going surface-by-surface, two chokepoints plus one correctness guard:

**A1. Timeline entry discriminator** — `com/twitter/model/json/timeline/urt/C48152g2.java` (a big switch/registry at ~lines 598–972). It maps JSON entry-type strings to timeline item classes. The key string `"rtbImageAd"` (line 746) dispatches to `C49091j4` (the `TimelineRtbImageAd` domain model, file `C49091j4.java`). Strip that case and you kill every RTB image ad from the home/profile/search/explore feeds in one shot. File: `com/twitter/model/json/timeline/urt/JsonTimelineRtbImageAd.java` is where the item is deserialized; returning `null` from `JsonTimelineRtbImageAd.mo32701r()` is an equally clean alternative hook.

**A2. Ad feature-switch registry** — `com/twitter/ads/featureswitches/C25329a.java`. Confirmed string keys (all default `false`, server-toggled on):
- `ssp_ads_home_enabled` (home timeline)
- `ssp_ads_tweet_details`
- `ssp_ads_spotlight` (trends/spotlight)
- `ssp_ads_profile`
- `ssp_ads_immersive` (fullscreen video feed)
- plus `*_client_only_integration` twins for each

The method checks call `C60991o.m54086b().m54096a(key, false)`. Force `C25329a.m32727d(int)` / `m32726c(int)` to return `false` (constant return) and the SSP ad loader stops requesting ads for all four surfaces. This doesn't remove the timeline-item form of promoted posts (still deserialized from the home-timeline response) — that's what A1 handles — but it disables the client-integrated ad pod loading.

**A3. `Earned` disclosure exclusion — correctness guard that applies to every promoted-content filter below.** `com/twitter/model/core/entity/p506ad/C47258f.java:160` defines `m47695a()` which returns `"Earned".equalsIgnoreCase(disclosureType)`. Items marked with `disclosureType == "Earned"` are earned media (e.g. organic boosted content the user opted into), not paid ads, and must pass through untouched. **Any filter on the `C47258f` promoted-content envelope should be expressed as `promotedContent != null && !promotedContent.m47695a()` — not just `promotedContent != null`.** Omitting this guard would strip earned/organic content alongside true ads.

A1 and A2 are the two structural chokepoints; A3 is an always-on clause every downstream filter inherits. Everything below is a more targeted version of these, or a different surface entirely.

---

## B. Passive ad surfaces — patches needed

### B1. Promoted timeline items (home / profile / search / explore feeds)
Covers "promoted" posts inline with organic posts, including `EmphasizedPromotedTweet` and `CompactPromotedTweet` variants, plus RTB image ads and dynamic-ad cards. Promoted posts can reach the screen via **two** binder paths, so both must be handled:

1. **Dedicated promoted-tweet binders**, matching on the subtype string in `C48959o2.f267511l`:
   - `com/twitter/timeline/itembinder/C57355h.java:73` — matches `"CompactPromotedTweet"` and requires `f257762y != null`; inflates `R.layout.grouped_compact_promoted_tweet`.
   - `com/twitter/timeline/itembinder/C57370m.java:38` — matches `"EmphasizedPromotedTweet"`; inflates `R.layout.profile_promoted_tweet_view`.
2. **Generic tweet binder path** — a promoted post that lacks a dedicated subtype still renders through the standard tweet stack if it carries promoted metadata:
   - `com/twitter/model/timeline/C48959o2.java` (tweet timeline-item model; `f257851b` is the `C47258f` promoted-content envelope)
   - `com/twitter/tweetview/core/C58543m.java`, `com/twitter/tweetview/core/p748ui/badge/BadgeViewDelegateBinder.java` (badge rendering derived from the model)
   - Removing only the dedicated binders leaves this path intact.

- **Trigger**: always, server-driven, inserted into any URT timeline response.
- **URT anchors** (deserialization side):
  - Entry type string `"rtbImageAd"` in `com/twitter/model/json/timeline/urt/C48152g2.java:746`
  - `com/twitter/model/json/timeline/urt/JsonTimelineRtbImageAd.java`
  - `com/twitter/model/json/timeline/urt/JsonPromotedContentUrt.java` (JSON key `"promoted_content"`; fields `rtbAdMetadata`, `pac_in_timeline`, `suppress_media_forward`)
  - `com/twitter/model/json/timeline/urt/JsonAdMetadataContainerUrt.java` (fields `isQuickPromote`, `removePromotedAttributionForPreroll`, `unifiedCardOverride`, `overlayMediaResults`, `destination_url_params`)
  - `com/twitter/model/json/timeline/urt/promoted/JsonDynamicAdPromotedMetadata.java`
  - `com/p819x/models/TimelinePromotedMetadata.java` (the domain object — non-null means "this item is an ad")
- **Patch (preferred, model-level)**: filter items at the `C48959o2` stage where the promoted-content envelope is present and not Earned — `tweet.f257851b != null && !tweet.f257851b.m47695a()`. This covers both binder paths with one hook.
- **Secondary hook**: in `C48152g2` drop the `"rtbImageAd"` dispatch; in `UrtTimelinePost` (or its deserializer `UrtTimelinePost$$serializer`) return `null` from the constructor/parser when `promotedMetadata != null`. Catches RTB image ads before the model is built.
- **Avoid**: badge-only suppression (e.g. nulling `BadgeViewDelegateBinder`) — it hides the "Ad" label but leaves the sponsored payload on screen.

### B2. Promoted trends (Explore / Search / Trends module)
Paid trends that appear alongside organic trends.

- **Trigger**: always, in Explore tab and inline trends modules.
- **Anchors**:
  - `com/twitter/model/json/timeline/urt/JsonPromotedTrendMetadata.java` (fields: trend name, description, advertiser id)
  - `com/twitter/model/timeline/urt/C49062i6.java` (domain class `PromotedTrendInfo`)
  - Binder: `com/twitter/explore/timeline/C42764a0.java:336, 363` — inflates the promoted trend container
  - Resource strings `promoted_trend`, `social_promoted_trend`, layout `R.layout.promoted_trend`, stub `promoted_trend_container_view_stub` inside `trend_item`
  - Scribe labels `promoted_trend_id`, `promoted_trend_click`
- **Patch**: hook `JsonTimelineTrend` parsing and drop entries where the promoted-trend metadata is non-null, OR no-op the `JsonPromotedTrendMetadata` deserializer so organic trends still pass through. Hiding only the container stub is weaker because the sponsored trend entry itself remains.

### B3. Pre-roll / mid-roll video ads
Video ads shown before/during video playback, plus their attribution and CTA overlays.

- **Trigger**: opening any video that has preroll metadata attached.
- **Anchors**:
  - `com/twitter/ads/api/JsonPreroll.java`, `JsonVideoAd.java`, `JsonVideoAdResponse.java`, `JsonVideoAnalyticsScribe.java`
  - `com/twitter/model/json/timeline/urt/promoted/JsonPrerollMetadata.java`
  - Playback wiring: `com/twitter/android/p062av/video/C26045t.java:208, 257`, `com/twitter/media/p479av/p488ui/C46143p.java`
  - `com/twitter/ads/media/api/` (ad media rendering)
  - Observed CTA label strings: `Go to website`, `Shop now`, `See more`, `Visit website`, `Watch now`
- **Patch (preferred)**: return `null` from `JsonPrerollMetadata.mo34365s()` (or null-out the `preroll` field during deserialization). The player then skips straight to the organic content.
- **Weaker fallback**: hide preroll attribution/CTA views at the binder layer — leaves playback timing/state wired to the absent ad.

### B4. Promoted "Who to Follow" / user carousels
Paid user recommendations mixed into a carousel module, and promoted user rows that appear across many other surfaces via shared binders.

- **Trigger**: inserted as a `Carousel`/`GridCarousel` timeline module containing users with `JsonPromotedContentUrt` attached; also surfaces in "Creators for you", generic user timelines, typeahead, sheets, and incoming-friendship lists — all via shared user-row binders that read `user.f258364C` (the `C47258f` promoted-content envelope).
- **URT / model anchors**:
  - `com/twitter/model/json/timeline/urt/JsonTimelineModule.java` + domain class `com/twitter/model/timeline/urt/C49182u2.java` (module type enum at line ~23: `Vertical`, `Carousel`, `VerticalWithContextLine`, `VerticalConversation`, `ConversationTree`, `CompactCarousel`, `GridCarousel`, `VerticalGrid`, `PagedCarousel`)
  - `com/twitter/model/json/timeline/urt/JsonTimelineUser.java` field `f263743d` (`JsonPromotedContentUrt`)
- **Shared user-row binders that consume `user.f258364C`** (patch at any one does not cover the others — these are independent call-sites that must all be filtered):
  - `com/twitter/p758ui/user/BaseUserView.java:247`
  - `com/twitter/app/users/C33547j.java:282, 463`
  - `com/twitter/users/api/timeline/creatorsforyou/C60721b.java:112` (Creators for you)
  - `com/twitter/users/legacy/C60771n.java:166`
  - `com/twitter/users/legacy/C60755d.java:150`
  - `com/twitter/users/api/sheet/C60718f.java:132`
  - `com/twitter/incomingfriendships/C44371e.java:166`
- **Layouts that reuse the promoted user chrome**: `timeline_user_social_row_view`, `who_to_follow_carousel_with_fixed_name_item`, `typeahead_user_social_row_view`, `new_profile_card_user_info_layout`, `user_promoted_info`, `rooms_profile_user_with_bio`, `immersive_profile_user_with_bio`; string `social_who_to_follow`.
- **Patch (preferred)**: filter user items in the module-item mapper and at the shared row-model stage where `user.f258364C != null && !user.f258364C.m47695a()`. If every item in a carousel is promoted, drop the whole module. Organic user-recs pass through untouched.
- **Fallback**: suppress `setPromotedContent` on the binder. Hides the badge only, leaves the sponsored account visible — not recommended.

### B5. Sponsored tab on Live Event landing pages
When a Live Event has `customization_info`, its tab renders as a sponsored tab with sponsor logo + text.

- **Trigger**: Live Event landing page (e.g. sports/breaking-news pages) when backend ships sponsor customization.
- **Anchors**:
  - `com/twitter/android/liveevent/landing/timeline/tabcustomizer/C26984a.java:52` (`mo33765a()` inflates `R.layout.sponsored_timeline_tab_view`, binds `R.id.sponsored_icon` + `R.id.sponsored_text` from `C48363g`)
  - Customization pulled via key string `"customization_info"` from `C26943d`
  - Layout `apktool/res/layout/sponsored_timeline_tab_view.xml` confirmed present
- **Patch**: short-circuit `C26984a.mo33765a()` to return `null` (hides the tab), or null-out the `customization_info` lookup.

### B6. Premium subscription upsell — passive surfaces
There are six `UpsellSurface` enum values in `com/twitter/subscriptions/upsell/UpsellSurface.java` (mirrored as GraphQL enum `EnumC43921n0`/`EnumC43917m0` with an extra `OtherUserProfileHeader` value backend-side):
`HomeNav`, `UserProfileHeader`, `HomeSidebar`, `PremiumNav`, `ReplyInterstitial`, `UserProfileName`.

All are passive — the app decides to show them. Each patch should force the upsell configuration to be absent/empty:

- **B6a HomeNav** — rotating upsell banner in the home toolbar. Controller `com/twitter/app/main/toolbar/C32701m.java` (`m40687a()`), config via `com/twitter/subscriptions/api/upsell/C56382g.java` (`m51547d()`/`m51596b()`), gate `subscriptions_upsells_premium_home_nav`. String keys `premium_upsell_get_premium`, `premium_upsell_premium`, `premium_upsell_upgrade`, `premium_upsell_offer_phrase`.
- **B6b UserProfileHeader + UserProfileName** — nudge on profile page ("Get verified"). Controller `com/twitter/app/profiles/header/upsell/C32927f.java`, view wired through `com/p819x/profile/header/UserProfileHeaderComponent.java`. String `profile_get_verified_upsell`. Also covers own-profile and other-profile variants (GraphQL has `OtherUserProfileHeader`).
- **B6c HomeSidebar / PremiumNav** — sidebar upsell items. Same config manager `C56382g`; configure the surface to return empty.
- **B6d ReplyInterstitial** — upsell shown interleaved with replies under a post. Same config plumbing.
- **B6e Verified-tab empty-state upsell (Notifications → Verified tab)** — `com/p819x/notifications/tab/C81857h.java`, strings `activity_tab_vit_description_blue_available`, `activity_tab_vit_subscribe_button`, `activity_tab_vit_subscribe_with_price_duration`, `activity_tab_vit_limited_time_subscribe_with_price_duration`.

- **Patch (all six)**: the single chokepoint is `C56382g.m51547d(UpsellSurface surface)`. Return a config whose `defaultContent` and `variantConfig` are empty/null (or a sentinel "no upsell" variant). Every passive upsell surface is fed from there. Alternative hook: `C56482g.m51596b()` (variant resolver) — force the NO_UPSELL variant. For the Verified-tab empty-state, hook `C81857h` to skip the upsell composable when `tabType == Verified`.

### B7. Google RTB / GMS native ads (timeline, immersive, event hero)
Distinct from B1's URT `rtbImageAd` path: these are Google-served native ads loaded through the Google Mobile SDK and rendered by dedicated GMS binders. The gate is `C48903b1.m48584m()` in `com/twitter/model/timeline/C48903b1.java:43`, which returns true when the RTB DSP type is `EnumC47257e.Google` or `EnumC47257e.LocalGoogleInjection`.

Three binders consume this gate:

- **B7a Home/profile timeline Google RTB** — `com/twitter/timeline/itembinder/C57376o.java:38-42, 77` — inflates `R.layout.gms_native_ads`.
- **B7b Immersive media Google RTB** — `com/twitter/explore/immersive/p402ui/C42499g.java:40, 69` — inflates `R.layout.immersive_gms_native_ads`.
- **B7c Explore event-hero Google RTB** — `com/twitter/explore/timeline/events/C42801o.java:68-81, 181` — matches where `c49055i.f268009c == 2 && c47260h.f258017a == EnumC47257e.LocalGoogleInjection`, inflates `R.layout.event_summary_hero_rtb_google_ad`.

- **Trigger**: all three automatic once the timeline/immersive/event response contains a Google-flagged RTB item. No user interaction required.
- **Patch**: force `C48903b1.m48584m()` to return `false` (const return). Drops the item out of all three binders' `mo33961a` matchers in one shot, and the generic tweet binder then doesn't pick it up either because the `C48903b1` subclass short-circuits. Layout-name suppression alone won't work (binder would still try to inflate).

### B8. Taboola RTB image ads
Taboola is a separate DSP. It bypasses the Google binders and is hydrated into a unified-card tweet-like item, so it enters the generic tweet rendering path.

- **Trigger**: automatic — whenever the RTB response includes a Taboola-flagged item.
- **Anchor**: `com/twitter/database/legacy/hydrator/C41261u.java:71` — checks `enumC47257e == EnumC47257e.Taboola` and rewrites the RTB payload as a `C47455q` unified card with image-website type.
- **Patch**: intercept before the Taboola branch in the hydrator — drop the item when the DSP type is `Taboola`. Patching only B7 leaves Taboola ads in place.

### B9. Promoted event / spotlight hero in Explore
Sponsored hero/spotlight card that is not the Google-RTB variant (§B7c). A separate promoted-event info object (`C49173t0`) drives the sponsored treatment.

- **Trigger**: automatic in Explore and live-event themed sections when the event summary carries promoted event metadata.
- **Anchor**: `com/twitter/explore/timeline/events/C42799m.java:178` — binds `C49055i.f268016j` (the `C49173t0` promoted-event info). The binder reads advertiser name/URL into the hero chrome.
- **Patch**: filter event-summary items where the promoted-event info (`c49055i.f268016j`) is non-null before the binder runs.

### B10. Promoted sticker categories (sticker picker)
The sticker picker shows promoted sticker category headers with sponsor attribution.

- **Trigger**: opens with the sticker picker/editor. The category row itself is passive within the picker — no tap required to see it.
- **Anchor**: `com/twitter/android/media/imageeditor/stickers/C27262g.java:292-310` — checks `c48420g2.f264948g == true` (promoted flag), makes `R.id.promoted_category` visible, writes the text via `R.string.stickers_promoted_by` (with sponsor name) or `R.string.promoted_trend` (fallback).
- **Patch**: filter promoted categories from the adapter data — drop `C48420g` entries where `f264948g == true` before binding. Hiding only `R.id.promoted_category` leaves the promoted category row in the list.

### B11. Dynamic-ad display-location subgraph
DI container that decides where ads get inserted. Not a UI surface itself, but a defense-in-depth hook so that if an ad item slips past the model-layer patches, it still has no valid insertion index.

- **Anchors**: `com/twitter/app/common/timeline/p172di/retained/TimelineDynamicAdDisplayLocationSubgraph.java`, `com/twitter/ads/model/p045di/DefaultDynamicAdDisplayLocationSubgraph.java`, `com/twitter/analytics/promoted/di/`.
- **Patch**: provide an empty location list from the subgraph's factory.

---

## C. Organic surfaces with promoted variants — filter only the promoted variant

These are mostly organic (user-curated) but have a paid ad variant. Patch by advertiser-flag, not by presence, so organic usage stays intact.

### C1. Shop modules on profiles / product cards in posts
- **Anchors**: `com/twitter/commerce/shopmodule/`, `com/twitter/model/json/unifiedcard/commerce/`, `com/twitter/profilemodules/json/` (Shop module); strings `commerce_shop_component_content_description`, `catalog_exist_text`, `shop_spotlight_title`, `shop_disclaimer_text`.
- **Patch**: filter items carrying an advertiser/promoted metadata field (paid "Shop Spotlight" variant). Leave organic seller-curated shop items alone.

### C2. Community spotlight modules
- **Anchors**: `com/twitter/communities/profilemodule/`, `com/twitter/communities/json/spotlight/JsonCommunitiesModule.java`; string `community_spotlight_title`.
- **Patch**: same strategy as C1 — filter by advertiser flag, not by module type.

---

## D. Surfaces that don't need patches (user-initiated or already-in-ad context)

These match the carve-out: user opts in to promotional content, or is already engaged with an ad.

1. **Edit-tweet Premium bottom sheet** — only fires when the user taps the Edit button on a post.
   File: `com/twitter/edit/p397ui/C42276e.java`. Strings `twitter_blue_edit_upsell_bottom_sheet_title/body/primary_button/dismiss_button`. Fragment: `PremiumUpsellBottomSheetFragment` with key `"edit_post"`.

2. **Download-video Premium sheet** — only fires when user taps Download Video in the media options sheet.
   File: `com/twitter/tweetview/core/p748ui/mediaoptionssheet/C58828r.java`; guard at `!C56482g.a.m51603c(C56482g.Companion)`. Strings `download_video_upsell_bottom_sheet_*` (and `x_lite_download_video_upsell_bottom_sheet_*` for Lite).

3. **DM verification-only gates** — triggered by the user attempting a gated DM action (DM a non-follower, create group with non-followers, audio/video call, exceed daily cap).
   Files: `com/twitter/subsystem/chat/verifiedonlydialog/C56859g.java`, `com/p819x/p837dm/newdm/C74998o.java`. String families `dm_verified_only_title_*`, `dm_verified_only_text_*`, `dm_verified_only_yes_button`.

4. **Profile Articles / Highlights empty-state upsell** — shown only when the user navigates to the Articles or Highlights profile tab, so they're already in that opt-in context. Strings `profile_tab_articles_upsell_*`, `profile_tab_highlights_upsell_*`.

5. **Video-length Premium hint** — shown when the user tries to post/send a video over the free-tier length cap. Strings `video_long_blue_suggestion`, `video_long_dm_blue_suggestion`.

6. **Grok 2 model-selector upsell** — shown only when the user opens the Grok model dropdown and picks a paid model. File: `com/p819x/grok/modeselector/C78161a.java`; string `grok_model_selector_upgrade_to_premium`. Free Grok tier keeps working.

7. **Ad action-sheet items** — only appear after the user opens the overflow/share menu on an ad. Actions: `PromotedDismissAd`, `PromotedAdsInfo`, `PromotedReportAd`, `PromotedShareVia`, `PromotedCopyLinkTo`. Anchors: `com/twitter/tweet/action/legacy/C58019d0.java:242, 682`, `com/twitter/tweet/action/legacy/C58037i1.java:1130, 1134, 1145`. Once the underlying ad is stripped by §B1/B7/B8, these never render.

8. **Immersive promoted-media "Learn more" CTA** — exists only once the user is already viewing promoted immersive media. Anchor: `com/twitter/explore/immersive/p402ui/learnmore/LearnMoreViewDelegateBinder.java:129`. Redundant once §B7b filters the item.

9. **Preroll CTA buttons and offline-ad opt-in flows** — downstream of preroll ads; once §B3 suppresses preroll, these paths never fire.

---

## E. Feature-switch flip (alternative / complementary approach)

If you want a single-line patch as a defense-in-depth layer in addition to the model-level hooks, the ad feature-switch resolver is the shortest path:

- `com/twitter/ads/featureswitches/C25329a.java` — methods `m32724a(int)`, `m32726c(int)`, `m32727d(int)` read the SSP keys listed in §A2. Force each to return `false`. This stops client-side ad fetches on home, tweet-details, spotlight, profile, and immersive in one place.
- `com/twitter/subscriptions/api/upsell/C56382g.java` — `m51547d(UpsellSurface)` returns the upsell config. Force an empty config. Kills every upsell surface in §B6 in one place.

Apply the URT-model patches from B1–B10 as the primary layer, since feature switches can be overridden server-side and the timeline-model layer is what actually shoulders the promoted-item construction. Feature switches are belt-and-braces.

---

## F. Recommended patch set (prioritized)

In rough order of "most impact per patch":

1. **Strip promoted tweet items (Earned-aware)** — filter at `C48959o2` where `tweet.f257851b != null && !tweet.f257851b.m47695a()`. Covers both the `CompactPromotedTweet` / `EmphasizedPromotedTweet` dedicated binders and the generic tweet path. (§B1.)
2. **Strip promoted user rows (Earned-aware)** — filter where `user.f258364C != null && !user.f258364C.m47695a()`, applied at each of the eight shared binder call-sites in §B4. Also drop carousel modules where all items were stripped.
3. **Drop Google RTB ads** — force `C48903b1.m48584m()` to return `false`. Kills timeline, immersive, and Explore event-hero Google RTB in one hook. (§B7.)
4. **Drop Taboola RTB ads** — intercept `com/twitter/database/legacy/hydrator/C41261u.java:71` and skip items where DSP type is `Taboola` before the hydrator rewrites them into a unified card. (§B8.)
5. **Kill pre-roll** — return `null` from `JsonPrerollMetadata.mo34365s()`. (§B3.)
6. **Strip promoted trends** — drop trend entries when `JsonPromotedTrendMetadata` is non-null. (§B2.)
7. **Strip promoted event hero** — filter event-summary items where `c49055i.f268016j != null`. (§B9.)
8. **Filter promoted sticker categories** — drop `C48420g` entries where `f264948g == true` from the sticker adapter. (§B10.)
9. **Empty all Premium upsell configs** — intercept `C56382g.m51547d(UpsellSurface)`. Covers HomeNav, HomeSidebar, PremiumNav, ReplyInterstitial, UserProfileHeader, UserProfileName, OtherUserProfileHeader. (§B6a–d.)
10. **Kill Verified-tab empty-state upsell** — hook `C81857h` to skip upsell branch on `NotificationTabType.Verified`. (§B6e.)
11. **Hide sponsored live-event tab** — short-circuit `C26984a.mo33765a()` to `null`. (§B5.)
12. **Disable SSP ad feature switches** — const-return `false` from `C25329a.m32727d`/`m32726c`. Belt-and-braces against server-side ad pod requests. (§A2, E.)
13. **Filter promoted variants of Shop modules and Community Spotlight** — drop items with advertiser metadata only. (§C1–C2.)
14. **Empty dynamic-ad display-location subgraph** — defense-in-depth. (§B11.)

Every filter at B1, B4, B7, and B8 must respect the §A3 `Earned` disclosure guard — earned content carries a `C47258f` envelope but is not a paid ad and must pass through.
