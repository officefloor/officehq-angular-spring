package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claimtemplate.ClaimTemplate;
import net.officefloor.hq.app.claimtemplate.ClaimTemplateRepository;
import net.officefloor.hq.app.holiday.Holiday;
import net.officefloor.hq.app.holiday.HolidayRepository;
import net.officefloor.hq.app.patron.Patron;
import net.officefloor.hq.app.patron.PatronRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.voidreason.VoidReason;
import net.officefloor.hq.app.voidreason.VoidReasonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Cloning a sheet to another id. */
@Service
public class SheetCloneService {

    /** Claims in the order they were opened: by the sheet's today when each was, then by the order they were
     *  added (on the clone). */
    private static final Comparator<Claim> BY_OPENED = Comparator
            .comparing(Claim::openedOn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Claim::getId);

    /** A person's claims oldest first, as they are listed. */
    private static final Comparator<Claim> OLDEST_FIRST = Comparator.comparingLong(Claim::getListed)
            .thenComparing(Claim::getId);

    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RoleService roleService;
    private final RoleRepository roles;
    private final RateRepository rates;
    private final HolidayRepository holidays;
    private final VoidReasonRepository voidReasons;
    private final ClaimTemplateRepository templates;
    private final PersonRepository people;
    private final PatronRepository patrons;
    private final PoolRepository pools;
    private final PoolMemberRepository poolMembers;
    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final Rollup rollup;
    private final Audit audit;

    public SheetCloneService(SheetService sheets, SheetRepository sheetStore, RoleService roleService,
            RoleRepository roles, RateRepository rates, HolidayRepository holidays, VoidReasonRepository voidReasons,
            ClaimTemplateRepository templates, PersonRepository people, PatronRepository patrons, PoolRepository pools,
            PoolMemberRepository poolMembers, PeriodRepository periods, ClaimRepository claims,
            ClaimService claimService, Rollup rollup, Audit audit) {
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.roleService = roleService;
        this.roles = roles;
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
        this.claimService = claimService;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * A steward clones the sheet to another id, which must never have been used. The clone gets the sheet's today,
     * settings, roles, rates, holidays, void reasons, templates, people (with their grades, terms, contacts, aliases,
     * holds, retirements and patrons), pools, periods (all open) and claims (status, frozen figures, dates, notes,
     * logs), each claim that had a number taking the clone's next in the order the claims were opened. Statements are
     * not copied, so no figure is bound on the clone. The clone's audit starts with SHEET_CLONED and a rollup of
     * every person and its totals.
     */
    @Transactional
    public Sheet cloneSheet(String sheetId, String user, String toSheetId) {
        sheets.load(sheetId, user);
        if (!Role.STEWARD.equals(roleService.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can clone the sheet");
        }
        String target = toSheetId == null ? "" : toSheetId.trim();
        Sheet from = sheetStore.findLockedById(sheetId).orElseThrow();
        if (target.isEmpty() || target.equals(sheetId) || sheetStore.existsById(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sheet " + target + " has already been used");
        }
        Sheet clone = sheetStore.saveAndFlush(from.copyTo(target, user, Instant.now()));

        roles.saveAll(roles.findBySheetIdOrderByUserName(sheetId).stream()
                .map(r -> new Role(target, r.getUserName(), r.getRole())).toList());
        rates.saveAll(rates.findBySheetId(sheetId).stream().sorted(Comparator.comparing(Rate::getId)).map(r -> {
            Rate copy = new Rate(target, r.getGrade(), r.getFromDate(), r.getRate());
            copy.setToDate(r.getToDate());
            return copy;
        }).toList());
        holidays.saveAll(holidays.findBySheetIdOrderByDateAsc(sheetId).stream()
                .map(h -> new Holiday(target, h.getDate())).toList());
        voidReasons.saveAll(voidReasons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .map(v -> new VoidReason(target, v.getReason())).toList());
        templates.saveAll(templates.findBySheetIdOrderByNameAsc(sheetId).stream()
                .sorted(Comparator.comparing(ClaimTemplate::getId))
                .map(t -> new ClaimTemplate(target, t.getName(), t.getAmount(), t.getNote())).toList());
        periods.saveAllAndFlush(periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .sorted(Comparator.comparing(Period::getId))
                .map(p -> new Period(target, p.getName(), p.getFromDate(), p.getToDate())).toList());

        List<Person> originals = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Long> personOf = new HashMap<>();
        for (Person person : originals) {
            personOf.put(person.getId(), people.saveAndFlush(person.copyTo(target)).getId());
        }
        patrons.saveAll(patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> personOf.containsKey(p.getPersonId()) && personOf.containsKey(p.getPatronId()))
                .map(p -> new Patron(target, personOf.get(p.getPersonId()), personOf.get(p.getPatronId()))).toList());

        List<Pool> sheetPools = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Long> poolOf = new HashMap<>();
        for (Pool pool : sheetPools) {
            poolOf.put(pool.getId(), pools.saveAndFlush(new Pool(target, pool.getName())).getId());
        }
        if (!sheetPools.isEmpty()) {
            List<PoolMember> members = new ArrayList<>();
            for (PoolMember member : poolMembers.findByPoolIdInOrderByIdAsc(poolOf.keySet())) {
                PoolMember copy;
                if (member.getMemberPoolId() != null) {
                    if (!poolOf.containsKey(member.getMemberPoolId())) {
                        continue;
                    }
                    copy = PoolMember.ofPool(poolOf.get(member.getPoolId()), poolOf.get(member.getMemberPoolId()),
                            member.getShare());
                } else {
                    if (!personOf.containsKey(member.getPersonId())) {
                        continue;
                    }
                    copy = new PoolMember(poolOf.get(member.getPoolId()), personOf.get(member.getPersonId()),
                            member.getShare());
                }
                copy.setDates(member.getFrom(), member.getTo());
                members.add(copy);
            }
            poolMembers.saveAll(members);
        }

        // Each person's claims are added in turn, oldest first, then numbered in the order they were opened.
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Map<Claim, Claim> copyOf = new HashMap<>();
        List<Claim> copies = new ArrayList<>();
        for (Person person : originals) {
            for (Claim claim : sheetClaims.stream().filter(c -> c.getPersonId().equals(person.getId()))
                    .sorted(OLDEST_FIRST).toList()) {
                Claim copy = claims.saveAndFlush(claim.copyTo(target, personOf.get(person.getId())));
                copyOf.put(copy, claim);
                copies.add(copy);
            }
        }
        Map<String, String> renumbered = new HashMap<>();
        for (Claim copy : copies.stream().filter(c -> copyOf.get(c).getNumber() != null).sorted(BY_OPENED).toList()) {
            String number = claimService.nextNumberOn(clone, copy.getDueBy());
            renumbered.put(copyOf.get(copy).getNumber(), number);
            copy.setNumber(number);
        }
        for (Claim copy : copies) {
            copy.renameLinks(
                    copy.getCorrects() == null ? null : renumbered.getOrDefault(copy.getCorrects(), copy.getCorrects()),
                    copy.getSplitFrom() == null ? null : renumbered.getOrDefault(copy.getSplitFrom(), copy.getSplitFrom()));
        }
        claims.saveAllAndFlush(copies);
        Sheet saved = sheetStore.saveAndFlush(clone);

        audit.recordActingOnCommit(sheetId, user, "SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + target);
        audit.recordChangeOnCommit(target, user, "SHEET_CLONED sheet=" + target + " by=" + user + " from=" + sheetId);
        Rollup.Figures figures = rollup.of(target);
        figures.people().values().forEach(f -> audit.recordOnCommit("ROLLUP sheet=" + target + " by=" + user
                + " person=" + f.name() + " claimed=" + f.claimed() + " due-total=" + f.dueTotal()
                + " overdue-total=" + f.overdueTotal()));
        audit.recordOnCommit("TOTALS sheet=" + target + " by=" + user + " claimed-total=" + figures.claimedTotal()
                + " due-total=" + figures.dueTotal() + " overdue-total=" + figures.overdueTotal());
        return saved;
    }
}
