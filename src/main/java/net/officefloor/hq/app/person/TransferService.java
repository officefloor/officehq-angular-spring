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
import net.officefloor.hq.app.claim.ClaimLineRepository;
import net.officefloor.hq.app.claim.ClaimShareRepository;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewRepository;
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
 * the order they were added). Figures a statement bound are kept, and references are not carried. The person's
 * statements are counted with those of the person they arrive as or are merged into, whose last statement stays
 * theirs (or, having none, becomes the transferred person's last). Where the other sheet has a person with their
 * name or one of their aliases, the person arrives merged into them, as a merge does:
 * their claims are listed after that person's, their name and aliases join that person's aliases, and their grades,
 * terms and contact are dropped (two such people, or a retired one, refuse the transfer). Otherwise a name or alias
 * used elsewhere in the other sheet's tree, or a contact the other sheet has already, refuses the transfer. The person
 * leaves this sheet entirely: their holds, patron links, pool and crew memberships and watches are dropped, and a
 * crew they lead is left without a lead.
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
    private final ClaimLineRepository lines;
    private final ClaimShareRepository shares;
    private final ClaimService claimService;
    private final SheetTagRepository tags;
    private final StatementRepository statements;
    private final PoolRepository pools;
    private final CrewRepository crews;
    private final RoleService roles;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;
    private final PersonService personService;

    public TransferService(PersonRepository people, PersonAliasRepository aliases, PersonPatronRepository patrons,
            PersonWatchRepository watches, ClaimRepository claims, ClaimLogRepository logs,
            ClaimCommentRepository comments, ClaimService claimService, SheetTagRepository tags,
            StatementRepository statements, PoolRepository pools, RoleService roles, SheetService sheets,
            RollupService rollup, Audit audit, PersonService personService, ClaimLineRepository lines, CrewRepository crews,
            ClaimShareRepository shares) {
        this.shares = shares;
        this.crews = crews;
        this.lines = lines;
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
        this.personService = personService;
    }

    /**
     * Transfer the person to the other sheet (started when its id was never used); the person as they arrived, or the
     * person there they were merged into.
     */
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
        Person into = null;
        for (String name : names) {
            Person match = personService.findByNameOrAlias(to, name).orElse(null);
            if (match != null && into != null && !match.getId().equals(into.getId())) {
                throw new PersonTransferRefusedException(from.getName() + " matches both " + into.getName() + " and "
                        + match.getName() + " on sheet " + to);
            }
            if (match != null) {
                into = match;
            } else if (personService.usedInTree(to, Person.key(name), from.getId())) {
                throw new PersonTransferRefusedException("The name " + name + " is already in use on sheet " + to);
            }
        }
        if (into != null) {
            Long intoId = into.getId();
            into = people.findForUpdate(intoId, to).orElseThrow(() -> new PersonNotFoundException(to, intoId));
            if (into.isRetired()) {
                throw new PersonTransferRefusedException(from.getName() + " cannot be merged into " + into.getName()
                        + ", who is retired");
            }
        } else if (from.getContact() != null && people.existsBySheetIdAndContact(to, from.getContact())) {
            throw new PersonTransferRefusedException("The contact " + from.getContact() + " is already in use on sheet " + to);
        }
        RollupService.Snapshot beforeHere = rollup.snapshot(sheetId);
        RollupService.Snapshot beforeThere = rollup.snapshot(to);

        aliases.deleteAll(theirAliases);
        aliases.flush();
        Person saved;
        if (into == null) {
            // The person, with every grade and its from-date, their terms and their dated terms, contact, and their aliases.
            List<PersonGrade> grades = from.getGrades();
            Person arrived = new Person(to, from.getName(), grades.get(0).getGrade());
            grades.stream().skip(1).forEach(g -> arrived.setGrade(g.getGrade(), g.getFromDate()));
            arrived.setTerms(from.getTerms());
            from.getDatedTerms().forEach(t -> arrived.setTerms(t.getTerms(), t.getFromDate()));
            arrived.setContact(from.getContact());
            arrived.setTags(from.getTags());
            personService.noteTags(to, SheetTag.parse(from.getTags()));
            arrived.setAddedOn(toSheet.getToday());
            arrived.setOriginSheet(sheetId);
            arrived.setTransferredOn(toSheet.getToday());
            arrived.setPosition(people.maxPosition(to) + 1);
            saved = people.saveAndFlush(arrived);
            theirAliases.forEach(a -> aliases.save(
                    new PersonAlias(to, saved.getId(), a.getAlias(), a.getAliasSlot())));
        } else {
            // Merged into the person with their name: their name and aliases join that person's aliases.
            saved = into;
            for (String name : names) {
                aliases.saveAndFlush(new PersonAlias(to, saved.getId(), name, aliases.nextSlot(to, Person.key(name))));
            }
        }

        // The claims, listed in the order they were, and numbered anew in the order they were opened.
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId());
        Map<Long, List<ClaimLog>> logOf = new HashMap<>();
        theirs.forEach(c -> logOf.put(c.getId(), logs.findByClaimIdOrderByIdAsc(c.getId())));
        for (Claim claim : theirs.stream()
                .sorted(Comparator.comparingLong(Claim::getListed).thenComparing(Claim::getId)).toList()) {
            claim.moveTo(to, saved.getId());
            claim.setListed(sheets.nextListed(to));
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
            lines.moveTo(claimIds, to);
        }

        // Shares of their claims, and the shares they hold, stay on this sheet's people: they are dropped.
        if (!claimIds.isEmpty()) {
            shares.deleteAll(shares.findByClaimIdInOrderByIdAsc(claimIds));
        }
        shares.deleteAll(shares.findByPersonIdOrderByIdAsc(from.getId()));
        shares.flush();

        // The statements follow the person; patron links, pool and crew memberships and watches are dropped.
        statements.follow(from, saved);
        patrons.deleteAll(patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> p.getPersonId().equals(from.getId()) || p.getPatronId().equals(from.getId()))
                .toList());
        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            if (pool.getMembers().removeIf(m -> from.getId().equals(m.getPersonId()))) {
                pools.saveAndFlush(pool);
            }
        }
        // Crew memberships are dropped; a crew the person leads is left without a lead.
        for (Crew crew : crews.findBySheetIdOrderByIdAsc(sheetId)) {
            if (from.getId().equals(crew.getLeadId())) {
                crew.setLeadId(null);
                crews.saveAndFlush(crew);
            } else if (crew.getMembers().removeIf(m -> from.getId().equals(m.getPersonId()))) {
                crews.saveAndFlush(crew);
            }
        }
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        people.delete(from);
        people.flush();

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user
                + " name=" + from.getName() + " to=" + to);
        rollup.recordChanges(sheetId, user, beforeHere);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + to + " by=" + user
                + " name=" + from.getName() + " from=" + sheetId);
        if (into != null) {
            audit.recordOnCommit("PERSON_MERGED sheet=" + to + " by=" + user
                    + " from=" + from.getName() + " into=" + into.getName());
        }
        rollup.recordChanges(to, user, beforeThere);
        return saved;
    }

    /**
     * Undo the transfer that brought the person to this sheet (a steward only, on the sheet's today they arrived, and
     * only a person who arrived by a transfer): they are transferred back to the sheet they came from, by the rules
     * and records of a transfer, and this sheet records PERSON_TRANSFER_UNDONE.
     */
    @Transactional
    public Person undo(String sheetId, String user, Long id) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PersonTransferRefusedException("Only a steward can undo a transfer");
        }
        if (person.getOriginSheet() == null || person.getTransferredOn() == null) {
            throw new PersonTransferRefusedException(person.getName() + " did not arrive by a transfer");
        }
        if (!person.getTransferredOn().equals(sheets.today(sheetId))) {
            throw new PersonTransferRefusedException("The transfer of " + person.getName()
                    + " can only be undone on the day they arrived");
        }
        String back = person.getOriginSheet();
        String name = person.getName();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person returned = transfer(sheetId, user, id, back);
        audit.recordOnCommit("PERSON_TRANSFER_UNDONE sheet=" + sheetId + " by=" + user
                + " name=" + name + " to=" + back);
        rollup.recordChanges(sheetId, user, before);
        return returned;
    }

    /** The sheet's today when the claim was opened: its first log line past DRAFT, else its date. */
    private static LocalDate openedOn(Claim claim, List<ClaimLog> log) {
        return log.stream().filter(l -> l.getStatus() != ClaimStatus.DRAFT && l.getStatus() != ClaimStatus.DELETED).map(ClaimLog::getDate)
                .findFirst().orElse(claim.getDate());
    }
}
