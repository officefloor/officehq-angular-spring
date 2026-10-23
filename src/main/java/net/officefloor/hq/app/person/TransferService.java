package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transferring a person to another sheet: a steward only. The person arrives on the other sheet with
 * their grades, terms, contact and aliases, and with their claims (status, dates, notes, fixed figures
 * and logs kept); each claim that had a number takes the other sheet's next, in the order the claims
 * were opened, keeps any figure a statement bound and loses its reference. Their statements go with
 * them. They leave this sheet entirely: anyone naming them as a patron no longer does, and their
 * patrons, holds, pool memberships and watchers are dropped. Their claims count as added to the other
 * sheet when they arrive, so every forward-only rate saved there before applies to them. When someone on the other sheet already
 * goes by their name or an alias, they are merged into that person there: their claims listed after
 * the other's own, their name and aliases joined to the other's aliases, their statements joined to
 * the other's (the other's last staying the other's, or, the other having none, becoming theirs), and
 * their contact dropped.
 */
@Service
public class TransferService {

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final StatementService statements;
    private final WatchRepository watches;
    private final PoolMemberRepository poolMembers;
    private final CrewService crews;
    private final RoleService roles;
    private final SheetService sheets;
    private final PeriodService periods;
    private final Rollups rollups;
    private final Audit audit;

    public TransferService(PersonRepository people, ClaimRepository claims, StatementService statements,
            WatchRepository watches, PoolMemberRepository poolMembers, CrewService crews, RoleService roles, SheetService sheets,
            PeriodService periods, Rollups rollups, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.statements = statements;
        this.watches = watches;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.roles = roles;
        this.sheets = sheets;
        this.periods = periods;
        this.rollups = rollups;
        this.audit = audit;
    }

    /**
     * Transfer a person to another sheet by its id. Refused unless the user is a steward on this sheet,
     * for this sheet's own id, for an archived sheet, for a retired person, when their
     * contact is already in use on the other sheet (unless they are merged there), and when the person
     * they would be merged into is retired.
     */
    @Transactional
    public void transfer(String sheetId, String user, long personId, String toSheetId) {
        // Both sheets are locked, always in the same order, so two transfers between them wait in turn.
        Stream.of(sheetId, toSheetId).sorted().forEach(sheets::lock);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward transfers people.");
        }
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (sheetId.equals(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be transferred to their own sheet.");
        }
        if (sheets.archived(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet is archived.");
        }
        if (person.getRetiredFrom() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be transferred.");
        }
        // Someone there going by their name or an alias takes them in, as a merge would.
        Person into = Stream.concat(Stream.of(person.getName()), person.getAliases().stream())
                .map(name -> people.findBySheetIdAndName(toSheetId, name)).flatMap(Optional::stream)
                .findFirst().orElse(null);
        if (into != null && into.getRetiredFrom() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged.");
        }
        if (into == null && person.getContact() != null
                && people.findBySheetIdAndContact(toSheetId, person.getContact()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on the other sheet.");
        }
        Rollups.Rollup before = rollups.take(sheetId);
        Rollups.Rollup toBefore = rollups.take(toSheetId);

        // Anyone naming them as a patron no longer does; their pool memberships and watchers go.
        for (Person other : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (!other.getId().equals(person.getId()) && other.removePatron(person.getId())) {
                people.save(other);
            }
        }
        poolMembers.deleteAll(poolMembers.findByPersonIdOrderByIdAsc(person.getId()));
        // Their crew memberships go, and so do the crews they lead.
        crews.leave(person);
        watches.deleteAll(watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, person.getId()));

        if (into == null) {
            int last = people.findMaxPositionBySheetId(toSheetId);
            person.transferTo(toSheetId);
            person.setPosition(last + 1);
            people.saveAndFlush(person);
            statements.follow(sheetId, person);
        }

        // Their claims follow, each that had a number taking the other sheet's next in the order opened.
        List<Claim> moved = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, person.getId()).stream()
                .sorted(Comparator.comparing((Claim claim) -> claim.openedOn(),
                        Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())).thenComparing(Claim::getId))
                .toList();
        // The numbers are all taken before any claim moves, so none is written there under its old one.
        Map<String, String> numbers = new HashMap<>();
        for (Claim claim : moved) {
            if (claim.getNumber() != null) {
                numbers.put(claim.getNumber(), periods.nextClaimNumber(toSheetId, claim.getDueBy()));
            }
        }
        for (Claim claim : moved) {
            claim.transferTo(toSheetId);
            if (claim.getNumber() != null) {
                claim.number(numbers.get(claim.getNumber()));
            }
            claim.renumberLinks(numbers);
            if (into != null) {
                claim.reassign(into.getId());
            }
        }
        claims.saveAllAndFlush(moved);

        String name = person.getName();
        if (into != null) {
            // Their name and aliases join the other's aliases, and they leave with their holds and contact.
            into.addAlias(name);
            person.getAliases().forEach(into::addAlias);
            statements.handOver(sheetId, person, into);
            people.delete(person);
            people.flush();
            people.saveAndFlush(into);
            // The arriving claims are listed after the other's own, in the order they were added.
            moved.stream().map(Claim::getId).sorted().forEach(claims::relist);
        }
        // The arriving claims are added to the other sheet now: a forward-only rate saved there applies to them.
        moved.stream().map(Claim::getId).sorted().forEach(claims::arrive);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user + " name=" + name
                + " to=" + toSheetId);
        rollups.audit(sheetId, user, before);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + toSheetId + " by=" + user + " name=" + name
                + " from=" + sheetId);
        if (into != null) {
            audit.recordOnCommit("PERSON_MERGED sheet=" + toSheetId + " by=" + user + " from=" + name
                    + " into=" + into.getName());
        }
        rollups.audit(toSheetId, user, toBefore);
    }
}
