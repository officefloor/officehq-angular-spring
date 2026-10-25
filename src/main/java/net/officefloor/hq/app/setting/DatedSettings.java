package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.person.Grade;

/**
 * A sheet's dated settings as the store held them when read: for each key, the values it takes from
 * their from-dates, in date order. The value in force on a date is the one of the latest from-date on or
 * before it; before every from-date, and from a blank value, a key has its starting value (the floor
 * {@link SettingService#DEFAULT_FLOOR}, the late percent {@link SettingService#DEFAULT_LATE_PCT}, the terms
 * {@link SettingService#DEFAULT_TERMS}; none for the clerk limit, the proof threshold, the note threshold, the cap, a grade's own terms and a grade's grace days).
 */
public final class DatedSettings {

    private final Map<String, List<SheetSettingDated>> byKey;

    private DatedSettings(Map<String, List<SheetSettingDated>> byKey) {
        this.byKey = byKey;
    }

    /** The sheet's dated settings as the store holds them, within the caller's transaction. */
    public static DatedSettings of(SheetSettingDatedRepository settings, String sheetId) {
        return of(settings.findBySheetIdOrderByKeyAscFromAsc(sheetId));
    }

    /** Dated settings of the values given, in key then date order. */
    public static DatedSettings of(List<SheetSettingDated> settings) {
        Map<String, List<SheetSettingDated>> byKey = new LinkedHashMap<>();
        for (SheetSettingDated s : settings) byKey.computeIfAbsent(s.getKey(), k -> new ArrayList<>()).add(s);
        return new DatedSettings(byKey);
    }

    /** The key's values from their from-dates, in date order. */
    public List<SheetSettingDated> history(String key) {
        return byKey.getOrDefault(key, List.of());
    }

    /** The keys that have a value recorded. */
    public Iterable<String> keys() {
        return byKey.keySet();
    }

    /** The key's value in force on the date, or null when it has none then (blank or before every from-date). */
    public String on(String key, LocalDate date) {
        String value = null;
        if (date == null) return null;
        for (SheetSettingDated s : history(key)) {
            if (s.getFrom().isAfter(date)) break;
            value = s.getValue();
        }
        return value == null || value.isEmpty() ? null : value;
    }

    private BigDecimal decimalOn(String key, LocalDate date) {
        String value = on(key, date);
        return value == null ? null : new BigDecimal(value);
    }

    /** The floor in force on the date, with two decimals. */
    public BigDecimal floorOn(LocalDate date) {
        BigDecimal floor = decimalOn(SettingService.FLOOR, date);
        return floor == null ? SettingService.DEFAULT_FLOOR : floor;
    }

    /** The late percent in force on the date, with two decimals. */
    public BigDecimal latePctOn(LocalDate date) {
        BigDecimal pct = decimalOn(SettingService.LATE_PCT, date);
        return pct == null ? SettingService.DEFAULT_LATE_PCT : pct;
    }

    /** The cap in force on the date, or null when there is none. */
    public BigDecimal capOn(LocalDate date) {
        return decimalOn(SettingService.CAP, date);
    }

    /** The proof threshold in force on the date, or null when there is none. */
    public BigDecimal proofThresholdOn(LocalDate date) {
        return decimalOn(SettingService.PROOF_THRESHOLD, date);
    }

    /** The note threshold in force on the date, or null when there is none. */
    public BigDecimal noteThresholdOn(LocalDate date) {
        return decimalOn(SettingService.NOTE_THRESHOLD, date);
    }

    /** The clerk limit in force on the date, or null when there is none. */
    public BigDecimal clerkLimitOn(LocalDate date) {
        return decimalOn(SettingService.CLERK_LIMIT, date);
    }

    /** The sheet's terms in force on the date, in days. */
    public int termsOn(LocalDate date) {
        String value = on(SettingService.TERMS, date);
        return value == null ? SettingService.DEFAULT_TERMS : Integer.parseInt(value);
    }

    /** The grade's own terms in force on the date, in days, or null when it uses the sheet's then. */
    public Integer gradeTermsOn(Grade grade, LocalDate date) {
        String value = on(SettingService.termsKey(grade), date);
        return value == null ? null : Integer.valueOf(value);
    }

    /** The grade's grace days in force on the date, or 0 when it has none then (no grade has none). */
    public int graceOn(Grade grade, LocalDate date) {
        String value = grade == null ? null : on(SettingService.graceKey(grade), date);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** The terms of a grade in force on the date: its own, else the sheet's (no grade has the sheet's). */
    public int termsOn(Grade grade, LocalDate date) {
        Integer own = grade == null ? null : gradeTermsOn(grade, date);
        return own != null ? own : termsOn(date);
    }
}
