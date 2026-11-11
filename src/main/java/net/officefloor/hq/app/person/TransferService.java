package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimCommentRepository;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers a person to another sheet by id (a steward only; not a retired person, not to the same sheet, not to an
 * archived one). The person arrives with every grade and its from-date, their terms, contact and aliases, and their
 * claims with their status, dates, notes, tags, frozen figures and logs; each claim that had a number takes the next
 * number of the other sheet, in the order the claims were opened (by the sheet's today when each was opened, then by
 * the order they were added). Figures a statement bound are lost and references are not carried. A name, alias or
 * contact the other sheet has already refuses the transfer. The person leaves this sheet entirely: their statements,
 * holds, patron links, pool memberships and watches are dropped.
 */
@Service
public class TransferService {

    private final PersonRepository people;
    private final PersonAliasRepository aliases;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final ClaimCommentRepository comments;
    private final ClaimService claimService;
    private final SheetTagRepository tags;
    private final StatementRepository statements;
    private final PoolRepository pools;
    private final RoleService roles;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public TransferService(PersonRepository people, PersonAliasRepository aliases, PersonPatronRepository patrons,
            PersonWatchRepository watches, ClaimRepository claims, ClaimLogRepository logs,
            ClaimCommentRepository comments, ClaimService claimService, SheetTagRepository tags,
            StatementRepository statements, PoolRepository pools, RoleService roles, SheetService sheets,
            RollupService rollup, Audit audit) {
        this.people = people;
        this.aliases = aliases;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.logs = logs;
        this.comments = comments;
        this.claimService = claimService;
        this.tags = tags;
        this.statements = statements;
        this.pools = pools;
        this.roles = roles;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Transfer the person to the other sheet (started when its id was never used); the person as they arrived. */
    @Transactional
    public Person transfer(String sheetId, String user, Long id, String toSheetId) {
        Person from = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PersonTransferRefusedException("Only a steward can transfer a person");
        }
        if (from.isRetired()) {
            throw new PersonTransferRefusedException("A retired person cannot be transferred");
        }
        String to = toSheetId == null ? "" : toSheetId.trim();
        if (to.isEmpty() || to.equals(sheetId)) {
            throw new PersonTransferRefusedException(from.getName() + " cannot be transferred to their own sheet");
        }
        Sheet toSheet = sheets.load(to, user);
        if (toSheet.isArchived()) {
            throw new PersonTransferRefusedException("Sheet " + to + " is archived");
        }
        List<PersonAlias> theirAliases = aliases.findByPersonId(from.getId());
        List<String> names = new ArrayList<>();
        names.add(from.getName());
        theirAliases.forEach(a -> names.add(a.getAlias()));
        for (String name : names) {
            String key = Person.key(name);
            if (people.existsBySheetIdAndNameKey(to, key) || aliases.existsBySheetIdAndAliasKey(to, key)) {
                throw new PersonTransferRefusedException("The name " + name + " is already in use on sheet " + to);
            }
        }
        if (from.getContact() != null && people.existsBySheetIdAndContact(to, from.getContact())) {
            throw new PersonTransferRefusedException("The contact " + from.getContact() + " is already in use on sheet " + to);
        }
        RollupService.Snapshot beforeHere = rollup.snapshot(sheetId);
        RollupService.Snapshot beforeThere = rollup.snapshot(to);

        // The person, with every grade and its from-date, their terms and contact, and their aliases.
        List<PersonGrade> grades = from.getGrades();
        Person arrived = new Person(to, from.getName(), grades.get(0).getGrade());
        grades.stream().skip(1).forEach(g -> arrived.setGrade(g.getGrade(), g.getFromDate()));
        arrived.setTerms(from.getTerms());
        arrived.setContact(from.getContact());
        arrived.setAddedOn(toSheet.getToday());
        Person saved = people.saveAndFlush(arrived);
        aliases.deleteAll(theirAliases);
        aliases.flush();
        theirAliases.forEach(a -> aliases.save(new PersonAlias(to, saved.getId(), a.getAlias())));

        // The claims, listed in the order they were, and numbered anew in the order they were opened.
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId());
        Map<Long, List<ClaimLog>> logOf = new HashMap<>();
        theirs.forEach(c -> logOf.put(c.getId(), logs.findByClaimIdOrderByIdAsc(c.getId())));
        for (Claim claim : theirs.stream()
                .sorted(Comparator.comparingLong(Claim::getListed).thenComparing(Claim::getId)).toList()) {
            claim.moveTo(to, saved.getId());
            claim.setListed(sheets.nextListed(to));
            claim.setStated(false, null);
            claim.setRef(null);
            claim.setSettleBatch(null);
            claim.setLockedBy(null);
            if (claim.getTags() != null) {
                for (String tag : claim.getTags().split(",")) {
                    if (!tags.existsBySheetIdAndTag(to, tag)) {
                        tags.save(new SheetTag(to, tag));
                    }
                }
            }
        }
        for (Claim claim : theirs.stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing((Claim c) -> openedOn(c, logOf.get(c.getId())))
                        .thenComparing(Claim::getId))
                .toList()) {
            claim.setNumber(claimService.nextNumber(to, claim.getDueBy()));
        }
        claims.saveAllAndFlush(theirs);
        List<Long> claimIds = theirs.stream().map(Claim::getId).toList();
        if (!claimIds.isEmpty()) {
            logs.moveTo(claimIds, to);
            comments.moveTo(claimIds, to);
        }

        // The person leaves this sheet: patron links, pool memberships, statements and watches are dropped.
        patrons.deleteAll(patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> p.getPersonId().equals(from.getId()) || p.getPatronId().equals(from.getId()))
                .toList());
        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            if (pool.getMembers().removeIf(m -> m.getPersonId().equals(from.getId()))) {
                pools.saveAndFlush(pool);
            }
        }
        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()));
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        people.delete(from);
        people.flush();

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user
                + " name=" + from.getName() + " to=" + to);
        rollup.recordChanges(sheetId, user, beforeHere);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + to + " by=" + user
                + " name=" + saved.getName() + " from=" + sheetId);
        rollup.recordChanges(to, user, beforeThere);
        return saved;
    }

    /** The sheet's today when the claim was opened: its first log line past DRAFT, else its date. */
    private static LocalDate openedOn(Claim claim, List<ClaimLog> log) {
        return log.stream().filter(l -> l.getStatus() != ClaimStatus.DRAFT).map(ClaimLog::getDate)
                .findFirst().orElse(claim.getDate());
    }
}
