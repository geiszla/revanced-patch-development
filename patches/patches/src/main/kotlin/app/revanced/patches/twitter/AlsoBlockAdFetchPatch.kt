package app.revanced.patches.twitter

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

/**
 * Blocks the SSP client-side ad gating and RTB envelope parsing on X
 * (Twitter). Narrow-scope network-saver companion to "Hide ads" — only
 * covers the SSP/RTB ad families (home/tweet-details/spotlight/profile/
 * immersive SSP pods, plus any DSP riding on the rtbImageAd URT entry).
 * Does NOT add fetch-side gates for Premium upsells, sponsored live-event
 * tabs, promoted tweets/users, promoted event heroes, promoted trends, or
 * preroll — those ship through other endpoints and are handled
 * display-side by Hide ads. Apply on top of Hide ads to spare
 * bandwidth/battery/CPU on the SSP/RTB families.
 *
 * What each patch does:
 *  Patch 1 — Kills client-side ad-pod fetching on every surface gated
 *            by an `ssp_ads_*` key (home, tweet details, spotlight,
 *            profile, immersive).
 *  Patch 2 — Same, for the `*_client_only_integration` entry point
 *            (the premium-user exclusion path).
 *  Patch 3 — Drops every rtbImageAd-envelope item (Google RTB,
 *            Taboola RTB, any future DSP) at JSON parse time, before
 *            any timeline-item model or binder sees it.
 */
@Suppress("unused")
val alsoBlockAdFetchPatch = bytecodePatch(
    name = "Also block ad fetch",
    description = "Extends Hide ads for the SSP/RTB ad families by also blocking client-side SSP feature-switch gating and RTB envelope JSON deserialization. Does not cover Premium upsells, sponsored live-event tabs, promoted tweets/users, promoted event heroes, promoted trends, or preroll — those are handled display-side by Hide ads.",
) {
    compatibleWith("com.twitter.android")

    apply {
        // -- Patch 1: SSP ad feature switches off --
        //
        // C25329a.m32727d(int) is the primary resolver the ad pipeline
        // consults before requesting ads for a given surface. Return-
        // false at entry makes every `ssp_ads_*`-gated surface read as
        // "ads disabled," so no ad-pod fetch is initiated. The ad-fetch
        // state machine treats a disabled surface as "nothing to
        // attempt," so no retry churn.
        sspAdsFeatureSwitchMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 2: SSP ad feature switches (premium-user path) --
        //
        // C25329a.m32724a(int, userPrefs) is the sister resolver on
        // the premium-bypass path; reads the
        // `*_client_only_integration` keys and OR-combines with the
        // primary resolver (see m32726c). Same return-false pattern
        // as Patch 1 — both entry points short-circuit so premium and
        // non-premium users both get no ads.
        sspAdsFeatureSwitchPremiumMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 3: RTB image ad JSON deserialization returns null --
        //
        // JsonTimelineRtbImageAd builds the RTB domain object from the
        // parsed JSON fields. The URT registry lambda that dispatches
        // the "rtbImageAd" entry-type string already null-checks the
        // deserialized value and drops the entry on null. Returning
        // null from the builder drops every RTB image ad at parse
        // time — before any timeline-item model is constructed and
        // before any binder sees it. Covers Google RTB, Taboola RTB,
        // and any other DSP that rides on the rtbImageAd envelope.
        // Duplicates Hide ads Patch 8 so each patch is self-contained;
        // applying both is idempotent.
        patchJsonMapperToReturnNull(JSON_TIMELINE_RTB_IMAGE_AD)
    }
}
