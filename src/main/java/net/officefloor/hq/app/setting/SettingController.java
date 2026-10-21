package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has, its clerk limit, its floor, its terms, its late percent, its policy date, its period numbering date, its cap, its proof threshold and its note threshold. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
public class SettingController {

    /** The role is clerk, steward or viewer. */
    public record RoleForm(@NotBlank @Size(max = 100) String user, @NotNull String role) {}

    public record RoleView(String user, String role) {

        static RoleView of(SheetRole role) {
            return new RoleView(role.getUser(), role.getRole().code());
        }
    }

    /** A delegation of the steward role to a user name, from a date to a date (yyyy-mm-dd), inclusive. */
    public record DelegationForm(@NotBlank @Size(max = 100) String user,
            @NotNull @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String from,
            @NotNull @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String to) {}

    public record DelegationView(long id, String by, String user, String from, String to) {

        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getDelegator(), d.getUser(), d.getFrom().toString(),
                    d.getTo().toString());
        }
    }

    /** The clerk limit has up to two decimals, 0.00 or more; blank means no limit. The floor has up to two
     * decimals, 0.00 or more. The terms are a whole number of days, 0 or more; so are each grade's terms, where
     * blank means the grade uses the sheet's terms. The late percent has up to two
     * decimals, 0.00 or more. The policy date is a date (yyyy-mm-dd); blank means the sheet's today. The period numbering date is a date; blank means
     * off. The cap has up to two decimals, 0.00 or more; blank means no cap. The proof threshold has up to two
     * decimals, 0.00 or more; blank means none; so has the note threshold. The entry date is a date; blank means
     * off. The grace days are a whole number of days, 0 or more; blank means
     * off. A setting
     * left out (null) is left as it is. Each setting that carries a figure has a from-date (yyyy-mm-dd) its value
     * is recorded from; blank or left out means the sheet's today. Each permission (open, settle, void, reassign,
     * close, reopen) is the roles that may take the action, comma separated; blank keeps the standing rule. */
    public record SettingsForm(@Pattern(regexp = "|\\d{1,10}(\\.\\d{1,2})?") String clerkLimit,
            @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String floor,
            @Pattern(regexp = "\\d{1,5}") String terms,
            @Pattern(regexp = "|\\d{1,5}") String termsA,
            @Pattern(regexp = "|\\d{1,5}") String termsB,
            @Pattern(regexp = "|\\d{1,5}") String termsC,
            @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String latePct,
            @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}") String policyDate,
            @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}") String periodNumberingFrom,
            @Pattern(regexp = "|\\d{1,10}(\\.\\d{1,2})?") String cap,
            @Pattern(regexp = "|\\d{1,10}(\\.\\d{1,2})?") String proofThreshold,
            @Pattern(regexp = "|\\d{1,5}") String graceDays,
            @Pattern(regexp = "|\\d{1,10}(\\.\\d{1,2})?") String noteThreshold,
            @Pattern(regexp = DATE) String entryDate,
            @Pattern(regexp = DATE) String clerkLimitFrom,
            @Pattern(regexp = DATE) String floorFrom,
            @Pattern(regexp = DATE) String termsFrom,
            @Pattern(regexp = DATE) String termsAFrom,
            @Pattern(regexp = DATE) String termsBFrom,
            @Pattern(regexp = DATE) String termsCFrom,
            @Pattern(regexp = DATE) String latePctFrom,
            @Pattern(regexp = DATE) String capFrom,
            @Pattern(regexp = DATE) String proofThresholdFrom,
            @Pattern(regexp = DATE) String noteThresholdFrom,
            @Size(max = 100) String permOpen,
            @Size(max = 100) String permSettle,
            @Size(max = 100) String permVoid,
            @Size(max = 100) String permReassign,
            @Size(max = 100) String permClose,
            @Size(max = 100) String permReopen) {}

    /** A from-date as typed: blank, or yyyy-mm-dd. */
    private static final String DATE = "|\\d{4}-\\d{2}-\\d{2}";

    /** A value a dated setting has from a from-date (null for the beginning of time); the value as the setting
     * shows it, null for none. */
    public record DatedView(String from, String value) {}

    /** The clerk limit and floor are shown with two decimals; the clerk limit is null when there is none. The
     * terms are the days after its date a claim is due by; each grade's terms are null when it uses the sheet's. The late percent is shown with two decimals. The policy date is null when there is none (the sheet's
     * today is used). The period numbering date is null when it is off. The cap is shown with two decimals,
     * null when there is none; so are the proof threshold and the note threshold. The grace days are null when they are off; so is the entry date. Each of these that carries a figure is the value in force on the sheet's today;
     * {@code dated} has, by key, every value recorded for it by from-date. {@code permissions} has, by action, the
     * roles that may take it, comma separated, blank for the standing rule. */
    public record SettingsView(String clerkLimit, String floor, int terms, Integer termsA, Integer termsB,
            Integer termsC, String latePct, String policyDate, String periodNumberingFrom, String cap,
            String proofThreshold, Integer graceDays, String noteThreshold, String entryDate,
            Map<String, List<DatedView>> dated,
            Map<String, String> permissions) {

        static SettingsView of(Sheet sheet) {
            BigDecimal clerkLimit = sheet.getClerkLimit();
            BigDecimal cap = sheet.getCap();
            BigDecimal proofThreshold = sheet.getProofThreshold();
            BigDecimal noteThreshold = sheet.getNoteThreshold();
            Map<String, List<DatedView>> dated = new LinkedHashMap<>();
            for (String key : List.of(Sheet.CLERK_LIMIT, Sheet.FLOOR, Sheet.TERMS, Sheet.LATE_PCT, Sheet.CAP,
                    Sheet.PROOF_THRESHOLD, Sheet.NOTE_THRESHOLD)) {
                dated.put(key, history(sheet, key, !key.equals(Sheet.TERMS)));
            }
            for (String grade : GRADES) {
                dated.put(Sheet.gradeTermsKey(grade), history(sheet, Sheet.gradeTermsKey(grade), false));
            }
            Map<String, String> permissions = new LinkedHashMap<>();
            for (Permission action : Permission.values()) {
                permissions.put(action.code(), SettingService.permissionOf(sheet, action));
            }
            return new SettingsView(clerkLimit != null ? clerkLimit.setScale(2).toPlainString() : null,
                    sheet.getFloor().toPlainString(), sheet.getTerms(), sheet.getGradeTerms("A"),
                    sheet.getGradeTerms("B"), sheet.getGradeTerms("C"), sheet.getLatePct().toPlainString(),
                    sheet.getPolicyDate() != null ? sheet.getPolicyDate().toString() : null,
                    sheet.getPeriodNumberingFrom() != null ? sheet.getPeriodNumberingFrom().toString() : null,
                    cap != null ? cap.setScale(2).toPlainString() : null,
                    proofThreshold != null ? proofThreshold.setScale(2).toPlainString() : null,
                    sheet.getGraceDays(),
                    noteThreshold != null ? noteThreshold.setScale(2).toPlainString() : null,
                    sheet.getEntryDate() != null ? sheet.getEntryDate().toString() : null, dated,
                    permissions);
        }

        /** The values recorded for a key by from-date, shown with two decimals when {@code amount}, else as whole
         * days. */
        private static List<DatedView> history(Sheet sheet, String key, boolean amount) {
            return sheet.history(key).stream()
                    .map(d -> new DatedView(d.getFrom() != null ? d.getFrom().toString() : null,
                            d.getValue() == null ? null
                                    : amount ? d.getValue().setScale(2).toPlainString()
                                            : String.valueOf(d.getValue().intValue())))
                    .toList();
        }
    }

    /** The grades that can have terms of their own. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public List<RoleView> roles(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/roles")
    public RoleView setRole(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody RoleForm form) {
        Role role = Role.of(form.role().trim()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "A role is clerk, steward or viewer."));
        return RoleView.of(service.setRole(user, sheetId, form.user().trim(), role));
    }

    @GetMapping("/delegations")
    public List<DelegationView> delegations(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.delegations(sheetId).stream().map(DelegationView::of).toList();
    }

    @PostMapping("/delegations")
    public DelegationView delegate(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody DelegationForm form) {
        LocalDate from = dateOrBlank(form.from(), "A from-date is a date.").orElseThrow();
        LocalDate to = dateOrBlank(form.to(), "A to-date is a date.").orElseThrow();
        return DelegationView.of(service.delegate(user, sheetId, form.user().trim(), from, to));
    }

    @GetMapping("/settings")
    public SettingsView settings(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return view(sheetId);
    }

    @PutMapping("/settings")
    public SettingsView save(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody SettingsForm form) {
        String limit = form.clerkLimit();
        Optional<BigDecimal> clerkLimit = amountOrBlank(limit);
        Optional<BigDecimal> cap = amountOrBlank(form.cap());
        Optional<BigDecimal> proofThreshold = amountOrBlank(form.proofThreshold());
        Optional<BigDecimal> noteThreshold = amountOrBlank(form.noteThreshold());
        Optional<LocalDate> policyDate = dateOrBlank(form.policyDate(), "A policy date is a date, or blank.");
        Optional<LocalDate> periodNumberingFrom = dateOrBlank(form.periodNumberingFrom(),
                "A period numbering date is a date, or blank.");
        Optional<LocalDate> entryDate = dateOrBlank(form.entryDate(), "An entry date is a date, or blank.");
        service.save(user, sheetId, clerkLimit, form.floor() == null ? null : new BigDecimal(form.floor()),
                form.terms() == null ? null : Integer.valueOf(form.terms()), gradeTerms(form),
                form.latePct() == null ? null : new BigDecimal(form.latePct()), policyDate, periodNumberingFrom, cap,
                proofThreshold, form.graceDays() == null ? null
                        : form.graceDays().isEmpty() ? Optional.empty() : Optional.of(Integer.valueOf(form.graceDays())),
                noteThreshold, entryDate, froms(form), permissions(form));
        return view(sheetId);
    }

    private SettingsView view(String sheetId) {
        return SettingsView.of(service.sheet(sheetId));
    }

    /** The from-dates given, by the key of their setting; a setting without one is recorded from the sheet's
     * today. */
    private static Map<String, LocalDate> froms(SettingsForm form) {
        Map<String, String> typed = new LinkedHashMap<>();
        typed.put(Sheet.CLERK_LIMIT, form.clerkLimitFrom());
        typed.put(Sheet.FLOOR, form.floorFrom());
        typed.put(Sheet.TERMS, form.termsFrom());
        typed.put(Sheet.gradeTermsKey("A"), form.termsAFrom());
        typed.put(Sheet.gradeTermsKey("B"), form.termsBFrom());
        typed.put(Sheet.gradeTermsKey("C"), form.termsCFrom());
        typed.put(Sheet.LATE_PCT, form.latePctFrom());
        typed.put(Sheet.CAP, form.capFrom());
        typed.put(Sheet.PROOF_THRESHOLD, form.proofThresholdFrom());
        typed.put(Sheet.NOTE_THRESHOLD, form.noteThresholdFrom());
        Map<String, LocalDate> froms = new LinkedHashMap<>();
        typed.forEach((key, from) -> {
            Optional<LocalDate> date = dateOrBlank(from, "A from-date is a date, or blank.");
            if (date != null && date.isPresent()) {
                froms.put(key, date.get());
            }
        });
        return froms;
    }

    /** The permissions given, in action order, each the roles typed (blank keeps the standing rule); an action
     * left out is left as it is. */
    private static Map<Permission, List<Role>> permissions(SettingsForm form) {
        Map<Permission, String> typed = new LinkedHashMap<>();
        typed.put(Permission.OPEN, form.permOpen());
        typed.put(Permission.SETTLE, form.permSettle());
        typed.put(Permission.VOID, form.permVoid());
        typed.put(Permission.REASSIGN, form.permReassign());
        typed.put(Permission.CLOSE, form.permClose());
        typed.put(Permission.REOPEN, form.permReopen());
        Map<Permission, List<Role>> given = new LinkedHashMap<>();
        typed.forEach((action, roles) -> {
            if (roles != null) {
                given.put(action, rolesOf(roles));
            }
        });
        return given;
    }

    /** The roles typed, comma separated, each once in the order typed; none when blank. */
    private static List<Role> rolesOf(String typed) {
        if (typed.isBlank()) {
            return List.of();
        }
        List<Role> roles = new ArrayList<>();
        for (String code : typed.split(",", -1)) {
            Role role = Role.of(code.trim()).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A permission is roles (clerk or steward), comma separated, or blank."));
            if (!roles.contains(role)) {
                roles.add(role);
            }
        }
        return roles;
    }

    /** The grades' terms given, in grade order: empty when blank (the grade uses the sheet's terms); a grade
     * left out is left as it is. */
    private static Map<String, Optional<Integer>> gradeTerms(SettingsForm form) {
        Map<String, Optional<Integer>> given = new LinkedHashMap<>();
        Map<String, String> typed = new LinkedHashMap<>();
        typed.put("A", form.termsA());
        typed.put("B", form.termsB());
        typed.put("C", form.termsC());
        typed.forEach((grade, days) -> {
            if (days != null) {
                given.put(grade, days.isEmpty() ? Optional.empty() : Optional.of(Integer.valueOf(days)));
            }
        });
        return given;
    }

    /** An amount setting as given: null when left out, empty when blank. */
    private static Optional<BigDecimal> amountOrBlank(String amount) {
        return amount == null ? null : amount.isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(amount));
    }

    /** A date setting as given: null when left out, empty when blank. */
    private static Optional<LocalDate> dateOrBlank(String date, String refusal) {
        try {
            return date == null ? null : date.isEmpty() ? Optional.empty() : Optional.of(LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refusal);
        }
    }
}
