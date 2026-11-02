package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.patron.PatronRepository;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.rollup.Rollup;
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

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PatronRepository patrons;
    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final StatementRepository statements;
    private final WatchRepository watches;
    private final PeriodRepository periods;
    private final SheetService sheets;
    private final RoleService roles;
    private final Rollup rollup;
    private final Audit audit;

    public PersonTransferService(PersonRepository people, ClaimRepository claims, ClaimService claimService,
            PatronRepository patrons, PoolRepository pools, PoolMemberRepository members,
            StatementRepository statements, WatchRepository watches, PeriodRepository periods,
            SheetService sheets, RoleService roles, Rollup rollup, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.patrons = patrons;
        this.pools = pools;
        this.members = members;
        this.statements = statements;
        this.watches = watches;
        this.periods = periods;
        this.sheets = sheets;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Transfer a person to another sheet. They arrive with every grade they were given, their terms,
     *  contact and aliases, and their claims (status, dates, notes, tags, frozen figures and logs
     *  kept): each claim that had a number takes the other sheet's next one, in the order the claims
     *  were opened (by the sheet's today then, then by the order they were added); figures a
     *  statement bound and references are not carried, and they have no statements there. They
     *  leave this sheet entirely (patron links, pool memberships, watches and statements dropped).
     *  A sheet never loaded is started empty, as loading it would. When their name or an alias is
     *  already the name or an alias of someone on the other sheet (compared as names are), they
     *  are merged into that person there instead, exactly as a merge does (their claims listed
     *  after that person's, their name and aliases joining that person's aliases, their grades,
     *  terms and contact not kept). Refused unless
     *  the user is a steward, and for a retired person, this sheet's own id, an archived sheet,
     *  when their names match more than one person there or a retired one, or when their contact is
     *  held there by anyone but the person they merge into. */
    @Transactional
    public void transfer(String sheetId, String user, long personId, String toSheetId) {
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may transfer a person");
        }
        if (toSheetId.equals(sheetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A person cannot be transferred to their own sheet");
        }
        if (sheets.archived(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No sheet to transfer to");
        }
        Person from = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (from.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be transferred");
        }
        sheets.load(toSheetId, user);
        List<Person> matches = Stream.concat(Stream.of(from.getName()), from.getAliases().stream())
                .flatMap(n -> people.findByNameOrAlias(toSheetId, n).stream())
                .map(Person::getId).distinct()
                .map(id -> people.lockByIdAndSheetId(id, toSheetId).orElseThrow())
                .toList();
        if (matches.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The names match more than one person on the other sheet");
        }
        Person into = matches.isEmpty() ? null : matches.get(0);
        if (into != null && into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged");
        }
        if (from.getContact() != null && (into == null
                ? people.existsBySheetIdAndContact(toSheetId, from.getContact())
                : people.existsBySheetIdAndContactAndIdNot(toSheetId, from.getContact(), into.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The contact is on the other sheet");
        }
        rollup.tracking(sheetId, user, () -> {
            audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user + " name=" + from.getName()
                    + " to=" + toSheetId);
            List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, from.getId()).stream()
                    .sorted(Comparator.comparing(Claim::getListed)).toList();
            rollup.tracking(toSheetId, user, () -> {
                audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + toSheetId + " by=" + user + " name="
                        + from.getName() + " from=" + sheetId);
                if (into != null) {
                    audit.recordOnCommit("PERSON_MERGED sheet=" + toSheetId + " by=" + user + " from="
                            + from.getName() + " into=" + into.getName());
                }
                Person arrived = into != null ? into
                        : people.saveAndFlush(from.transferredTo(toSheetId, people.nextPosition(toSheetId)));
                Map<String, String> numbers = renumber(toSheetId, theirs);
                for (Claim claim : theirs) {
                    Claim copy = claims.saveAndFlush(claim.transferredTo(toSheetId, arrived.getId(), numbers::get));
                    claimService.keepTags(toSheetId, copy.getTags());
                }
                if (into != null) {
                    // Merged as a merge would: their claims after the other's (no figure a statement
                    // bound), their name and aliases joining the other's aliases.
                    Stream.concat(Stream.of(from.getName()), from.getAliases().stream())
                            .filter(n -> !n.equalsIgnoreCase(into.getName()))
                            .forEach(into::addAlias);
                    people.saveAndFlush(into);
                }
                return arrived;
            });
            leave(sheetId, from, theirs);
            return null;
        });
    }

    /** The other sheet's next number for each claim that had one, by its old number, given in the
     *  order the claims were opened. */
    private Map<String, String> renumber(String toSheetId, List<Claim> theirs) {
        Map<String, String> numbers = new HashMap<>();
        theirs.stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing((Claim c) -> Objects.requireNonNullElse(c.openedOn(), LocalDate.MAX))
                        .thenComparing(Claim::getId))
                .forEach(c -> numbers.put(c.getNumber(), claimService.nextNumber(toSheetId, c.getDueBy())));
        return numbers;
    }

    /** The person leaves the sheet entirely, with everything that names them. */
    private void leave(String sheetId, Person from, List<Claim> theirs) {
        patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> p.getPersonId().equals(from.getId()) || p.getPatronId().equals(from.getId()))
                .forEach(patrons::delete);
        List<Long> poolIds = pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(p -> p.getId()).toList();
        if (!poolIds.isEmpty()) {
            List<PoolMember> theirMemberships = members.findByPoolIdInOrderByIdAsc(poolIds).stream()
                    .filter(m -> from.getId().equals(m.getPersonId())).toList();
            members.deleteAll(theirMemberships);
        }
        periods.findBySheetIdOrderByFromDateAsc(sheetId).forEach(period -> period.dropPersonTotal(from.getId()));
        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()));
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        claims.deleteAll(theirs);
        people.flush();
        people.delete(from);
        people.flush();
    }
}
