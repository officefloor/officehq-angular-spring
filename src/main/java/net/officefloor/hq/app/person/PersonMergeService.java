package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.patron.Patron;
import net.officefloor.hq.app.patron.PatronRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import net.officefloor.hq.app.watch.WatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Merging one person of a sheet into another. */
@Service
public class PersonMergeService {

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final PatronRepository patrons;
    private final PoolMemberRepository poolMembers;
    private final StatementRepository statements;
    private final WatchRepository watches;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RoleService roles;
    private final Audit audit;
    private final Rollup rollup;

    public PersonMergeService(PersonRepository people, ClaimRepository claims, PatronRepository patrons,
            PoolMemberRepository poolMembers, StatementRepository statements, WatchRepository watches,
            SheetService sheets, SheetRepository sheetStore, RoleService roles, Audit audit, Rollup rollup) {
        this.people = people;
        this.claims = claims;
        this.patrons = patrons;
        this.poolMembers = poolMembers;
        this.statements = statements;
        this.watches = watches;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.roles = roles;
        this.audit = audit;
        this.rollup = rollup;
    }

    /**
     * A steward merges a person into another of the sheet. The merged person's claims move to the other, keeping
     * their numbers and dates, listed as the other's newest in the order they were added, each losing any figure a
     * statement bound it to; their name and aliases become aliases of the other; their patrons are added to the
     * other's (never the other themselves, nor one twice), anyone naming them as patron names the other instead (once),
     * and their pool memberships move to the other (shares added where the other is already a member). Their
     * statements, holds, watchers and contact are dropped with them. Refused for anyone but a steward, a merge into
     * the same person, and when either of them is retired.
     */
    @Transactional
    public Person merge(String sheetId, String user, long id, long intoId) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can merge people");
        }
        Person from = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        Person into = people.findByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (from.getId().equals(into.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves");
        }
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged");
        }
        Rollup.Figures before = rollup.of(sheetId);

        long listed = claims.lastListed(sheetId);
        List<Claim> moving = claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, from.getId()).stream()
                .sorted(Comparator.comparing(Claim::getId)).toList();
        for (Claim claim : moving) {
            claim.unbind();
            claim.reassign(into.getId(), ++listed);
        }
        claims.saveAll(moving);

        // The patron pairs the other has once the merged person's are taken over.
        List<Patron> sheetPatrons = patrons.findBySheetIdOrderByIdAsc(sheetId);
        Set<List<Long>> pairs = new LinkedHashSet<>();
        sheetPatrons.stream().filter(p -> !p.getPersonId().equals(from.getId()) && !p.getPatronId().equals(from.getId()))
                .forEach(p -> pairs.add(List.of(p.getPersonId(), p.getPatronId())));
        List<Patron> gone = new ArrayList<>();
        List<Patron> added = new ArrayList<>();
        for (Patron p : sheetPatrons) {
            if (!p.getPersonId().equals(from.getId()) && !p.getPatronId().equals(from.getId())) {
                continue;
            }
            gone.add(p);
            long person = p.getPersonId().equals(from.getId()) ? into.getId() : p.getPersonId();
            long patron = p.getPatronId().equals(from.getId()) ? into.getId() : p.getPatronId();
            if (person != patron && pairs.add(List.of(person, patron))) {
                added.add(new Patron(sheetId, person, patron));
            }
        }
        patrons.deleteAll(gone);
        patrons.flush();

        for (PoolMember member : poolMembers.findByPersonIdOrderByIdAsc(from.getId())) {
            var existing = poolMembers.findByPoolIdAndPersonId(member.getPoolId(), into.getId());
            if (existing.isPresent()) {
                existing.get().setShare(existing.get().getShare().add(member.getShare()));
                poolMembers.delete(member);
            } else {
                member.moveTo(into.getId());
            }
        }
        poolMembers.flush();

        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()));
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        String fromName = from.getName();
        List<String> fromAliases = from.getAliases();
        people.delete(from);
        people.flush();

        patrons.saveAll(added);
        into.addAlias(fromName);
        fromAliases.forEach(into::addAlias);
        Person merged = people.saveAndFlush(into);
        audit.recordChangeOnCommit(sheetId, user, "PERSON_MERGED sheet=" + sheetId + " by=" + user
                + " from=" + fromName + " into=" + merged.getName());
        rollup.recordChanges(sheetId, user, before);
        return merged;
    }
}
