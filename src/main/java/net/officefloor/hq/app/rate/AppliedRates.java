package net.officefloor.hq.app.rate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The older versions of some sheets' rates, to tell which version of each rate applies to a claim: a
 *  forward-only version applies only to claims added after it was saved, so a claim already on the sheet
 *  then keeps the newest version before it that applies to it. */
public final class AppliedRates {

    /** Each rate's older versions, newest first, by sheet, grade and from-date. */
    private final Map<String, List<RateChange>> older = new HashMap<>();

    private AppliedRates() {
    }

    /** The older versions of the rates of these sheets. */
    public static AppliedRates of(RateChangeRepository changes, Collection<String> sheetIds) {
        AppliedRates applied = new AppliedRates();
        for (String sheetId : sheetIds) {
            Map<String, List<RateChange>> byRate = new HashMap<>();
            for (RateChange change : changes.findBySheetIdOrderByIdAsc(sheetId)) {
                byRate.computeIfAbsent(key(sheetId, change.getGrade().name(), change.getFrom()), k -> new ArrayList<>())
                        .add(0, change);
            }
            // The newest change of each rate made the version the rate holds.
            byRate.forEach((key, versions) -> applied.older.put(key, versions.subList(1, versions.size())));
        }
        return applied;
    }

    /** The rate's older versions, newest first, down to the newest that applies to every claim. */
    public List<RateChange> olderVersions(Rate rate) {
        List<RateChange> versions = new ArrayList<>();
        if (rate.getForwardAfter() == null) return versions;
        for (RateChange change : older.getOrDefault(key(rate), List.of())) {
            versions.add(change);
            if (change.getForwardAfter() == null) break;
        }
        return versions;
    }

    /** The version of the rate that applies to the claim: the rate as it is when its newest does, else the
     *  newest older one that does; null when none does. */
    public Rate applied(Rate rate, Long claimId) {
        if (rate.appliesToClaim(claimId)) return rate;
        for (RateChange change : older.getOrDefault(key(rate), List.of())) {
            if (change.appliesToClaim(claimId)) return change.toRate();
        }
        return null;
    }

    /** Of one grade's rates, in their order, the version of each that applies to the claim; those with none
     *  that does are left out. */
    public List<Rate> applied(Iterable<Rate> gradeRates, Long claimId) {
        List<Rate> applied = new ArrayList<>();
        for (Rate rate : gradeRates) {
            Rate version = applied(rate, claimId);
            if (version != null) applied.add(version);
        }
        return applied;
    }

    /** Of one grade's rates on the sheet and each of its parents in turn, the versions that apply to the claim. */
    public List<List<Rate>> appliedLevels(List<? extends Iterable<Rate>> levels, Long claimId) {
        List<List<Rate>> applied = new ArrayList<>();
        for (Iterable<Rate> gradeRates : levels) applied.add(applied(gradeRates, claimId));
        return applied;
    }

    private static String key(Rate rate) {
        return key(rate.getSheetId(), rate.getGrade().name(), rate.getFrom());
    }

    private static String key(String sheetId, String grade, Object from) {
        return sheetId + "|" + grade + "|" + from;
    }
}
