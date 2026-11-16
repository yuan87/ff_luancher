package com.ff.launcher.viewmodel

import android.icu.text.AlphabeticIndex
import android.icu.text.RuleBasedCollator
import com.ff.launcher.data.AppInfo
import java.util.Locale

/**
 * Pure function building the [HomeListState] from raw inputs.
 *
 * Extracted from [LauncherViewModel] to make the locale-collated, Pinyin-rescued
 * alphabetic grouping independently testable. No Android framework dependencies
 * beyond ICU (available since API 24 / minSdk 26).
 *
 * Called on [kotlinx.coroutines.Dispatchers.Default] because it rebuilds the ICU
 * index and re-sorts hundreds of localized labels.
 */
object HomeStateBuilder {

    /**
     * Builds the primary home list. The ICU index locale IS the scroller's locale (a
     * confirmed product decision): ja devices get kana buckets, ko devices jamo, zh
     * devices pinyin letters — and only buckets that actually contain apps become
     * sections, so the strip renders no dead nodes.
     *
     * Pinyin rescue: labels the system locale's collation can't place (hanzi under en,
     * digits, symbols) get a SECOND pass through the [Locale.SIMPLIFIED_CHINESE] index
     * so hanzi apps still land in the A–Z sections under non-CJK systems — while ja/ko
     * natives, already NORMAL in their own locale, never touch the rescue and keep their
     * native sections. Everything the two indexes can't place ends up in one trailing `#`
     * section. In-group sorting uses the zh collation for A–Z sections (latin + rescued
     * hanzi order correctly) and the primary collation otherwise.
     *
     * @param apps All installed apps.
     * @param orderedFavoriteKeys User-arranged favorite component keys, in display order.
     * @param hiddenKeys Component keys of hidden apps (excluded from both favorites and sections).
     * @return A [HomeListState] with flattened rows, section jump targets, and favorite count.
     */
    fun build(
        apps: List<AppInfo>,
        orderedFavoriteKeys: List<String>,
        hiddenKeys: Set<String>,
    ): HomeListState {
        // Hidden apps leave the home entirely — both the A–Z sections and favorites.
        val visibleApps = apps.filter { it.componentKey !in hiddenKeys }
        val favoritesSet = orderedFavoriteKeys.toSet()
        val primaryBuilder = runCatching {
            AlphabeticIndex<Void>(Locale.getDefault()).addLabels(Locale.ENGLISH)
        }.getOrNull()
        val primaryCollator = primaryBuilder?.collator
        val primary = runCatching { primaryBuilder?.buildImmutableIndex() }.getOrNull()
        val pinyinBuilder = runCatching {
            AlphabeticIndex<Void>(Locale.SIMPLIFIED_CHINESE).addLabels(Locale.ENGLISH)
        }.getOrNull()
        val pinyinCollator = pinyinBuilder?.collator
        val pinyin = runCatching { pinyinBuilder?.buildImmutableIndex() }.getOrNull()

        if (primary == null || primaryCollator == null || pinyin == null || pinyinCollator == null) {
            // Environment fallback (e.g. host JVM unit tests without native android.icu runtime)
            val rows = mutableListOf<HomeRow>()
            val visibleByKey = visibleApps.associateBy { it.componentKey }
            val favoriteApps = orderedFavoriteKeys.mapNotNull { visibleByKey[it] }
            favoriteApps.forEach { rows += HomeRow.AppRow(app = it, isFavorite = true) }
            if (favoriteApps.isNotEmpty()) rows += HomeRow.Divider
            return HomeListState(
                rows = rows,
                sections = emptyList(),
                favoriteCount = favoriteApps.size,
                favoriteKeys = orderedFavoriteKeys,
            )
        }

        // Primary bucket display order for section sequencing.
        val primaryOrder = HashMap<String, Int>()
        for (bucketIndex in 0 until primary.getBucketCount()) {
            val label = sectionLabelOf(primary, bucketIndex)
            if (label != null) primaryOrder[label] = bucketIndex
        }

        data class Placement(val app: AppInfo, val section: String)
        val placements = visibleApps.map { app ->
            val direct = sectionLabelOf(app.label, primary)?.let { label ->
                // NORMAL bucket confirmed by label lookup — keep the direct placement.
                label
            }
            when {
                direct != null -> Placement(app, direct)
                else -> {
                    val rescued = sectionLabelOf(app.label, pinyin)
                    if (rescued != null) Placement(app, rescued) else Placement(app, "#")
                }
            }
        }

        val rows = mutableListOf<HomeRow>()
        val sections = mutableListOf<HomeListState.Section>()

        // Maintain the user's arranged sequence for favorites:
        val visibleByKey = visibleApps.associateBy { it.componentKey }
        val favoriteApps = orderedFavoriteKeys.mapNotNull { visibleByKey[it] }
        favoriteApps.forEach { rows += HomeRow.AppRow(app = it, isFavorite = true) }
        if (favoriteApps.isNotEmpty()) rows += HomeRow.Divider

        val bySection = placements.groupBy { it.section }
        bySection.keys
            .filter { it != "#" }
            .sortedWith(compareBy({ primaryOrder[it] ?: Int.MAX_VALUE }, { it }))
            .forEach { section ->
                // A–Z sections hold latin + pinyin-rescued hanzi → zh collation orders
                // both correctly; native-script sections use their own collation.
                val latinSection = section.length == 1 && section[0] in 'A'..'Z'
                val collator = if (latinSection) pinyinCollator else primaryCollator
                sections += HomeListState.Section(section, rows.size)
                rows += HomeRow.Header(section)
                bySection.getValue(section)
                    .sortedWith { a, b -> compareLabels(a.app.label, b.app.label, collator, a.app.componentKey, b.app.componentKey) }
                    .forEach { rows += HomeRow.AppRow(it.app, isFavorite = it.app.componentKey in favoritesSet) }
            }
        bySection["#"]?.let { members ->
            sections += HomeListState.Section("#", rows.size)
            rows += HomeRow.Header("#")
            members.sortedWith { a, b -> compareLabels(a.app.label, b.app.label, pinyinCollator, a.app.componentKey, b.app.componentKey) }
                .forEach { rows += HomeRow.AppRow(it.app, isFavorite = it.app.componentKey in favoritesSet) }
        }
        return HomeListState(
            rows = rows,
            sections = sections,
            favoriteCount = favoriteApps.size,
            favoriteKeys = orderedFavoriteKeys,
        )
    }

    /** The real (NORMAL) bucket label for [bucketIndex], or null for under/overflow. */
    private fun sectionLabelOf(
        index: AlphabeticIndex.ImmutableIndex<Void>,
        bucketIndex: Int,
    ): String? = runCatching {
        val bucket = index.getBucket(bucketIndex)
        if (bucket.labelType != AlphabeticIndex.Bucket.LabelType.NORMAL) return@runCatching null
        bucket.label.trim().takeUnless { it.isEmpty() }
    }.getOrNull()

    /** The section label [label] resolves to under [index], or null if it overflows. */
    private fun sectionLabelOf(
        label: String,
        index: AlphabeticIndex.ImmutableIndex<Void>,
    ): String? = runCatching {
        val bucket = index.getBucket(index.getBucketIndex(label))
        if (bucket.labelType != AlphabeticIndex.Bucket.LabelType.NORMAL) return@runCatching null
        bucket.label.trim().takeUnless { it.isEmpty() }
    }.getOrNull()

    private fun compareLabels(
        a: String,
        b: String,
        collator: RuleBasedCollator,
        aKey: String,
        bKey: String,
    ): Int = collator.compare(a, b).takeIf { it != 0 } ?: aKey.compareTo(bKey)
}
