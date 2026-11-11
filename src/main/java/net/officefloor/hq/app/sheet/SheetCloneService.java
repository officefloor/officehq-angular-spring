package net.officefloor.hq.app.sheet;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.ClaimTemplate;
import net.officefloor.hq.app.claim.ClaimTemplateRepository;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.holiday.Holiday;
import net.officefloor.hq.app.holiday.HolidayRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonAlias;
import net.officefloor.hq.app.person.PersonAliasRepository;
import net.officefloor.hq.app.person.PersonPatron;
import net.officefloor.hq.app.person.PersonPatronRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateLogRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.voidreason.VoidReason;
import net.officefloor.hq.app.voidreason.VoidReasonRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clones a sheet to another id never used (a steward only). The clone gets the sheet's today, settings, roles, rates,
 * holidays, void reasons, templates, people (grades, terms, contacts, aliases, holds, retirements, patrons), pools,
 * periods (all open) and claims with their status, frozen figures, dates, notes and logs. Each claim that had a number
 * takes the next number of the clone, in the order the originals were opened (by the sheet's today when each was
 * opened, then by the order they were added to the clone: person by person as listed, each person's claims in the
 * order they were added). Statements are not copied, so no claim of the clone is bound by one.
 */
@Service
public class SheetCloneService {

    private final SheetRepository sheets;
    private final EntityManager entities;
    private final RoleService roleService;
    private final RoleRepository roles;
    private final RateRepository rates;
    private final RateLogRepository rateLogs;
    private final HolidayRepository holidays;
    private final VoidReasonRepository voidReasons;
    private final ClaimTemplateRepository templates;
    private final SheetTagRepository tags;
    private final PersonRepository people;
    private final PersonAliasRepository aliases;
    private final PersonPatronRepository patrons;
    private final PoolRepository pools;
    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final ClaimService claimService;
    private final RollupService rollup;
    private final Audit audit;

    public SheetCloneService(SheetRepository sheets, EntityManager entities, RoleService roleService,
            RoleRepository roles, RateRepository rates, RateLogRepository rateLogs, HolidayRepository holidays,
            VoidReasonRepository voidReasons, ClaimTemplateRepository templates, SheetTagRepository tags,
            PersonRepository people, PersonAliasRepository aliases, PersonPatronRepository patrons,
            PoolRepository pools, PeriodRepository periods, ClaimRepository claims, ClaimLogRepository logs,
            ClaimService claimService, RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.entities = entities;
        this.roleService = roleService;
        this.roles = roles;
        this.rates = rates;
        this.rateLogs = rateLogs;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.templates = templates;
        this.tags = tags;
        this.people = people;
        this.aliases = aliases;
        this.patrons = patrons;
        this.pools = pools;
        this.periods = periods;
        this.claims = claims;
        this.logs = logs;
        this.claimService = claimService;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Clone the sheet to the other id; the clone as made. */
    @Transactional
    public Sheet cloneTo(String sheetId, String user, String toSheetId) {
        Sheet from = sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId));
        if (roleService.roleOf(sheetId, user) != Role.STEWARD) {
            throw new SheetCloneRefusedException("Only a steward can clone the sheet");
        }
        String to = toSheetId == null ? "" : toSheetId.trim();
        if (to.isEmpty()) {
            throw new SheetCloneRefusedException("Type the id of the sheet to clone to");
        }
        if (to.length() > 100) {
            throw new SheetCloneRefusedException("A sheet id is at most 100 characters");
        }
        if (sheets.existsById(to)) {
            throw new SheetCloneRefusedException("Sheet " + to + " is already in use");
        }
        Sheet clone = from.copyTo(to, user);
        try {
            entities.persist(clone);
            entities.flush();
        } catch (PersistenceException | DataIntegrityViolationException e) {
            // Someone started the same sheet at the same moment.
            throw new SheetCloneRefusedException("Sheet " + to + " is already in use");
        }

        for (SheetRole role : roles.findBySheetIdOrderByUserNameAsc(sheetId)) {
            roles.save(new SheetRole(to, role.getUserName(), role.getRole()));
        }
        for (Rate rate : rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId)) {
            Rate copy = new Rate(to, rate.getGrade(), rate.getFromDate(), rate.getPct());
            copy.setToDate(rate.getToDate());
            rates.save(copy);
        }
        rateLogs.findBySheetIdOrderByIdAsc(sheetId).forEach(l -> rateLogs.save(l.copyTo(to)));
        holidays.findBySheetIdOrderByDateAsc(sheetId).forEach(h -> holidays.save(new Holiday(to, h.getDate())));
        voidReasons.findBySheetIdOrderByIdAsc(sheetId)
                .forEach(r -> voidReasons.save(new VoidReason(to, r.getReason())));
        templates.findBySheetIdOrderByNameAsc(sheetId)
                .forEach(t -> templates.save(new ClaimTemplate(to, t.getName(), t.getAmount(), t.getNote())));
        tags.findBySheetIdOrderByTagAsc(sheetId).forEach(t -> tags.save(new SheetTag(to, t.getTag())));
        for (Period period : periods.findBySheetIdOrderByFromDateAscIdAsc(sheetId)) {
            periods.save(new Period(to, period.getName(), period.getFromDate(), period.getToDate(),
                    period.getCap()));
        }

        // The people as listed, with their aliases and patrons.
        Map<Long, Long> personIds = new HashMap<>();
        List<Person> theirPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        for (Person person : theirPeople) {
            personIds.put(person.getId(), people.save(person.copyTo(to)).getId());
        }
        people.flush();
        for (PersonAlias alias : aliases.findBySheetId(sheetId).stream()
                .sorted(Comparator.comparing(PersonAlias::getId)).toList()) {
            aliases.save(new PersonAlias(to, personIds.get(alias.getPersonId()), alias.getAlias(),
                    alias.getAliasSlot()));
        }
        for (PersonPatron patron : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            patrons.save(new PersonPatron(to, personIds.get(patron.getPersonId()),
                    personIds.get(patron.getPatronId())));
        }

        // The pools, then their members: a member may be another pool.
        List<Pool> theirPools = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Pool> poolCopies = new HashMap<>();
        for (Pool pool : theirPools) {
            poolCopies.put(pool.getId(), pools.saveAndFlush(new Pool(to, pool.getName())));
        }
        for (Pool pool : theirPools) {
            Pool copy = poolCopies.get(pool.getId());
            for (PoolMember member : pool.getMembers()) {
                PoolMember copied = member.getMemberPoolId() != null
                        ? PoolMember.ofPool(poolCopies.get(member.getMemberPoolId()).getId(), member.getShare())
                        : new PoolMember(personIds.get(member.getPersonId()), member.getShare());
                copied.setDates(member.getFromDate(), member.getToDate());
                copy.getMembers().add(copied);
            }
            pools.saveAndFlush(copy);
        }

        // The claims, person by person as listed, each person's in the order they were added, listed as they were.
        Map<Long, List<ClaimLog>> logOf = new HashMap<>();
        logs.findBySheetIdOrderByIdAsc(sheetId)
                .forEach(l -> logOf.computeIfAbsent(l.getClaimId(), k -> new ArrayList<>()).add(l));
        Map<Long, List<Claim>> claimsOf = new HashMap<>();
        claims.findBySheetIdOrderByIdDesc(sheetId)
                .forEach(c -> claimsOf.computeIfAbsent(c.getPersonId(), k -> new ArrayList<>()).add(c));
        List<Claim> originals = new ArrayList<>();
        for (Person person : theirPeople) {
            originals.addAll(claimsOf.getOrDefault(person.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Claim::getId)).toList());
        }
        Map<Claim, Claim> copies = new HashMap<>();
        List<Claim> added = new ArrayList<>();
        for (Claim claim : originals) {
            Claim copy = claim.copyTo(to, personIds.get(claim.getPersonId()));
            copy.setListed(claim.getListed());
            Claim saved = claims.save(copy);
            copies.put(claim, saved);
            added.add(saved);
        }
        claims.flush();
        sheets.setLastListed(to, sheets.lastListed(sheetId));

        // Numbered anew in the order the originals were opened, by the clone's own numbering.
        Map<String, String> numbers = new HashMap<>();
        Map<Claim, LocalDate> openedOn = new HashMap<>();
        originals.forEach(c -> openedOn.put(copies.get(c), openedOn(c, logOf.getOrDefault(c.getId(), List.of()))));
        for (Claim claim : originals.stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing((Claim c) -> openedOn.get(copies.get(c)))
                        .thenComparing(c -> copies.get(c).getId()))
                .toList()) {
            Claim copy = copies.get(claim);
            copy.setNumber(claimService.nextNumber(to, copy.getDueBy()));
            numbers.put(claim.getNumber(), copy.getNumber());
        }
        for (Claim copy : added) {
            if (copy.getCorrects() != null) {
                copy.setCorrects(numbers.getOrDefault(copy.getCorrects(), copy.getCorrects()));
            }
            if (copy.getSplitFrom() != null) {
                copy.setSplitFrom(numbers.getOrDefault(copy.getSplitFrom(), copy.getSplitFrom()));
            }
        }
        claims.saveAllAndFlush(added);
        for (Claim claim : originals) {
            Long copyId = copies.get(claim).getId();
            for (ClaimLog log : logOf.getOrDefault(claim.getId(), List.of())) {
                logs.save(new ClaimLog(to, copyId, log.getStatus(), log.getBy(), log.getDate(), log.getDue()));
            }
        }
        logs.flush();

        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + to);
        audit.recordOnCommit("SHEET_CLONED sheet=" + to + " by=" + user + " from=" + sheetId);
        rollup.recordAll(to, user);
        return clone;
    }

    /** The sheet's today when the claim was opened: its first log line past DRAFT, else its date. */
    private static LocalDate openedOn(Claim claim, List<ClaimLog> log) {
        return log.stream().filter(l -> l.getStatus() != ClaimStatus.DRAFT && l.getStatus() != ClaimStatus.DELETED)
                .map(ClaimLog::getDate).findFirst().orElse(claim.getDate());
    }
}
