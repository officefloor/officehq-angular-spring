package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.plan.PlanRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.statement.Statement;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merges one person of a sheet into another: the merged person leaves the sheet, what they had passing to the other.
 */
@Service
public class PersonMergeService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final PoolMemberRepository poolMembers;
    private final PeriodRepository periods;
    private final PlanRepository plans;
    private final SettingsService settings;
    private final RollupService rollup;
    private final Audit audit;

    public PersonMergeService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, StatementRepository statements, PoolMemberRepository poolMembers,
            PeriodRepository periods, PlanRepository plans, SettingsService settings, RollupService rollup,
            Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.periods = periods;
        this.plans = plans;
        this.settings = settings;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * A steward merges the person into another person of the sheet. The merged person's claims move to the other
     * (numbers and dates kept), listed after the other's in the order they were added, each keeping any figure a
     * statement bound; their name and aliases become the other's aliases; their patrons are added to the other's (not
     * the other themselves, no repeats), anyone naming them as patron names the other instead (no repeats), and their
     * pool memberships and plans move to the other (shares added, at most 100.00, where the other is already a member); a
     * closed period's figure fixed for them is added to the other's. Their statements pass to the other, standing
     * before the other's own (the other's last statement stays theirs, or, having none, becomes the merged person's);
     * their holds, watchers and contact are dropped, and they leave the sheet. Refused unless the user is a steward, for the same person, and when
     * either of them is retired. The person merged into, as saved.
     */
    @Transactional
    public Person merge(String by, String sheetId, Long id, Long intoId) {
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can merge people.");
        }
        if (id.equals(intoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves.");
        }
        Person from = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Person into = people.findWithLockByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person saved = absorb(by, sheetId, from, into);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Merges the one person into the other, both of the sheet and locked, exactly as a merge does, recording it; the
     * caller checks the merge may be made and records the changes to the figures.
     */
    Person absorb(String by, String sheetId, Person from, Person into) {
        // the claims, in the order they were added, each listed as the other's newest in turn
        List<Claim> moved = new ArrayList<>(claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, from.getId()));
        moved.sort(Comparator.comparing(Claim::getId));
        for (Claim c : moved) {
            c.setPersonId(into.getId());
            claims.saveAndFlush(c);
            claims.relist(c.getId());
        }

        plans.movePlans(sheetId, from.getId(), into.getId());
        mergePatrons(sheetId, from.getId(), into.getId());
        mergePools(sheetId, from.getId(), into.getId());
        for (Period p : periods.findBySheetIdOrderByFromAsc(sheetId)) {
            p.mergePersonTotal(from.getId(), into.getId());
        }
        periods.flush();
        mergeStatements(sheetId, from.getId(), into.getId());
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        statements.flush();
        watches.flush();

        String fromName = from.getName();
        List<String> names = new ArrayList<>();
        names.add(fromName);
        names.addAll(from.getAliases());
        people.delete(from);
        people.flush();
        // one they already have exactly is not given twice
        names.stream().filter(n -> !n.equals(into.getName()) && !into.getAliases().contains(n)).forEach(into::addAlias);
        Person saved = people.saveAndFlush(into);

        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + by + " from=" + fromName + " into="
                + saved.getName());
        return saved;
    }

    /**
     * The merged person's statements pass to the other, in their order, standing before the other's own, so the
     * other's last statement stays theirs (or, having none, becomes the merged person's).
     */
    private void mergeStatements(String sheetId, Long from, Long into) {
        List<Statement> moved = statements.findBySheetIdAndPersonIdInOrder(sheetId, from);
        List<Statement> theirs = statements.findBySheetIdAndPersonIdInOrder(sheetId, into);
        long place = theirs.isEmpty() ? 0 : theirs.get(0).getPlace() - moved.size();
        for (Statement s : moved) {
            s.moveTo(sheetId, into, theirs.isEmpty() ? s.getPlace() : place++);
        }
    }

    /** The merged person's patrons pass to the other, and whoever named them names the other; never twice, nor self. */
    private void mergePatrons(String sheetId, Long from, Long into) {
        for (Patron p : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!p.getPersonId().equals(from)) {
                continue;
            }
            if (p.getPatronId().equals(into) || patrons.existsByPersonIdAndPatronId(into, p.getPatronId())) {
                patrons.delete(p);
            } else {
                p.setPersonId(into);
                patrons.save(p);
            }
            patrons.flush();
        }
        for (Patron p : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!p.getPatronId().equals(from)) {
                continue;
            }
            if (p.getPersonId().equals(into) || patrons.existsByPersonIdAndPatronId(p.getPersonId(), into)) {
                patrons.delete(p);
            } else {
                p.setPatronId(into);
                patrons.save(p);
            }
            patrons.flush();
        }
    }

    /** The merged person's pool memberships pass to the other; where the other is a member, the shares are added. */
    private void mergePools(String sheetId, Long from, Long into) {
        List<PoolMember> all = poolMembers.findBySheetIdOrderByIdAsc(sheetId);
        for (PoolMember m : all) {
            if (!from.equals(m.getPersonId())) {
                continue;
            }
            PoolMember theirs = all.stream()
                    .filter(o -> o.getPoolId().equals(m.getPoolId()) && into.equals(o.getPersonId()))
                    .findFirst().orElse(null);
            if (theirs != null) {
                theirs.setShare(theirs.getShare().add(m.getShare()).min(HUNDRED));
                poolMembers.save(theirs);
                poolMembers.delete(m);
            } else {
                m.setPersonId(into);
                poolMembers.save(m);
            }
        }
        poolMembers.flush();
    }
}
