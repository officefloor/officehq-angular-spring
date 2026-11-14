package net.officefloor.hq.app.person;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.Statement;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Merges a person into another on the same sheet: the merged person leaves the sheet and the other
 *  takes on their claims, names, patrons, pool memberships and crews. */
@Service
public class PersonMergeService {

    private final PersonRepository people;
    private final PersonAliasRepository aliases;
    private final PersonPatronRepository patrons;
    private final PersonHoldRepository holds;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final PoolMemberRepository poolMembers;
    private final CrewService crews;
    private final SettingService settings;
    private final SheetService sheets;
    private final Audit audit;

    public PersonMergeService(PersonRepository people, PersonAliasRepository aliases, PersonPatronRepository patrons,
            PersonHoldRepository holds, PersonWatchRepository watches, ClaimRepository claims,
            StatementRepository statements, PoolMemberRepository poolMembers, CrewService crews, SettingService settings,
            SheetService sheets, Audit audit) {
        this.people = people;
        this.aliases = aliases;
        this.patrons = patrons;
        this.holds = holds;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.settings = settings;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** A steward merges a person into another. The merged person's claims move to the other, keeping
     *  their numbers and dates, listed as the other's newest in the order they were added, each keeping
     *  any figure a statement bound; the merged name and aliases become the other's aliases; the merged
     *  person's patrons are added to the other's (not the other themselves, no repeats), anyone naming
     *  the merged person as patron names the other instead (no repeats), and the merged person's pool
     *  memberships move to the other (shares added where the other is already a member). Their
     *  statements become the other's, counted with theirs, the other's last statement staying theirs
     *  (or, having none, becoming the merged person's last). Their holds, watchers and contact are dropped, and they leave the sheet. Anyone but a
     *  steward, a merge into the same person, or either of them retired, is refused. Returns the other. */
    @Transactional
    public Person merge(String sheetId, String user, Long id, Long intoId) {
        sheets.lock(sheetId);
        Person from = person(sheetId, id);
        Person into = person(sheetId, intoId);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new MergeRefusedException(user + " is not a steward: only a steward merges people");
        }
        if (from.getId().equals(into.getId())) {
            throw new MergeRefusedException(from.getName() + " cannot be merged into themselves");
        }
        if (from.isRetired() || into.isRetired()) {
            throw new MergeRefusedException("A retired person is neither merged nor merged into");
        }
        absorb(sheetId, user, from, into);
        return into;
    }

    /** Merges one person into another on the sheet, as {@link #merge} does once it is allowed: a person
     *  transferred onto a sheet that has them already by a name or alias is merged this way. */
    void absorb(String sheetId, String user, Person from, Person into) {

        List<Claim> moved = claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, from.getId()).stream()
                .sorted(Comparator.comparing(Claim::getId)).toList();
        for (Claim claim : moved) {
            claim.setPersonId(into.getId());
        }
        claims.saveAllAndFlush(moved);
        moved.forEach(claim -> claims.relist(claim.getId()));

        List<PersonAlias> theirAliases = aliases.findByPersonIdOrderByIdAsc(from.getId());
        theirAliases.forEach(alias -> alias.setPersonId(into.getId()));
        aliases.saveAllAndFlush(theirAliases);
        aliases.saveAndFlush(new PersonAlias(sheetId, into.getId(), from.getName(), sheets.today(sheetId)));

        List<PersonPatron> theirPatrons = patrons.findByPersonIdOrderByIdAsc(from.getId());
        patrons.deleteAll(theirPatrons);
        patrons.flush();
        for (PersonPatron patron : theirPatrons) {
            Long patronId = patron.getPatronId();
            if (!patronId.equals(into.getId()) && !patrons.existsByPersonIdAndPatronId(into.getId(), patronId)) {
                patrons.saveAndFlush(new PersonPatron(sheetId, into.getId(), patronId, sheets.today(sheetId)));
            }
        }
        for (PersonPatron naming : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!naming.getPatronId().equals(from.getId())) {
                continue;
            }
            if (naming.getPersonId().equals(into.getId())
                    || patrons.existsByPersonIdAndPatronId(naming.getPersonId(), into.getId())) {
                patrons.delete(naming);
                patrons.flush();
            } else {
                naming.setPatronId(into.getId());
                patrons.saveAndFlush(naming);
            }
        }

        for (PoolMember member : poolMembers.findByPersonIdOrderByIdAsc(from.getId())) {
            Optional<PoolMember> already = poolMembers.findByPoolIdAndPersonId(member.getPoolId(), into.getId());
            if (already.isPresent()) {
                already.get().addShare(member.getShare());
                poolMembers.delete(member);
                poolMembers.flush();
                poolMembers.saveAndFlush(already.get());
            } else {
                member.setPersonId(into.getId());
                poolMembers.saveAndFlush(member);
            }
        }
        crews.merge(from.getId(), into.getId());

        // Their statements become the other's, before the other's own (when they have any), so the
        // other's last statement stays theirs.
        List<Statement> theirStatements = statements.inOrder(sheetId, from.getId());
        List<Statement> ownStatements = statements.inOrder(sheetId, into.getId());
        long first = ownStatements.isEmpty() ? Long.MAX_VALUE
                : ownStatements.get(0).getOrder() - theirStatements.size();
        for (int i = 0; i < theirStatements.size(); i++) {
            Statement statement = theirStatements.get(i);
            statement.moveTo(into.getId(), ownStatements.isEmpty() ? statement.getOrder() : first + i);
        }
        statements.saveAllAndFlush(theirStatements);
        holds.deleteAll(holds.findByPersonIdOrderByIdAsc(from.getId()));
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        from.setContact(null);
        from.mergeInto(into.getId());
        people.saveAndFlush(from);

        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + from.getName()
                + " into=" + into.getName());
    }

    private Person person(String sheetId, Long id) {
        return people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
    }
}
