package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.access.SheetRole;
import net.officefloor.hq.app.access.SheetRoleRepository;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLogLine;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.ClaimTemplate;
import net.officefloor.hq.app.claim.ClaimTemplateRepository;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.claim.VoidReason;
import net.officefloor.hq.app.claim.VoidReasonRepository;
import net.officefloor.hq.app.holiday.Holiday;
import net.officefloor.hq.app.holiday.HolidayRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Patron;
import net.officefloor.hq.app.person.PatronRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cloning a sheet to another id. Only a steward of this sheet may, and only to an id never used; anything
 * else is refused. The clone gets this sheet's today, settings, roles, rates, holidays, void reasons,
 * templates and tags, its people (grades, terms, contacts, aliases, holds, retirements, patrons), pools,
 * periods (all open) and claims (status, frozen figures, dates, notes, tags, logs and comments). Each claim
 * that had a number takes the clone's next one, in the order the claims were opened (by the sheet's today
 * when each was opened, then by the order they were added, person by person in list order). Statements are
 * not copied: figures a statement bound are released.
 */
@Service
public class SheetCloneService {

    private final SheetRepository sheetRows;
    private final SheetSettingRepository settings;
    private final SheetRoleRepository roles;
    private final RateRepository rates;
    private final HolidayRepository holidays;
    private final VoidReasonRepository voidReasons;
    private final ClaimTemplateRepository templates;
    private final SheetTagRepository tags;
    private final PersonRepository people;
    private final PatronRepository patrons;
    private final PoolRepository pools;
    private final PoolMemberRepository poolMembers;
    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final AccessService access;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public SheetCloneService(SheetRepository sheetRows, SheetSettingRepository settings, SheetRoleRepository roles,
            RateRepository rates, HolidayRepository holidays, VoidReasonRepository voidReasons,
            ClaimTemplateRepository templates, SheetTagRepository tags, PersonRepository people,
            PatronRepository patrons, PoolRepository pools, PoolMemberRepository poolMembers,
            PeriodRepository periods, ClaimRepository claims, ClaimService claimService, AccessService access,
            Rollup rollup, ApplicationEventPublisher events) {
        this.sheetRows = sheetRows;
        this.settings = settings;
        this.roles = roles;
        this.rates = rates;
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
        this.claimService = claimService;
        this.access = access;
        this.rollup = rollup;
        this.events = events;
    }

    /** Clone the sheet to the other id; refused (403) for anyone but a steward, (409) when the id was used. */
    @Transactional
    public Sheet cloneTo(String sheetId, String user, String toSheet) {
        Sheet sheet = sheetRows.lockById(sheetId);
        if (sheet == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet");
        }
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can clone the sheet");
        }
        if (toSheet.equals(sheetId) || sheetRows.existsById(toSheet)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet is already in use");
        }
        sheetRows.saveAndFlush(sheet.cloned(toSheet));

        settings.findBySheetId(sheetId).stream().sorted(Comparator.comparing(SheetSetting::getId))
                .forEach(s -> settings.save(new SheetSetting(toSheet, s.getKey(), s.getFromDate(), s.getValue())));
        roles.findBySheetIdOrderByUserName(sheetId)
                .forEach(r -> roles.save(new SheetRole(toSheet, r.getUserName(), r.getRole())));
        Map<Rate, Long> forwardOnly = new HashMap<>();
        rates.findBySheetId(sheetId).stream().sorted(Comparator.comparing(Rate::getId)).forEach(r -> {
            Rate copy = rates.save(new Rate(toSheet, r.getGrade(), r.getFromDate(), r.getToDate(), r.getPct()));
            if (r.getForwardAfter() != null) {
                forwardOnly.put(copy, r.getForwardAfter());
            }
        });
        holidays.findBySheetIdOrderByDayAsc(sheetId).forEach(h -> holidays.save(new Holiday(toSheet, h.getDay())));
        voidReasons.findBySheetIdOrderByIdAsc(sheetId)
                .forEach(v -> voidReasons.save(new VoidReason(toSheet, v.getReason())));
        templates.findBySheetIdOrderByNameAsc(sheetId)
                .forEach(t -> templates.save(new ClaimTemplate(toSheet, t.getName(), t.getAmount(), t.getNote())));
        tags.findBySheetIdOrderByTagAsc(sheetId).forEach(t -> tags.save(new SheetTag(toSheet, t.getTag())));
        periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().sorted(Comparator.comparing(Period::getId))
                .forEach(p -> periods.save(new Period(toSheet, p.getName(), p.getFromDate(), p.getToDate(), p.getCap())));

        Map<Long, Long> personIds = new HashMap<>();
        List<Person> theirs = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        for (Person person : theirs) {
            personIds.put(person.getId(), people.save(person.cloned(toSheet)).getId());
        }
        patrons.findBySheetIdOrderByIdAsc(sheetId).forEach(p -> patrons
                .save(new Patron(toSheet, personIds.get(p.getPersonId()), personIds.get(p.getPatronId()))));

        Map<Long, Long> poolIds = new HashMap<>();
        List<Pool> theirPools = pools.findBySheetIdOrderByNameAsc(sheetId).stream()
                .sorted(Comparator.comparing(Pool::getId)).toList();
        for (Pool pool : theirPools) {
            poolIds.put(pool.getId(), pools.save(new Pool(toSheet, pool.getName())).getId());
        }
        if (!poolIds.isEmpty()) {
            for (PoolMember m : poolMembers.findByPoolIdInOrderByIdAsc(poolIds.keySet())) {
                PoolMember copy = m.getMemberPoolId() != null
                        ? PoolMember.ofPool(poolIds.get(m.getPoolId()), poolIds.get(m.getMemberPoolId()), m.getShare())
                        : new PoolMember(poolIds.get(m.getPoolId()), personIds.get(m.getPersonId()), m.getShare());
                poolMembers.save(copy.dated(m.getFrom(), m.getTo()));
            }
        }
        sheetRows.flush();

        // person by person in list order, each one's claims oldest listed first, so they list as they do here
        Map<Long, List<Claim>> byPerson = new HashMap<>();
        List<Claim> listed = new ArrayList<>(claims.findBySheetIdOrderByListedDesc(sheetId));
        Collections.reverse(listed);
        listed.forEach(c -> byPerson.computeIfAbsent(c.getPersonId(), k -> new ArrayList<>()).add(c));
        List<Claim> copies = new ArrayList<>();
        Map<Claim, String> oldNumbers = new HashMap<>();
        Map<Claim, LocalDate> openedOn = new HashMap<>();
        Map<Claim, Long> originalIds = new HashMap<>();
        for (Person person : theirs) {
            for (Claim claim : byPerson.getOrDefault(person.getId(), List.of())) {
                Claim copy = claim.cloned(toSheet, personIds.get(person.getId()));
                copies.add(copy);
                if (claim.getShownNumber() != null) {
                    oldNumbers.put(copy, claim.getShownNumber());
                }
                openedOn.put(copy, openedOn(claim));
                originalIds.put(copy, claim.getId());
            }
        }
        claims.saveAllAndFlush(copies);
        // a forward-only rate stays one: the copies of the claims added before it was saved keep leaving it out
        long beforeCopies = copies.stream().mapToLong(Claim::getId).min().orElse(rates.maxClaimId() + 1) - 1;
        forwardOnly.forEach((rate, after) -> rate.forwardAfter(copies.stream()
                .filter(c -> originalIds.get(c) <= after).mapToLong(Claim::getId).max().orElse(beforeCopies)));
        rates.flush();

        Map<String, String> renumbered = new HashMap<>();
        copies.stream().filter(oldNumbers::containsKey).sorted(Comparator.comparing(openedOn::get))
                .forEach(claim -> {
                    claimService.numberArrived(toSheet, claim);
                    renumbered.put(oldNumbers.get(claim), claim.getShownNumber());
                });
        // a correction, or a claim split off another, names the claim it came from by its new number
        for (Claim claim : copies) {
            if (claim.getCorrects() != null) {
                claim.setCorrects(renumbered.getOrDefault(claim.getCorrects(), claim.getCorrects()));
            }
            if (claim.getSplitFrom() != null) {
                claim.setSplitFrom(renumbered.getOrDefault(claim.getSplitFrom(), claim.getSplitFrom()));
            }
        }
        claims.flush();

        events.publishEvent(new SheetCloned(sheetId, user, toSheet, null));
        events.publishEvent(new SheetCloned(toSheet, user, null, sheetId));
        // the copy had no figures before: everyone and the totals count as moved
        rollup.changed(toSheet, user, new Rollup.Figures(Map.of(), null));
        return sheet;
    }

    /** The sheet's today when the claim was opened: its first status after DRAFT; its date when it has no log of it. */
    private static LocalDate openedOn(Claim claim) {
        return claim.getLog().stream().filter(l -> l.getStatus() != ClaimStatus.DRAFT).map(ClaimLogLine::getOn)
                .findFirst().orElse(claim.getDate());
    }
}
