package app.revanced.patches.instagram

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

/**
 * Hides ads from Instagram's primary surfaces by cutting upstream insertion
 * and fetch paths.
 *
 * Patch 1 -- Feed/grid/story/explore: blocks SponsoredContentController.insertItem,
 *           the central ad insertion point shared across all surfaces
 *           (including story ads, which flow through the same controller).
 * Patch 2 -- Reels/clips: blocks the fetchSponsoredContent network call so
 *           sponsored reels are never fetched.
 *
 * Known uncovered surfaces (future work):
 *  - Related ads pivots (RelatedAdsPivotRepository — shop/commerce feeds)
 *  - Lead ads (LeadAdsActivity — interactive form overlays)
 *  - Direct inbox ad layouts
 *
 * Dropped patches (too risky / incomplete):
 *  - Stories ad pool (AFS.FJz): "stories_feed_of_ads" matches 6 methods,
 *    and Patch 1 already covers story ads at the insertion level.
 *  - Ad classifier (DqI): gates playback, session tracking, and media
 *    behavior beyond just labels; also incomplete (ReelItem.DqI has an
 *    independent A0z==A0Z path). Too global for a safety net.
 *
 * See workspace/instagram/patch-claude.md and patch-codex.md for analysis.
 */
@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides ads from feed, stories, reels, and explore.",
) {
    compatibleWith("com.instagram.android"("422.0.0.44.64"))
    extendWith("extensions/extension.rve")

    apply {
        // -- Patch 1: Block feed/grid/story/explore ad insertion --
        //
        // SponsoredContentController.insertItem (C98953oB.A0J) is the central
        // ad insertion method. Returning false at index 0 is safe because:
        //  - No state has been modified yet (Dvu adapter call at line 952 is
        //    also skipped, so the Dvu/Dvs notification pair stays balanced).
        //  - The profiler is not yet opened, so no resource leak.
        //  - All three callers handle false correctly:
        //      * Ejq (line 1305): ignores return, sets A0D=true (prevents retry)
        //      * Line 1340: skips E6v ad tracking notification (desired)
        //      * Line 1380: skips ad re-insertion/removal logic (desired)
        // This is the same early-return pattern used by official ReVanced patches.
        adInjectorMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 2: Block reels/clips sponsored content fetch --
        //
        // C2DM.A0R (fetchSponsoredContent) is a ~700-line method with queue
        // bookkeeping and state flags before the actual network dispatch.
        // Returning void at index 0 skips all of it, which means the fetch
        // state stays at "not attempted." The ad-fetch state machine handles
        // failures/timeouts already, so this is functionally equivalent to
        // "the fetch instantly completed with nothing." If this causes retry
        // churn in a future version, Patch 1 still blocks insertion regardless.
        fetchSponsoredContentMatch.method.addInstructions(0, "return-void")
    }
}
