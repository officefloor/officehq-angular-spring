package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.crew.SheetCrew;
import net.officefloor.hq.app.crew.SheetCrewMember;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonPatron;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.pool.SheetPool;
import net.officefloor.hq.app.pool.SheetPoolMember;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.DatedSettings;
import net.officefloor.hq.app.settings.SheetDelegation;
import net.officefloor.hq.app.settings.SheetPermission;
import net.officefloor.hq.app.settings.SheetRole;
import net.officefloor.hq.app.settings.SheetSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.statement.Statement;

/** A sheet as it stood at the end of a date (see {@link SheetHistory#asOf}), that date standing in for
 * its today in every figure worked out from it. Its rows are detached copies, each list in the order the
 * store lists them: changing them changes nothing in the store. */
public class SheetAsOf implements RollupService.Basis {

    private final LocalDate date;
    private final Sheet sheet;
    private final List<Person> people;
    private final List<Claim> claims;
    private final List<ClaimShare> shares;
    private final List<Rate> rates;
    private final List<List<Rate>> inherited;
    private final List<SheetSetting> settings;
    private final List<Period> periods;
    private final List<Statement> statements;
    private final List<PersonPatron> patrons;
    private final List<PersonWatch> watches;
    private final List<SheetRole> roles;
    private final List<SheetPermission> permissions;
    private final List<SheetDelegation> delegations;
    private final List<SheetPool> pools;
    private final List<SheetPoolMember> poolMembers;
    private final List<SheetCrew> crews;
    private final List<SheetCrewMember> crewMembers;

    SheetAsOf(LocalDate date, Sheet sheet, Map<Class<?>, List<Object>> rows, List<List<Rate>> inherited) {
        this.date = date;
        this.inherited = List.copyOf(inherited);
        this.sheet = sheet;
        this.people = rowsOf(rows, Person.class).stream()
                .sorted(Comparator.comparing(Person::getPosition, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Person::getId))
                .toList();
        this.claims = rowsOf(rows, Claim.class).stream()
                .sorted(Comparator.comparingLong(Claim::getListed).thenComparing(Claim::getId).reversed())
                .toList();
        this.shares = rowsOf(rows, ClaimShare.class).stream().sorted(Comparator.comparing(ClaimShare::getId))
                .toList();
        this.rates = rowsOf(rows, Rate.class).stream()
                .sorted(Comparator.comparing(Rate::getGrade)
                        .thenComparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        this.settings = rowsOf(rows, SheetSetting.class).stream()
                .sorted(Comparator.comparing(SheetSetting::getFrom)).toList();
        this.periods = rowsOf(rows, Period.class).stream().sorted(Comparator.comparing(Period::getFrom)).toList();
        this.statements = rowsOf(rows, Statement.class).stream().sorted(Statement.ORDER)
                .toList();
        this.patrons = rowsOf(rows, PersonPatron.class).stream().sorted(Comparator.comparing(PersonPatron::getId))
                .toList();
        this.watches = rowsOf(rows, PersonWatch.class).stream().sorted(Comparator.comparing(PersonWatch::getId))
                .toList();
        this.roles = rowsOf(rows, SheetRole.class).stream().sorted(Comparator.comparing(SheetRole::getUser))
                .toList();
        this.permissions = rowsOf(rows, SheetPermission.class);
        this.delegations = rowsOf(rows, SheetDelegation.class).stream()
                .sorted(Comparator.comparing(SheetDelegation::getId)).toList();
        this.pools = rowsOf(rows, SheetPool.class).stream().sorted(Comparator.comparing(SheetPool::getId)).toList();
        this.poolMembers = rowsOf(rows, SheetPoolMember.class).stream()
                .sorted(Comparator.comparing(SheetPoolMember::getId)).toList();
        this.crews = rowsOf(rows, SheetCrew.class).stream().sorted(Comparator.comparing(SheetCrew::getId)).toList();
        this.crewMembers = rowsOf(rows, SheetCrewMember.class).stream()
                .sorted(Comparator.comparing(SheetCrewMember::getId)).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> rowsOf(Map<Class<?>, List<Object>> rows, Class<T> type) {
        return List.copyOf((List<T>) rows.getOrDefault(type, List.of()));
    }

    /** Whether the sheet was archived then. */
    public boolean archived() {
        return sheet != null && sheet.isArchived();
    }

    /** The number of the sheet's last settle batch then; null when it had had none. */
    public Integer lastSettleBatch() {
        return sheet == null ? null : sheet.getLastSettleBatch();
    }

    /** The policy date as it was set then; null when it was the sheet's today. */
    public LocalDate policyDate() {
        return sheet == null ? null : sheet.getPolicyDate();
    }

    /** The period numbering from then; null when it was off. */
    public LocalDate periodNumberingFrom() {
        return sheet == null ? null : sheet.getPeriodNumberingFrom();
    }

    /** The id of the sheet's parent then; null when it had none. */
    public String parentId() {
        return sheet == null ? null : sheet.getParentId();
    }

    /** The sheet's rates then, then its parent's as they stood then, and so on up to
     * {@link RateChain#LEVELS_UP} levels up: each by grade, then by from-date. */
    public List<List<Rate>> rateLevels() {
        List<List<Rate>> levels = new ArrayList<>();
        levels.add(rates);
        levels.addAll(inherited);
        return levels;
    }

    /** The pot then; null when there was none. */
    public BigDecimal pot() {
        return sheet == null ? null : sheet.getPot();
    }

    /** The alert threshold then; null when it was off. */
    public BigDecimal alertThreshold() {
        return sheet == null ? null : sheet.getAlertThreshold();
    }

    /** The entry date then; null when it was off. */
    public LocalDate entryDate() {
        return sheet == null ? null : sheet.getEntryDate();
    }

    /** The fiscal start month then; 1 before the sheet was made. */
    public int fiscalStartMonth() {
        return sheet == null ? 1 : sheet.getFiscalStartMonth();
    }

    /** The mid score threshold then; null when it was not set. */
    public Integer scoreMid() {
        return sheet == null ? null : sheet.getScoreMid();
    }

    /** The high score threshold then; null when it was not set. */
    public Integer scoreHigh() {
        return sheet == null ? null : sheet.getScoreHigh();
    }

    /** Whether the score log was on then; off before the sheet was made. */
    public boolean scoreLog() {
        return sheet != null && sheet.isScoreLog();
    }

    /** The escalation gap then; null when it was off. */
    public Integer escalateAfter() {
        return sheet == null ? null : sheet.getEscalateAfter();
    }

    /** The proof kinds then; null when none were listed. */
    public String proofKinds() {
        return sheet == null ? null : sheet.getProofKinds();
    }

    /** The default grade as set then; null when it was A. */
    public String defaultGrade() {
        return sheet == null ? null : sheet.getDefaultGrade();
    }

    /** The review SLA then; null when there was none. */
    public Integer reviewSla() {
        return sheet == null ? null : sheet.getReviewSla();
    }

    /** The pending SLA then; null when there was none. */
    public Integer pendingSla() {
        return sheet == null ? null : sheet.getPendingSla();
    }

    /** The most OPEN or PENDING claims a person could have at once; null when there was no maximum. */
    public Integer maxOpen() {
        return sheet == null ? null : sheet.getMaxOpen();
    }

    /** The grace days then; null when they were off. */
    public Integer graceDays() {
        return sheet == null ? null : sheet.getGraceDays();
    }

    /** The people then, in list order. */
    public List<Person> people() {
        return people;
    }

    /** The person as they stood then; empty when they were not yet on the sheet. */
    public Optional<Person> person(long personId) {
        return people.stream().filter(p -> p.getId() == personId).findFirst();
    }

    /** The claims then, newest first. */
    public List<Claim> claims() {
        return claims;
    }

    /** The person's claims as they stood then, newest first. */
    public List<Claim> claimsOf(long personId) {
        return claims.stream().filter(c -> c.getPersonId() == personId).toList();
    }

    /** The shares of the claims then, in the order they were given. */
    @Override
    public List<ClaimShare> shares() {
        return shares;
    }

    /** The shares of the claim then, in the order they were given. */
    public List<ClaimShare> sharesOf(long claimId) {
        return shares.stream().filter(s -> s.getClaimId() == claimId).toList();
    }

    /** Of {@code log}, the lines written by the end of the date (each is dated the sheet's today then). */
    public List<ClaimLog> logged(List<ClaimLog> log) {
        return log.stream().filter(l -> !l.getDate().isAfter(date)).toList();
    }

    /** The rates then, by grade and from-date. */
    public List<Rate> rates() {
        return rates;
    }

    /** The periods then, by from-date. */
    public List<Period> periods() {
        return periods;
    }

    /** The statements taken by then, oldest first. */
    public List<Statement> statements() {
        return statements;
    }

    /** The patrons named by then, oldest first. */
    public List<PersonPatron> patrons() {
        return patrons;
    }

    /** The ids of the people the user watched then, in the order they started. */
    public List<Long> watchedBy(String user) {
        return watches.stream().filter(w -> w.getUser().equals(user)).map(PersonWatch::getPersonId).toList();
    }

    /** The roles given by then, by user name. */
    public List<SheetRole> roles() {
        return roles;
    }

    /** The permission matrix entries set by then. */
    public List<SheetPermission> permissions() {
        return permissions;
    }

    /** The delegations made by then, oldest first. */
    public List<SheetDelegation> delegations() {
        return delegations;
    }

    /** The pools then, in the order they were added. */
    public List<SheetPool> pools() {
        return pools;
    }

    /** The pools' memberships then, in the order they were added. */
    public List<SheetPoolMember> poolMembers() {
        return poolMembers;
    }

    /** The crews then, in the order they were added. */
    public List<SheetCrew> crews() {
        return crews;
    }

    /** The crews' memberships then, in the order they were added. */
    public List<SheetCrewMember> crewMembers() {
        return crewMembers;
    }

    /** Every value recorded for the key by then, oldest from-date first. */
    public List<SheetSetting> history(String key) {
        return settings.stream().filter(s -> s.getKey().equals(key)).toList();
    }

    @Override
    public LocalDate today() {
        return date;
    }

    @Override
    public LocalDate policyDateInForce() {
        LocalDate policyDate = policyDate();
        return policyDate == null ? date : policyDate;
    }

    @Override
    public Optional<Rate> rateInForce(String grade, LocalDate on) {
        return RateChain.inForce(rateLevels(), grade, on);
    }

    @Override
    public Optional<Rate> rateFor(Claim claim, String grade, LocalDate on) {
        return RateChain.inForce(rateLevels(), grade, on, claim.addedMark());
    }

    @Override
    public BigDecimal floorOn(LocalDate on) {
        return valueOn(DatedSettings.FLOOR, on).map(BigDecimal::new).orElse(DatedSettings.FLOOR_START);
    }

    @Override
    public BigDecimal latePctOn(LocalDate on) {
        return valueOn(DatedSettings.LATE_PCT, on).map(BigDecimal::new).orElse(DatedSettings.LATE_PCT_START);
    }

    @Override
    public BigDecimal cap() {
        return valueOn(DatedSettings.CAP, date).map(BigDecimal::new).orElse(null);
    }

    @Override
    public Optional<Claim> claimNumbered(Long personId, String number) {
        return claims.stream()
                .filter(c -> c.getPersonId().equals(personId) && Objects.equals(c.getNumber(), number))
                .findFirst();
    }

    @Override
    public Optional<Person> personOf(Long personId) {
        return person(personId);
    }

    @Override
    public Integer graceOn(String grade, LocalDate on) {
        return valueOn(DatedSettings.gradeGraceKey(grade), on).map(Integer::valueOf).orElse(null);
    }

    @Override
    public RoundingMode roundingOn(LocalDate on) {
        return valueOn(DatedSettings.ROUNDING, on).map(DatedSettings::roundingMode).orElse(null);
    }

    /** The key's value in force on the date (see {@link DatedSettings}); empty when none is recorded then,
     * or the one recorded is "none". */
    public Optional<String> valueOn(String key, LocalDate on) {
        return settings.stream()
                .filter(s -> s.getKey().equals(key) && !s.getFrom().isAfter(on))
                .max(Comparator.comparing(SheetSetting::getFrom))
                .map(SheetSetting::getValue);
    }
}
