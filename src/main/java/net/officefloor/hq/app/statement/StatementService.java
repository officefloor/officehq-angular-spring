package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.history.SheetAsOf;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements taken of the people on each sheet. Taking one binds each of the person's claims that
 * counts (OPEN, PENDING or SETTLED, but not an OPEN one deferred on the sheet's today) to the figure it shows then, and leaves
 * each that does not count unbound;
 * it records the person's due-total then: the sum of what is due on those claims as each shows it, late
 * charges included (a claim with no figure is skipped), at most the sheet's cap. A bound claim shows and
 * totals what was due on it as it showed then, late charge included: its late charge no longer follows the
 * sheet's today, and the next statement binds it to that figure again.
 * <p>
 * A steward can cancel a person's last statement: the claims it bound become live again (a figure a
 * statement before it bound is not restored), and the person's statement before it is their last again.
 * <p>
 * A statement can also be taken as of an earlier date, of the sheet as it stood then (see
 * {@link #take(String, String, long, LocalDate)}).
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final SettingsService settings;
    private final SheetHistory history;

    public StatementService(StatementRepository statements, ClaimRepository claims,
            PersonRepository people, SheetService sheets, Audit audit, RollupService rollup,
            SettingsService settings, SheetHistory history) {
        this.settings = settings;
        this.history = history;
        this.statements = statements;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** A statement taken, with the person's claims as it left them, newest first. */
    public record Taken(Statement statement, List<Claim> claims) {
    }

    /** The sheet's statements, each person's as they stand among theirs, oldest first. */
    @Transactional(readOnly = true)
    public List<Statement> list(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream().sorted(Statement.ORDER).toList();
    }

    /** Take the person's statement now (see {@link #take(String, String, long, LocalDate)}). */
    @Transactional
    public Taken take(String sheetId, String user, long personId) {
        return take(sheetId, user, personId, null);
    }

    /**
     * Take the person's statement as of {@code asOf}, a date on or before the sheet's today (now when
     * null): it binds their claims that counted at the end of that date to their figures as they stood
     * then — every change made while the sheet's today was on or before it counts, every later one does
     * not, and it stands in for the sheet's today in every figure — and leaves every other claim of theirs
     * unbound. Its date is that date and its due-total that day's. Refused when the date is after the
     * sheet's today, and when the person was not yet on the sheet then.
     */
    @Transactional
    public Taken take(String sheetId, String user, long personId, LocalDate asOf) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        List<Claim> theirs = claims.lockBySheetIdAndPersonId(sheetId, personId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.today(sheetId);
        if (asOf != null && asOf.isAfter(today)) throw new StatementRefusedException("after the sheet's today");
        // What the statement is taken of: the person and their claims as they stood then, with the
        // rates and settings then, the statement's date standing in for the sheet's today.
        Person then = person;
        List<Claim> stood = theirs;
        RollupService.Basis basis = rollup.live(sheetId);
        if (asOf != null) {
            SheetAsOf sheet = history.asOf(sheetId, asOf);
            then = sheet.person(personId).orElseThrow(() -> new StatementRefusedException("not on the sheet then"));
            stood = sheet.claimsOf(personId);
            basis = sheet;
        }
        LocalDate date = basis.today();
        Map<Long, BigDecimal> figures = new HashMap<>();
        Map<Long, BigDecimal> shown = new HashMap<>();
        for (Claim claim : stood) {
            if (!RollupService.countsLive(claim, then, date)) continue;
            figures.put(claim.getId(), rollup.statedFigureOf(claim, then, basis));
            shown.put(claim.getId(), rollup.shownOf(claim, then, basis));
        }
        int counted = figures.size();
        // The person's due-total as shown (the sum of those claims' figures, at most the sheet's cap): n/a
        // when none of their claims has a figure and no rate applies.
        BigDecimal total = rollup.dueTotalOf(stood, then, basis);
        BigDecimal dueTotal = total == null ? null : total.setScale(2, RoundingMode.HALF_EVEN);
        Statement statement = statements.saveAndFlush(new Statement(sheetId, personId, date, dueTotal, counted));
        for (Claim claim : theirs) {
            if (figures.containsKey(claim.getId())) claim.bind(figures.get(claim.getId()), shown.get(claim.getId()), statement.getId());
            else claim.unbind();
        }
        claims.flush();
        List<String> fields = new ArrayList<>(List.of("name=" + person.getName(),
                "due-total=" + (dueTotal == null ? RollupService.NONE : dueTotal.toPlainString()), "claims=" + counted));
        if (asOf != null) fields.add("asof=" + asOf);
        audit.change("STATEMENT", sheetId, user, fields.toArray(String[]::new));
        rollup.recordSince(sheetId, user, before);
        return new Taken(statement, theirs);
    }

    /** Cancel the person's last statement: the claims it bound become live again. Refused when the user is
     * not a steward of the sheet, and when the person has no statement. */
    @Transactional
    public Taken cancelLast(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new StatementNotStewardException();
        List<Claim> theirs = claims.lockBySheetIdAndPersonId(sheetId, personId);
        Statement last = statements.last(sheetId, personId)
                .orElseThrow(StatementNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        for (Claim claim : theirs) {
            if (claim.isStated() && last.getId().equals(claim.getStatedBy())) claim.unbind();
        }
        claims.flush();
        statements.delete(last);
        statements.flush();
        audit.change("STATEMENT_CANCELLED", sheetId, user, "name=" + person.getName(), "due-total="
                + (last.getDueTotal() == null ? RollupService.NONE : last.getDueTotal().toPlainString()));
        rollup.recordSince(sheetId, user, before);
        return new Taken(last, theirs);
    }
}
