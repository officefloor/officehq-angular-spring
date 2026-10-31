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
import java.util.Set;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimComment;
import net.officefloor.hq.app.claim.ClaimLine;
import net.officefloor.hq.app.claim.ClaimLogLine;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.claim.Holds;
import net.officefloor.hq.app.claim.Tags;
import net.officefloor.hq.app.pool.Pools;
import net.officefloor.hq.app.rate.GradeService;
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
import org.springframework.web.bind.annotation.RequestParam;
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
     * correction, the number of the claim it corrects (null otherwise), and whether it was deferred until a date after the
     * sheet's today, whatever its status since (its counting follows {@link Dues#deferred}), and the date it is deferred until (null when never),
     * its proofs count, and of them its receipts and its photos, and, for a claim split off another, the number of the claim it was split from (null otherwise),
     * whether it was added while its period was soft-closed, the last sheet's today it was reminded on (null when never), its external reference (null when none), its tags, sorted, the reason it was voided with (null when none), its comments, oldest first, whether it counts in the live totals (see {@link Dues#counts}), the bulk settle batch that settled it (null when none), its lines, in the order they were added, whether it is late in review (see {@link Dues#reviewLate}), whether its late charge was waived, whether it is held, the people it is shared with, the user its review is assigned to (null when none), and whether it is late pending (see {@link Dues#pendingLate}).
     */
    public record ClaimView(long id, String amount, LocalDate date, String note, String due, ClaimStatus status,
            String number, String openedBy, boolean stated, LocalDate dueBy, boolean overdue, String corrects,
            List<LogLineView> log, boolean deferred, LocalDate deferredUntil, int proofs, int proofsReceipt, int proofsPhoto, String splitFrom,
            String lockedBy, boolean lateEntry, LocalDate remindedOn, String ref, List<String> tags,
            String voidReason, List<CommentView> comments, boolean counts, Integer settleBatch, List<LineView> lines,
            boolean reviewLate, boolean waived, boolean held, List<ShareView> shares, String reviewAssigned,
            boolean pendingLate) {

        static ClaimView of(Claim claim, Grades grade, RateTable rates, Holds held) {
            return new ClaimView(claim.getId(), money(claim.getAmount()), claim.getDate(), claim.getNote(),
                    money(Dues.figure(claim, grade, rates)), claim.getStatus(), claim.getShownNumber(),
                    claim.getOpenedBy(), claim.isStated(), claim.getDueBy(), Dues.overdue(claim, grade, rates),
                    claim.getCorrects(), claim.getLog().stream().map(LogLineView::of).toList(),
                    claim.getDeferredUntil() != null && rates.today().isBefore(claim.getDeferredUntil()),
                    claim.getDeferredUntil(), claim.getProofs(), claim.getProofs(Claim.RECEIPT), claim.getProofs(Claim.PHOTO), claim.getSplitFrom(),
                    claim.getLockedBy(), claim.isLateEntry(), claim.getRemindedOn(), claim.getRef(),
                    Tags.list(claim.getTags()), claim.getVoidReason(),
                    claim.getComments().stream().map(CommentView::of).toList(),
                    Dues.counts(claim, held, rates.today()), claim.getSettleBatch(),
                    claim.getLines().stream().map(LineView::of).toList(), Dues.reviewLate(claim, rates), claim.isWaived(),
                    claim.isHeld(), claim.getShares().stream().map(ShareView::of).toList(), claim.getReviewAssigned(),
                    Dues.pendingLate(claim, rates));
        }
    }

    /** A share of a claim: the id of the person holding it and their percent with two decimals. */
    public record ShareView(long personId, String share) {

        static ShareView of(ClaimShare share) {
            return new ShareView(share.getPersonId(), money(share.getShare()));
        }
    }

    /** A line of a claim: its label, quantity and unit amount. */
    public record LineView(String label, int qty, String unit) {

        static LineView of(ClaimLine line) {
            return new LineView(line.getLabel(), line.getQty(), money(line.getUnit()));
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
     * A person with the grade in force on the sheet's today, their own terms in force on it (null: none), their claims, newest first, claimed: the sum of the amounts of the claims (see
     * {@link Dues#claimed}: a hold leaves a claim in it), dueTotal: the sum of the due figures of the claims that count (see
     * {@link Dues#total}, held to the sheet's cap less their cut total, not below 0.00), excess: what the sum loses to that held cap (0.00 when nothing), and their statements: how many were taken, the last one's due-total (null when
     * there is none or it was n/a) and date (null when there is none), whether they are retired and
     * the date they are retired from (null when not), whether they have any hold and their holds, and how many of the claims
     * that count are overdue and the sum of their due figures, the ids of their patrons, and whether they are
     * active (see {@link Dues#active}), and recentSettled: their recent settled figures (see {@link Dues#recentSettled}), and retiredNow: whether
     * the sheet's today has reached their retirement date (their unbound OPEN and PENDING claims then count in no live total),
     * and aged0to30, aged31to60 and aged61: the due of their OPEN and PENDING claims that count by the claim's age on the
     * sheet's today (see {@link Dues#aged}), and settledTotal and openTotal: the parts of their uncapped due-total
     * from their SETTLED claims and from their OPEN and PENDING claims (see {@link Dues#settledTotal} and
     * {@link Dues#openTotal}; 0.00 with none, never n/a), and their aliases, sorted as names are compared, and clashing: whether their name or an alias is
     * another's on a sheet of the sheet's tree (see {@link PersonService#clashing}), and the sheet they were
     * transferred from (null: none), their past retirements, earliest first, their snapshot lines, oldest first, and
     * their statement lines: one per statement still standing, in the order taken, and the reason of their flag
     * while it is in force on the sheet's today (null otherwise, see {@link Person#flagOn}), and their approver (null: none),
     * and their score (see {@link Dues#score}), a whole number, and their band while both score thresholds are set:
     * HIGH when the score is at or above the high one, MID when at or above the mid one, else LOW (null otherwise).
     */
    public record PersonView(long id, long version, String name, String grade, Integer terms, String contact,
            List<String> tags,
            List<ClaimView> claims,
            String claimed, String dueTotal, String excess, int statementCount, String statedTotal, LocalDate statementDate,
            boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds, int overdueCount,
            String overdueTotal, List<Long> patrons, boolean active, String recentSettled, boolean retiredNow,
            String aged0to30, String aged31to60, String aged61, String settledTotal, String openTotal,
            List<String> aliases, boolean clashing, String origin, List<RetirementView> retirements,
            List<SnapshotView> snapshots, List<StatementLineView> statementLines, String flag,
            String approver, String score, String band) {

        /** The person as shown, given the sheet's rates and whether they clash (see {@link PersonService#clashing}). */
        public static PersonView of(Person person, List<Claim> claims, RateTable rates, Statements statements,
                List<Long> patrons, boolean clashing, List<PersonSnapshot> snapshots) {
            return of(person, claims, rates, statements, patrons, clashing, snapshots, Dues.Parts.NONE);
        }

        /** The person as shown, with their parts of the sheet's shared claims (see {@link Dues#parts}). */
        public static PersonView of(Person person, List<Claim> claims, RateTable rates, Statements statements,
                List<Long> patrons, boolean clashing, List<PersonSnapshot> snapshots, Dues.Parts parts) {
            return of(person, claims, rates, statements, patrons, clashing, snapshots, parts, BigDecimal.ZERO);
        }

        /**
         * The person as shown, with their parts of the sheet's shared claims and their cut total (see
         * {@link Pools#personCuts}), which the cap covers along with their due-total.
         */
        public static PersonView of(Person person, List<Claim> claims, RateTable rates, Statements statements,
                List<Long> patrons, boolean clashing, List<PersonSnapshot> snapshots, Dues.Parts parts, BigDecimal cut) {
            Grades grade = person::gradeOn;
            Holds held = person.leaves();
            BigDecimal claimed = Dues.claimed(claims, held, rates.today());
            List<Claim> overdue = Dues.overdue(claims, grade, rates, held);
            BigDecimal score = Dues.score(grade, claims, rates, held);
            return new PersonView(person.getId(), person.getVersion(), person.getName(),
                    grade.on(rates.today()), person.termsOn(rates.today()), person.getContact(), Tags.list(person.getTags()), claims.stream().map(c -> ClaimView.of(c, grade, rates, held)).toList(), money(claimed),
                    money(Dues.total(grade, claims, rates, held, parts, cut)), money(Dues.excess(grade, claims, rates, held, parts, cut)),
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
                    money(Dues.settledTotal(grade, claims, rates, held, parts)),
                    money(Dues.openTotal(grade, claims, rates, held, parts)), person.getAliases(), clashing,
                    person.getOrigin(), person.getRetirements().stream()
                            .map(r -> new RetirementView(r.getFromDate(), r.getToDate())).toList(),
                    snapshots.stream().map(s -> new SnapshotView(s.getDay(), s.getDueTotal())).toList(),
                    statements.lines().stream()
                            .map(l -> new StatementLineView(l.date(), l.dueTotal() == null ? "n/a" : money(l.dueTotal())))
                            .toList(),
                    person.flagOn(rates.today()), person.getApprover(),
                    score.toPlainString(), band(score, rates));
        }

        private static String band(BigDecimal score, RateTable rates) {
            Integer mid = rates.settings().scoreMid();
            Integer high = rates.settings().scoreHigh();
            if (mid == null || high == null) {
                return null;
            }
            return score.compareTo(BigDecimal.valueOf(high)) >= 0 ? "HIGH"
                    : score.compareTo(BigDecimal.valueOf(mid)) >= 0 ? "MID" : "LOW";
        }
    }

    /** A past retirement of a person: from the date they were retired from to the day they were unretired. */
    public record RetirementView(LocalDate from, LocalDate to) {
    }

    /** A snapshot line of a person: the day being left and their due-total on it as shown ('n/a': none). */
    public record SnapshotView(LocalDate day, String dueTotal) {
    }

    /** A statement line of a person: the statement's date and its due-total as shown ('n/a': none). */
    public record StatementLineView(LocalDate date, String dueTotal) {
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
     * The person's own terms are a number of days, blank (or none) for none, from a date (none: from the
     * beginning of time); their contact a text, blank
     * (or none) for none; their tags comma separated text (none: none on an add, as they were on an edit).
     */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = GradeService.CODE) String grade, Long version, LocalDate gradeFrom,
            @Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String terms, LocalDate termsFrom,
            @Size(max = 200) String contact, @Size(max = 1000) String tags) {

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
            @NotNull @Pattern(regexp = GradeService.CODE) String grade, LocalDate from) {
    }

    /** A retirement for the selected people as entered: from a date (none: the sheet's today). */
    public record BulkRetireForm(@NotEmpty List<@NotNull Long> personIds, LocalDate from) {
    }

    /** Pasted lines of name,grade,terms,contact, one person per line; terms and contact may be left out. */
    public record ImportForm(@NotNull @Size(max = 100_000) String text) {
    }

    private final PersonService service;
    private final PersonImportService imports;
    private final BulkGradeService bulkGrade;
    private final BulkRetireService bulkRetire;
    private final ClaimService claims;
    private final RateService rates;
    private final StatementService statements;
    private final PersonMergeService merges;
    private final PersonTransferService transfers;
    private final Pools pools;

    public PersonController(PersonService service, BulkGradeService bulkGrade, ClaimService claims,
            RateService rates, StatementService statements, PersonMergeService merges,
            PersonTransferService transfers, PersonImportService imports, BulkRetireService bulkRetire, Pools pools) {
        this.pools = pools;
        this.bulkRetire = bulkRetire;
        this.service = service;
        this.imports = imports;
        this.transfers = transfers;
        this.merges = merges;
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
        Set<Long> clashing = service.clashing(sheet);
        Map<Long, List<PersonSnapshot>> snapshots = service.snapshotsBySheet(sheet);
        List<Person> everyone = service.list(sheet);
        Map<Long, Dues.Parts> parts = Person.parts(everyone,
                bySheet.values().stream().flatMap(List::stream).toList(), table);
        Map<Long, BigDecimal> cuts = pools.personCuts(sheet);
        return everyone.stream()
                .map(p -> PersonView.of(p, bySheet.getOrDefault(p.getId(), List.of()), table,
                        stated.getOrDefault(p.getId(), Statements.NONE),
                        patrons.getOrDefault(p.getId(), List.of()), clashing.contains(p.getId()),
                        snapshots.getOrDefault(p.getId(), List.of()),
                        parts.getOrDefault(p.getId(), Dues.Parts.NONE),
                        cuts.getOrDefault(p.getId(), BigDecimal.ZERO))).toList();
    }

    /** The people of the sheet's tree whose name or an alias contains the text (see {@link PersonService#search}). */
    @GetMapping("/search")
    public List<PersonService.Found> search(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        return service.search(sheetId.trim(), text);
    }

    @PostMapping
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.add(sheet, user.trim(), form.name(), form.grade(), form.termsDays(),
                form.termsFrom(), form.contactKey(), form.tags()), List.of(),
                rates.table(sheet), Statements.NONE, List.of(), false, List.of());
    }

    /** Update or add a person per pasted line, in order; one refused line imports none. */
    @PostMapping("/import")
    public PersonImportService.Result importPeople(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody ImportForm form) {
        return imports.importPeople(sheetId.trim(), user.trim(), form.text());
    }

    @PutMapping("/{personId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.edit(sheet, user.trim(), personId, form.version(), form.name(),
                form.grade(), form.gradeFrom(), form.termsDays(), form.termsFrom(), form.contactKey(), form.tags()));
    }

    /** Give the selected people the grade from the date, in list order; answers how many were changed. */
    @PostMapping("/grade")
    public BulkGradeService.Result grade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody BulkGradeForm form) {
        return bulkGrade.grade(sheetId.trim(), user.trim(), new LinkedHashSet<>(form.personIds()), form.grade(),
                form.from());
    }

    /** Retire the selected people from the date, in list order; answers how many were retired. */
    @PostMapping("/retire")
    public BulkRetireService.Result retireAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody BulkRetireForm form) {
        return bulkRetire.retire(sheetId.trim(), user.trim(), new LinkedHashSet<>(form.personIds()), form.from());
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

    /** Unretire the retired person on the sheet's today; answers with the person as they are now. */
    @PostMapping("/{personId}/unretire")
    public PersonView unretire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, service.unretire(sheet, user.trim(), personId));
    }

    /** A move as asked for: up or down the list. */
    public record MoveForm(@NotNull @Pattern(regexp = "up|down") String direction) {
    }

    /** Move the person up or down the list, swapping them with their neighbour; answers with the person as they are now. */
    @PostMapping("/{personId}/move")
    public PersonView move(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody MoveForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.move(sheet, user.trim(), personId, form.direction().equals("up")));
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

    /** An approver as entered: a user name (blank: refused). */
    public record ApproverForm(@Size(max = 100) String user) {
    }

    /** Give the person an approver; answers with the person as they are now. */
    @PostMapping("/{personId}/approver")
    public PersonView approver(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody ApproverForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.setApprover(sheet, user.trim(), personId, form.user()));
    }

    /** A flag as entered: a reason (blank: refused) until a date (none: the sheet's today). */
    public record FlagForm(@Size(max = 200) String reason, LocalDate until) {
    }

    /** A steward flags the person; answers with the person as they are now. */
    @PostMapping("/{personId}/flag")
    public PersonView flag(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody FlagForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.flag(sheet, user.trim(), personId, form.reason(), form.until()));
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

    /** An alias as entered; a blank one is refused. */
    public record AliasForm(@Size(max = 200) String alias) {
    }

    /** Give the person an alias; answers with the person as they are now. */
    @PostMapping("/{personId}/aliases")
    public PersonView addAlias(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody AliasForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.addAlias(sheet, user.trim(), personId, form.alias()));
    }

    /** A merge as asked for: the id of the other person on the sheet the person is merged into. */
    public record MergeForm(@NotNull Long intoId) {
    }

    /** Merge the person into another on the sheet; answers with the other as they are now. */
    @PostMapping("/{personId}/merge")
    public PersonView merge(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody MergeForm form) {
        String sheet = sheetId.trim();
        return view(sheet, merges.merge(sheet, user.trim(), personId, form.intoId()));
    }

    /** A transfer as asked for: the id of the other sheet the person is transferred to. */
    public record TransferForm(@NotBlank @Size(max = 100) String toSheet) {
    }

    /** Transfer the person to another sheet; they leave this one entirely. */
    @PostMapping("/{personId}/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody TransferForm form) {
        transfers.transfer(sheetId.trim(), user.trim(), personId, form.toSheet().trim());
    }

    /** Undo the transfer that brought the person to this sheet today; they go back to the sheet they came from. */
    @PostMapping("/{personId}/transfer-undo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undoTransfer(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user) {
        transfers.undo(sheetId.trim(), user.trim(), personId);
    }

    private PersonView view(String sheet, Person person) {
        long personId = person.getId();
        RateTable table = rates.table(sheet);
        return PersonView.of(person, claims.forPerson(sheet, personId), table,
                statements.forPerson(sheet, personId), service.patrons(sheet, personId),
                service.clashing(sheet, person), service.snapshots(sheet, personId),
                claims.parts(sheet, personId, table), pools.personCuts(sheet).getOrDefault(personId, BigDecimal.ZERO));
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
