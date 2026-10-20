package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatementService {

    /** What a person's statements come to: how many, and the last one (none before the first). */
    public record Summary(int count, Statement last) {}

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final SheetService sheets;
    private final RollupService rollup;
    private final SettingService settings;
    private final Audit audit;

    public StatementService(StatementRepository statements, ClaimRepository claims, ClaimService claimService,
            PersonRepository people, SheetService sheets, RollupService rollup, SettingService settings, Audit audit) {
        this.statements = statements;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    /** Each person's statements on the sheet, by person id. */
    @Transactional(readOnly = true)
    public Map<Long, Summary> summaries(String sheetId) {
        Map<Long, Summary> by = new HashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            Summary was = by.get(s.getPersonId());
            by.put(s.getPersonId(), new Summary(was == null ? 1 : was.count() + 1, s));
        }
        return by;
    }

    /**
     * Take a statement for a person: record their due-total now, late charges included and held to the cap, and bind each of their
     * claims that counts (OPEN and PENDING ones within a hold, unbound, and deferred ones do not) to the figure it has now (never its late charge, which keeps following the sheet's
     * today); a claim of theirs that does not count is left unbound.
     */
    @Transactional
    public Person take(String user, String sheetId, Long personId) {
        return take(user, sheetId, personId, null);
    }

    /**
     * Take a statement for a person as of a date on or before the sheet's today (none: now): as {@link #take}, but
     * by their claims that counted at the end of that date and their figures as they stood then (see
     * {@link RollupService#asOf}). A date after the sheet's today, or before the person was added, is refused.
     */
    @Transactional
    public Person take(String user, String sheetId, Long personId, LocalDate asOf) {
        LocalDate today = sheets.today(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (asOf != null && asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A statement cannot be taken as of a date after the sheet's today.");
        }
        if (asOf != null && !person.addedBy(asOf)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    person.getName() + " was not on the sheet on " + asOf + ".");
        }
        Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonId(sheetId, personId);
        BigDecimal dueTotal;
        int counted;
        if (asOf != null) {
            RollupService.AsOf then = rollup.asOf(person, theirs, asOf);
            for (Claim claim : theirs) {
                if (then.figures().containsKey(claim.getId())) {
                    claim.bind(then.figures().get(claim.getId()), today);
                } else {
                    claim.unbind(today);
                }
            }
            dueTotal = then.dueTotal();
            counted = then.figures().size();
        } else {
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            boolean fixed = false;
            List<Claim> bound = new ArrayList<>();
            for (Claim claim : theirs) {
                if (!RollupService.counts(claim, person, today)) {
                    claim.unbind(today);
                    continue;
                }
                BigDecimal figure = claimService.figureOf(claim, person);
                BigDecimal due = rollup.dueOf(claim, person);
                fixed |= due != null;
                claim.bind(figure, today);
                bound.add(claim);
                if (due != null) {
                    total = total.add(due);
                }
            }
            // Like the screen: with no rate in force today and no claim having a figure, the due-total is n/a.
            boolean rated = rollup.rated(person, bound, today);
            dueTotal = rollup.capped(sheetId, rated || fixed ? total : null);
            counted = bound.size();
        }
        claims.flush();
        statements.saveAndFlush(new Statement(sheetId, personId, today, user, dueTotal, counted, asOf));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal != null ? dueTotal.toPlainString() : "n/a")
                + " claims=" + counted + (asOf != null ? " asof=" + asOf : ""));
        rollup.recordChanges(user, sheetId, before);
        return person;
    }

    /**
     * A steward cancels a person's last statement: it is removed, and the claims it bound are live again (a figure
     * bound by the statement before it is not restored). Refused for anyone else, and for a person with no statement.
     */
    @Transactional
    public Person cancel(String user, String sheetId, Long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        settings.requireSteward(sheetId, user);
        Statement last = statements.findFirstBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "That person has no statement to cancel."));
        LocalDate today = sheets.today(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        // Every claim of the person still bound was bound by their last statement, which rebinds or unbinds each.
        for (Claim claim : claims.findBySheetIdAndPersonId(sheetId, personId)) {
            if (claim.isStated()) {
                claim.unbind(today);
            }
        }
        claims.flush();
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (last.getDueTotal() != null ? last.getDueTotal().toPlainString() : "n/a"));
        rollup.recordChanges(user, sheetId, before);
        return person;
    }
}
