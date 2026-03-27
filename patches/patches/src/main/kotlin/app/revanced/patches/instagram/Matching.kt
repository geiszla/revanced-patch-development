package app.revanced.patches.instagram

import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Matches SponsoredContentController.insertItem — the central ad insertion
 * point for feed, grid, story, and explore ads.
 * Decompiled ref: p000X/C98953oB.java:950
 */
internal val BytecodePatchContext.adInjectorMatch by composingFirstMethod(
    "SponsoredContentController.insertItem",
) {
    accessFlags(AccessFlags.PRIVATE)
    returnType("Z")
    parameterTypes("L", "L")
}

/**
 * Matches the reels/clips sponsored content fetch method.
 * Identified by the unique debug log string at the start of the method body.
 * Decompiled ref: p000X/C2DM.java:1324
 */
internal val BytecodePatchContext.fetchSponsoredContentMatch by composingFirstMethod(
    "fetchSponsoredContent: organicContentIds.size=",
    ", isHeadLoad=",
    ", isPrefetch=",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
}

// Note: a DqI() ad classifier patch (AbstractC144325fA.A00) was considered
// but dropped. DqI() gates more than just ad labels — it affects playback
// handling (C1MG.java:55), ad session tracking (ViewOnKeyListenerC100373qT),
// and media behavior (C112254Mz). Additionally, ReelItem.DqI() has an
// independent `this.A0z == A0Z` code path that bypasses the underlying
// classifier entirely. The risk of breaking non-ad media behavior outweighs
// the benefit as a safety net, given Patches 1+2 already block ad insertion
// and fetching.
