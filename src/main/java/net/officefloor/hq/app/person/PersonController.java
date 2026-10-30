package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimComment;
import net.officefloor.hq.app.claim.ClaimLogLine;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.claim.Holds;
import net.officefloor.hq.app.claim.Tags;
import net.officefloor.hq.app.rate.Grades;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.Statements;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The people on a sheet. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /**
     * A claim as shown: its amount with two decimals, its date, its note, its due: the amount at
     * the rate in force on its date for its person's grade in force then (null when none is) or, once settled, the due fixed
     * then, or while bound by a statement, the figure bound then, its status, its number (null while it has
     * none), who opened it (null while it was never opened), whether a statement binds it, the date it is
     * due by (fixed when it was added: its date plus its person's own terms, else the terms then of their grade on its date, else the sheet's), whether it is overdue (see {@link Dues#overdue}) and, for a
     * correction, the number of the claim it corrects (null otherwise), and whether it is deferred on the
     * sheet's today and the date it is deferred until then (null when it is not, see {@link Dues#deferred}),
     * its proofs count and, for a claim split off another, the number of the claim it was split from (null otherwise),
     * whether it was added while its period was soft-closed, the last sheet's today it was reminded on (null when never), its external reference (null when none), its tags, sorted, the reason it was voided with (null when none), its comments, oldest first, whether it counts in the live totals (see {@link Dues#counts}) and the bulk settle batch that settled it (null when none).
     */
    public record ClaimView(long id, String amount, LocalDate date, String note, String due, ClaimStatus status,
            String number, String openedBy, boolean stated, LocalDate dueBy, boolean overdue, String corrects,
            List<LogLineView> log, boolean deferred, LocalDate deferredUntil, int proofs, String splitFrom,
            String lockedBy, boolean lateEntry, LocalDate remindedOn, String ref, List<String> tags,
            String voidReason, List<CommentView> comments, boolean counts, Integer settleBatch) {

        static ClaimView of(Claim claim, Grades grade, RateTable rates, Holds held) {
            return new ClaimView(claim.getId(), money(claim.getAmount()), claim.getDate(), claim.getNote(),
                    money(Dues.figure(claim, grade, rates)), claim.getStatus(), claim.getShownNumber(),
                    claim.getOpenedBy(), claim.isStated(), claim.getDueBy(), Dues.overdue(claim, grade, rates),
                    claim.getCorrects(), claim.getLog().stream().map(LogLineView::of).toList(),
                    Dues.deferred(claim, rates.today()),
                    claim.getDeferredUntil(), claim.getProofs(), claim.getSplitFrom(),
                    claim.getLockedBy(), claim.isLateEntry(), claim.getRemindedOn(), claim.getRef(),
                    Tags.list(claim.getTags()), claim.getVoidReason(),
                    claim.getComments().stream().map(CommentView::of).toList(),
                    Dues.counts(claim, held, rates.today()), claim.getSettleBatch());
        }
    }

    /** A comment on a claim: who wrote it, the sheet's today then and its text. */
    public record CommentView(String by, LocalDate on, String text) {

        static CommentView of(ClaimComment comment) {
            return new CommentView(comment.getBy(), comment.getOn(), comment.getText());
        }
    }

    /** A line of a claim's status log: the status, who changed it, the sheet's today then and its due then (null when none). */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {

        static LogLineView of(ClaimLogLine line) {
            return new LogLineView(line.getStatus(), line.getBy(), line.getOn(), money(line.getDue()));
        }
    }

    /**
     * A person with the grade in force on the sheet's today, their own terms (null: none), their claims, newest first, claimed: the sum of the amounts of the claims that
     * count (OPEN, PENDING and SETTLED, but not an unbound OPEN claim dated within a hold, see {@link Dues#counts}), dueTotal: the sum of the due figures of the claims that count (see
     * {@link Dues#total}, held to the sheet's cap), excess: what the sum is above the cap (0.00 when it is not), and their statements: how many were taken, the last one's due-total (null when
     * there is none or it was n/a) and date (null when there is none), whether they are retired and
     * the date they are retired from (null when not), whether they have any hold and their holds, and how many of the claims
     * that count are overdue and the sum of their due figures, the ids of their patrons, and whether they are
     * active (see {@link Dues#active}), and recentSettled: their recent settled figures (see {@link Dues#recentSettled}), and retiredNow: whether
     * the sheet's today has reached their retirement date (their unbound OPEN and PENDING claims then count in no live total),
     * and aged0to30, aged31to60 and aged61: the due of their OPEN and PENDING claims that count by the claim's age on the
     * sheet's today (see {@link Dues#aged}), and settledTotal and openTotal: the parts of their uncapped due-total
     * from their SETTLED claims and from their OPEN and PENDING claims (see {@link Dues#settledTotal} and
     * {@link Dues#openTotal}; 0.00 with none, never n/a).
     */
    public record PersonView(long id, long version, String name, String grade, Integer terms, String contact,
            List<ClaimView> claims,
            String claimed, String dueTotal, String excess, int statementCount, String statedTotal, LocalDate statementDate,
            boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds, int overdueCount,
            String overdueTotal, List<Long> patrons, boolean active, String recentSettled, boolean retiredNow,
            String aged0to30, String aged31to60, String aged61, String settledTotal, String openTotal) {

        /** The person as shown, given the sheet's rates. */
        public static PersonView of(Person person, List<Claim> claims, RateTable rates, Statements statements,
                List<Long> patrons) {
            Grades grade = person::gradeOn;
            Holds held = person.leaves();
            BigDecimal claimed = claims.stream().filter(c -> Dues.counts(c, held, rates.today())).map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Claim> overdue = Dues.overdue(claims, grade, rates, held);
            return new PersonView(person.getId(), person.getVersion(), person.getName(),
                    grade.on(rates.today()), person.getTerms(), person.getContact(), claims.stream().map(c -> ClaimView.of(c, grade, rates, held)).toList(), money(claimed),
                    money(Dues.total(grade, claims, rates, held)), money(Dues.excess(grade, claims, rates, held)),
                    statements.count(),
                    money(statements.lastDueTotal()), statements.lastDate(), person.isRetired(),
                    person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(h -> new HoldView(h.getFromDate(), h.getToDate())).toList(),
                    overdue.size(), money(Dues.sum(grade, overdue, rates)),
                    patrons, Dues.active(claims, held, rates.today()),
                    money(Dues.recentSettled(claims, rates.today())), person.retiredOn(rates.today()),
                    money(Dues.aged(grade, claims, rates, held, 0, 30)),
                    money(Dues.aged(grade, claims, rates, held, 31, 60)),
                    money(Dues.aged(grade, claims, rates, held, 61, Long.MAX_VALUE)),
                    money(Dues.settledTotal(grade, claims, rates, held)),
                    money(Dues.openTotal(grade, claims, rates, held)));
        }
    }

    /** A hold on a person: from a date to a date (null: open-ended). */
    public record HoldView(LocalDate from, LocalDate to) {
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * A person as entered; an edit carries the version its editor loaded (none: no check) and the date
     * its grade applies from (none: the sheet's today). An add's grade applies from the beginning of time.
     * The person's own terms are a number of days, blank (or none) for none; their contact a text, blank
     * (or none) for none.
     */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, Long version, LocalDate gradeFrom,
            @Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String terms, @Size(max = 200) String contact) {

        /** The contact trimmed and lower-cased; null when there is none. */
        String contactKey() {
            return contact == null || contact.isBlank() ? null : contact.trim().toLowerCase(Locale.ROOT);
        }

        /** The terms as days; null when there are none. */
        Integer termsDays() {
            return terms == null || terms.isBlank() ? null : Integer.valueOf(terms.trim());
        }
    }

    /** A retirement as entered; a date left out takes the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** A grade for the selected people as entered: from a date (none: the sheet's today). */
    public record BulkGradeForm(@NotEmpty List<@NotNull Long> personIds,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate from) {
    }

    private final PersonService service;
    private final BulkGradeService bulkGrade;
    private final ClaimService claims;
    private final RateService rates;
    private final StatementService statements;

    public PersonController(PersonService service, BulkGradeService bulkGrade, ClaimService claims,
            RateService rates, StatementService statements) {
        this.service = service;
        this.bulkGrade = bulkGrade;
        this.claims = claims;
        this.rates = rates;
        this.statements = statements;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        Map<Long, List<Claim>> bySheet = claims.bySheet(sheet);
        RateTable table = rates.table(sheet);
        Map<Long, Statements> stated = statements.bySheet(sheet);
        Map<Long, List<Long>> patrons = service.patronsBySheet(sheet);
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, bySheet.getOrDefault(p.getId(), List.of()), table,
                        stated.getOrDefault(p.getId(), Statements.NONE),
                        patrons.getOrDefault(p.getId(), List.of()))).toList();
    }

    @PostMapping
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.add(sheet, user.trim(), form.name(), form.grade(), form.termsDays(),
                form.contactKey()), List.of(),
                rates.table(sheet), Statements.NONE, List.of());
    }

    @PutMapping("/{personId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.edit(sheet, user.trim(), personId, form.version(), form.name(),
                form.grade(), form.gradeFrom(), form.termsDays(), form.contactKey()));
    }

    /** Give the selected people the grade from the date, in list order; answers how many were changed. */
    @PostMapping("/grade")
    public BulkGradeService.Result grade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody BulkGradeForm form) {
        return bulkGrade.grade(sheetId.trim(), user.trim(), new LinkedHashSet<>(form.personIds()), form.grade(),
                form.from());
    }

    /** A statement as asked for: as of a date on or before the sheet's today (none: as of now). */
    public record StatementForm(LocalDate asOf) {
    }

    /** Take a statement for the person; answers with the person as bound by it. */
    @PostMapping("/{personId}/statement")
    public PersonView statement(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @RequestBody(required = false) StatementForm form) {
        String sheet = sheetId.trim();
        statements.take(sheet, user.trim(), personId, form == null ? null : form.asOf());
        return view(sheet, service.get(sheet, personId));
    }

    /** Cancel the person's last statement; answers with the person as they are without it. */
    @DeleteMapping("/{personId}/statement")
    public PersonView unstate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        statements.cancel(sheet, user.trim(), personId);
        return view(sheet, service.get(sheet, personId));
    }

    /** Retire the person from a date; answers with the person as retired. */
    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @RequestBody(required = false) RetireForm form) {
        String sheet = sheetId.trim();
        Person person = service.retire(sheet, user.trim(), personId, form == null ? null : form.from());
        return view(sheet, person);
    }

    /** A hold as entered: from a date (none: the sheet's today) to an optional date. */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** Add a hold on the person; answers with the person as they are now. */
    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @RequestBody(required = false) HoldForm form) {
        String sheet = sheetId.trim();
        Person person = service.hold(sheet, user.trim(), personId, form == null ? null : form.from(),
                form == null ? null : form.to());
        return view(sheet, person);
    }

    /** A patron as named: the id of another person on the sheet. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** The person names another person on the sheet as their patron; answers with the person as they are now. */
    @PostMapping("/{personId}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody PatronForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.addPatron(sheet, user.trim(), personId, form.patronId()));
    }

    private PersonView view(String sheet, Person person) {
        long personId = person.getId();
        return PersonView.of(person, claims.forPerson(sheet, personId), rates.table(sheet),
                statements.forPerson(sheet, personId), service.patrons(sheet, personId));
    }

    /** Another save took the name or contact first: the unique name or contact on the sheet refuses this one. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameInUse() {
    }

    /** Another save of the person committed between this edit's check and its write. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    public void editedMeanwhile() {
    }
}
