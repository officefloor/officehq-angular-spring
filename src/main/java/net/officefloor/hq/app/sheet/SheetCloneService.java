package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLine;
import net.officefloor.hq.app.claim.ClaimLineRepository;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.holiday.SheetHoliday;
import net.officefloor.hq.app.holiday.SheetHolidayRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonPatron;
import net.officefloor.hq.app.person.PersonPatronRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.SheetPool;
import net.officefloor.hq.app.pool.SheetPoolMember;
import net.officefloor.hq.app.pool.SheetPoolMemberRepository;
import net.officefloor.hq.app.pool.SheetPoolRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rate.RateVersion;
import net.officefloor.hq.app.rate.RateVersionRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.SheetPermission;
import net.officefloor.hq.app.settings.SheetPermissionRepository;
import net.officefloor.hq.app.settings.SheetRole;
import net.officefloor.hq.app.settings.SheetRoleRepository;
import net.officefloor.hq.app.settings.SheetSetting;
import net.officefloor.hq.app.settings.SheetSettingRepository;
import net.officefloor.hq.app.template.ClaimTemplate;
import net.officefloor.hq.app.template.ClaimTemplateRepository;
import net.officefloor.hq.app.voidreason.SheetVoidReason;
import net.officefloor.hq.app.voidreason.SheetVoidReasonRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A steward's clone of a sheet to another id, one never used. The clone gets the sheet's today, settings,
 * roles, permissions, rates (with their older versions), holidays, void reasons, templates, tags, people (grades, terms, contacts,
 * aliases, holds and retirements, patrons), pools and periods (all open), and its claims with their status,
 * frozen figures, dates, notes, lines and logs; each claim that had a number is given the next number of the
 * clone, in the order the claims were opened (by the sheet's today when each was opened, then by the order
 * they were added to the clone: by person in list order, each person's oldest first). Statements are not
 * copied, so no figure is bound; nor are watches, delegations, comments, locks or settle batches.
 */
@Service
public class SheetCloneService {

    private final SheetRepository sheets;
    private final SettingsService settings;
    private final SheetSettingRepository settingRows;
    private final SheetRoleRepository roles;
    private final SheetPermissionRepository permissions;
    private final RateRepository rates;
    private final RateVersionRepository rateVersions;
    private final SheetHolidayRepository holidays;
    private final SheetVoidReasonRepository voidReasons;
    private final ClaimTemplateRepository templates;
    private final SheetTagRepository tags;
    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final SheetPoolRepository pools;
    private final SheetPoolMemberRepository poolMembers;
    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final ClaimLineRepository lines;
    private final ClaimService claimService;
    private final SheetService sheetService;
    private final RollupService rollup;
    private final Audit audit;
    private final Clock clock = Clock.systemDefaultZone();

    public SheetCloneService(SheetRepository sheets, SettingsService settings, SheetSettingRepository settingRows,
            SheetRoleRepository roles, SheetPermissionRepository permissions, RateRepository rates,
            RateVersionRepository rateVersions,
            SheetHolidayRepository holidays, SheetVoidReasonRepository voidReasons, ClaimTemplateRepository templates,
            SheetTagRepository tags, PersonRepository people, PersonPatronRepository patrons,
            SheetPoolRepository pools, SheetPoolMemberRepository poolMembers, PeriodRepository periods,
            ClaimRepository claims, ClaimLogRepository logs, ClaimLineRepository lines, ClaimService claimService, SheetService sheetService,
            RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.settings = settings;
        this.settingRows = settingRows;
        this.roles = roles;
        this.permissions = permissions;
        this.rates = rates;
        this.rateVersions = rateVersions;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.templates = templates;
        this.tags = tags;
        this.people = people;
        this.patrons = patrons;
        this.pools = pools;
        this.poolMembers = poolMembers;
        this.periods = periods;
        this.claims = claims;
        this.logs = logs;
        this.lines = lines;
        this.claimService = claimService;
        this.sheetService = sheetService;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Clones the sheet {@code sheetId} to {@code toSheetId}, returning the clone; refused unless the user
     * is a steward and the other sheet was never used. Records SHEET_CLONE_MADE on this sheet, and
     * SHEET_CLONED then a ROLLUP line per person and a TOTALS line on the clone. */
    @Transactional
    public Sheet cloneSheet(String sheetId, String user, String toSheetId) {
        Sheet from = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new SheetCloneException();
        if (toSheetId.isEmpty() || toSheetId.length() > 100 || toSheetId.equals(sheetId)
                || sheets.existsById(toSheetId)) {
            throw new SheetCloneException();
        }
        Sheet clone;
        try {
            clone = sheets.saveAndFlush(from.clonedTo(toSheetId, user, Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // Another user started the other sheet at the same moment.
            throw new SheetCloneException();
        }

        settingRows.findBySheetIdOrderByIdAsc(sheetId).forEach(s ->
                settingRows.save(new SheetSetting(toSheetId, s.getKey(), s.getFrom(), s.getValue())));
        roles.findBySheetIdOrderByUser(sheetId).forEach(r -> roles.save(new SheetRole(toSheetId, r.getUser(), r.getRole())));
        permissions.findBySheetId(sheetId).forEach(p ->
                permissions.save(new SheetPermission(toSheetId, p.getAction(), p.getRoles())));
        rates.findBySheetIdOrderByGradeAndFrom(sheetId).forEach(r -> {
            Rate copy = new Rate(toSheetId, r.getGrade(), r.getFrom(), r.getTo(), r.getPct());
            copy.setVersion(r.getVersion());
            copy.setTerms(r.getTerms());
            Long copyId = rates.save(copy).getId();
            rateVersions.findByRateIdOrderByVersionAsc(r.getId()).forEach(v ->
                    rateVersions.save(new RateVersion(copyId, v.getVersion(), v.getPct(), v.getTo())));
        });
        holidays.findBySheetIdOrderByDateAsc(sheetId).forEach(h -> holidays.save(new SheetHoliday(toSheetId, h.getDate())));
        voidReasons.findBySheetIdOrderByIdAsc(sheetId).forEach(v ->
                voidReasons.save(new SheetVoidReason(toSheetId, v.getReason())));
        templates.findBySheetIdOrderByNameAsc(sheetId).forEach(t -> {
            ClaimTemplate copy = new ClaimTemplate(toSheetId, t.getName());
            copy.setAmount(t.getAmount());
            copy.setNote(t.getNote());
            templates.save(copy);
        });
        tags.findBySheetIdOrderByTagAsc(sheetId).forEach(t -> tags.save(new SheetTag(toSheetId, t.getTag())));
        // Open periods first: the clone numbers its claims within them while it numbers by period.
        periods.findBySheetIdOrderByFromAsc(sheetId).forEach(p ->
                periods.save(new Period(toSheetId, p.getName(), p.getFrom(), p.getTo(), p.getCap())));
        periods.flush();

        List<Person> originals = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Long> personIds = new HashMap<>();
        for (Person person : originals) {
            personIds.put(person.getId(), people.saveAndFlush(person.clonedTo(toSheetId)).getId());
        }
        patrons.findBySheetIdOrderByIdAsc(sheetId).forEach(p -> patrons.save(
                new PersonPatron(toSheetId, personIds.get(p.getPersonId()), personIds.get(p.getPatronId()))));

        List<SheetPool> originalPools = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Long> poolIds = new HashMap<>();
        for (SheetPool pool : originalPools) {
            poolIds.put(pool.getId(), pools.saveAndFlush(new SheetPool(toSheetId, pool.getName())).getId());
        }
        if (!originalPools.isEmpty()) {
            poolMembers.findByPoolIdInOrderByIdAsc(poolIds.keySet()).forEach(m -> {
                Long poolId = poolIds.get(m.getPoolId());
                SheetPoolMember copy = m.getPersonId() != null
                        ? new SheetPoolMember(poolId, personIds.get(m.getPersonId()), m.getShare())
                        : SheetPoolMember.ofPool(poolId, poolIds.get(m.getMemberPoolId()), m.getShare());
                poolMembers.save(copy.dated(m.getFromDate(), m.getToDate()));
            });
        }

        cloneClaims(sheetId, toSheetId, originals, personIds);
        sheets.flush();

        audit.change("SHEET_CLONE_MADE", sheetId, user, "to=" + toSheetId);
        audit.change("SHEET_CLONED", toSheetId, user, "from=" + sheetId);
        rollup.recordSince(toSheetId, user, new RollupService.Snapshot(Map.of(), null, null, null));
        return clone;
    }

    /** Copies the sheet's claims, with their logs, to the people they were cloned to. */
    private void cloneClaims(String sheetId, String toSheetId, List<Person> originals, Map<Long, Long> personIds) {
        List<Claim> all = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        if (all.isEmpty()) return;
        // Added to the clone by person in list order, each person's oldest first.
        Map<Long, Integer> place = new HashMap<>();
        for (int i = 0; i < originals.size(); i++) place.put(originals.get(i).getId(), i);
        List<Claim> added = new ArrayList<>(all);
        added.sort(Comparator.comparing((Claim c) -> place.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                .thenComparingLong(Claim::getListed).thenComparing(Claim::getId));
        Map<Long, List<ClaimLog>> logsOf = logs.findByClaimIdInOrderByIdAsc(all.stream().map(Claim::getId).toList())
                .stream().collect(Collectors.groupingBy(ClaimLog::getClaimId));
        Map<Long, List<ClaimLine>> linesOf = lines.findByClaimIdInOrderByIdAsc(all.stream().map(Claim::getId).toList())
                .stream().collect(Collectors.groupingBy(ClaimLine::getClaimId));

        Map<Claim, Claim> copies = new HashMap<>();
        for (Claim claim : added) copies.put(claim, claim.clonedTo(toSheetId, personIds.get(claim.getPersonId())));
        // Listed anew in the order the sheet lists them, oldest first, so the clone lists them alike.
        for (int i = all.size() - 1; i >= 0; i--) {
            copies.get(all.get(i)).setListed(sheetService.nextInSequence(toSheetId, "listed"));
        }
        Map<String, String> renumbered = new HashMap<>();
        Map<Claim, Integer> order = new HashMap<>();
        for (int i = 0; i < added.size(); i++) order.put(added.get(i), i);
        added.stream()
                .filter(claim -> claim.getNumber() != null)
                .sorted(Comparator.comparing((Claim claim) -> ClaimLog.openedOn(logsOf.get(claim.getId())),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(order::get))
                .forEach(claim -> {
                    Claim copy = copies.get(claim);
                    copy.setNumber(claimService.nextNumber(toSheetId, copy.getDueBy()));
                    renumbered.put(claim.getNumber(), copy.getNumber());
                });
        for (Claim claim : added) {
            Claim copy = copies.get(claim);
            if (claim.getCorrects() != null) {
                copy.setCorrects(renumbered.getOrDefault(claim.getCorrects(), claim.getCorrects()));
            }
            if (claim.getSplitFrom() != null) {
                copy.setSplitFrom(renumbered.getOrDefault(claim.getSplitFrom(), claim.getSplitFrom()));
            }
        }
        for (Claim claim : added) {
            Claim copy = claims.saveAndFlush(copies.get(claim));
            logsOf.getOrDefault(claim.getId(), List.of()).forEach(line -> logs.save(line.copyFor(copy.getId())));
            linesOf.getOrDefault(claim.getId(), List.of()).forEach(line -> lines.save(line.copyFor(copy.getId())));
        }
        logs.flush();
        lines.flush();
    }
}
