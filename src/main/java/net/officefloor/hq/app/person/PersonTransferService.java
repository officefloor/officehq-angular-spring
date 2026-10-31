package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.patron.PatronRepository;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import net.officefloor.hq.app.watch.WatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transferring a person from one sheet to another. */
@Service
public class PersonTransferService {

    /** Claims in the order they were opened: by the sheet's today when each was, then by the order they were
     *  added. */
    private static final Comparator<Claim> BY_OPENED = Comparator
            .comparing(Claim::openedOn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Claim::getId);

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PatronRepository patrons;
    private final PoolMemberRepository poolMembers;
    private final StatementRepository statements;
    private final WatchRepository watches;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RoleService roles;
    private final Audit audit;
    private final Rollup rollup;

    public PersonTransferService(PersonRepository people, ClaimRepository claims, ClaimService claimService,
            PatronRepository patrons, PoolMemberRepository poolMembers, StatementRepository statements,
            WatchRepository watches, SheetService sheets, SheetRepository sheetStore, RoleService roles, Audit audit,
            Rollup rollup) {
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
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
     * A steward transfers a person to another sheet (started empty if never loaded). They arrive there with every
     * grade they were given, their terms, contact and aliases, and their claims with their status, dates, notes,
     * tags, frozen figures and logs, listed in the order they had; each claim that had a number takes the other
     * sheet's next, in the order the claims were opened. Figures a statement bound are lost, and so are references,
     * settle batches and holds. They leave this sheet entirely: their statements, patron links (either way), pool
     * memberships and watches are dropped. Refused for anyone but a steward, a retired person, a transfer to this
     * sheet itself or to an archived sheet, and when the other sheet already has their name, one of their aliases (as
     * a name or alias) or their contact.
     */
    @Transactional
    public void transfer(String sheetId, String user, long id, String toSheetId) {
        sheets.load(sheetId, user);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can transfer a person");
        }
        String target = toSheetId == null ? "" : toSheetId.trim();
        if (target.equals(sheetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A person cannot be transferred to their own sheet");
        }
        sheets.load(target, user);
        // Both sheets are locked, always in the same order, so transfers between them are made one at a time.
        for (String locked : sheetId.compareTo(target) < 0 ? List.of(sheetId, target) : List.of(target, sheetId)) {
            sheetStore.findLockedById(locked).orElseThrow();
        }
        Sheet to = sheetStore.findById(target).orElseThrow();
        if (to.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sheet " + target + " is archived");
        }
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be transferred");
        }
        List<String> names = new ArrayList<>(person.getAliases());
        names.add(0, person.getName());
        if (names.stream().anyMatch(n -> people.findBySheetIdAndNameOrAliasIgnoreCase(target, n).isPresent())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That name or alias is already in use on sheet " + target);
        }
        if (person.getContact() != null
                && people.existsBySheetIdAndContact(target, person.getContact().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on sheet " + target);
        }
        Rollup.Figures before = rollup.of(sheetId);
        Rollup.Figures beforeThere = rollup.of(target);

        patrons.deleteAll(patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> p.getPersonId().equals(person.getId()) || p.getPatronId().equals(person.getId()))
                .toList());
        poolMembers.deleteAll(poolMembers.findByPersonIdOrderByIdAsc(person.getId()));
        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, person.getId()));
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(person.getId()));
        patrons.flush();

        List<Claim> moving = claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, person.getId());
        Map<String, String> renumbered = new HashMap<>();
        for (Claim claim : moving.stream().filter(c -> c.getNumber() != null).sorted(BY_OPENED).toList()) {
            renumbered.put(claim.getNumber(), claimService.nextNumberOn(to, claim.getDueBy()));
        }
        sheetStore.save(to);
        long listed = claims.lastListed(target);
        List<Claim> oldestFirst = new ArrayList<>(moving);
        Collections.reverse(oldestFirst);
        for (Claim claim : oldestFirst) {
            claim.transferTo(target, person.getId(), ++listed, renumbered.get(claim.getNumber()),
                    claim.getCorrects() == null ? null : renumbered.getOrDefault(claim.getCorrects(), claim.getCorrects()),
                    claim.getSplitFrom() == null ? null : renumbered.getOrDefault(claim.getSplitFrom(), claim.getSplitFrom()));
        }
        person.transferTo(target);
        people.saveAndFlush(person);
        claims.saveAllAndFlush(moving);

        audit.recordChangeOnCommit(sheetId, user, "PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " to=" + target);
        rollup.recordChanges(sheetId, user, before);
        audit.recordChangeOnCommit(target, user, "PERSON_TRANSFERRED sheet=" + target + " by=" + user
                + " name=" + person.getName() + " from=" + sheetId);
        rollup.recordChanges(target, user, beforeThere);
    }
}
