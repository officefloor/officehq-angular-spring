package net.officefloor.hq.app.person;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLogEntry;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transfers a person to another sheet: they arrive there with their grades, terms, contact, aliases
 *  and claims, and leave this sheet entirely. */
@Service
public class PersonTransferService {

    private final PersonRepository people;
    private final PersonAliasRepository aliases;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final ClaimService claimService;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;
    private final History history;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final Audit audit;

    public PersonTransferService(PersonRepository people, PersonAliasRepository aliases, ClaimRepository claims,
            ClaimLogRepository logs, ClaimService claimService, RollupService rollups, SettingService settings,
            SheetService sheets, History history, JdbcTemplate jdbc, EntityManager entities, Audit audit) {
        this.people = people;
        this.aliases = aliases;
        this.claims = claims;
        this.logs = logs;
        this.claimService = claimService;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
        this.history = history;
        this.jdbc = jdbc;
        this.entities = entities;
        this.audit = audit;
    }

    /** A steward transfers a person to another sheet by id. The person arrives there with every grade
     *  they were given, their terms, contact and aliases, and their claims with their status, dates,
     *  notes, tags, frozen figures and logs; each claim that had a number takes the other sheet's next
     *  number, in the order the claims were opened (by the sheet's today when each was, then the order
     *  they were added). Figures a statement bound are lost with the statements, references, comments
     *  and settle batches are not carried; patron links, pool memberships, holds and watches are
     *  dropped. Anyone but a steward, a retired person, this sheet's own id, an archived sheet, or a
     *  name, alias or contact already on the other sheet, is refused. Each sheet writes its line,
     *  followed by its rollup lines. */
    @Transactional
    public void transfer(String sheetId, String user, Long id, String toSheetId) {
        String to = toSheetId == null ? "" : toSheetId.trim();
        if (to.isEmpty() || to.length() > 200) {
            throw new TransferRefusedException("A sheet id to transfer to is needed");
        }
        // Lock both sheets in one order, so transfers between them made at the same moment wait.
        Stream.of(sheetId, to).sorted().distinct().forEach(sheets::lock);
        rollups.track(sheetId, user, () -> rollups.track(to, user, () -> {
            move(sheetId, user, id, to);
            return null;
        }));
    }

    private void move(String sheetId, String user, Long id, String to) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new TransferRefusedException(user + " is not a steward: only a steward transfers people");
        }
        if (person.isRetired()) {
            throw new TransferRefusedException("A retired person is not transferred");
        }
        if (to.equals(sheetId)) {
            throw new TransferRefusedException(person.getName() + " is on " + sheetId + " already");
        }
        if (sheets.isArchived(sheetId) || sheets.isArchived(to)) {
            throw new TransferRefusedException("An archived sheet takes no transfer");
        }
        List<PersonAlias> theirAliases = aliases.findByPersonIdOrderByIdAsc(person.getId());
        boolean nameTaken = Stream.concat(Stream.of(person.getName()), theirAliases.stream().map(PersonAlias::getAlias))
                .anyMatch(name -> people.nameInUse(to, name, null) || aliases.aliasInUse(to, name));
        if (nameTaken) {
            throw new NameInUseException(person.getName());
        }
        if (person.getContact() != null && people.contactInUse(to, person.getContact(), null)) {
            throw new ContactInUseException(person.getContact());
        }

        List<Claim> theirClaims = claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, person.getId());
        Map<Long, LocalDate> openedOn = new HashMap<>();
        for (Claim claim : theirClaims) {
            logs.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                    .filter(line -> line.getStatus() == ClaimStatus.OPEN).map(ClaimLogEntry::getOn).findFirst()
                    .ifPresent(on -> openedOn.put(claim.getId(), on));
        }
        List<Claim> numbered = theirClaims.stream().filter(claim -> claim.getNumber() != null)
                .sorted(Comparator.comparing((Claim claim) -> openedOn.getOrDefault(claim.getId(), LocalDate.MAX))
                        .thenComparing(Claim::getId))
                .toList();
        Map<String, String> oldNumbers = new HashMap<>();
        numbered.forEach(claim -> oldNumbers.put(String.valueOf(claim.getId()), claim.getNumber()));
        List<String> tags = theirClaims.stream().flatMap(claim -> claim.getTags().stream()).distinct().toList();
        LocalDate today = sheets.today(to);
        Long personId = person.getId();
        entities.flush();

        // Left behind on this sheet: patron links both ways, pool memberships, holds, watches,
        // statements, and the claims' comments.
        jdbc.update("DELETE FROM person_patron WHERE person_id = ? OR patron_id = ?", personId, personId);
        jdbc.update("DELETE FROM pool_member WHERE person_id = ?", personId);
        jdbc.update("DELETE FROM person_hold WHERE person_id = ?", personId);
        jdbc.update("DELETE FROM person_watch WHERE person_id = ?", personId);
        jdbc.update("DELETE FROM statement WHERE person_id = ?", personId);
        jdbc.update("DELETE FROM claim_comment WHERE claim_id IN (SELECT id FROM claim WHERE person_id = ?)",
                personId);

        // Carried to the other sheet: the person, their grades, aliases and claims with their logs.
        jdbc.update("UPDATE person SET sheet_id = ?, added_on = ?, origin_sheet_id = ? WHERE id = ?", to, today,
                sheetId, personId);
        jdbc.update("UPDATE person_grade SET sheet_id = ? WHERE person_id = ?", to, personId);
        jdbc.update("UPDATE person_alias SET sheet_id = ? WHERE person_id = ?", to, personId);
        jdbc.update("UPDATE claim SET sheet_id = ?, claim_number = NULL, ref = NULL, ref_key = NULL, stated = FALSE,"
                + " stated_due = NULL, settle_batch = NULL, locked_by = NULL WHERE person_id = ?", to, personId);
        jdbc.update("UPDATE claim_status_log SET sheet_id = ? WHERE claim_id IN (SELECT id FROM claim WHERE"
                + " person_id = ?)", to, personId);
        entities.clear();

        Map<String, String> newNumbers = new HashMap<>();
        for (Claim was : numbered) {
            Claim claim = claims.findById(was.getId()).orElseThrow();
            String number = claimService.numberFor(to, claim.getDueBy());
            newNumbers.put(oldNumbers.get(String.valueOf(claim.getId())), number);
            claim.setNumber(number);
            claims.saveAndFlush(claim);
        }
        for (Claim was : theirClaims) {
            Claim claim = claims.findById(was.getId()).orElseThrow();
            if (claim.getCorrects() != null) {
                claim.setCorrects(newNumbers.get(claim.getCorrects()));
                claims.saveAndFlush(claim);
            }
            if (claim.getSplitFrom() != null) {
                jdbc.update("UPDATE claim SET split_from = ? WHERE id = ?", newNumbers.get(claim.getSplitFrom()),
                        claim.getId());
            }
            history.claimKept(claim.getId());
        }
        history.personKept(personId);
        tags.forEach(tag -> claims.useTag(to, tag));
        entities.clear();

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " to=" + to);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + to + " by=" + user + " name=" + person.getName()
                + " from=" + sheetId);
    }
}
