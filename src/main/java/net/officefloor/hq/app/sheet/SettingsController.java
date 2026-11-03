package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import net.officefloor.hq.app.history.History;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}/settings")
@Validated
public class SettingsController {

    /** What the screen shows of a sheet's settings, each dated one as in force on the sheet's today; the clerk limit as text with two decimals
     *  (null when there is no limit), the floor as text with two decimals, the terms in days, and the late percent as text with two
     *  decimals, and the policy date (null when it is blank: the sheet's today stands in), and the
     *  period numbering from date (null when it is blank: off), and the terms each grade has of its own
     *  (a grade not in it takes the sheet's terms), and the cap as text with two decimals (null when
     *  there is no cap), and the floors, late percents and caps recorded, each from its from-date, earliest
     *  first (a claim takes the one in force on its date, or else the sheet's starting one); and the
     *  proof threshold as text with two decimals (null when there is none); and the permission matrix:
     *  for each action, the comma separated roles that may take it (blank: the standing rule); and the
     *  grace days (null when blank: off); and the note threshold as text with two decimals (null when
     *  there is none); and the entry date (null when blank: off); and the grace days each grade has in
     *  force (a grade not in it has none), and the grace days recorded for each grade, each from its
     *  from-date, earliest first (null: blank, none); and the proof threshold each grade has of its
     *  own in force, as text with two decimals (a grade not in it takes the sheet's); and the pot as
     *  text with two decimals (null when there is none); and the parent sheet's id (null when there is
     *  none); and the fiscal year start month, 1 to 12; and the alert threshold as text with two
     *  decimals (null when blank: off); and the rounding mode in force, half-up or half-even (null when
     *  blank: none, a claim rounds by the policy date), and the rounding modes recorded, each from its
     *  from-date, earliest first (null: blank); and the review threshold in force as text with two
     *  decimals (null when there is none); and the escalation gap in days (null when blank: off); and
     *  the review SLA in days (null when blank: none); and the proof kinds, comma separated (empty when
     *  blank: none); and the default grade (null when blank: A); and the clerk limit each grade has of
     *  its own in force, as text with two decimals (a grade not in it takes the sheet's). */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap, List<DatedValue> floors,
            List<DatedValue> latePcts, List<DatedValue> caps, String proofThreshold,
            Map<String, String> permissions, Integer graceDays, String noteThreshold, LocalDate entryDate,
            Map<String, Integer> gradeGraces, Map<String, List<DatedDays>> graces,
            Map<String, String> gradeProofThresholds, String pot, String parent, int fiscalStartMonth,
            String alertThreshold, String rounding, List<DatedText> roundings, String reviewThreshold,
            Integer escalateAfter, Integer reviewSla, String proofKinds, String defaultGrade,
            Map<String, String> gradeClerkLimits) {
        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms, BigDecimal latePct,
                LocalDate policyDate, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms,
                BigDecimal cap, List<SettingValue> floors, List<SettingValue> latePcts,
                List<SettingValue> caps, BigDecimal proofThreshold, Map<String, String> permissions,
                Integer graceDays, BigDecimal noteThreshold, LocalDate entryDate, Map<String, Integer> gradeGraces,
                Map<String, List<SettingValue>> graces, Map<String, BigDecimal> gradeProofThresholds, BigDecimal pot,
                String parent, int fiscalStartMonth, BigDecimal alertThreshold, Optional<Rounding> rounding,
                List<SettingValue> roundings, BigDecimal reviewThreshold, Integer escalateAfter,
                Integer reviewSla, List<String> proofKinds, String defaultGrade,
                Map<String, BigDecimal> gradeClerkLimits) {
            Map<String, List<DatedDays>> gracesView = new TreeMap<>();
            graces.forEach((grade, values) -> gracesView.put(grade, values.stream().map(DatedDays::of).toList()));
            Map<String, String> thresholdsView = new TreeMap<>();
            gradeProofThresholds.forEach((grade, v) -> thresholdsView.put(grade, v.setScale(2).toPlainString()));
            return new SettingsView(clerkLimit == null ? null : clerkLimit.setScale(2).toPlainString(),
                    floor.setScale(2).toPlainString(), terms, latePct.setScale(2).toPlainString(), policyDate,
                    periodNumberingFrom, gradeTerms, cap == null ? null : cap.setScale(2).toPlainString(),
                    floors.stream().map(DatedValue::of).toList(), latePcts.stream().map(DatedValue::of).toList(),
                    caps.stream().map(DatedValue::of).toList(),
                    proofThreshold == null ? null : proofThreshold.setScale(2).toPlainString(), permissions, graceDays,
                    noteThreshold == null ? null : noteThreshold.setScale(2).toPlainString(), entryDate, gradeGraces,
                    gracesView, thresholdsView, pot == null ? null : pot.setScale(2).toPlainString(), parent,
                    fiscalStartMonth, alertThreshold == null ? null : alertThreshold.setScale(2).toPlainString(),
                    rounding.map(Rounding::text).orElse(null), roundings.stream().map(DatedText::rounding).toList(),
                    reviewThreshold == null ? null : reviewThreshold.setScale(2).toPlainString(), escalateAfter,
                    reviewSla, String.join(",", proofKinds), defaultGrade, twoDecimals(gradeClerkLimits));
        }
    }

    /** Values by grade as text with two decimals. */
    private static Map<String, String> twoDecimals(Map<String, BigDecimal> values) {
        Map<String, String> view = new TreeMap<>();
        values.forEach((grade, v) -> view.put(grade, v.setScale(2).toPlainString()));
        return view;
    }

    /** A whole number of days a dated setting takes from a date; null when blank. */
    public record DatedDays(LocalDate from, Integer value) {
        static DatedDays of(SettingValue v) {
            return new DatedDays(v.getFrom(), v.getValue() == null ? null : v.getValue().intValue());
        }
    }

    /** A value a dated setting takes from a date, as text with two decimals; null when blank. */
    public record DatedValue(LocalDate from, String value) {
        static DatedValue of(SettingValue v) {
            return new DatedValue(v.getFrom(), v.getValue() == null ? null : v.getValue().setScale(2).toPlainString());
        }
    }

    /** A word a dated setting takes from a date; null when blank. */
    public record DatedText(LocalDate from, String value) {
        static DatedText rounding(SettingValue v) {
            return new DatedText(v.getFrom(), Rounding.ofCode(v.getValue()).map(Rounding::text).orElse(null));
        }
    }

    /** A yyyy-mm-dd date, or blank. */
    static final String DATE = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*";

    /** A dated setting's form: its from-date is a date, or blank for the sheet's today. */
    interface Dated {
        String from();

        default LocalDate fromValue() {
            return date(from());
        }
    }

    /** A date typed (yyyy-mm-dd), or null when blank. */
    static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
        }
    }

    /** The clerk limit form: 0.00 or more with up to two decimals, or blank for no limit; from a
     *  date, or blank for the sheet's today. */
    public record ClerkLimitForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal limitValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The cap form: 0.00 or more with up to two decimals, or blank for no cap; from a date, or
     *  blank for the sheet's today. */
    public record CapForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal capValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The proof threshold form: 0.00 or more with up to two decimals, or blank for none; from a
     *  date, or blank for the sheet's today. */
    public record ProofThresholdForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal thresholdValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The note threshold form: 0.00 or more with up to two decimals, or blank for none; from a
     *  date, or blank for the sheet's today. */
    public record NoteThresholdForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal thresholdValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The review threshold form: 0.00 or more with up to two decimals, or blank for none; from a
     *  date, or blank for the sheet's today. */
    public record ReviewThresholdForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal thresholdValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The floor form: 0.00 or more with up to two decimals; from a date, or blank for the sheet's
     *  today. */
    public record FloorForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal floorValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The rounding mode form: half-up or half-even, or blank for none; from a date, or blank for
     *  the sheet's today. */
    public record RoundingForm(@Pattern(regexp = "\\s*(half-up|half-even)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        Rounding roundingValue() {
            return value == null || value.isBlank() ? null : Rounding.ofText(value.trim()).orElseThrow();
        }
    }

    /** The terms form: a whole number of days, 0 or more; from a date, or blank for the sheet's
     *  today. */
    public record TermsForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,4}\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        int termsValue() {
            return Integer.parseInt(value.trim());
        }
    }

    /** The late percent form: a percent of 0.00 or more with up to two decimals; from a date, or
     *  blank for the sheet's today. */
    public record LatePctForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal latePctValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The policy date form: a date (yyyy-mm-dd), or blank for the sheet's today. */
    public record PolicyDateForm(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        LocalDate policyDateValue() {
            return date(value);
        }
    }

    /** The period numbering from form: a date (yyyy-mm-dd), or blank for off. */
    public record PeriodNumberingFromForm(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        LocalDate fromValue() {
            return date(value);
        }
    }

    /** The entry date form: a date (yyyy-mm-dd), or blank for off. */
    public record EntryDateForm(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        LocalDate entryDateValue() {
            return date(value);
        }
    }

    /** The grace days form: a whole number of days, 0 or more, or blank for off. */
    public record GraceDaysForm(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value) {

        Integer graceDaysValue() {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        }
    }

    /** The escalation gap form: a whole number of days, 0 or more, or blank for off; not dated. */
    public record EscalateAfterForm(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value) {

        Integer daysValue() {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        }
    }

    /** The review SLA form: a whole number of days, 0 or more, or blank for none; not dated. */
    public record ReviewSlaForm(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value) {

        Integer daysValue() {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        }
    }

    /** A grade's grace days form: a whole number of days, 0 or more, or blank for none; from a date,
     *  or blank for the sheet's today. */
    public record GradeGraceForm(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        Integer daysValue() {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        }
    }

    /** The pot form: 0.00 or more with up to two decimals, or blank for none; not dated. */
    public record PotForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value) {

        BigDecimal potValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The alert threshold form: 0.00 or more with up to two decimals, or blank for off; not dated. */
    public record AlertThresholdForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value) {

        BigDecimal thresholdValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The fiscal year start month form: a month, 1 to 12; not dated. */
    public record FiscalStartMonthForm(@NotBlank @Pattern(regexp = "\\s*(0?[1-9]|1[0-2])\\s*") String value) {

        int monthValue() {
            return Integer.parseInt(value.trim());
        }
    }

    /** The proof kinds form: a comma separated list of kinds (receipt, photo), or blank for none;
     *  not dated. */
    public record ProofKindsForm(@Size(max = 100)
            @Pattern(regexp = "\\s*((receipt|photo)\\s*(,\\s*(receipt|photo)\\s*)*)?") String value) {

        List<String> kindsValue() {
            return value == null || value.isBlank() ? List.of()
                    : Arrays.stream(value.split(",")).map(String::trim).toList();
        }
    }

    /** The default grade form: one of the grades (A, B, C), or blank for A; not dated. */
    public record DefaultGradeForm(@Pattern(regexp = "\\s*[ABC]?\\s*") String value) {

        String gradeValue() {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** The parent form: a sheet id, or blank for none; not dated. */
    public record ParentForm(@Size(max = 100) String value) {

        String parentValue() {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** The permission form: a comma separated list of roles (clerk, steward), or blank for the
     *  standing rule. */
    public record PermissionForm(@Size(max = 200)
            @Pattern(regexp = "\\s*((clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String value) {

        /** The roles as stored: each once, comma separated with no spaces; "" when blank. */
        String rolesValue() {
            return value == null ? "" : String.join(",", Arrays.stream(value.split(","))
                    .map(String::trim).filter(r -> !r.isEmpty()).distinct().toList());
        }
    }

    private final SheetService service;
    private final History history;

    public SettingsController(SheetService service, History history) {
        this.service = service;
        this.history = history;
    }

    /** The sheet's settings; as of a day when one is given, each dated one (and the policy date) as
     *  it stood at the end of that day (see {@link History}), in force on that day. */
    @GetMapping
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return asOf != null ? view(sheetId.trim(), history.asOf(sheetId.trim(), asOf)) : view(sheetId.trim());
    }

    /** The settings view of a sheet as it stood at the end of a day. */
    private SettingsView view(String id, History.SheetAsOf then) {
        LocalDate day = then.day();
        Function<String, BigDecimal> value = key -> then.setting(key, day).map(SettingValue::getValue).orElse(null);
        Map<String, Integer> gradeTerms = new TreeMap<>();
        for (String grade : List.of("A", "B", "C")) {
            then.setting("terms-" + grade, day).ifPresent(v -> gradeTerms.put(grade, v.getValue().intValue()));
        }
        int terms = then.setting("terms", day).map(v -> v.getValue().intValue()).orElse(SheetService.STARTING_TERMS);
        Map<String, Integer> gradeGraces = new TreeMap<>();
        Map<String, List<SettingValue>> graces = new TreeMap<>();
        for (String grade : List.of("A", "B", "C")) {
            then.setting("grace-" + grade, day).map(SettingValue::getValue)
                    .ifPresent(v -> gradeGraces.put(grade, v.intValue()));
            graces.put(grade, then.recorded("grace-" + grade));
        }
        Map<String, BigDecimal> gradeProofThresholds = new TreeMap<>();
        for (String grade : List.of("A", "B", "C")) {
            then.setting("proof-threshold-" + grade, day).map(SettingValue::getValue)
                    .ifPresent(v -> gradeProofThresholds.put(grade, v));
        }
        Map<String, BigDecimal> gradeClerkLimits = new TreeMap<>();
        for (String grade : List.of("A", "B", "C")) {
            then.setting("clerk-limit-" + grade, day).map(SettingValue::getValue)
                    .ifPresent(v -> gradeClerkLimits.put(grade, v));
        }
        return SettingsView.of(value.apply("clerk-limit"), then.basis().floor(day), terms, then.basis().latePct(day),
                then.policyDate(), service.periodNumberingFrom(id), gradeTerms, then.basis().cap(),
                then.recorded("floor"), then.recorded("late-pct"), then.recorded("cap"),
                value.apply("proof-threshold"), service.permissions(id), service.graceDays(id),
                value.apply("note-threshold"), service.entryDate(id), gradeGraces, graces,
                gradeProofThresholds, service.pot(id), service.parent(id), service.fiscalStartMonth(id),
                service.alertThreshold(id),
                then.setting(Rounding.KEY, day).map(SettingValue::getValue).flatMap(Rounding::ofCode),
                then.recorded(Rounding.KEY), value.apply("review-threshold"), service.escalateAfter(id),
                service.reviewSla(id), service.proofKinds(id), service.defaultGradeSet(id), gradeClerkLimits);
    }

    /** The settings view of a sheet as the store holds it now. */
    private SettingsView view(String id) {
        return SettingsView.of(service.clerkLimit(id), service.floor(id), service.terms(id), service.latePct(id),
                service.policyDate(id), service.periodNumberingFrom(id), service.gradeTerms(id), service.cap(id),
                service.recorded(id, "floor"), service.recorded(id, "late-pct"),
                service.recorded(id, "cap"), service.proofThreshold(id), service.permissions(id),
                service.graceDays(id), service.noteThreshold(id), service.entryDate(id), service.gradeGraces(id),
                gracesOf(id), service.gradeProofThresholds(id), service.pot(id), service.parent(id),
                service.fiscalStartMonth(id), service.alertThreshold(id), service.rounding(id),
                service.recorded(id, Rounding.KEY), service.reviewThreshold(id), service.escalateAfter(id),
                service.reviewSla(id), service.proofKinds(id), service.defaultGradeSet(id),
                service.gradeClerkLimits(id));
    }

    /** The grace days recorded for each grade of a sheet, earliest from-date first. */
    private Map<String, List<SettingValue>> gracesOf(String id) {
        Map<String, List<SettingValue>> graces = new TreeMap<>();
        for (String grade : List.of("A", "B", "C")) {
            graces.put(grade, service.recorded(id, "grace-" + grade));
        }
        return graces;
    }

    /** The settings view of a sheet just saved. */
    private SettingsView view(Sheet sheet) {
        return view(sheet.getId());
    }

    @PutMapping("/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm form) {
        return view(service.setClerkLimit(sheetId.trim(), user.trim(), form.limitValue(), form.fromValue()));
    }

    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorForm form) {
        return view(service.setFloor(sheetId.trim(), user.trim(), form.floorValue(), form.fromValue()));
    }

    @PutMapping("/rounding")
    public SettingsView setRounding(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoundingForm form) {
        return view(service.setRounding(sheetId.trim(), user.trim(), form.roundingValue(), form.fromValue()));
    }

    @PutMapping("/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm form) {
        return view(service.setTerms(sheetId.trim(), user.trim(), form.termsValue(), form.fromValue()));
    }

    @PutMapping("/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm form) {
        return view(service.setGradeTerms(sheetId.trim(), user.trim(), grade, form.termsValue(), form.fromValue()));
    }

    @PutMapping("/grace/{grade}")
    public SettingsView setGrace(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GradeGraceForm form) {
        return view(service.setGrace(sheetId.trim(), user.trim(), grade, form.daysValue(), form.fromValue()));
    }

    @PutMapping("/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctForm form) {
        return view(service.setLatePct(sheetId.trim(), user.trim(), form.latePctValue(), form.fromValue()));
    }

    @PutMapping("/policy-date")
    public SettingsView setPolicyDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PolicyDateForm form) {
        return view(service.setPolicyDate(sheetId.trim(), user.trim(), form.policyDateValue()));
    }

    @PutMapping("/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodNumberingFromForm form) {
        return view(service.setPeriodNumberingFrom(sheetId.trim(), user.trim(), form.fromValue()));
    }

    @PutMapping("/cap")
    public SettingsView setCap(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CapForm form) {
        return view(service.setCap(sheetId.trim(), user.trim(), form.capValue(), form.fromValue()));
    }

    @PutMapping("/proof-threshold")
    public SettingsView setProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdForm form) {
        return view(service.setProofThreshold(sheetId.trim(), user.trim(), form.thresholdValue(), form.fromValue()));
    }

    @PutMapping("/clerk-limit/{grade}")
    public SettingsView setGradeClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm form) {
        return view(service.setGradeClerkLimit(sheetId.trim(), user.trim(), grade, form.limitValue(),
                form.fromValue()));
    }

    @PutMapping("/proof-threshold/{grade}")
    public SettingsView setGradeProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdForm form) {
        return view(service.setGradeProofThreshold(sheetId.trim(), user.trim(), grade, form.thresholdValue(),
                form.fromValue()));
    }

    @PutMapping("/note-threshold")
    public SettingsView setNoteThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody NoteThresholdForm form) {
        return view(service.setNoteThreshold(sheetId.trim(), user.trim(), form.thresholdValue(), form.fromValue()));
    }

    @PutMapping("/review-threshold")
    public SettingsView setReviewThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReviewThresholdForm form) {
        return view(service.setReviewThreshold(sheetId.trim(), user.trim(), form.thresholdValue(), form.fromValue()));
    }

    @PutMapping("/grace-days")
    public SettingsView setGraceDays(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GraceDaysForm form) {
        return view(service.setGraceDays(sheetId.trim(), user.trim(), form.graceDaysValue()));
    }

    @PutMapping("/entry-date")
    public SettingsView setEntryDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody EntryDateForm form) {
        return view(service.setEntryDate(sheetId.trim(), user.trim(), form.entryDateValue()));
    }

    @PutMapping("/pot")
    public SettingsView setPot(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PotForm form) {
        return view(service.setPot(sheetId.trim(), user.trim(), form.potValue()));
    }

    @PutMapping("/escalate-after")
    public SettingsView setEscalateAfter(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody EscalateAfterForm form) {
        return view(service.setEscalateAfter(sheetId.trim(), user.trim(), form.daysValue()));
    }

    @PutMapping("/review-sla")
    public SettingsView setReviewSla(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReviewSlaForm form) {
        return view(service.setReviewSla(sheetId.trim(), user.trim(), form.daysValue()));
    }

    @PutMapping("/alert-threshold")
    public SettingsView setAlertThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AlertThresholdForm form) {
        return view(service.setAlertThreshold(sheetId.trim(), user.trim(), form.thresholdValue()));
    }

    @PutMapping("/fiscal-start-month")
    public SettingsView setFiscalStartMonth(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FiscalStartMonthForm form) {
        return view(service.setFiscalStartMonth(sheetId.trim(), user.trim(), form.monthValue()));
    }

    @PutMapping("/proof-kinds")
    public SettingsView setProofKinds(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofKindsForm form) {
        return view(service.setProofKinds(sheetId.trim(), user.trim(), form.kindsValue()));
    }

    @PutMapping("/default-grade")
    public SettingsView setDefaultGrade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DefaultGradeForm form) {
        return view(service.setDefaultGrade(sheetId.trim(), user.trim(), form.gradeValue()));
    }

    @PutMapping("/parent")
    public SettingsView setParent(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ParentForm form) {
        return view(service.setParent(sheetId.trim(), user.trim(), form.parentValue()));
    }

    @PutMapping("/permissions/{action}")
    public SettingsView setPermission(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PermissionForm form) {
        return view(service.setPermission(sheetId.trim(), user.trim(), action, form.rolesValue()));
    }
}
