package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merging a person into another on the sheet (a steward only). The merged person's claims move to
 * the other, keeping their numbers and dates, listed after the other's in the order they were added,
 * each keeping any figure a statement bound it to; their statements go to the other, listed before the
 * other's own (so the other's last statement stays theirs, or, having none, becomes the merged
 * person's); their name and aliases become aliases of the other; their patrons
 * are added to the other's (not the other themselves, no repeats), anyone naming them as patron names
 * the other instead (no repeats), and their pool memberships move to the other (shares added when the
 * other is already a member), as do their crew memberships and the crews they lead (dropped where the
 * other is already in the crew or leads it). Their holds, watchers and contact are dropped, and they leave the sheet.
 */
@Service
public class PersonMergeService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final PoolMemberRepository poolMembers;
    private final CrewService crews;
    private final SheetService sheets;
    private final RoleService roles;
    private final RollupService rollup;
    private final Audit audit;

    public PersonMergeService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, StatementRepository statements, PoolMemberRepository poolMembers,
            CrewService crews, SheetService sheets, RoleService roles, RollupService rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.sheets = sheets;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * Merge the person into another. Refused (403) to anyone but a steward, (409) for a merge into the
     * person themselves or when either of them is retired.
     */
    @Transactional
    public Person merge(String sheetId, String user, long id, long intoId) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessSteward(sheetId, user);
        sheets.lock(sheetId);
        Person from = person(sheetId, id);
        Person into = person(sheetId, intoId);
        if (from.getId().equals(into.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves.");
        }
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String fromName = from.getName();
        List<String> names = new ArrayList<>(List.of(fromName));
        names.addAll(from.getAliases());

        // the claims, oldest listed first, each listed first in turn: after the other's, in their order
        List<Claim> moved = claims.findBySheetIdAndPersonIdOrderByListedAsc(sheetId, from.getId());
        for (Claim claim : moved) {
            claim.setPersonId(into.getId());
        }
        claims.saveAllAndFlush(moved);
        for (Claim claim : moved) {
            claims.listFirst(claim.getId());
        }

        // shares the merged person held go to the other
        List<Claim> shared = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getShares().stream().anyMatch(sh -> sh.getHolderId().equals(from.getId())
                        || sh.getHolderId().equals(c.getPersonId())))
                .toList();
        shared.forEach(c -> c.mergeHolder(from.getId(), into.getId()));
        claims.saveAllAndFlush(shared);

        // patrons: the merged person's go to the other, and whoever named them names the other
        List<Patron> links = patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(l -> l.getPersonId().equals(from.getId()) || l.getPatronId().equals(from.getId())).toList();
        patrons.deleteAll(links);
        patrons.flush();
        for (Patron link : links) {
            long personId = link.getPersonId().equals(from.getId()) ? into.getId() : link.getPersonId();
            long patronId = link.getPatronId().equals(from.getId()) ? into.getId() : link.getPatronId();
            if (personId != patronId && !patrons.existsByPersonIdAndPatronId(personId, patronId)) {
                patrons.saveAndFlush(new Patron(sheetId, personId, patronId));
            }
        }

        // pool memberships move, adding the share where the other is already a member
        for (PoolMember member : poolMembers.findByPersonIdOrderByIdAsc(from.getId())) {
            var existing = poolMembers.findByPoolIdAndPersonId(member.getPoolId(), into.getId());
            if (existing.isPresent()) {
                existing.get().addShare(member.getShare());
                poolMembers.save(existing.get());
                poolMembers.delete(member);
            } else {
                member.setPersonId(into.getId());
                poolMembers.save(member);
            }
        }
        poolMembers.flush();
        crews.merge(from.getId(), into.getId());

        statements.moveStatements(sheetId, from.getId(), sheetId, into.getId());
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        watches.flush();

        // listing the claims first cleared what was read: read both people again
        people.delete(person(sheetId, from.getId()));
        people.flush();
        Person merged = person(sheetId, into.getId());
        names.forEach(merged::addAlias);
        Person saved = people.saveAndFlush(merged);

        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + fromName + " into="
                + saved.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    private Person person(String sheetId, long id) {
        return people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }
}
