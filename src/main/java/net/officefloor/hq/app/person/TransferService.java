package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transferring a person to another sheet: a steward only. The person arrives on the other sheet with
 * their grades, terms, contact and aliases, and with their claims (status, dates, notes, fixed figures
 * and logs kept); each claim that had a number takes the other sheet's next, in the order the claims
 * were opened, and loses any figure a statement bound and its reference. No statement is counted for
 * them there. They leave this sheet entirely: anyone naming them as a patron no longer does, and their
 * patrons, holds, pool memberships, statements and watchers are dropped.
 */
@Service
public class TransferService {

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final WatchRepository watches;
    private final PoolMemberRepository poolMembers;
    private final RoleService roles;
    private final SheetService sheets;
    private final PeriodService periods;
    private final Rollups rollups;
    private final Audit audit;

    public TransferService(PersonRepository people, ClaimRepository claims, StatementRepository statements,
            WatchRepository watches, PoolMemberRepository poolMembers, RoleService roles, SheetService sheets,
            PeriodService periods, Rollups rollups, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.statements = statements;
        this.watches = watches;
        this.poolMembers = poolMembers;
        this.roles = roles;
        this.sheets = sheets;
        this.periods = periods;
        this.rollups = rollups;
        this.audit = audit;
    }

    /**
     * Transfer a person to another sheet by its id. Refused unless the user is a steward on this sheet,
     * for this sheet's own id, for an archived sheet, for a retired person, and when the person's name,
     * an alias or their contact is already in use on the other sheet.
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
        for (String name : Stream.concat(Stream.of(person.getName()), person.getAliases().stream()).toList()) {
            if (people.findBySheetIdAndName(toSheetId, name).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on the other sheet.");
            }
        }
        if (person.getContact() != null && people.findBySheetIdAndContact(toSheetId, person.getContact()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on the other sheet.");
        }
        Rollups.Rollup before = rollups.take(sheetId);
        Rollups.Rollup toBefore = rollups.take(toSheetId);

        // Anyone naming them as a patron no longer does; their pool memberships, statements and watchers go.
        for (Person other : people.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!other.getId().equals(person.getId()) && other.removePatron(person.getId())) {
                people.save(other);
            }
        }
        poolMembers.deleteAll(poolMembers.findByPersonIdOrderByIdAsc(person.getId()));
        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderBySeqDesc(sheetId, person.getId()));
        watches.deleteAll(watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, person.getId()));

        person.transferTo(toSheetId);
        people.saveAndFlush(person);

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
        }
        claims.saveAllAndFlush(moved);

        String name = person.getName();
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user + " name=" + name
                + " to=" + toSheetId);
        rollups.audit(sheetId, user, before);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + toSheetId + " by=" + user + " name=" + name
                + " from=" + sheetId);
        rollups.audit(toSheetId, user, toBefore);
    }
}
