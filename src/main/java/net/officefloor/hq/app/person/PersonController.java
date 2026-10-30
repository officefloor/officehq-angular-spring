package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.Statements;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.annotation.Validated;
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
     * the rate for its person's grade in force on its date (null when none is) or, once settled, the due fixed
     * then, or while bound by a statement, the figure bound then, its status, its number (null while it has
     * none), who opened it (null while it was never opened), whether a statement binds it, the date it is
     * due by (its date plus the sheet's terms) and whether it is overdue (see {@link Dues#overdue}).
     */
    public record ClaimView(long id, String amount, LocalDate date, String note, String due, ClaimStatus status,
            Integer number, String openedBy, boolean stated, LocalDate dueBy, boolean overdue) {

        static ClaimView of(Claim claim, String grade, RateTable rates) {
            return new ClaimView(claim.getId(), money(claim.getAmount()), claim.getDate(), claim.getNote(),
                    money(Dues.figure(claim, grade, rates)), claim.getStatus(), claim.getNumber(),
                    claim.getOpenedBy(), claim.isStated(), rates.dueBy(claim.getDate()), Dues.overdue(claim, rates));
        }
    }

    /**
     * A person with their claims, newest first, claimed: the sum of the amounts of the claims that
     * count (OPEN and SETTLED, but only SETTLED while on hold, see {@link Dues#counts}), dueTotal: the sum of the due figures of the claims that count (see
     * {@link Dues#total}), and their statements: how many were taken, the last one's due-total (null when
     * there is none or it was n/a) and date (null when there is none), whether they are retired and
     * the date they are retired from (null when not), whether they are on hold, and how many of the claims
     * that count are overdue and the sum of their due figures.
     */
    public record PersonView(long id, long version, String name, String grade, List<ClaimView> claims,
            String claimed, String dueTotal, int statementCount, String statedTotal, LocalDate statementDate,
            boolean retired, LocalDate retiredFrom, boolean held, int overdueCount, String overdueTotal) {

        /** The person as shown, given the sheet's rates. */
        public static PersonView of(Person person, List<Claim> claims, RateTable rates, Statements statements) {
            String grade = person.getGrade();
            boolean held = person.isHeld();
            BigDecimal claimed = claims.stream().filter(c -> Dues.counts(c, held)).map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Claim> overdue = Dues.overdue(claims, rates, held);
            return new PersonView(person.getId(), person.getVersion(), person.getName(), grade,
                    claims.stream().map(c -> ClaimView.of(c, grade, rates)).toList(), money(claimed),
                    money(Dues.total(grade, claims, rates, held)), statements.count(),
                    money(statements.lastDueTotal()), statements.lastDate(), person.isRetired(),
                    person.getRetiredFrom(), held, overdue.size(), money(Dues.sum(grade, overdue, rates)));
        }
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** A person as entered; an edit carries the version its editor loaded (none: no check). */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, Long version) {
    }

    /** A retirement as entered; a date left out takes the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    private final PersonService service;
    private final ClaimService claims;
    private final RateService rates;
    private final StatementService statements;

    public PersonController(PersonService service, ClaimService claims, RateService rates,
            StatementService statements) {
        this.service = service;
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
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, bySheet.getOrDefault(p.getId(), List.of()), table,
                        stated.getOrDefault(p.getId(), Statements.NONE))).toList();
    }

    @PostMapping
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.add(sheet, user.trim(), form.name(), form.grade()), List.of(),
                rates.table(sheet), Statements.NONE);
    }

    @PutMapping("/{personId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.edit(sheet, user.trim(), personId, form.version(), form.name(), form.grade()),
                claims.forPerson(sheet, personId), rates.table(sheet), statements.forPerson(sheet, personId));
    }

    /** Take a statement for the person; answers with the person as bound by it. */
    @PostMapping("/{personId}/statement")
    public PersonView statement(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        statements.take(sheet, user.trim(), personId);
        return PersonView.of(service.get(sheet, personId), claims.forPerson(sheet, personId), rates.table(sheet),
                statements.forPerson(sheet, personId));
    }

    /** Retire the person from a date; answers with the person as retired. */
    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @RequestBody(required = false) RetireForm form) {
        String sheet = sheetId.trim();
        Person person = service.retire(sheet, user.trim(), personId, form == null ? null : form.from());
        return PersonView.of(person, claims.forPerson(sheet, personId), rates.table(sheet),
                statements.forPerson(sheet, personId));
    }

    /** A hold as asked for: on hold (true) or released (false). */
    public record HoldForm(@NotNull Boolean held) {
    }

    /** Put the person on hold or take them off it; answers with the person as they are now. */
    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody HoldForm form) {
        String sheet = sheetId.trim();
        Person person = service.hold(sheet, user.trim(), personId, form.held());
        return PersonView.of(person, claims.forPerson(sheet, personId), rates.table(sheet),
                statements.forPerson(sheet, personId));
    }

    /** Another save took the name first: the unique name on the sheet refuses this one. */
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
