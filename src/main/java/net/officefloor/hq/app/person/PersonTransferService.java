package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLogEntry;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.UsedTag;
import net.officefloor.hq.app.claim.UsedTagRepository;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetHistory;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.Statement;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transferring a person to another sheet by id (a steward only). The person arrives there with
 * every grade they were given, their terms, contact and aliases, and their claims with their status,
 * dates, notes, tags, frozen figures and logs. Each claim that had a number takes the next number of
 * the other sheet, in the order the claims were opened (by the sheet's today when each was opened,
 * then by the order they were added). Figures a statement bound are lost, their statements are
 * dropped, and references are not carried. They leave this sheet entirely: patron links, pool
 * memberships, holds and watches are dropped. They and their claims arrive as new rows, so
 * this sheet's history (its as-of view) keeps its own.
 */
@Service
public class PersonTransferService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final PoolMemberRepository poolMembers;
    private final UsedTagRepository usedTags;
    private final SheetService sheets;
    private final SheetHistory history;
    private final RoleService roles;
    private final RollupService rollup;
    private final Audit audit;

    public PersonTransferService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, StatementRepository statements, PoolMemberRepository poolMembers,
            UsedTagRepository usedTags, SheetService sheets, SheetHistory history, RoleService roles, RollupService rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.usedTags = usedTags;
        this.sheets = sheets;
        this.history = history;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * Transfer the person to the other sheet. Refused (403) to anyone but a steward, (409) for a
     * transfer to this same sheet, of a retired person, or when the person's name, an alias or their
     * contact is already on the other sheet; (423) when either sheet is archived.
     */
    @Transactional
    public Person transfer(String sheetId, String user, long id, String toSheetId) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessSteward(sheetId, user);
        if (sheetId.equals(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be transferred to their own sheet.");
        }
        sheets.refuseIfArchived(sheetId);
        sheets.refuseIfArchived(toSheetId);
        sheets.load(toSheetId, user);
        // lock both sheets, in a fixed order, so two transfers between them do not deadlock
        if (sheetId.compareTo(toSheetId) < 0) {
            sheets.lock(sheetId);
            sheets.lock(toSheetId);
        } else {
            sheets.lock(toSheetId);
            sheets.lock(sheetId);
        }
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be transferred.");
        }
        Set<String> toTree = new HashSet<>(sheets.tree(toSheetId));
        toTree.remove(toSheetId);
        List<String> names = new ArrayList<>(List.of(person.getName()));
        names.addAll(person.getAliases());
        for (String name : names) {
            if (people.existsBySheetIdAndNameIgnoreCase(toSheetId, name) || people.existsAliasOnSheet(toSheetId, name)
                    || !toTree.isEmpty() && people.existsNameOrAliasOnSheets(toTree, name, person.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The name " + name + " is already in use on sheet " + toSheetId + ".");
            }
        }
        if (person.getContact() != null && people.existsBySheetIdAndContact(toSheetId, person.getContact())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That contact is already in use on sheet " + toSheetId + ".");
        }
        history.captureFirst(toSheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        RollupService.Snapshot beforeTo = rollup.snapshot(toSheetId);

        // the links to this sheet go: patrons either way, pool memberships, watches and statements
        patrons.deleteAll(patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(l -> l.getPersonId().equals(person.getId()) || l.getPatronId().equals(person.getId()))
                .toList());
        patrons.flush();
        poolMembers.deleteAll(poolMembers.findByPersonIdOrderByIdAsc(person.getId()));
        poolMembers.flush();
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(person.getId()));
        watches.flush();
        List<Statement> theirStatements = statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(s -> Objects.equals(s.getPersonId(), person.getId())).toList();
        statements.deleteAll(theirStatements);
        statements.flush();

        // the person and their claims arrive on the other sheet as new rows, listed in the order they
        // were, the numbered ones numbered afresh there in the order they were opened; this sheet's go
        Person arriving = person.transferredTo(toSheetId);
        arriving.setPosition(people.maxPosition(toSheetId) + 1);
        Person arrived = people.saveAndFlush(arriving);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByListedAsc(sheetId, person.getId());
        Map<Long, String> numbers = new HashMap<>();
        theirs.stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing(PersonTransferService::openedOn,
                        Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Claim::getId))
                .forEach(c -> numbers.put(c.getId(), sheets.nextClaimNumber(toSheetId, c.getDueBy())));
        for (Claim claim : theirs) {
            Claim copy = claim.transferredTo(toSheetId, arrived.getId());
            copy.setNumber(numbers.get(claim.getId()));
            claims.saveAndFlush(copy);
            for (String tag : copy.getTags()) {
                if (!usedTags.existsBySheetIdAndTag(toSheetId, tag)) {
                    usedTags.saveAndFlush(new UsedTag(toSheetId, tag));
                }
            }
        }
        claims.deleteAll(theirs);
        claims.flush();
        people.delete(person);
        people.flush();
        history.capture(toSheetId);

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user + " name=" + arrived.getName()
                + " to=" + toSheetId);
        rollup.recordChanges(sheetId, user, before, true);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + toSheetId + " by=" + user + " name=" + arrived.getName()
                + " from=" + sheetId);
        rollup.recordChanges(toSheetId, user, beforeTo);
        return arrived;
    }

    /** The sheet's today when the claim was first opened, as its status log records it; none when not recorded. */
    private static LocalDate openedOn(Claim claim) {
        return claim.getLog().stream().filter(e -> e.getStatus() != ClaimStatus.DRAFT && e.getStatus() != ClaimStatus.DELETED).map(ClaimLogEntry::getDate)
                .findFirst().orElse(null);
    }
}
