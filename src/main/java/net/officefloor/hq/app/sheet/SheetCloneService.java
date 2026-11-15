package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLine;
import net.officefloor.hq.app.claim.ClaimLineRepository;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonPatron;
import net.officefloor.hq.app.person.PersonPatronRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.SheetHoliday;
import net.officefloor.hq.app.settings.SheetHolidayRepository;
import net.officefloor.hq.app.settings.SheetPermission;
import net.officefloor.hq.app.settings.SheetPermissionRepository;
import net.officefloor.hq.app.settings.SheetRole;
import net.officefloor.hq.app.settings.SheetRoleRepository;
import net.officefloor.hq.app.settings.SheetVoidReason;
import net.officefloor.hq.app.settings.SheetVoidReasonRepository;
import net.officefloor.hq.app.template.ClaimTemplate;
import net.officefloor.hq.app.template.ClaimTemplateRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Clones a sheet to another id that was never used. */
@Service
public class SheetCloneService {

    private final SheetRepository sheets;
    private final SettingsService settings;
    private final SheetRoleRepository roles;
    private final SheetPermissionRepository permissions;
    private final RateRepository rates;
    private final SheetHolidayRepository holidays;
    private final SheetVoidReasonRepository voidReasons;
    private final ClaimTemplateRepository templates;
    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PoolRepository pools;
    private final PoolMemberRepository poolMembers;
    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final ClaimLogRepository claimLog;
    private final ClaimLineRepository claimLines;
    private final SheetTagRepository tags;
    private final ClaimService claimService;
    private final RollupService rollup;
    private final Audit audit;

    public SheetCloneService(SheetRepository sheets, SettingsService settings, SheetRoleRepository roles,
            SheetPermissionRepository permissions, RateRepository rates, SheetHolidayRepository holidays,
            SheetVoidReasonRepository voidReasons, ClaimTemplateRepository templates, PersonRepository people,
            PersonPatronRepository patrons, PoolRepository pools, PoolMemberRepository poolMembers,
            PeriodRepository periods, ClaimRepository claims, ClaimLogRepository claimLog, ClaimLineRepository claimLines, SheetTagRepository tags,
            ClaimService claimService, RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.settings = settings;
        this.roles = roles;
        this.permissions = permissions;
        this.rates = rates;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.templates = templates;
        this.people = people;
        this.patrons = patrons;
        this.pools = pools;
        this.poolMembers = poolMembers;
        this.periods = periods;
        this.claims = claims;
        this.claimLog = claimLog;
        this.claimLines = claimLines;
        this.tags = tags;
        this.claimService = claimService;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Clone the sheet to another id, as a steward: the clone gets this sheet's today, settings,
     *  roles, permissions, rates, holidays, void reasons, templates and tags; its people (with their
     *  grades, terms, contacts, aliases, holds, retirements and patrons), pools and periods (all
     *  open); and its claims with their status, frozen figures, dates, notes, logs and lines. Each claim
     *  that had a number takes the clone's next one, in the order the claims were opened (by the
     *  sheet's today when each was opened, then by the order they were added to the clone, people
     *  in list order and each one's claims in the order they are listed). Statements (and the
     *  figures they bound) are not copied. Refused (403) unless the user acts as a steward, and
     *  (409) when the other id was ever used. */
    @Transactional
    public Sheet cloneSheet(String sheetId, String user, String toSheetId) {
        String target = toSheetId.trim();
        Sheet sheet = sheets.findForUpdate(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may clone the sheet");
        }
        if (sheets.existsById(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet " + target + " is already in use");
        }
        Sheet clone;
        try {
            clone = sheets.saveAndFlush(sheet.clonedTo(target, user));
        } catch (DataIntegrityViolationException e) {
            // another user took the id at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet " + target + " is already in use");
        }

        roles.findBySheetIdOrderByUserAsc(sheetId)
                .forEach(r -> roles.save(new SheetRole(target, r.getUser(), r.getRole())));
        permissions.findBySheetId(sheetId)
                .forEach(p -> permissions.save(new SheetPermission(target, p.getAction(), p.getRoles())));
        for (Rate r : rates.findBySheetIdOrderByGradeAsc(sheetId)) {
            rates.save(new Rate(target, r.getGrade(), r.getFrom(), r.getTo(), r.getPct()));
        }
        holidays.findBySheetIdOrderByDayAsc(sheetId).forEach(h -> holidays.save(new SheetHoliday(target, h.getDay())));
        voidReasons.findBySheetIdOrderByIdAsc(sheetId)
                .forEach(v -> voidReasons.save(new SheetVoidReason(target, v.getReason())));
        for (ClaimTemplate t : templates.findBySheetIdOrderByNameAsc(sheetId)) {
            templates.save(new ClaimTemplate(target, t.getName(), t.getAmount(), t.getNote()));
        }
        tags.findBySheetIdOrderByTagAsc(sheetId).forEach(t -> tags.save(new SheetTag(target, t.getTag())));
        for (Period p : periods.findBySheetIdOrderByFromAsc(sheetId)) {
            Period copy = new Period(target, p.getName(), p.getFrom(), p.getTo());
            copy.setCap(p.getCap());
            periods.save(copy);
        }

        // people in list order, then their patrons
        Map<Long, Long> personIds = new HashMap<>();
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        for (Person p : sheetPeople) {
            personIds.put(p.getId(), people.saveAndFlush(p.clonedTo(target)).getId());
        }
        for (PersonPatron pp : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            patrons.save(new PersonPatron(target, personIds.get(pp.getPersonId()), personIds.get(pp.getPatronId())));
        }

        // pools, then their members (people or other pools)
        Map<Long, Long> poolIds = new HashMap<>();
        List<Pool> sheetPools = pools.findBySheetIdOrderByIdAsc(sheetId);
        for (Pool p : sheetPools) {
            poolIds.put(p.getId(), pools.saveAndFlush(new Pool(target, p.getName())).getId());
        }
        List<PoolMember> members = poolIds.isEmpty() ? List.of() : poolMembers.findByPoolIdInOrderByIdAsc(poolIds.keySet());
        for (PoolMember m : members) {
            PoolMember copy = m.getMemberPoolId() != null
                    ? PoolMember.ofPool(poolIds.get(m.getPoolId()), poolIds.get(m.getMemberPoolId()), m.getShare())
                    : new PoolMember(poolIds.get(m.getPoolId()), personIds.get(m.getPersonId()), m.getShare());
            copy.setDates(m.getFromDate(), m.getToDate());
            poolMembers.save(copy);
        }
        sheets.flush();

        // claims: each person's (in list order) oldest listed first, with their logs and lines
        Map<Long, List<Claim>> byPerson = new LinkedHashMap<>();
        claims.findBySheetIdOrderByIdDesc(sheetId)
                .forEach(c -> byPerson.computeIfAbsent(c.getPersonId(), k -> new ArrayList<>()).add(c));
        Map<Claim, Claim> copies = new LinkedHashMap<>();
        Map<Long, List<ClaimLog>> logs = new HashMap<>();
        for (Person p : sheetPeople) {
            List<Claim> theirs = byPerson.getOrDefault(p.getId(), List.of()).stream()
                    .sorted(Comparator.comparingLong(Claim::getListed)).toList();
            for (Claim claim : theirs) {
                Claim copy = claims.saveAndFlush(claim.clonedTo(target, personIds.get(p.getId())));
                copies.put(claim, copy);
                List<ClaimLog> lines = claimLog.findByClaimIdOrderByIdAsc(claim.getId());
                logs.put(claim.getId(), lines);
                for (ClaimLog line : lines) {
                    claimLog.save(new ClaimLog(target, copy.getId(), line.getStatus(), line.getBy(), line.getOn(),
                            line.getDue()));
                }
                for (ClaimLine line : claimLines.findByClaimIdOrderByIdAsc(claim.getId())) {
                    claimLines.save(new ClaimLine(target, copy.getId(), line.getLabel(), line.getQty(), line.getUnit()));
                }
            }
        }
        claimLog.flush();
        claimLines.flush();

        // numbers: the clone's next, in the order the claims were opened
        Map<String, String> renumbered = new HashMap<>();
        List<Claim> numbered = copies.keySet().stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing((Claim c) -> openedOn(c, logs.get(c.getId())))
                        .thenComparing(c -> copies.get(c).getId()))
                .toList();
        for (Claim claim : numbered) {
            Claim copy = copies.get(claim);
            copy.setNumber(claimService.nextNumber(target, copy.getDueBy()));
            claims.flush();
            renumbered.put(claim.getNumber(), copy.getNumber());
        }
        for (Claim copy : copies.values()) {
            if (copy.getCorrects() != null) {
                copy.setCorrects(renumbered.get(copy.getCorrects()));
            }
            if (copy.getSplitFrom() != null) {
                copy.setSplitFrom(renumbered.get(copy.getSplitFrom()));
            }
        }
        claims.flush();

        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + target);
        audit.recordOnCommit("SHEET_CLONED sheet=" + target + " by=" + user + " from=" + sheetId);
        rollup.recordAll(target, user);
        return clone;
    }

    /** The sheet's today when the claim was opened: its first OPEN log line's, else its first
     *  line's, else its date. */
    private static LocalDate openedOn(Claim claim, List<ClaimLog> log) {
        return log.stream().filter(l -> l.getStatus() == ClaimStatus.OPEN).findFirst()
                .or(() -> log.stream().findFirst())
                .map(ClaimLog::getOn)
                .orElse(claim.getDate());
    }
}
