package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed, due-total and overdue-total,
 * and the sheet's claimed-total, due-total and overdue-total. A change takes a {@link #snapshot} before it and passes it
 * to {@link #recordChanges} after its own audit lines, which then records a ROLLUP line for each
 * person whose figures moved (people in list order; a person new to the sheet
 * counts as moved) and a TOTALS line when any of the sheet's totals moved, then an ALERT line for
 * each person whose due-total went from at or below the sheet's alert threshold (or n/a, or new) to
 * above it.
 */
@Service
public class RollupService {

    /** A person's figures: claimed, due-total held to the sheet's cap (null: n/a), and
     *  overdue-total (the dues their overdue counting claims show, n/a ones skipped, not capped);
     *  with their grade on the sheet's today, whether they have a retirement date, and their aliases;
     *  and the dues (n/a ones skipped) their counting SETTLED claims that correct nothing show, and how many of their
     *  counting claims are overdue. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal,
            String grade, boolean retired, List<String> aliases, BigDecimal settled, int overdueCount) {
        /** The score: the settled figures over 100, less 5 for each overdue claim, rounded half to
         *  even to a whole number. */
        BigDecimal score() {
            return settled.subtract(BigDecimal.valueOf(500L * overdueCount))
                    .divide(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_EVEN);
        }

        boolean sameAs(Figures other) {
            return other != null && claimed.compareTo(other.claimed) == 0
                    && Objects.equals(text(dueTotal), text(other.dueTotal))
                    && overdueTotal.compareTo(other.overdueTotal) == 0;
        }
    }

    /** The sheet's figures at one moment: each person's, by person id in list order; and how many
     *  of its claims are in each status. */
    public record Snapshot(Map<Long, Figures> people, Map<ClaimStatus, Integer> statuses) {
        Snapshot(Map<Long, Figures> people) {
            this(people, Map.of());
        }

        /** The due-totals (n/a skipped) of the people not retired, added up per grade. */
        Map<String, BigDecimal> dueByGrade() {
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            people.values().stream().filter(f -> !f.retired()).forEach(f -> out.merge(f.grade(),
                    f.dueTotal() == null ? zero() : f.dueTotal(), BigDecimal::add));
            return out;
        }

        /** How many of everyone's counting claims are overdue. */
        int overdueCount() {
            return people.values().stream().mapToInt(Figures::overdueCount).sum();
        }

        BigDecimal claimedTotal() {
            return people.values().stream().map(Figures::claimed).reduce(zero(), BigDecimal::add);
        }

        /** Everyone's due-total added up, skipping n/a, trimmed. */
        BigDecimal dueTotal() {
            return trimmedSum(people.values().stream().map(Figures::dueTotal).filter(Objects::nonNull).toList());
        }

        /** Everyone's overdue-total added up, trimmed. */
        BigDecimal overdueTotal() {
            return trimmedSum(people.values().stream().map(Figures::overdueTotal).toList());
        }

        /** Each person's names and figures, in list order. */
        List<Member> members() {
            return people.values().stream().map(f -> {
                List<String> names = new ArrayList<>(List.of(f.name()));
                names.addAll(f.aliases());
                return new Member(names, f.dueTotal(), f.overdueTotal());
            }).toList();
        }
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are
     *  three or more. */
    static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(zero(), BigDecimal::add);
        if (figures.size() < 3) {
            return sum;
        }
        BigDecimal max = figures.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = figures.stream().min(BigDecimal::compareTo).orElseThrow();
        return sum.subtract(max).subtract(min);
    }

    private static final List<String> GRADES = List.of("A", "B", "C");

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimLogRepository log;
    private final RateChain rateChain;
    private final SheetRepository sheets;
    private final HistoryService history;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, ClaimLogRepository log,
            RateChain rateChain, SheetRepository sheets, HistoryService history, Audit audit) {
        this.history = history;
        this.rateChain = rateChain;
        this.people = people;
        this.claims = claims;
        this.log = log;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures now, as the store (and this transaction) holds them. */
    public Snapshot snapshot(String sheetId) {
        Map<Long, Person> sheetPeople = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> sheetPeople.put(p.getId(), p));
        return snapshotOf(sheets.findById(sheetId), sheetPeople, claims.findBySheetIdOrderByIdDesc(sheetId),
                rateChain.levels(sheetId));
    }

    /** The sheet's figures as it stood at the end of {@code on}, rebuilt from its history (and its
     *  ancestors' rates from theirs), with {@code on} as its today. */
    public Snapshot snapshotAsOf(String sheetId, LocalDate on) {
        HistoryService.AsOf then = history.asOf(sheetId, on);
        List<Claim> sheetClaims = then.claims().entrySet().stream()
                .sorted(Map.Entry.<Long, Claim>comparingByKey().reversed())
                .map(Map.Entry::getValue)
                .toList();
        RateChain.Levels sheetRates = new RateChain.Levels(sheetId, then.rates(),
                rateChain.ancestors(sheetId).stream().map(a -> history.asOf(a, on).rates()).toList());
        return snapshotOf(Optional.of(then.sheet()), then.people(), sheetClaims, sheetRates);
    }

    /** The figures of a sheet with these settings (none: the defaults, today the real date), people
     *  (by id, in list order), claims (newest first) and rates. */
    private static Snapshot snapshotOf(Optional<Sheet> sheet, Map<Long, Person> sheetPeople, List<Claim> sheetClaims,
            RateChain.Levels sheetRates) {
        LocalDate today = sheet.map(Sheet::getToday).orElseGet(LocalDate::now);
        Function<LocalDate, BigDecimal> floor = floorOf(sheet);
        BigDecimal cap = sheet.map(Sheet::getCap).orElse(null);
        LateCharge late = LateCharge.of(sheet);
        Map<Long, Person> held = new LinkedHashMap<>();
        sheetPeople.forEach((id, p) -> {
            if (p.isHeld() || p.isRetired()) {
                held.put(id, p);
            }
        });
        Function<Claim, RoundingMode> rounding = roundingOf(sheet, sheetClaims);
        Map<Long, List<Claim>> counted = new LinkedHashMap<>();
        Map<Long, BigDecimal> claimedBy = new LinkedHashMap<>();
        for (Claim c : sheetClaims) {
            if (counts(c, held, today)) {
                counted.computeIfAbsent(c.getPersonId(), id -> new ArrayList<>()).add(c);
            }
            if (countsClaimed(c, held.get(c.getPersonId()), today) && !isCorrection(c)) {
                claimedBy.merge(c.getPersonId(), c.getAmount(), BigDecimal::add);
            }
        }
        Map<Long, List<BigDecimal>> parts = sharedParts(counted, sheetPeople, sheetRates, floor, late, rounding);
        Map<Long, Figures> out = new LinkedHashMap<>();
        sheetPeople.forEach((id, p) -> {
            List<Claim> theirs = counted.getOrDefault(id, List.of());
            List<BigDecimal> shared = parts.getOrDefault(id, List.of());
            BigDecimal claimed = zero().add(claimedBy.getOrDefault(id, zero()));
            List<Claim> overdueClaims = theirs.stream()
                    .filter(c -> c.getStatus().isOpen() && late.isPast(c, p))
                    .toList();
            BigDecimal overdue = overdueClaims.stream()
                    .map(c -> shownDueOf(c, p, sheetRates, floor, late, rounding))
                    .filter(Objects::nonNull)
                    .reduce(zero(), BigDecimal::add);
            BigDecimal settled = theirs.stream()
                    .filter(c -> c.getStatus() == ClaimStatus.SETTLED
                            && (c.getCorrects() == null || c.getCorrects().isBlank()))
                    .map(c -> shownDueOf(c, p, sheetRates, floor, late, rounding))
                    .filter(Objects::nonNull)
                    .reduce(zero(), BigDecimal::add);
            int overdueCount = (int) overdueClaims.stream().filter(c -> !c.isDeferredOn(today)).count();
            out.put(id, new Figures(p.getName(), claimed,
                    Sheet.capped(dueTotalOf(theirs, p, sheetRates, today, floor, late, rounding, shared), cap), overdue,
                    p.gradeOn(today), p.isRetired(), List.copyOf(p.getAliases()), settled, overdueCount));
        });
        Map<ClaimStatus, Integer> statuses = new LinkedHashMap<>();
        sheetClaims.stream().map(Claim::getStatus).filter(Objects::nonNull)
                .forEach(st -> statuses.merge(st, 1, Integer::sum));
        return new Snapshot(out, statuses);
    }

    /** The parts of their counting claims' dues (n/a ones skipped) the people on the sheet share,
     *  by person id: each holder's part (the due times their share over 100, rounded half to even),
     *  and the same parts taken off the claim's own person. */
    private static Map<Long, List<BigDecimal>> sharedParts(Map<Long, List<Claim>> counted, Map<Long, Person> sheetPeople,
            RateChain.Levels sheetRates, Function<LocalDate, BigDecimal> floor, LateCharge late,
            Function<Claim, RoundingMode> rounding) {
        Map<Long, List<BigDecimal>> out = new LinkedHashMap<>();
        counted.forEach((ownerId, theirs) -> {
            Person owner = sheetPeople.get(ownerId);
            if (owner == null) {
                return;
            }
            for (Claim c : theirs) {
                if (c.getShares().isEmpty()) {
                    continue;
                }
                BigDecimal due = shownDueOf(c, owner, sheetRates, floor, late, rounding);
                if (due == null) {
                    continue;
                }
                c.getShares().stream().filter(sh -> sheetPeople.containsKey(sh.getPersonId())).forEach(sh -> {
                    BigDecimal part = sh.partOf(due);
                    out.computeIfAbsent(sh.getPersonId(), k -> new ArrayList<>()).add(part);
                    out.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(part.negate());
                });
            }
        });
        return out;
    }

    /** One line per grade (A, B, C) comparing the sheet, as it is now or as it stood at the end of
     *  {@code asOf} when given, with another sheet as it is now: the grade, then each sheet's summary
     *  due-total for it (the due-totals of the people not retired on it, n/a skipped), zero for a
     *  grade a sheet has no one on; no lines when the other sheet does not exist. */
    public List<String> compare(String sheetId, String otherId, LocalDate asOf) {
        if (sheets.findById(otherId).isEmpty()) {
            return List.of();
        }
        Map<String, BigDecimal> mine = (asOf == null ? snapshot(sheetId) : snapshotAsOf(sheetId, asOf)).dueByGrade();
        Map<String, BigDecimal> theirs = snapshot(otherId).dueByGrade();
        return GRADES.stream().map(g -> g + " " + mine.getOrDefault(g, zero()).setScale(2).toPlainString() + " "
                + theirs.getOrDefault(g, zero()).setScale(2).toPlainString()).toList();
    }

    /** Figures added up over sheets: how many people (retired ones too), the due-totals and
     *  overdue-totals each sheet shows, per grade the due-totals of the people on it (retired ones
     *  left out), per status how many claims are in it, and each person's names and figures (so a
     *  tree's figures can be trimmed over all its people, and people matched by name counted once),
     *  and how many counting claims are overdue. */
    public record Consolidated(int people, BigDecimal dueTotal, BigDecimal overdueTotal,
            Map<String, BigDecimal> gradeDueTotals, Map<ClaimStatus, Integer> statusCounts, List<Member> members,
            int overdueCount) {
    }

    /** A person as the consolidated figures take them: their name and aliases, due-total held to
     *  the cap (null: n/a) and overdue-total. */
    public record Member(List<String> names, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** The sheet's own figures as it is now, or as it stood at the end of {@code asOf} when given:
     *  how many people (retired ones too), and the due-total and overdue-total it shows. */
    public Consolidated own(String sheetId, LocalDate asOf) {
        Snapshot figures = asOf == null ? snapshot(sheetId) : snapshotAsOf(sheetId, asOf);
        return new Consolidated(figures.people().size(), figures.dueTotal(), figures.overdueTotal(),
                figures.dueByGrade(), figures.statuses(), figures.members(), figures.overdueCount());
    }

    /** The figures of the sheet's descendants (its children, theirs, and so on), each sheet's as
     *  it is now, or as it stood at the end of {@code asOf} when given, added up; all zero for a
     *  sheet with no children. */
    public Consolidated descendants(String sheetId, LocalDate asOf) {
        int count = 0;
        BigDecimal due = zero();
        BigDecimal overdue = zero();
        Map<String, BigDecimal> grades = new LinkedHashMap<>();
        Map<ClaimStatus, Integer> statuses = new LinkedHashMap<>();
        List<Member> members = new ArrayList<>();
        int overdueCount = 0;
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(sheetId));
        while (!todo.isEmpty()) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(todo.poll())) {
                if (!seen.add(child.getId())) {
                    continue;
                }
                todo.add(child.getId());
                Snapshot figures = asOf == null ? snapshot(child.getId()) : snapshotAsOf(child.getId(), asOf);
                count += figures.people().size();
                due = due.add(figures.dueTotal());
                overdue = overdue.add(figures.overdueTotal());
                figures.dueByGrade().forEach((g, d) -> grades.merge(g, d, BigDecimal::add));
                figures.statuses().forEach((st, n) -> statuses.merge(st, n, Integer::sum));
                members.addAll(figures.members());
                overdueCount += figures.overdueCount();
            }
        }
        return new Consolidated(count, due, overdue, grades, statuses, members, overdueCount);
    }

    /** Each person's due-total for the period from {@code from} to {@code to}, inclusive, by person
     *  id, for everyone on the sheet: the dues (n/a ones skipped) their claims due inside it show
     *  now, counting each by the status its log gives it at the end of {@code to} (OPEN and SETTLED
     *  ones count; one added after then does not; OPEN ones within a hold of their person, unbound,
     *  or deferred, left out). */
    public Map<Long, BigDecimal> dueByPersonBetween(String sheetId, LocalDate from, LocalDate to) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElseGet(LocalDate::now);
        Function<LocalDate, BigDecimal> floor = floorOf(sheet);
        LateCharge late = LateCharge.of(sheet);
        RateChain.Levels sheetRates = rateChain.levels(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Person> held = heldPeople(sheetPeople);
        Map<Long, Person> byId = sheetPeople.stream().collect(Collectors.toMap(Person::getId, p -> p));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Function<Claim, RoundingMode> rounding = roundingOf(sheet, sheetClaims);
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (Person p : sheetPeople) {
            out.put(p.getId(), zero());
        }
        Map<Long, ClaimStatus> statusAtEnd = statusesOn(sheetId, to);
        sheetClaims.stream()
                .filter(c -> countsAt(c, statusAtEnd.containsKey(c.getId()) ? statusAtEnd.get(c.getId()) : c.getStatus(),
                        held, today))
                .filter(c -> !c.getDueBy().isBefore(from) && !c.getDueBy().isAfter(to))
                .filter(c -> byId.containsKey(c.getPersonId()))
                .forEach(c -> {
                    BigDecimal due = shownDueOf(c, byId.get(c.getPersonId()), sheetRates, floor, late, rounding);
                    if (due != null) {
                        out.merge(c.getPersonId(), due, BigDecimal::add);
                    }
                });
        return out;
    }

    /** Each person's carried figure for the period from {@code from} to {@code to}, by person id,
     *  for everyone on the sheet: the dues (n/a ones skipped) their claims show now whose due-by
     *  falls in another of the sheet's periods ({@code periodEndOn} gives the to-date of the period
     *  containing a date, null outside every one) ending before {@code from}, and that were still
     *  OPEN or PENDING at the end of {@code to} by their log, counted as {@link #dueByPersonBetween}
     *  counts them. */
    public Map<Long, BigDecimal> carriedByPersonInto(String sheetId, LocalDate from, LocalDate to,
            Function<LocalDate, LocalDate> periodEndOn) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElseGet(LocalDate::now);
        Function<LocalDate, BigDecimal> floor = floorOf(sheet);
        LateCharge late = LateCharge.of(sheet);
        RateChain.Levels sheetRates = rateChain.levels(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Person> held = heldPeople(sheetPeople);
        Map<Long, Person> byId = sheetPeople.stream().collect(Collectors.toMap(Person::getId, p -> p));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Function<Claim, RoundingMode> rounding = roundingOf(sheet, sheetClaims);
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (Person p : sheetPeople) {
            out.put(p.getId(), zero());
        }
        Map<Long, ClaimStatus> statusAtEnd = statusesOn(sheetId, to);
        sheetClaims.stream()
                .filter(c -> byId.containsKey(c.getPersonId()))
                .filter(c -> {
                    LocalDate ownEnd = periodEndOn.apply(c.getDueBy());
                    return ownEnd != null && ownEnd.isBefore(from);
                })
                .filter(c -> {
                    ClaimStatus status = statusAtEnd.containsKey(c.getId()) ? statusAtEnd.get(c.getId()) : c.getStatus();
                    return status != null && status.isOpen() && countsAt(c, status, held, today);
                })
                .forEach(c -> {
                    BigDecimal due = shownDueOf(c, byId.get(c.getPersonId()), sheetRates, floor, late, rounding);
                    if (due != null) {
                        out.merge(c.getPersonId(), due, BigDecimal::add);
                    }
                });
        return out;
    }

    /** A statement's figures for a person: their claims that count, each with the figure a statement
     *  binds it to (null: n/a), and their due-total held to the cap (null: n/a). */
    public record Stated(Map<Claim, BigDecimal> counted, BigDecimal dueTotal) {
    }

    /** The statement of a person on a sheet that is not stored (one rebuilt as it stood at the end of
     *  a date, its today that date): counting their claims among {@code sheetClaims}, by the rates,
     *  floor, late charge and cap of the sheet and the person's holds and retirement, as the live
     *  totals do. */
    public static Stated statedOn(Sheet sheet, Person person, Long personId, List<Claim> sheetClaims,
            RateChain.Levels sheetRates) {
        Optional<Sheet> held = Optional.of(sheet);
        LocalDate today = sheet.getToday();
        Function<LocalDate, BigDecimal> floor = floorOf(held);
        LateCharge late = LateCharge.of(held);
        Function<Claim, RoundingMode> rounding = roundingOf(held, sheetClaims);
        Map<Claim, BigDecimal> counted = new LinkedHashMap<>();
        sheetClaims.stream()
                .filter(c -> c.getPersonId().equals(personId) && countsFor(c, person, today))
                .forEach(c -> counted.put(c, shownDueOf(c, person, sheetRates, floor, late, rounding)));
        BigDecimal dueTotal = dueTotalOf(List.copyOf(counted.keySet()), person, sheetRates, today, floor, late, rounding, List.of());
        return new Stated(counted, Sheet.capped(dueTotal, sheet.getCap()));
    }

    /** The due a claim of a person shows on a sheet that is not stored (one rebuilt as it stood at
     *  the end of a date, its today that date), among {@code sheetClaims}, by the rates, floor and
     *  late charge of that sheet, as the live figures show it (null: n/a). */
    public static BigDecimal dueOn(Sheet sheet, Person person, Claim claim, List<Claim> sheetClaims,
            RateChain.Levels sheetRates) {
        Optional<Sheet> held = Optional.of(sheet);
        return shownDueOf(claim, person, sheetRates, floorOf(held), LateCharge.of(held), roundingOf(held, sheetClaims));
    }

    /** Record, once the transaction commits, a SCORE line for each person (in list order) with
     *  their score in {@code before}. */
    public void recordScores(String sheetId, String user, Snapshot before) {
        before.people().values().forEach(p -> audit.recordOnCommit("SCORE sheet=" + sheetId + " by=" + user
                + " name=" + p.name() + " score=" + p.score().toPlainString()));
    }

    /** Keep on each person a snapshot line for {@code day} (the day being left) with their
     *  due-total in {@code before}, and record, once the transaction commits, a SNAPSHOT line for
     *  each (in list order). */
    public void recordSnapshot(String sheetId, String user, LocalDate day, Snapshot before) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, p -> p));
        before.people().forEach((id, p) -> {
            Person person = byId.get(id);
            if (person != null) {
                person.addSnapshot(day, p.dueTotal() == null ? null : p.dueTotal().setScale(2));
            }
            audit.recordOnCommit("SNAPSHOT sheet=" + sheetId + " by=" + user
                    + " name=" + p.name() + " due-total=" + text(p.dueTotal()));
        });
        people.flush();
    }

    /** Record, once the transaction commits, a ROLLUP line for each person whose figures moved
     *  since {@code before}, then a TOTALS line when the sheet's totals moved. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        recordChanges(sheetId, user, before, false);
    }

    private void recordChanges(String sheetId, String user, Snapshot before, boolean totals) {
        Snapshot after = snapshot(sheetId);
        after.people().forEach((id, now) -> {
            if (!now.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + now.name()
                        + " claimed=" + text(now.claimed()) + " due-total=" + text(now.dueTotal()) + " overdue-total=" + text(now.overdueTotal()));
            }
        });
        if (totals || after.claimedTotal().compareTo(before.claimedTotal()) != 0
                || after.dueTotal().compareTo(before.dueTotal()) != 0
                || after.overdueTotal().compareTo(before.overdueTotal()) != 0) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total="
                    + text(after.claimedTotal()) + " due-total=" + text(after.dueTotal())
                    + " overdue-total=" + text(after.overdueTotal()));
        }
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold != null) {
            after.people().forEach((id, now) -> {
                Figures was = before.people().get(id);
                if (above(now.dueTotal(), threshold) && (was == null || !above(was.dueTotal(), threshold))) {
                    audit.recordOnCommit("ALERT sheet=" + sheetId + " by=" + user + " name=" + now.name()
                            + " due-total=" + text(now.dueTotal()));
                }
            });
        }
    }

    /** Whether a due-total (null: n/a) is above the alert threshold. */
    private static boolean above(BigDecimal dueTotal, BigDecimal threshold) {
        return dueTotal != null && dueTotal.compareTo(threshold) > 0;
    }

    /** Record, once the transaction commits, a ROLLUP line for every person (in list order), then
     *  the sheet's TOTALS line, as the sheet's figures are now. */
    public void recordAll(String sheetId, String user) {
        recordChanges(sheetId, user, new Snapshot(Map.of()), true);
    }

    /** Whether a claim counts toward due totals: only OPEN (or PENDING) and SETTLED ones
     *  do, but not (unless bound by a statement) any open one of a person (by id in {@code held})
     *  whose retirement date the sheet's
     *  today has reached, nor one deferred on the sheet's today. */
    private static boolean counts(Claim c, Map<Long, Person> held, LocalDate today) {
        return countsFor(c, held.get(c.getPersonId()), today);
    }

    /** Whether a claim of the person (null: one with neither a hold nor a retirement) counts, as
     *  {@link #counts} has it. */
    private static boolean countsFor(Claim c, Person person, LocalDate today) {
        if (c.getStatus() == ClaimStatus.SETTLED) {
            return true;
        }
        if (!c.getStatus().isOpen() || c.isDeferredOn(today)) {
            return false;
        }
        return c.isBound() || !leftOut(person, c, today);
    }

    /** Whether a claim counts toward claimed: as {@link #countsFor} has it. */
    private static boolean countsClaimed(Claim c, Person person, LocalDate today) {
        if (c.getStatus() == ClaimStatus.SETTLED) {
            return true;
        }
        if (!c.getStatus().isOpen() || c.isDeferredOn(today)) {
            return false;
        }
        return c.isBound() || person == null || !person.retiredOn(today);
    }

    /** Whether a claim counts toward a period's due-total, taken in {@code status} (the one it had
     *  at the period's end; null when it was not yet added then): as {@link #counts} has it. */
    private static boolean countsAt(Claim c, ClaimStatus status, Map<Long, Person> held, LocalDate today) {
        if (status == null) {
            return false;
        }
        if (status == ClaimStatus.SETTLED) {
            return true;
        }
        if (!status.isOpen() || c.isDeferredOn(today)) {
            return false;
        }
        // a reached retirement leaves out a claim still open now, as the live due-total does
        return !c.getStatus().isOpen() || c.isBound() || !leftOut(held.get(c.getPersonId()), c, today);
    }

    /** The status each of the sheet's claims had at the end of {@code on}, by claim id, as their
     *  status logs record it (null for a claim not yet added then); a claim with no log at all is
     *  left out, taken in its status now. */
    private Map<Long, ClaimStatus> statusesOn(String sheetId, LocalDate on) {
        Map<Long, ClaimStatus> out = new LinkedHashMap<>();
        for (ClaimLog line : log.findBySheetIdOrderByIdAsc(sheetId)) {
            ClaimStatus before = out.get(line.getClaimId());
            out.put(line.getClaimId(), line.getOn().isAfter(on) ? before : line.getStatus());
        }
        return out;
    }

    /** Whether an unbound open claim of the person (null: one with neither a hold nor a retirement)
     *  is left out of the live totals: their retirement date reached on the sheet's today. A hold
     *  (the claim's or its person's) leaves nothing out; it only takes the person out of pool cuts. */
    private static boolean leftOut(Person person, Claim c, LocalDate today) {
        return person != null && person.retiredOn(today);
    }

    /** The people with a hold or a retirement date, by id. */
    private static Map<Long, Person> heldPeople(List<Person> sheetPeople) {
        return sheetPeople.stream().filter(p -> p.isHeld() || p.isRetired()).collect(Collectors.toMap(Person::getId, p -> p));
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped) and
     *  of their {@code shared} parts of shared claims' dues; null (n/a) when their grade in force on
     *  the sheet's today has no rate then, unless a claim shows a due (a fixed or bound one, or one at
     *  the rate of the grade they had on its date) or they hold a part. */
    private static BigDecimal dueTotalOf(List<Claim> counted, Person person, RateChain.Levels sheetRates, LocalDate today,
            Function<LocalDate, BigDecimal> floor, LateCharge late, Function<Claim, RoundingMode> rounding,
            List<BigDecimal> shared) {
        List<BigDecimal> dues = new ArrayList<>(counted.stream()
                .map(c -> shownDueOf(c, person, sheetRates, floor, late, rounding))
                .filter(Objects::nonNull)
                .toList());
        dues.addAll(shared);
        if (inForce(person, sheetRates.inForce(), today).isEmpty() && dues.isEmpty()) {
            return null;
        }
        return dues.stream().reduce(zero(), BigDecimal::add);
    }

    /** The due a claim shows: the due fixed when it was settled, else the one a statement bound it
     *  to (null: n/a), late charge included, else its amount times the rate in force on its date of its person's grade
     *  then (as the claim applies the rates: not a forward-only one saved after it was added), rounded as its rounding policy has it, raised to the sheet's floor in force on its date (a
     *  correction's never is), with the late charge worked out on that floored figure on top. */
    private static BigDecimal shownDueOf(Claim c, Person person, RateChain.Levels sheetRates, Function<LocalDate, BigDecimal> floor,
            LateCharge late, Function<Claim, RoundingMode> rounding) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        if (c.isBound()) {
            return c.getStatedDue();
        }
        RoundingMode mode = rounding.apply(c);
        BigDecimal figure = figureOf(c, person, sheetRates, mode);
        BigDecimal floored = figure == null || c.isCorrection() ? figure : figure.max(floor.apply(c.getDate()));
        return late.addTo(c, person, floored, mode);
    }

    /** A claim's rate figure before any late charge or floor. */
    private static BigDecimal figureOf(Claim c, Person person, RateChain.Levels sheetRates, RoundingMode mode) {
        return inForce(person, sheetRates.forClaim(c.getAdded()), c.getDate())
                .map(r -> c.getAmount().multiply(r.getPct()).divide(BigDecimal.valueOf(100), 2, mode))
                .orElse(null);
    }

    /** How each claim's live figures round: by the sheet's policy date against its date, or for a
     *  correction the date of the claim (of the same person) it corrects. */
    private static Function<Claim, RoundingMode> roundingOf(Optional<Sheet> sheet, List<Claim> sheetClaims) {
        Rounding rounding = Rounding.of(sheet);
        Map<String, LocalDate> numbered = new LinkedHashMap<>();
        for (Claim c : sheetClaims) {
            if (c.getNumber() != null) {
                numbered.putIfAbsent(c.getPersonId() + "/" + c.getNumber(), c.getDate());
            }
        }
        return c -> rounding.on(c.getCorrects() == null ? c.getDate()
                : numbered.getOrDefault(c.getPersonId() + "/" + c.getCorrects(), c.getDate()));
    }

    /** The rate in force on a date for the person's grade in force then; none when that one's
     *  to-date has passed by then. */
    private static Optional<Rate> inForce(Person person, List<Rate> sheetRates, LocalDate on) {
        String grade = person.gradeOn(on);
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade) && r.appliesOn(on))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.runsThrough(on));
    }

    /** The sheet's floor in force on each date. */
    private static Function<LocalDate, BigDecimal> floorOf(Optional<Sheet> sheet) {
        return on -> sheet.map(s -> s.floorOn(on)).orElse(Sheet.DEFAULT_FLOOR);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    /** A figure as the screen shows it: two decimals, or n/a. */
    public static String text(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2).toPlainString();
    }

    /** Whether a claim corrects another: claimed leaves such claims out. */
    private static boolean isCorrection(Claim c) {
        return c.getCorrects() != null && !c.getCorrects().isBlank();
    }
}
