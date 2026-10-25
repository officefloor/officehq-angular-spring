package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transfers a person from one sheet to another; a steward's change. */
@Service
public class TransferService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final StatementRepository statements;
    private final PoolRepository pools;
    private final SettingService settings;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final SheetHistory history;
    private final NameTree tree;
    private final Rollup rollup;
    private final MergeService merges;
    private final Audit audit;

    public TransferService(PersonRepository people, PersonPatronRepository patrons,
            PersonWatchRepository watches, ClaimRepository claims, ClaimService claimService,
            StatementRepository statements, PoolRepository pools, SettingService settings, SheetService sheets,
            SheetRepository sheetRows, SheetHistory history, NameTree tree, Rollup rollup, MergeService merges, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.claimService = claimService;
        this.statements = statements;
        this.pools = pools;
        this.settings = settings;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.history = history;
        this.tree = tree;
        this.rollup = rollup;
        this.merges = merges;
        this.audit = audit;
    }

    /**
     * Transfer a person to another sheet (a sheet never loaded before starts empty). They arrive with every
     * grade they were given (each from its from-date), their terms, contact and aliases, and their claims
     * with their status, dates, notes, tags, frozen figures, logs and comments, listed as they were; each
     * claim that had a number takes the other sheet's next one, in the order the claims were opened (by the
     * sheet's today when each was opened, then by the order they were added); figures a statement bound are
     * lost, statements are not carried and nor are references. The person then leaves this sheet entirely:
     * their patron links (both ways), pool memberships, watches, holds and statements are dropped. Refused
     * unless the user is a steward of this sheet, for a retired person, to this sheet itself, to an archived
     * sheet, and when their name or an alias is on another sheet of the other sheet's tree. When the person's
     * name or an alias is already that of someone on the other sheet, the arriving person is merged into
     * them, as a merge does (refused when that someone is retired, or when the names are those of more than
     * one person there); otherwise refused when their contact is already on the other sheet.
     */
    @Transactional
    public void transfer(String sheetId, String user, long id, String toSheetId) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can transfer people.");
        }
        if (toSheetId.equals(sheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be transferred to their own sheet.");
        }
        Person from = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (from.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be transferred.");
        }
        sheets.load(toSheetId);
        if (sheetRows.isArchived(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet is archived.");
        }
        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.getAliases());
        Set<String> elsewhere = tree.keysElsewhere(toSheetId, from.getId());
        Person match = null;
        for (String name : names) {
            String key = Person.keyOf(name);
            if (elsewhere.contains(key)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "That name is already in use on a sheet of the other sheet's tree.");
            }
            Person found = people.findByNameOrAlias(toSheetId, key).orElse(null);
            if (found != null) {
                if (match != null && !match.getId().equals(found.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Those names are already in use by more than one person on the other sheet.");
                }
                match = found;
            }
        }
        if (match != null) {
            match = people.findForUpdate(match.getId(), toSheetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
            if (match.isRetired()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged.");
            }
        } else if (from.getContact() != null && people.existsBySheetIdAndContact(toSheetId, from.getContact())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on the other sheet.");
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Rollup.Snapshot otherBefore = rollup.snapshot(toSheetId);

        Person copy = from.copyTo(toSheetId);
        // A merge drops the arriving person's contact.
        if (match != null) copy.setContact(null);
        Person arrived = people.saveAndFlush(copy);

        // The claims that had a number take the other sheet's next, in the order they were opened.
        List<Claim> theirs = claims.findForMove(sheetId, from.getId());
        Map<String, String> numbers = new HashMap<>();
        theirs.stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing(Claim::openedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Claim::getId))
                .forEach(c -> numbers.put(c.getNumber(), claimService.nextNumber(toSheetId, c)));
        long listed = claims.lastListed(toSheetId);
        for (Claim claim : theirs.stream().sorted(Comparator.comparingLong(Claim::getListed)).toList()) {
            claims.save(claim.copyTo(toSheetId, arrived.getId(), ++listed, numbers.get(claim.getNumber()),
                    claim.getCorrects() == null ? null : numbers.get(claim.getCorrects()),
                    claim.getSplitFrom() == null ? null : numbers.get(claim.getSplitFrom())));
            claimService.useTags(toSheetId, claim.getTags());
        }
        claims.deleteAll(theirs);
        claims.flush();

        patrons.deleteAll(patrons.findByPersonIdOrderByIdAsc(from.getId()));
        patrons.deleteAll(patrons.findByPatronIdOrderByIdAsc(from.getId()));
        patrons.flush();
        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            pool.removeMember(from.getId());
        }
        pools.flush();
        statements.deleteBySheetIdAndPersonId(sheetId, from.getId());
        watches.deleteByPersonId(from.getId());
        people.delete(from);
        people.flush();

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user + " name=" + from.getName()
                + " to=" + toSheetId);
        rollup.recordChanges(user, before);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + toSheetId + " by=" + user + " name=" + arrived.getName()
                + " from=" + sheetId);
        if (match != null) {
            Person into = merges.absorb(toSheetId, arrived, match);
            audit.recordOnCommit("PERSON_MERGED sheet=" + toSheetId + " by=" + user + " from=" + arrived.getName()
                    + " into=" + into.getName());
        }
        rollup.recordChanges(user, otherBefore);
        // The request changes this sheet, which is kept as it commits; the other sheet is kept here.
        history.keep(toSheetId);
    }
}
