package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.plan.PlanRepository;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
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
    private final CrewService crews;
    private final PeriodRepository periods;
    private final PlanRepository plans;
    private final SettingsService settings;
    private final RollupService rollup;
    private final SheetService sheets;
    private final PersonMergeRecords records;
    private final Audit audit;

    public PersonMergeService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, StatementRepository statements, PoolMemberRepository poolMembers, CrewService crews,
            PeriodRepository periods, PlanRepository plans, SettingsService settings, RollupService rollup,
            SheetService sheets, PersonMergeRecords records, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.periods = periods;
        this.plans = plans;
        this.settings = settings;
        this.rollup = rollup;
        this.sheets = sheets;
        this.records = records;
        this.audit = audit;
    }

    /**
     * A steward merges the person into another person of the sheet. The merged person's claims move to the other
     * (numbers and dates kept), listed after the other's in the order they were added, each keeping any figure a
     * statement bound; their name and aliases become the other's aliases; their patrons are added to the other's (not
     * the other themselves, no repeats), anyone naming them as patron names the other instead (no repeats), and their
     * pool memberships and plans move to the other (shares added, at most 100.00, where the other is already a member), as
     * do the crews they lead and their crew memberships (one the other already has, or leads, dropped); a
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
        // the merged person is kept as they stand, so the merge can be undone
        long mergeId = records.keep(sheetId, from.getId(), into.getId(), by, sheets.load(sheetId).getToday());
        List<String> had = into.getAliases();
        Person saved = absorb(by, sheetId, from, into);
        records.keepGiven(mergeId, saved.getAliases().stream().filter(a -> !had.contains(a)).toList());
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * The steward who made the last merge into the person undoes it on the sheet's today it was made: the merged
     * person is back, at the end of the list, as they were before it (with their grades, holds, terms and own
     * aliases, and their contact unless someone has it now), with the claims that came from them still the other's
     * (their numbers and status as they are now); the aliases the merge gave the other leave them. Patron, pool,
     * crew, plan, period and statement changes of the merge stay. Refused unless the user is a steward and the one who
     * made the merge, on any later day, for a person whose last merge into them is undone or who has none, and when
     * the merged person's name or an alias is someone else's now. The person brought back, as saved.
     */
    @Transactional
    public Person undo(String by, String sheetId, Long intoId) {
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can undo a merge.");
        }
        Person into = people.findWithLockByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        PersonMergeRecords.Merge merge = records.lastInto(sheetId, intoId).filter(m -> !m.undone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "There is no merge to undo."));
        if (!by.equals(merge.madeBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the steward who made the merge can undo it.");
        }
        if (!Objects.equals(merge.madeOn(), sheets.load(sheetId).getToday())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A merge can only be undone on the day it was made.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);

        // the aliases the merge gave the other leave them
        into.removeAliases(records.given(merge.id()));
        people.saveAndFlush(into);
        String name = records.name(merge.id());
        List<String> names = new ArrayList<>();
        names.add(name);
        names.addAll(records.aliases(merge.id()));
        if (names.stream().anyMatch(n -> people.nameOrAliasInUse(sheetId, n))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The merged person's name or an alias is someone else's now.");
        }
        String contact = records.contact(merge.id());
        if (contact != null && people.existsBySheetIdAndContact(sheetId, contact)) {
            contact = null;
        }
        records.restore(merge.id(), people.nextPosition(sheetId), contact);

        // the claims that came from them, still the other's, go back in the order they are listed
        List<Claim> back = new ArrayList<>(claims.findAllById(records.claims(merge.id())).stream()
                .filter(c -> c.getSheetId().equals(sheetId) && intoId.equals(c.getPersonId())).toList());
        back.sort(Comparator.comparing(Claim::getListed, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Claim::getId));
        for (Claim c : back) {
            c.setPersonId(merge.fromId());
            claims.saveAndFlush(c);
            claims.relist(c.getId());
        }

        audit.recordOnCommit("PERSON_MERGE_UNDONE sheet=" + sheetId + " by=" + by + " name=" + name + " from="
                + into.getName());
        rollup.recordChanges(by, sheetId, before);
        return people.findByIdAndSheetId(merge.fromId(), sheetId).orElseThrow();
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
        crews.merged(sheetId, from.getId(), into.getId());
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
