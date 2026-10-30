package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLogLine;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Tags;
import net.officefloor.hq.app.crew.Crews;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transferring a person to another sheet by id. Only a steward of this sheet may; a retired person, this
 * sheet's own id, an archived other sheet, or a name, alias or contact of theirs already on the other sheet's
 * tree, is refused; except that when one person on the other sheet itself (not retired) has a name or alias of
 * theirs, the person is merged into them as a merge does: their claims listed after the other's, their name and
 * aliases joined to the other's aliases, their contact dropped. The person arrives with their grades (each from its date), terms, contact and aliases, and their
 * claims with status, dates, notes, tags, frozen figures, logs and comments; each claim that had a number
 * takes the other sheet's next one, in the order the claims were opened (by the sheet's today when each was
 * opened, then by the order they were added). Figures a statement bound are kept and references dropped. Their
 * statements go with them: a person merged into keeps their own last statement (or, having none, takes the
 * arriving person's last) with the count of both. The person leaves this sheet entirely: their holds, patron links, pool memberships and
 * watches are dropped.
 */
@Service
public class PersonTransferService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final Tags tags;
    private final StatementService statements;
    private final PoolMemberRepository poolMembers;
    private final Crews crews;
    private final AccessService access;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public PersonTransferService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, ClaimService claimService, Tags tags, StatementService statements,
            PoolMemberRepository poolMembers, Crews crews, AccessService access, SheetService sheets, SheetRepository sheetRows,
            Rollup rollup, ApplicationEventPublisher events) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.claimService = claimService;
        this.tags = tags;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.access = access;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.rollup = rollup;
        this.events = events;
    }

    /** Transfer the person to the other sheet (started empty when its id was never used). */
    @Transactional
    public void transfer(String sheetId, String user, long personId, String toSheet) {
        if (toSheet.equals(sheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be transferred to their own sheet");
        }
        sheets.load(toSheet, user);
        // both sheets locked in the same order, so transfers each way between them take turns
        Stream.of(sheetId, toSheet).sorted().forEach(sheetRows::lockById);
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can transfer people");
        }
        if (sheets.isArchived(toSheet)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The other sheet is archived");
        }
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be transferred");
        }
        List<String> names = new ArrayList<>(person.getAliases());
        names.add(person.getName());
        // held to the other sheet's tree, which may hold the person themselves; one person on the other sheet
        // itself sharing a name is merged into instead
        Set<String> wanted = new HashSet<>();
        names.forEach(n -> wanted.add(n.trim().toLowerCase(Locale.ROOT)));
        Person into = null;
        for (Person other : people.findBySheetIdIn(sheets.tree(toSheet))) {
            if (other.getId().equals(person.getId())) {
                continue;
            }
            Set<String> theirs = new HashSet<>();
            theirs.add(other.getName().trim().toLowerCase(Locale.ROOT));
            other.getAliases().forEach(a -> theirs.add(a.trim().toLowerCase(Locale.ROOT)));
            if (Collections.disjoint(wanted, theirs)) {
                continue;
            }
            if (!other.getSheetId().equals(toSheet) || into != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use on the other sheet");
            }
            into = other;
        }
        if (into != null && into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged");
        }
        if (into == null && person.getContact() != null
                && people.existsBySheetIdAndContact(toSheet, person.getContact())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Contact already in use on the other sheet");
        }
        Rollup.Figures beforeHere = rollup.figures(sheetId);
        Rollup.Figures beforeThere = rollup.figures(toSheet);

        Person arrived;
        if (into == null) {
            Person newcomer = person.arriving(toSheet);
            newcomer.setArrivedOn(sheets.today(toSheet));
            newcomer.setPosition(people.maxPosition(toSheet) + 1);
            arrived = people.saveAndFlush(newcomer);
            tags.use(toSheet, arrived.getTags());
        } else {
            // merged as a merge does: their name and aliases become the other's aliases
            arrived = into;
            arrived.addAlias(person.getName());
            person.getAliases().forEach(arrived::addAlias);
            people.saveAndFlush(arrived);
        }
        // added oldest listed first, so they list on the other sheet in the order they listed here
        List<Claim> leaving = new ArrayList<>(claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, personId));
        Collections.reverse(leaving);
        List<Claim> arriving = new ArrayList<>();
        Map<Claim, String> oldNumbers = new HashMap<>();
        Map<Claim, LocalDate> openedOn = new HashMap<>();
        Map<Claim, Long> addedAs = new HashMap<>();
        for (Claim claim : leaving) {
            Claim copy = claim.arriving(toSheet, arrived.getId());
            if (claim.isStated()) {
                copy.bind(claim.getStatedDue());
            }
            arriving.add(copy);
            if (claim.getShownNumber() != null) {
                oldNumbers.put(copy, claim.getShownNumber());
            }
            openedOn.put(copy, openedOn(claim));
            addedAs.put(copy, claim.getId());
            tags.use(toSheet, claim.getTags());
        }
        claims.saveAllAndFlush(arriving);

        Map<String, String> renumbered = new HashMap<>();
        arriving.stream().filter(oldNumbers::containsKey)
                .sorted(Comparator.comparing(openedOn::get).thenComparing(addedAs::get))
                .forEach(claim -> {
                    claimService.numberArrived(toSheet, claim);
                    renumbered.put(oldNumbers.get(claim), claim.getShownNumber());
                });
        // a correction, or a claim split off another, names the claim it came from by its new number
        for (Claim claim : arriving) {
            if (claim.getCorrects() != null) {
                claim.setCorrects(renumbered.getOrDefault(claim.getCorrects(), claim.getCorrects()));
            }
            if (claim.getSplitFrom() != null) {
                claim.setSplitFrom(renumbered.getOrDefault(claim.getSplitFrom(), claim.getSplitFrom()));
            }
        }
        claims.flush();

        patrons.deleteAll(patrons.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
        patrons.deleteAll(patrons.findBySheetIdAndPatronIdOrderByIdAsc(sheetId, personId));
        poolMembers.deleteAll(poolMembers.findByPersonId(personId));
        crews.remove(personId);
        watches.deleteAll(watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
        statements.carry(sheetId, personId, toSheet, arrived.getId());
        claims.deleteAll(leaving);
        claims.flush();
        people.delete(person);
        people.flush();

        events.publishEvent(new PersonTransferred(sheetId, user, person.getName(), toSheet, null));
        rollup.changed(sheetId, user, beforeHere);
        events.publishEvent(new PersonTransferred(toSheet, user, person.getName(), null, sheetId));
        if (into != null) {
            events.publishEvent(new PersonMerged(toSheet, user, person.getName(), into.getName()));
        }
        rollup.changed(toSheet, user, beforeThere);
    }

    /**
     * Undo the transfer that brought the person to this sheet: only a steward of this sheet may, on the sheet's
     * today they arrived; the person is then transferred back to the sheet they came from, as a transfer does.
     */
    @Transactional
    public void undo(String sheetId, String user, long personId) {
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can undo a transfer");
        }
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String origin = person.getOrigin();
        if (origin == null || person.getArrivedOn() == null || !person.getArrivedOn().equals(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a transfer that arrived today can be undone");
        }
        String name = person.getName();
        Rollup.Figures before = rollup.figures(sheetId);
        transfer(sheetId, user, personId, origin);
        events.publishEvent(new PersonTransferUndone(sheetId, user, name, origin));
        rollup.changed(sheetId, user, before);
    }

    /** The sheet's today when the claim was opened: its first status after DRAFT; its date when it has no log of it. */
    private static LocalDate openedOn(Claim claim) {
        return claim.getLog().stream().filter(l -> l.getStatus() != ClaimStatus.DRAFT).map(ClaimLogLine::getOn)
                .findFirst().orElse(claim.getDate());
    }
}
