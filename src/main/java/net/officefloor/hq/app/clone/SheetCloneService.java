package net.officefloor.hq.app.clone;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
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
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.role.SheetRoleRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SettingValue;
import net.officefloor.hq.app.sheet.SettingValueRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetPermission;
import net.officefloor.hq.app.sheet.SheetPermissionRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.template.ClaimTemplate;
import net.officefloor.hq.app.template.ClaimTemplateRepository;
import net.officefloor.hq.app.voidreason.VoidReason;
import net.officefloor.hq.app.voidreason.VoidReasonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Cloning a sheet to another id. */
@Service
public class SheetCloneService {

    private final SheetRepository sheets;
    private final SettingValueRepository settingValues;
    private final SheetPermissionRepository permissions;
    private final SheetRoleRepository sheetRoles;
    private final RoleService roles;
    private final RateRepository rates;
    private final HolidayRepository holidays;
    private final VoidReasonRepository voidReasons;
    private final ClaimTemplateRepository templates;
    private final PersonRepository people;
    private final PatronRepository patrons;
    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final SheetTagRepository sheetTags;
    private final Rollup rollup;
    private final Audit audit;

    public SheetCloneService(SheetRepository sheets, SettingValueRepository settingValues,
            SheetPermissionRepository permissions, SheetRoleRepository sheetRoles, RoleService roles,
            RateRepository rates, HolidayRepository holidays, VoidReasonRepository voidReasons,
            ClaimTemplateRepository templates, PersonRepository people, PatronRepository patrons,
            PoolRepository pools, PoolMemberRepository members, PeriodRepository periods, ClaimRepository claims,
            ClaimService claimService, SheetTagRepository sheetTags, Rollup rollup, Audit audit) {
        this.sheets = sheets;
        this.settingValues = settingValues;
        this.permissions = permissions;
        this.sheetRoles = sheetRoles;
        this.roles = roles;
        this.rates = rates;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.templates = templates;
        this.people = people;
        this.patrons = patrons;
        this.pools = pools;
        this.members = members;
        this.periods = periods;
        this.claims = claims;
        this.claimService = claimService;
        this.sheetTags = sheetTags;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Clone the sheet to another id, which must never have been used. The clone has the sheet's
     *  today, settings, roles, rates, holidays, void reasons, templates, people (grades, terms,
     *  contacts, aliases, holds, retirements, patrons), pools, periods (all open) and claims (status,
     *  frozen figures, dates, notes, tags, logs), each claim that had a number taking the clone's next
     *  one, person by person in the order they are listed, in the order their claims were opened (by
     *  the sheet's today then, then by the order they were added); no statements, nor any figure one
     *  bound. Its audit starts with SHEET_CLONED, then the figures it has. Refused unless the user is
     *  a steward. */
    @Transactional
    public synchronized void cloneSheet(String sheetId, String user, String toSheetId) {
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may clone the sheet");
        }
        Sheet from = sheets.findById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No sheet " + sheetId));
        if (toSheetId.equals(sheetId) || sheets.existsById(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet " + toSheetId + " is already used");
        }
        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + toSheetId);
        Sheet to = new Sheet(toSheetId, user, from.getToday());
        to.setPolicyDate(from.getPolicyDate());
        to.setPeriodNumberingFrom(from.getPeriodNumberingFrom());
        to.setGraceDays(from.getGraceDays());
        to.setEscalateAfter(from.getEscalateAfter());
        to.setReviewSla(from.getReviewSla());
        to.setEntryDate(from.getEntryDate());
        to.setPot(from.getPot());
        to.setAlertThreshold(from.getAlertThreshold());
        to.setFiscalStartMonth(from.getFiscalStartMonth());
        to.setParentId(from.getParentId());
        sheets.saveAndFlush(to);
        rollup.tracking(toSheetId, user, () -> {
            audit.recordOnCommit("SHEET_CLONED sheet=" + toSheetId + " by=" + user + " from=" + sheetId);
            copySettings(sheetId, toSheetId);
            Map<Long, Long> personIds = copyPeople(sheetId, toSheetId);
            copyPools(sheetId, toSheetId, personIds);
            periods.findBySheetIdOrderByFromDateAsc(sheetId).forEach(p ->
                    periods.save(new Period(toSheetId, p.getName(), p.getFromDate(), p.getToDate(), p.getClaimCap())));
            periods.flush();
            copyClaims(sheetId, toSheetId, personIds);
            return null;
        });
    }

    private void copySettings(String sheetId, String toSheetId) {
        settingValues.findBySheetId(sheetId).forEach(v ->
                settingValues.save(new SettingValue(toSheetId, v.getKey(), v.getFrom(), v.getValue())));
        permissions.findBySheetId(sheetId).forEach(p ->
                permissions.save(new SheetPermission(toSheetId, p.getAction(), p.getRoles())));
        sheetRoles.findBySheetIdOrderByUserNameAsc(sheetId).forEach(r ->
                sheetRoles.save(new SheetRole(toSheetId, r.getUserName(), r.getRole())));
        rates.findBySheetIdOrdered(sheetId).forEach(r -> {
            Rate copy = new Rate(toSheetId, r.getGrade(), r.getPct(), r.getFromDate());
            copy.setToDate(r.getToDate());
            copy.setForwardAfter(r.getForwardAfter());
            rates.save(copy);
        });
        holidays.findBySheetIdOrderByDayAsc(sheetId).forEach(h ->
                holidays.save(new Holiday(toSheetId, h.getDay(), h.getAddedOn())));
        voidReasons.findBySheetIdOrderByIdAsc(sheetId).forEach(r ->
                voidReasons.save(new VoidReason(toSheetId, r.getReason(), r.getAddedOn())));
        templates.findBySheetIdOrderByIdAsc(sheetId).forEach(t ->
                templates.save(new ClaimTemplate(toSheetId, t.getName(), t.getAmount(), t.getNote())));
        sheetTags.findBySheetIdOrderByTagAsc(sheetId).forEach(t -> sheetTags.save(new SheetTag(toSheetId, t.getTag())));
        settingValues.flush();
    }

    /** Copy the people and their patrons; the clone's id of each person, by their id here. */
    private Map<Long, Long> copyPeople(String sheetId, String toSheetId) {
        Map<Long, Long> ids = new HashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream().sorted(Comparator.comparing(Person::getId))
                .forEach(p -> ids.put(p.getId(), people.saveAndFlush(p.clonedTo(toSheetId)).getId()));
        patrons.findBySheetIdOrderByIdAsc(sheetId).forEach(p -> patrons.save(
                new Patron(toSheetId, ids.get(p.getPersonId()), ids.get(p.getPatronId()), p.getNamedOn())));
        patrons.flush();
        return ids;
    }

    private void copyPools(String sheetId, String toSheetId, Map<Long, Long> personIds) {
        // a crew's pool is not copied: the crews are not
        List<Pool> theirs = pools.findBySheetIdOrderByIdAsc(sheetId).stream().filter(p -> !p.isCrew()).toList();
        if (theirs.isEmpty()) {
            return;
        }
        Map<Long, Long> poolIds = new HashMap<>();
        theirs.forEach(p -> poolIds.put(p.getId(), pools.saveAndFlush(new Pool(toSheetId, p.getName())).getId()));
        for (PoolMember m : members.findByPoolIdInOrderByIdAsc(poolIds.keySet())) {
            if (m.getMemberPoolId() != null && !poolIds.containsKey(m.getMemberPoolId())) {
                continue;
            }
            Long poolId = poolIds.get(m.getPoolId());
            PoolMember copy = m.getMemberPoolId() != null
                    ? PoolMember.ofPool(poolId, poolIds.get(m.getMemberPoolId()), m.getShare())
                    : new PoolMember(poolId, personIds.get(m.getPersonId()), m.getShare());
            copy.setDates(m.getFromDate(), m.getToDate());
            members.save(copy);
        }
        members.flush();
    }

    /** Copy the claims, listed as here; each that had a number takes the clone's next one, person by
     *  person in list order, in the order they were opened. */
    private void copyClaims(String sheetId, String toSheetId, Map<Long, Long> personIds) {
        List<Claim> all = claims.findBySheetIdOrderByListedDesc(sheetId);
        Map<String, String> numbers = new HashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            all.stream().filter(c -> c.getPersonId().equals(person.getId()) && c.getNumber() != null)
                    .sorted(Comparator.comparing((Claim c) -> Objects.requireNonNullElse(c.openedOn(), LocalDate.MAX))
                            .thenComparing(Claim::getId))
                    .forEach(c -> numbers.put(c.getNumber(), claimService.nextNumber(toSheetId, c.getDueBy())));
        }
        all.stream().sorted(Comparator.comparing(Claim::getListed)).forEach(c ->
                claims.saveAndFlush(c.clonedTo(toSheetId, personIds.get(c.getPersonId()), numbers::get)));
    }
}
