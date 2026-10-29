package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.plan.PlanRepository;
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

/** Transfers a person of a sheet, with their claims, to another sheet. */
@Service
public class PersonTransferService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final StatementRepository statements;
    private final PoolMemberRepository poolMembers;
    private final PlanRepository plans;
    private final SheetService sheets;
    private final SettingsService settings;
    private final RollupService rollup;
    private final PersonMergeService mergeService;
    private final Audit audit;

    public PersonTransferService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, ClaimService claimService, StatementRepository statements,
            PoolMemberRepository poolMembers, PlanRepository plans, SheetService sheets, SettingsService settings, RollupService rollup,
            PersonMergeService mergeService, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.claimService = claimService;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.plans = plans;
        this.sheets = sheets;
        this.settings = settings;
        this.rollup = rollup;
        this.mergeService = mergeService;
        this.audit = audit;
    }

    /**
     * A steward transfers the person to the other sheet. They arrive with every grade (and its from-date), their
     * holds, terms, contact and aliases, and their claims with status, dates, notes, tags, frozen figures and logs,
     * listed newest first as they were; each claim that had a number takes the other sheet's next, in the order the
     * claims were opened (by the sheet's today when each was opened, then the order they were added). Figures a
     * statement bound are kept, their statements go with them, and references are not carried. Their patron links, pool
     * memberships and watches are dropped, and they leave this sheet. Refused unless the user is a steward, for a
     * retired person, for this sheet's own id, for an archived sheet, and when their contact is already a contact on
     * the other sheet. When their name or an alias is already a name or alias of someone on the other sheet (compared
     * as names are), they arrive and are merged into that person exactly as a merge does (their contact then dropped
     * rather than refused); refused when that person is retired, or when their names match more than one person.
     */
    @Transactional
    public void transfer(String by, String sheetId, Long id, String toSheetId) {
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can transfer people.");
        }
        if (sheetId.equals(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be transferred to their own sheet.");
        }
        // both sheets are locked, in the order of their ids, so two transfers between them never deadlock
        if (sheetId.compareTo(toSheetId) < 0) {
            sheets.lock(sheetId);
            sheets.lock(toSheetId);
        } else {
            sheets.lock(toSheetId);
            sheets.lock(sheetId);
        }
        if (settings.isArchived(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet is archived.");
        }
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be transferred.");
        }
        List<String> names = new ArrayList<>();
        names.add(person.getName());
        names.addAll(person.getAliases());
        List<Long> matches = names.stream()
                .map(n -> people.findBySheetIdAndNameOrAlias(toSheetId, n).map(Person::getId).orElse(null))
                .filter(m -> m != null).distinct().toList();
        if (matches.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The name and aliases match more than one person on the other sheet.");
        }
        Person into = matches.isEmpty() ? null
                : people.findWithLockByIdAndSheetId(matches.get(0), toSheetId).orElseThrow();
        if (into != null && into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The person on the other sheet with the name is retired, so cannot be merged into.");
        }
        if (into == null && person.getContact() != null
                && people.existsBySheetIdAndContact(toSheetId, person.getContact())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The contact is already on the other sheet.");
        }
        RollupService.Snapshot beforeHere = rollup.snapshot(sheetId);
        RollupService.Snapshot beforeThere = rollup.snapshot(toSheetId);

        // what links the person to others on this sheet is dropped
        patrons.deleteAll(patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> p.getPersonId().equals(id) || p.getPatronId().equals(id)).toList());
        List<PoolMember> memberships = poolMembers.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(m -> id.equals(m.getPersonId())).toList();
        poolMembers.deleteAll(memberships);
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(id));
        // their statements go with them, in their order
        for (Statement s : statements.findBySheetIdAndPersonIdInOrder(sheetId, id)) {
            s.moveTo(toSheetId, id, s.getPlace());
        }
        plans.deleteAll(plans.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, id));
        plans.flush();
        patrons.flush();
        poolMembers.flush();
        watches.flush();
        statements.flush();

        // one merged on arrival is never listed there: their name is already someone's on the other sheet
        if (into == null) {
            person.moveToSheet(toSheetId);
            // they go to the end of the other sheet's list
            person.setPosition(people.nextPosition(toSheetId));
            people.saveAndFlush(person);
        }

        List<Claim> moved = new ArrayList<>(claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, id));
        // numbered afresh in the order they were opened
        moved.sort(Comparator.comparing(Claim::openedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Claim::getId));
        Map<String, String> renumbered = new HashMap<>();
        for (Claim c : moved) {
            String was = c.getNumber();
            c.moveToSheet(toSheetId);
            claimService.takeIn(toSheetId, c);
            if (was != null) {
                renumbered.put(was, c.getNumber());
            }
        }
        // a claim naming another of theirs by number names it by its new one; one naming any other names none
        for (Claim c : moved) {
            if (c.getCorrects() != null) {
                c.setCorrects(renumbered.get(c.getCorrects()));
            }
            if (c.getSplitFrom() != null) {
                c.setSplitFrom(renumbered.get(c.getSplitFrom()));
            }
            claims.saveAndFlush(c);
        }
        // listed as the other sheet's newest, in the order they were listed here
        moved.sort(Comparator.comparing(Claim::getListed, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Claim::getId));
        for (Claim c : moved) {
            claims.relist(c.getId());
        }

        String name = person.getName();
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + by + " name=" + name + " to="
                + toSheetId);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + toSheetId + " by=" + by + " name=" + name + " from="
                + sheetId);
        if (into != null) {
            mergeService.absorb(by, toSheetId, person, into);
        }
        rollup.recordChanges(by, sheetId, beforeHere);
        rollup.recordChanges(by, toSheetId, beforeThere);
    }
}
