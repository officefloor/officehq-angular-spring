package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimCommentRepository;
import net.officefloor.hq.app.claim.ClaimLineRepository;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.pool.SheetPoolMemberRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A steward's transfer of a person to another sheet, by its id. The person arrives there with every grade
 * they were given (and its from-date), their terms, contact and aliases, and their claims with their
 * status, dates, notes, tags, frozen figures, logs and comments; each claim that had a number is given the
 * next number of the other sheet, in the order the claims were opened (by the sheet's today when each was
 * opened, then by the order they were added). Figures a statement bound are kept, and their statements go
 * with them (as a merge carries them, when they are merged into one already there); references are not
 * carried. The person leaves this sheet entirely: their holds, patron links, pool memberships, crew memberships and
 * watches are dropped, and the crews they lead are kept without a lead.
 */
@Service
public class PersonTransferService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final ClaimCommentRepository comments;
    private final ClaimLineRepository lines;
    private final ClaimService claimService;
    private final StatementRepository statements;
    private final SheetPoolMemberRepository poolMembers;
    private final CrewService crews;
    private final PeriodRepository periods;
    private final SettingsService settings;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonTransferService(PersonRepository people, PersonPatronRepository patrons,
            PersonWatchRepository watches, ClaimRepository claims, ClaimLogRepository logs,
            ClaimCommentRepository comments, ClaimLineRepository lines, ClaimService claimService, StatementRepository statements,
            SheetPoolMemberRepository poolMembers, CrewService crews, PeriodRepository periods, SettingsService settings,
            SheetService sheets, RollupService rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.logs = logs;
        this.comments = comments;
        this.lines = lines;
        this.claimService = claimService;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.periods = periods;
        this.settings = settings;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Transfers the person {@code personId} to the sheet {@code toSheetId} (started when it was never
     * loaded), returning them as they now are there; refused unless the user is a steward, for a retired
     * person, for this sheet's own id or an archived sheet, and when their contact is already on the other
     * sheet. When the person's name or an alias is already on the other sheet (as names are compared), they
     * are merged into the one person who has it, as a merge does: their claims are listed after that
     * person's, their name and aliases become aliases, and their contact is dropped; refused when those
     * names belong to more than one person there, or to a retired one. */
    @Transactional
    public Person transfer(String sheetId, String user, long personId, String toSheetId) {
        Person from = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD || from.isRetired() || toSheetId.equals(sheetId)) {
            throw new PersonTransferException();
        }
        Sheet to = sheets.load(toSheetId, user);
        if (to.isArchived()) throw new PersonTransferException();
        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.getAliases());
        Person into = null;
        for (String name : names) {
            String key = Person.keyOf(name);
            for (Person match : List.of(people.findBySheetIdAndNameKey(toSheetId, key),
                    people.findBySheetIdAndAliasKey(toSheetId, key)).stream().flatMap(Optional::stream).toList()) {
                if (into != null && !into.getId().equals(match.getId())) throw new PersonTransferException();
                into = match;
            }
        }
        if (into != null && into.isRetired()) throw new PersonTransferException();
        if (into == null && from.getContact() != null
                && people.findBySheetIdAndContact(toSheetId, from.getContact()).isPresent()) {
            throw new PersonTransferException();
        }
        RollupService.Snapshot beforeHere = rollup.snapshot(sheetId);
        RollupService.Snapshot beforeThere = rollup.snapshot(toSheetId);

        Person arrived;
        if (into != null) {
            arrived = into;
        } else {
            Person transferred = from.transferredTo(toSheetId);
            transferred.setPosition(people.maxPosition(toSheetId) + 1);
            arrived = people.saveAndFlush(transferred);
        }

        // Oldest first: each is listed anew on the other sheet in this order, so they keep their order.
        List<Claim> moving = new ArrayList<>(claims.lockBySheetIdAndPersonId(sheetId, from.getId()));
        Collections.reverse(moving);
        Map<Long, List<ClaimLog>> logsOf = moving.isEmpty() ? Map.of()
                : logs.findByClaimIdInOrderByIdAsc(moving.stream().map(Claim::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ClaimLog::getClaimId));
        Map<Claim, Claim> copies = new HashMap<>();
        for (Claim claim : moving) {
            Claim copy = claim.transferredTo(toSheetId, arrived.getId());
            if (claim.isStated()) copy.bind(claim.getStatedDue(), claim.getStatedBy());
            copy.setListed(sheets.nextInSequence(toSheetId, "listed"));
            copies.put(claim, copy);
        }
        Map<String, String> renumbered = new HashMap<>();
        moving.stream()
                .filter(claim -> claim.getNumber() != null)
                .sorted(Comparator.comparing((Claim claim) -> ClaimLog.openedOn(logsOf.get(claim.getId())),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Claim::getId))
                .forEach(claim -> {
                    Claim copy = copies.get(claim);
                    copy.setNumber(claimService.nextNumber(toSheetId, copy.getDueBy()));
                    renumbered.put(claim.getNumber(), copy.getNumber());
                });
        for (Claim claim : moving) {
            Claim copy = copies.get(claim);
            if (claim.getCorrects() != null) copy.setCorrects(renumbered.get(claim.getCorrects()));
            if (claim.getSplitFrom() != null) copy.setSplitFrom(renumbered.get(claim.getSplitFrom()));
        }
        for (Claim claim : moving) {
            Claim copy = claims.saveAndFlush(copies.get(claim));
            claimService.keepTags(copy);
            logs.moveClaim(claim.getId(), copy.getId());
            comments.moveClaim(claim.getId(), copy.getId());
            lines.moveClaim(claim.getId(), copy.getId());
        }
        claims.deleteAll(moving);
        claims.flush();

        patrons.deleteAll(patrons.findByPersonIdOrderByIdAsc(from.getId()));
        patrons.deleteAll(patrons.findByPatronIdOrderByIdAsc(from.getId()));
        poolMembers.deleteAll(poolMembers.findByPersonIdOrderByIdAsc(from.getId()));
        crews.personLeaving(from.getId());
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        PersonMergeService.carryStatements(statements, sheetId, from.getId(), toSheetId, arrived.getId());
        periods.findBySheetIdOrderByFromAsc(sheetId).forEach(period -> period.dropPersonTotal(from.getId()));
        patrons.flush();
        poolMembers.flush();
        watches.flush();
        statements.flush();
        periods.flush();
        people.delete(from);
        people.flush();

        audit.change("PERSON_TRANSFERRED", sheetId, user, "name=" + from.getName(), "to=" + toSheetId);
        rollup.recordSince(sheetId, user, beforeHere);
        if (into != null) {
            // Merged into the one already there, as a merge does: their name and aliases become aliases.
            names.forEach(into::addAlias);
            people.flush();
            audit.change("PERSON_TRANSFERRED", toSheetId, user, "name=" + from.getName(), "from=" + sheetId);
            audit.change("PERSON_MERGED", toSheetId, user, "from=" + from.getName(), "into=" + into.getName());
        } else {
            audit.change("PERSON_TRANSFERRED", toSheetId, user, "name=" + arrived.getName(), "from=" + sheetId);
        }
        rollup.recordSince(toSheetId, user, beforeThere);
        return arrived;
    }
}
