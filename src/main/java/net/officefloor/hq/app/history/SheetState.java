package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.patron.PatronController.PatronView;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.rate.RateController.RateView;
import net.officefloor.hq.app.holiday.HolidayController.HolidayView;
import net.officefloor.hq.app.role.DelegationController.DelegationView;
import net.officefloor.hq.app.role.RoleController.RoleView;
import net.officefloor.hq.app.statement.StatementController.SummaryView;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.GradeFrom;
import net.officefloor.hq.app.person.Hold;
import net.officefloor.hq.app.person.PastRetirement;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateVersion;
import net.officefloor.hq.app.sheet.PolicyVersion;
import net.officefloor.hq.app.sheet.Sheet;

/** What a sheet held at a moment. As far as a person's figures depend on it (its dated settings and policy date, its
 *  rates, its people and its claims) each part is rebuilt as an unsaved entity, so the figures of the sheet as it
 *  stood then are worked out by the same rules as the live ones. Alongside, the rest of what the screen shows of it,
 *  as the screen reads it: its other settings, and each list the screen reads (see {@link Views}). */
public record SheetState(List<SettingState> settings, LocalDate policyDate, List<RateState> rates,
        List<PersonState> people, List<ClaimState> claims, Map<String, String> permissions,
        LocalDate periodNumberingFrom, Integer graceDays, boolean archived, Views views, LocalDate entryDate,
        Integer lastSettleBatch, BigDecimal pot, Integer fiscalStartMonth, BigDecimal alertThreshold,
        Integer escalateAfter, Integer reviewSla, String proofKinds, List<AncestorState> ancestors,
        List<PoolState> pools, List<CrewState> crews, String defaultGrade, Integer scoreMid, Integer scoreHigh,
        Boolean scoreLog, Integer maxOpen, Integer pendingSla, List<PolicyVersionState> policyVersions) {

    /** A policy version as it stood: the setting whose change made it and the from-date it was changed from
     *  (policy versions are null in a state kept before they were recorded). */
    public record PolicyVersionState(String key, LocalDate from) {
    }

    /** A pool as it stood, with its memberships in the order they were added (pools and crews are null in a state
     *  kept before they were recorded). */
    public record PoolState(long id, String name, List<PoolMemberState> members) {
    }

    /** A membership of a pool as it stood: a person's (personId) or another pool's (memberPoolId). */
    public record PoolMemberState(Long personId, Long memberPoolId, BigDecimal share, LocalDate from, LocalDate to) {
    }

    /** A crew as it stood: its lead and its members' person ids, in the order they were added. */
    public record CrewState(long id, String name, Long leadId, List<Long> members) {
    }

    /** An ancestor's rates as they stood when the sheet's state was kept, nearest ancestor first: what the sheet then
     *  inherited (none recorded in a state kept before ancestors were). */
    public record AncestorState(String sheetId, List<RateState> rates, List<RateView> views) {

        /** The ancestor's rates as they stood then, unsaved. */
        public List<Rate> rebuildRates() {
            return SheetState.rebuildRates(sheetId, rates);
        }
    }

    /** Each list the screen reads of the sheet, as it read it then; a person's grade there is the one in force on the
     *  sheet's today then (see {@link #people(LocalDate)} for it on another day). */
    public record Views(List<PersonView> people, List<ClaimView> claims, List<RateView> rates,
            List<PeriodView> periods, List<SummaryView> statements, List<PatronView> patrons,
            Map<String, List<Long>> watches, List<RoleView> roles, List<DelegationView> delegations,
            List<HolidayView> holidays) {

        /** Nothing: a sheet as it stood before any change was made on it. */
        public static final Views NONE = new Views(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of(), List.of());
    }

    public record SettingState(String key, LocalDate from, String value) {
    }

    /** A rate as it stood; claimsAfter is set for a forward-only one (null in a state kept before rates could be),
     *  with the older versions a claim it does not apply to may take, newest first. */
    public record RateState(String grade, LocalDate from, LocalDate to, BigDecimal rate, Long claimsAfter,
            List<EarlierRateState> earlier) {

        public RateState {
            earlier = earlier == null ? List.of() : earlier;
        }
    }

    public record EarlierRateState(int version, BigDecimal rate, LocalDate to, Long claimsAfter) {
    }

    public record GradeState(LocalDate from, String grade) {
    }

    public record TermsState(LocalDate from, Integer terms) {
    }

    public record HoldState(LocalDate from, LocalDate to) {
    }

    public record RetirementState(LocalDate from, LocalDate to) {
    }

    public record PersonState(long id, String name, List<GradeState> grades, Integer terms, LocalDate retiredFrom,
            List<HoldState> holds, List<RetirementState> pastRetirements, List<TermsState> datedTerms) {

        /** The person as they stood then, unsaved. */
        public Person rebuild(String sheetId) {
            Person person = new Person(sheetId, name, grades.get(0).grade());
            grades.stream().skip(1).forEach(g -> person.setGradeFrom(g.from(), g.grade()));
            person.setTerms(terms);
            if (datedTerms != null) {
                datedTerms.forEach(t -> person.setTermsFrom(t.from(), t.terms()));
            }
            person.setRetiredFrom(retiredFrom);
            holds.forEach(h -> person.addHold(new Hold(h.from(), h.to())));
            if (pastRetirements != null) {
                pastRetirements.forEach(r -> person.addPastRetirement(new PastRetirement(r.from(), r.to())));
            }
            return person;
        }
    }

    public record ClaimState(long id, long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note,
            String corrects, long listed, ClaimStatus status, String number, BigDecimal settledDue, boolean stated,
            BigDecimal statedDue, LocalDate deferredUntil, LocalDate remindedOn, String ref, String tags,
            boolean waived, BigDecimal statedLate, boolean held, List<ShareState> shares,
            String reviewAssigned, Boolean dueBySet) {

        /** The claim as it stood then, unsaved. */
        public Claim rebuild(String sheetId) {
            Claim claim = new Claim(sheetId, personId, amount, date, dueBy, note, corrects, listed);
            claim.setStatus(status);
            claim.setNumber(number);
            claim.setSettledDue(settledDue);
            claim.setDeferredUntil(deferredUntil);
            claim.remind(remindedOn);
            claim.setRef(ref);
            claim.setTags(tags);
            if (waived) {
                claim.waive();
            }
            claim.setHeld(held);
            claim.setReviewAssigned(reviewAssigned);
            if (Boolean.TRUE.equals(dueBySet)) {
                claim.setDueBy(dueBy);
            }
            if (stated) {
                claim.bind(statedDue, statedLate);
            }
            if (shares != null) {
                shares.forEach(sh -> claim.share(sh.personId(), sh.share()));
            }
            return claim;
        }
    }

    /** A share of a claim: the person holding it and their share percent. */
    public record ShareState(long personId, BigDecimal share) {
    }

    /** What the sheet holds now. */
    public static SheetState of(Sheet sheet, List<Rate> rates, List<Person> people, List<Claim> claims, Views views,
            List<AncestorState> ancestors, List<PoolState> pools, List<CrewState> crews) {
        return new SheetState(
                sheet.getSettings().stream().map(s -> new SettingState(s.getKey(), s.getFrom(), s.getValue())).toList(),
                sheet.getPolicyDate(),
                statesOf(rates),
                people.stream().map(SheetState::stateOf).toList(),
                claims.stream().map(c -> new ClaimState(c.getId(), c.getPersonId(), c.getAmount(), c.getDate(),
                        c.getDueBy(), c.getNote(), c.getCorrects(), c.getListed(), c.getStatus(), c.getNumber(),
                        c.getSettledDue(), c.isStated(), c.getStatedDue(), c.getDeferredUntil(), c.getRemindedOn(), c.getRef(), c.getTags(),
                        c.isWaived(), c.getBoundLate(), c.isHeld(), c.getShares().stream()
                                .map(sh -> new ShareState(sh.getPersonId(), sh.getShare())).toList(),
                        c.getReviewAssigned(), c.isDueBySet())).toList(),
                new TreeMap<>(sheet.getPermissions()), sheet.getPeriodNumberingFrom(), sheet.getGraceDays(),
                sheet.isArchived(), views, sheet.getEntryDate(), sheet.getLastSettleBatch(),
                sheet.getPot(), sheet.getFiscalStartMonth(), sheet.getAlertThreshold(),
                sheet.getEscalateAfter(), sheet.getReviewSla(), sheet.getProofKinds(), ancestors, pools, crews,
                sheet.getDefaultGrade(), sheet.getScoreMid(), sheet.getScoreHigh(), sheet.isScoreLog(), sheet.getMaxOpen(), sheet.getPendingSla(),
                sheet.getPolicyVersions().stream().map(v -> new PolicyVersionState(v.getKey(), v.getFrom())).toList());
    }

    /** The rates as they stand now. */
    public static List<RateState> statesOf(List<Rate> rates) {
        return rates.stream().map(r -> new RateState(r.getGrade(), r.getFromDate(), r.getToDate(), r.getRate(),
                r.getClaimsAfter(), r.getEarlier().stream().map(v -> new EarlierRateState(v.getVersion(),
                        v.getRate(), v.getToDate(), v.getClaimsAfter())).toList())).toList();
    }

    /** The people as the screen read them then, each with the grade in force on {@code today}. */
    public List<PersonView> people(String sheetId, LocalDate today) {
        return views.people().stream().map(v -> person(v.id())
                .map(p -> new PersonView(v.id(), v.name(), p.rebuild(sheetId).gradeOn(today), v.grades(),
                        p.rebuild(sheetId).termsOn(today),
                        v.version(), v.retired(), v.retiredFrom(), v.held(), v.holds(), v.contact(), v.aliases(), v.clashing(), v.origin(),
                        v.pastRetirements(), v.tags(), v.undatedTerms(), v.datedTerms(), v.flag(), v.approver()))
                .orElse(v)).toList();
    }

    private static PersonState stateOf(Person p) {
        List<GradeFrom> grades = p.getGrades();
        return new PersonState(p.getId(), p.getName(),
                grades.stream().map(g -> new GradeState(g.getFromDate(), g.getGrade())).toList(), p.getTerms(),
                p.getRetiredFrom(), p.getHolds().stream().map(h -> new HoldState(h.getFromDate(), h.getToDate())).toList(),
                p.getPastRetirements().stream().map(r -> new RetirementState(r.getFromDate(), r.getToDate())).toList(),
                p.getDatedTerms().stream().map(t -> new TermsState(t.getFromDate(), t.getTerms())).toList());
    }

    /** The sheet as it stood then, unsaved, with {@code today} standing in for its today. */
    public Sheet rebuildSheet(Sheet live, LocalDate today) {
        Sheet sheet = new Sheet(live.getId(), live.getCreatedBy(), live.getCreatedAt(), today);
        settings.forEach(s -> sheet.restore(s.key(), s.from(), s.value()));
        if (policyVersions != null) {
            sheet.restorePolicyVersions(policyVersions.stream().map(v -> new PolicyVersion(v.key(), v.from())).toList());
        }
        sheet.setPolicyDate(policyDate);
        if (permissions != null) {
            permissions.forEach(sheet::setPermission);
        }
        sheet.setPeriodNumberingFrom(periodNumberingFrom);
        sheet.setGraceDays(graceDays);
        sheet.setEscalateAfter(escalateAfter);
        sheet.setReviewSla(reviewSla);
        sheet.setPendingSla(pendingSla);
        sheet.setMaxOpen(maxOpen);
        sheet.setScoreMid(scoreMid);
        sheet.setScoreHigh(scoreHigh);
        sheet.setScoreLog(Boolean.TRUE.equals(scoreLog));
        sheet.setEntryDate(entryDate);
        sheet.setPot(pot);
        sheet.setAlertThreshold(alertThreshold);
        sheet.setProofKinds(proofKinds);
        sheet.setDefaultGrade(defaultGrade);
        sheet.setFiscalStartMonth(fiscalStartMonth == null ? 1 : fiscalStartMonth);
        // The parent and the grades are not dated: they are the ones the sheet has now.
        sheet.setParentId(live.getParentId());
        sheet.addGradesOf(live);
        if (lastSettleBatch != null) {
            sheet.setLastSettleBatch(lastSettleBatch);
        }
        if (archived) {
            sheet.archive();
        }
        return sheet;
    }

    /** The rates as they stood then, unsaved. */
    public List<Rate> rebuildRates(String sheetId) {
        return rebuildRates(sheetId, rates);
    }

    private static List<Rate> rebuildRates(String sheetId, List<RateState> rates) {
        return rates.stream().map(r -> {
            Rate rate = new Rate(sheetId, r.grade(), r.from(), r.rate());
            rate.setToDate(r.to());
            rate.setClaimsAfter(r.claimsAfter());
            rate.setEarlier(r.earlier().stream()
                    .map(v -> new RateVersion(null, v.version(), v.rate(), v.to(), v.claimsAfter())).toList());
            return rate;
        }).toList();
    }

    /** The people as they stood then, unsaved, each with the id they had. */
    public List<Person> rebuildPeople(String sheetId) {
        return people.stream().map(p -> {
            Person person = p.rebuild(sheetId);
            person.rebuiltWithId(p.id());
            return person;
        }).toList();
    }

    /** The claims as they stood then, unsaved, each with the id it had, in the order the sheet listed them. */
    public List<Claim> rebuildClaims(String sheetId) {
        return claims.stream().map(c -> {
            Claim claim = c.rebuild(sheetId);
            claim.rebuiltWithId(c.id());
            return claim;
        }).toList();
    }

    /** The person with this id as they stood then; empty when they were not on the sheet yet. */
    public Optional<PersonState> person(long id) {
        return people.stream().filter(p -> p.id() == id).findFirst();
    }
}
