package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.statement.Statement;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Merges a person into another on the same sheet; a steward's change. */
@Service
public class MergeService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final PersonMergeRepository merges;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final PoolRepository pools;
    private final CrewRepository crews;
    private final SettingService settings;
    private final Rollup rollup;
    private final Audit audit;

    public MergeService(PersonRepository people, PersonPatronRepository patrons, PersonWatchRepository watches,
            PersonMergeRepository merges, ClaimRepository claims, StatementRepository statements, PoolRepository pools, CrewRepository crews,
            SettingService settings, Rollup rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.merges = merges;
        this.claims = claims;
        this.statements = statements;
        this.pools = pools;
        this.crews = crews;
        this.settings = settings;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * Merge a person into another: the merged person's claims move to the other (numbers and dates kept),
     * listed after the other's claims in the order they were added, each keeping any figure a statement bound
     * it to; the merged person's statements count among the other's, whose last statement stays their own
     * (or, having none, becomes the merged person's); the merged name and aliases become aliases of the other; the merged person's patrons are added
     * to the other's (never the other themselves, nor twice), anyone naming the merged person as patron names
     * the other instead (not twice), and the merged person's pool memberships move to the other (their shares
     * added when the other is already a member), as do their places in crews (as lead or member; a member place
     * is dropped when the other is already in the crew). Their watchers are dropped, and they leave the
     * sheet, kept off it so the merge can be undone ({@link #undo}). Refused unless the user is a steward of the sheet, for a merge into the
     * person themselves, and when either of them is retired. The person merged into.
     */
    @Transactional
    public Person merge(String sheetId, String user, long id, long intoId) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can merge people.");
        }
        if (id == intoId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves.");
        }
        Person from = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Person into = people.findForUpdate(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged.");
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Set<Long> moved = claims.findForMove(sheetId, from.getId()).stream().map(Claim::getId)
                .collect(Collectors.toSet());
        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.getAliases());
        merges.save(new PersonMerge(sheetId, from.getId(), into.getId(), user, settings.today(sheetId), moved, names));
        Person saved = fold(sheetId, from, into, true);
        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + from.getName()
                + " into=" + saved.getName());
        rollup.recordChanges(user, before);
        return saved;
    }

    /**
     * Fold one person of the sheet into another, as a merge does, without checking who may or recording
     * it; the person merged into.
     */
    @Transactional
    public Person absorb(String sheetId, Person from, Person into) {
        return fold(sheetId, from, into, false);
    }

    /**
     * Undo the last merge made into a person: the person merged is back on the sheet, at the end of its
     * list, with their name and their own aliases (which the other loses) and the claims that moved from them
     * that the other still has (their numbers and status as they are now). Patrons, pools, crews and
     * statements stay as the merge left them. Refused unless the user is the steward who made the merge, on
     * the sheet's today it was made, for a person whose last merge is undone or who has none, and when a
     * name of the person merged is now another's on the sheet. The person restored.
     */
    @Transactional
    public Person undo(String sheetId, String user, long intoId) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can undo a merge.");
        }
        Person into = people.findForUpdate(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        PersonMerge merge = merges.findFirstBySheetIdAndIntoIdOrderByIdDesc(sheetId, intoId)
                .filter((m) -> !m.isUndone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This person has no merge to undo."));
        if (!merge.getMadeBy().equals(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the steward who made the merge can undo it.");
        }
        if (!merge.getMadeOn().equals(settings.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A merge can only be undone on the day it was made.");
        }
        Person from = people.findParkedForUpdate(merge.getPersonId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This person has no merge to undo."));
        Rollup.Snapshot before = rollup.snapshot(sheetId);

        List<String> names = merge.getAliases();
        names.forEach(into::removeAlias);
        people.saveAndFlush(into);
        for (String name : names) {
            if (people.findByNameOrAlias(sheetId, Person.keyOf(name)).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The name " + name + " is now another's.");
            }
        }
        from.restoreTo(sheetId, people.lastPlace(sheetId) + 1);
        names.stream().skip(1).forEach(from::addAlias);
        Person restored = people.saveAndFlush(from);

        for (Long claimId : merge.getClaimIds()) {
            claims.findByIdAndSheetId(claimId, sheetId)
                    .filter((claim) -> into.getId().equals(claim.getPersonId()))
                    .ifPresent((claim) -> claim.followTo(restored.getId(), claim.getListed()));
        }
        claims.flush();
        merge.undo();
        merges.save(merge);

        audit.recordOnCommit("PERSON_MERGE_UNDONE sheet=" + sheetId + " by=" + user + " name=" + restored.getName()
                + " from=" + into.getName());
        rollup.recordChanges(user, before);
        return restored;
    }

    /** Fold one person into another; the one folded is taken off the sheet to be put back by an undo (park),
     *  else deleted. */
    private Person fold(String sheetId, Person from, Person into, boolean park) {
        long listed = claims.lastListed(sheetId);
        for (Claim claim : claims.findForMove(sheetId, from.getId())) {
            claim.followTo(into.getId(), ++listed);
        }
        claims.flush();

        boolean intoStated = !statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, into.getId()).isEmpty();
        for (Statement statement : statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId())) {
            statement.carryTo(into.getId(), intoStated);
        }
        statements.flush();

        for (PersonPatron patron : patrons.findByPersonIdOrderByIdAsc(from.getId())) {
            patrons.delete(patron);
            if (!patron.getPatronId().equals(into.getId())
                    && !patrons.existsByPersonIdAndPatronId(into.getId(), patron.getPatronId())) {
                patrons.save(new PersonPatron(sheetId, into.getId(), patron.getPatronId()));
            }
        }
        for (PersonPatron named : patrons.findByPatronIdOrderByIdAsc(from.getId())) {
            patrons.delete(named);
            if (!named.getPersonId().equals(into.getId())
                    && !patrons.existsByPersonIdAndPatronId(named.getPersonId(), into.getId())) {
                patrons.save(new PersonPatron(sheetId, named.getPersonId(), into.getId()));
            }
        }
        patrons.flush();

        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            pool.moveMember(from.getId(), into.getId());
        }
        pools.flush();
        for (Crew crew : crews.findBySheetIdOrderByIdAsc(sheetId)) {
            crew.movePerson(from.getId(), into.getId());
        }
        crews.flush();

        watches.deleteByPersonId(from.getId());

        // The merged person, and with them their aliases, goes first so their names are free to become aliases.
        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.getAliases());
        if (park) {
            from.park();
            people.saveAndFlush(from);
        } else {
            people.delete(from);
            people.flush();
        }
        names.forEach(into::addAlias);
        return people.saveAndFlush(into);
    }
}
