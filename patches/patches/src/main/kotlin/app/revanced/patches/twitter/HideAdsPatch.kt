package app.revanced.patches.twitter

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Hides ads from X (Twitter) display surfaces. Self-contained — no
 * companion patch required. "Also block ad fetch" is an optional
 * network-saver layer that stops the upstream requests too.
 *
 * What each patch hides:
 *  Patch 1 — Compact promoted tweets (dedicated compact layout).
 *  Patch 2 — Emphasized promoted tweets (larger media/CTA layout).
 *  Patch 3 — Sponsored tab on Live Event landing pages.
 *  Patch 4 — All passive Premium upsell surfaces: HomeNav banner,
 *            HomeSidebar, PremiumNav, ReplyInterstitial, profile
 *            "Get verified" nudges (own + other profiles).
 *  Patch 5 — Google-SDK native ads on the home/profile timeline
 *            (gms_native_ads), in immersive media
 *            (immersive_gms_native_ads), and on the Explore event
 *            hero (event_summary_hero_rtb_google_ad).
 *  Patch 6 — Video pre-roll (and mid-roll) ads.
 *  Patch 7 — Sponsored trends in Explore, Search, and the Trends
 *            module. Organic trends still render.
 *  Patch 8 — Every item on the rtbImageAd URT envelope at parse
 *            time: Google RTB, Taboola RTB, and any future DSP
 *            that ships over it. Closes the Taboola gap that
 *            Patch 5 alone can't cover. Duplicates Also block ad
 *            fetch's Patch 3; applying both is idempotent.
 *  Patch 9 — Promoted tweets that would otherwise fall through to
 *            the generic tweet binder, plus promoted users across
 *            every shared user-row binder (Who-to-follow, Creators
 *            for you, typeahead, incoming friendships, etc.).
 *            Earned-media items pass through untouched.
 *
 * Surfaces deliberately not blocked:
 *
 * User-initiated Premium gates (fire only when the user invokes a
 * gated feature):
 *  - Edit tweet, Download video, DM verification-only gates, profile
 *    Articles / Highlights empty-state upsell, video-length hint,
 *    Grok 2 model picker.
 *
 * Already-in-ad-context surfaces (reachable only after engaging with
 * an ad; become unreachable once the underlying ad is hidden):
 *  - Ad action-sheet entries, "Why am I seeing this ad?" sheet,
 *    immersive "Learn more" CTA, preroll CTA buttons and offline-ad
 *    opt-in dialog.
 *
 * Organic surfaces with rare paid variants (mostly organic content;
 * paid variant slips through here but "Also block ad fetch" covers
 * some at the JSON layer):
 *  - Shop modules / product cards.
 *  - Community Spotlight modules.
 *
 * Known uncovered surfaces:
 *  - Who-to-Follow carousel shell. Patch 9 already filters the
 *    promoted users inside, but when every user in a carousel is
 *    promoted an empty shell still renders. A blanket wrapper-matcher
 *    patch would remove organic Who-to-Follow too.
 *  - Promoted event/spotlight hero in Explore (C42799m). The
 *    promoted marker sits behind a two-hop chain and has no stable
 *    string anchor.
 *  - Promoted sticker categories in the sticker picker. No stable
 *    string anchor to the flag or the read site; surface is
 *    user-initiated (picker only opens during tweet composition).
 */
@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides promoted posts, promoted trends, Google RTB ads, sponsored live-event tabs, Premium upsells, and video pre-roll ads.",
) {
    compatibleWith("com.twitter.android")
    extendWith("extensions/extension.rve")

    apply {
        // -- Patch 1: Compact promoted tweet binder declines --
        //
        // Return false from the matcher so the adapter moves on to the
        // next binder instead of rendering the dedicated compact
        // promoted layout. On its own, this only suppresses the
        // promoted chrome — the ad content would still render via the
        // generic tweet binder that matches on tweet flag bits. Patch 9
        // adds the pre-check that drops the item from every binder.
        compactPromotedTweetMatcherMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 2: Emphasized promoted tweet binder declines --
        //
        // Same pattern as Patch 1, for the emphasized layout variant
        // (larger media / CTA). Also relies on Patch 9 to finish the
        // job at the generic tweet binder.
        emphasizedPromotedTweetMatcherMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 3: Sponsored live-event tab returns null --
        //
        // The tab customizer already has a null branch for the "no
        // customization" case, so callers handle a null return. At
        // entry we short-circuit to that branch, hiding the sponsor
        // icon + text chrome on Live Event landing pages.
        //
        // TODO: tighter anchor. The customization_info bundle carries
        // a generic CustomizationInfo (image + title color); nothing
        // in the data model marks it as sponsored. We rely on the
        // fact that the only current rendering path inflates
        // R.layout.sponsored_timeline_tab_view. If X ever ships
        // organic branded tabs through the same bundle key, this
        // patch would hide them too.
        sponsoredLiveEventTabMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )

        // -- Patch 4: Premium upsell resolver returns empty sentinel --
        //
        // The upsell config manager constructs a "no upsell"
        // UpsellContent at static init (used internally when a config
        // expires) and keeps it in a static final field. Forcing the
        // UpsellConfig → UpsellContent resolver to return that sentinel
        // makes every passive upsell surface that reads through it —
        // HomeNav, HomeSidebar, PremiumNav, ReplyInterstitial,
        // UserProfileHeader, UserProfileName — render nothing. The
        // resolver is the only method taking UpsellConfig and returning
        // UpsellContent on the anchored class; the sentinel is the only
        // static final field of type UpsellContent on it.
        val upsellConfigManagerClass = upsellConfigManagerClassMatch.classDef

        val upsellContentSentinelField = upsellConfigManagerClass.fields.single { field ->
            field.accessFlags and (AccessFlags.STATIC.value or AccessFlags.FINAL.value) ==
                AccessFlags.STATIC.value or AccessFlags.FINAL.value &&
                field.type == UPSELL_CONTENT_TYPE
        }

        val upsellResolverMethod = upsellConfigManagerClass.methods.single { method ->
            method.returnType == UPSELL_CONTENT_TYPE &&
                method.parameterTypes.toList() == listOf(UPSELL_CONFIG_TYPE)
        }

        classDefs.getOrReplaceMutable(upsellConfigManagerClass).methods.first {
            it.name == upsellResolverMethod.name &&
                it.parameterTypes == upsellResolverMethod.parameterTypes
        }.addInstructions(
            0,
            """
                sget-object v0, ${upsellConfigManagerClass.type}->${upsellContentSentinelField.name}:$UPSELL_CONTENT_TYPE
                return-object v0
            """,
        )

        // -- Patch 5: Google RTB gate always returns false --
        //
        // C48903b1.m48584m() returns true when the RTB DSP type is
        // Google or LocalGoogleInjection. Three Google-SDK native-ad
        // binders — home/profile timeline (gms_native_ads), immersive
        // media (immersive_gms_native_ads), and Explore event hero
        // (event_summary_hero_rtb_google_ad) — use it as their claim
        // predicate. Forcing false makes all three decline, and the
        // adapter skips the item. Taboola items don't go through this
        // gate (Patch 8 handles them at parse time).
        val rtbDspEnumClass = rtbDspEnumClinitMatch.classDef
        val googleFieldName = rtbDspEnumClass.fields.single { it.name == "Google" }.name
        val localGoogleFieldName = rtbDspEnumClass.fields.single {
            it.name == "LocalGoogleInjection"
        }.name

        fun matchesRtbGateShape(method: com.android.tools.smali.dexlib2.iface.Method): Boolean {
            if (method.name.startsWith("<")) return false
            if (method.returnType != "Z") return false
            if (method.parameterTypes.isNotEmpty()) return false

            val fieldRefs = method.implementation?.instructions
                ?.mapNotNull { insn ->
                    (insn as? ReferenceInstruction)?.reference as? FieldReference
                }
                ?.filter { it.definingClass == rtbDspEnumClass.type }
                ?.toList()
                ?: return false

            return fieldRefs.any { it.name == googleFieldName } &&
                fieldRefs.any { it.name == localGoogleFieldName }
        }

        val rtbGateCandidates = classDefs.flatMap { classDef ->
            classDef.methods.mapNotNull { method ->
                if (matchesRtbGateShape(method)) classDef to method else null
            }
        }

        val (rtbTimelineItemClass, rtbGateMethod) = rtbGateCandidates.single()

        classDefs.getOrReplaceMutable(rtbTimelineItemClass).methods.first {
            it.name == rtbGateMethod.name &&
                it.parameterTypes == rtbGateMethod.parameterTypes
        }.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 6: Preroll metadata builder returns null --
        //
        // Drops video pre-roll (and mid-roll) ads at JSON parse time
        // so no preroll domain object is constructed. The URT registry
        // already null-checks the builder result and simply skips
        // absent items, so the player goes directly to the organic
        // content. Lives display-side even though it's a JSON hook
        // because pairing "ad fetched, no ad shown" with ordinary
        // playback would still surface the ad UI.
        patchJsonMapperToReturnNull(JSON_PREROLL_METADATA)

        // -- Patch 7: Promoted trend metadata builder returns null --
        //
        // Same pattern as Patch 6, for sponsored trends in Explore,
        // Search, and the Trends module. Organic trends still render
        // (they come through a different metadata class). Lives here
        // rather than in Also block ad fetch because the trend binder
        // dereferences the promoted metadata unconditionally — a late
        // hook would NPE instead of rendering organic trends.
        patchJsonMapperToReturnNull(JSON_PROMOTED_TREND_METADATA)

        // -- Patch 8: RTB image ad JSON deserialization returns null --
        //
        // Closes the Taboola gap Patch 5 can't cover. Taboola-flagged
        // RTB items are rewritten into tweet-card shape by a database
        // hydrator BEFORE reaching any RTB binder, so gating at the
        // binder layer (Patch 5) misses them. Dropping the item at
        // JSON parse time prevents the hydrator from running in the
        // first place, and incidentally covers Google RTB and any
        // future DSP on the rtbImageAd envelope. Duplicates Also
        // block ad fetch's Patch 3 so Hide ads stays self-contained;
        // applying both is idempotent.
        patchJsonMapperToReturnNull(JSON_TIMELINE_RTB_IMAGE_AD)

        // -- Patch 9: Shared promoted-content pre-binder filter --
        //
        // Covers two gaps Patches 1-2 leave open:
        //  - Promoted tweets that would otherwise fall through to the
        //    generic tweet binder (which matches on flag bits, not the
        //    "CompactPromotedTweet"/"EmphasizedPromotedTweet" subtype)
        //    and render as plain tweets with the sponsored payload
        //    still visible, just without promoted chrome.
        //  - Promoted users bound by any of the shared user-row
        //    binders (Who to follow, Creators for you, typeahead,
        //    incoming friendships, etc.) — none of which the other
        //    patches touch.
        //
        // Strategy: inject a pre-check into every binder matcher (any
        // method with the Compact matcher's obfuscated name, returning
        // boolean, taking a single non-Object parameter) whose param
        // type has a reachable C47258f envelope. The pre-check reads
        // the envelope and returns false if it's non-null with a
        // non-Earned disclosure type. Earned media (user-opted-in
        // content that isn't a paid ad) passes through untouched.
        //
        // Because the filter fires on every matcher for the item type,
        // non-Earned promoted items are rejected by ALL binders and
        // the adapter skips them entirely — no fallback binder
        // renders the sponsored payload as an organic row.
        //
        // Field paths are resolved structurally (no hardcoded
        // obfuscated names) so the patch survives X's per-release
        // reobfuscation. Two path shapes are supported: a direct
        // field of type C47258f on the timeline item (e.g. the user
        // timeline item) and a one-hop chain through an inner model
        // (e.g. tweet timeline item → tweet model → envelope).
        // Binders whose param type has neither path are left
        // untouched, so unrelated `mo33961a` methods with matching
        // signatures but no envelope reachability are safe.
        val envelopeClass = earnedDisclosureCheckMatch.classDef
        val envelopeType = envelopeClass.type
        val earnedCheckName = earnedDisclosureCheckMatch.method.name
        val matcherMethodName = compactPromotedTweetMatcherMatch.method.name

        // Direct envelope holders: classes with a non-static field of
        // type C47258f. These are the tweet model, user entity, user
        // timeline item, and any other class that carries the envelope.
        val directHolderEnvelopeField = classDefs
            .mapNotNull { classDef ->
                val field = classDef.fields.firstOrNull {
                    it.type == envelopeType &&
                        (it.accessFlags and AccessFlags.STATIC.value) == 0
                }
                field?.let { classDef.type to it }
            }
            .toMap()

        var labelCounter = 0

        classDefs.toList().forEach { ownerClass ->
            ownerClass.methods.toList().forEach matcherLoop@{ method ->
                if (method.name != matcherMethodName) return@matcherLoop
                if (method.returnType != "Z") return@matcherLoop
                if (method.parameterTypes.size != 1) return@matcherLoop
                val paramType = method.parameterTypes[0].toString()
                if (!paramType.startsWith("L")) return@matcherLoop
                if (paramType.startsWith("Ljava/")) return@matcherLoop
                if (method.implementation == null) return@matcherLoop

                val paramClass = classDefs.firstOrNull { it.type == paramType }
                    ?: return@matcherLoop

                // Path 1 (direct): param type carries the envelope itself.
                val directField = paramClass.fields.firstOrNull { field ->
                    field.type == envelopeType &&
                        (field.accessFlags and AccessFlags.STATIC.value) == 0
                }

                // Path 2 (one-hop): param type has a field whose type is
                // a direct envelope holder.
                val intermediateField = if (directField == null) {
                    paramClass.fields.firstOrNull { field ->
                        directHolderEnvelopeField.containsKey(field.type) &&
                            (field.accessFlags and AccessFlags.STATIC.value) == 0
                    }
                } else {
                    null
                }

                if (directField == null && intermediateField == null) return@matcherLoop

                val keepLabel = ":keep_ad_filter_${labelCounter++}"

                val preCheck = if (directField != null) {
                    """
                        iget-object v0, p1, $paramType->${directField.name}:$envelopeType
                        if-eqz v0, $keepLabel
                        invoke-virtual {v0}, $envelopeType->$earnedCheckName()Z
                        move-result v0
                        if-nez v0, $keepLabel
                        const/4 v0, 0x0
                        return v0
                        $keepLabel
                    """
                } else {
                    val holderType = intermediateField!!.type
                    val holderEnvelopeField = directHolderEnvelopeField.getValue(holderType)
                    """
                        iget-object v0, p1, $paramType->${intermediateField.name}:$holderType
                        if-eqz v0, $keepLabel
                        iget-object v0, v0, $holderType->${holderEnvelopeField.name}:$envelopeType
                        if-eqz v0, $keepLabel
                        invoke-virtual {v0}, $envelopeType->$earnedCheckName()Z
                        move-result v0
                        if-nez v0, $keepLabel
                        const/4 v0, 0x0
                        return v0
                        $keepLabel
                    """
                }

                classDefs.getOrReplaceMutable(ownerClass).methods.first {
                    it.name == method.name && it.parameterTypes == method.parameterTypes
                }.addInstructions(0, preCheck)
            }
        }
    }
}
