package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures a sheet rolls up to: what each person has claimed (a hold does not take a claim out
 * of it), their due-total (counting their parts of claims shared with them, less the parts others
 * hold of theirs) and their
 * overdue-total, and the sheet's totals of all three. The sheet's due-total and overdue-total leave
 * out the single highest and the single lowest person's figure when three or more people have one
 * (a due-total of n/a is no figure). A change made through {@link #tracking} is followed in the audit by one
 * ROLLUP line per person whose figures it moved (in the order they were added), then a TOTALS line
 * when the sheet's totals moved, then one ALERT line per person (in list order) whose due-total it
 * took from at or below the sheet's alert threshold (or n/a, or no figures) to above it; and the sheet's {@link History} records what it changed.
 */
@Service
public class Rollup {

    /** A person's figures as two-decimal text; a due-total of n/a is "n/a". */
    private record Figures(String name, String claimed, String dueTotal, String overdueTotal) {
        boolean sameAs(Figures other) {
            return other != null && claimed.equals(other.claimed) && dueTotal.equals(other.dueTotal)
                    && overdueTotal.equals(other.overdueTotal);
        }
    }

    /** The figures of a sheet: each person's by id in the order they were added, and the totals. */
    private record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal,
            String overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final Dues dues;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final History history;
    private final Audit audit;
    private final PoolService pools;

    public Rollup(PersonRepository people, ClaimRepository claims, Dues dues, SheetService sheets,
            SheetRepository sheetStore, History history, Audit audit, @Lazy PoolService pools) {
        this.pools = pools;
        this.people = people;
        this.claims = claims;
        this.dues = dues;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.history = history;
        this.audit = audit;
    }

    /** Make a change on the sheet, then record in the audit, after the change's own records, the
     *  figures it moved, and in the sheet's history what it changed. A change that is refused records
     *  nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T tracking(String sheetId, String user, Supplier<T> change) {
        Snapshot before = snapshot(sheetId);
        T result = change.get();
        history.record(sheetId);
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        after.people().forEach((id, figures) -> {
            if (!figures.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP" + prefix + " person=" + figures.name()
                        + " claimed=" + figures.claimed() + " due-total=" + figures.dueTotal()
                        + " overdue-total=" + figures.overdueTotal());
            }
        });
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())
                || !Objects.equals(before.overdueTotal(), after.overdueTotal())) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal() + " overdue-total=" + after.overdueTotal());
        }
        BigDecimal threshold = sheets.alertThreshold(sheetId);
        if (threshold != null) {
            after.people().forEach((id, figures) -> {
                Figures was = before.people().get(id);
                if (above(figures, threshold) && (was == null || !above(was, threshold))) {
                    audit.recordOnCommit("ALERT" + prefix + " name=" + figures.name()
                            + " due-total=" + figures.dueTotal());
                }
            });
        }
        return result;
    }

    /** Record in the audit one SCORE line per person (in list order) with their score as it stands
     *  now: the due figures of their settled claims that correct nothing divided by 100, minus 5 for each of their overdue claims that count,
     *  rounded half to even to a whole number. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordScores(String sheetId, String user) {
        String prefix = " sheet=" + sheetId + " by=" + user;
        Dues.Basis basis = dues.now(sheetId);
        LocalDate today = basis.today();
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            List<Claim> theirs = Dues.live(person, byPerson.getOrDefault(person.getId(), List.of()), today);
            BigDecimal settled = theirs.stream().filter(c -> c.getStatus() == ClaimStatus.SETTLED && c.getCorrects() == null)
                    .map(c -> dues.figure(basis, person, c)).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            long overdue = theirs.stream().filter(c -> dues.overdue(basis, person, c)).count();
            BigDecimal score = settled.subtract(BigDecimal.valueOf(500 * overdue))
                    .divide(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_EVEN);
            audit.recordOnCommit("SCORE" + prefix + " name=" + person.getName() + " score=" + score.toPlainString());
        }
    }

    /** Record in the audit one SNAPSHOT line per person (in the order they were added) with their
     *  due-total as it stands now, and keep it on the person with the day being left (the sheet's
     *  today). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSnapshot(String sheetId, String user) {
        String prefix = " sheet=" + sheetId + " by=" + user;
        LocalDate leaving = sheets.today(sheetId);
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, p -> p));
        snapshot(sheetId).people().forEach((id, figures) -> {
            byId.get(id).addSnapshot(leaving, figures.dueTotal());
            audit.recordOnCommit(
                    "SNAPSHOT" + prefix + " name=" + figures.name() + " due-total=" + figures.dueTotal());
        });
    }

    /** The figures a sheet's descendants add to its own to consolidate it: over all of them, the
     *  people (retired included), and each one's due-total and overdue-total as that sheet shows
     *  them, added up; per grade, the due-totals of the people not retired on the grade in force on
     *  their sheet's today, skipping those that are n/a, added up; and per status, how many claims
     *  have it; and each of their people (retired included), for the screen to trim and count once
     *  over the whole tree; and how many of their claims that count are overdue. */
    public record Descendants(int peopleCount, String dueTotal, String overdueTotal,
            Map<String, String> gradeDueTotals, Map<String, Integer> statusCounts, List<Member> people,
            int overdueCount) {
    }

    /** A person of a descendant sheet: their name and aliases, and their due-total (null when n/a),
     *  overdue-total, settled-total, open-total and cut total as that sheet shows them. */
    public record Member(List<String> names, String dueTotal, String overdueTotal, String settledTotal,
            String openTotal, String cutTotal) {
    }

    /** The figures of a sheet's descendants added up (all zero when it has none): as each stands now,
     *  or, as of a day when one is given, as each stood at the end of it with that day as its today
     *  (see {@link History}). */
    @Transactional(readOnly = true)
    public Descendants descendants(String sheetId, LocalDate asOf) {
        int count = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        Map<String, BigDecimal> gradeDue = new LinkedHashMap<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        List<Member> members = new ArrayList<>();
        int overdueCount = 0;
        for (String id : sheetStore.descendants(sheetId)) {
            Dues.Basis basis;
            List<Person> sheetPeople;
            List<Claim> sheetClaims;
            Map<Long, BigDecimal> cuts = pools.cutTotals(id, asOf);
            if (asOf == null) {
                basis = dues.now(id);
                sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(id);
                sheetClaims = claims.findBySheetIdOrderByListedDesc(id);
            } else {
                History.SheetAsOf then = history.asOf(id, asOf);
                basis = then.basis();
                sheetPeople = List.copyOf(then.people().values());
                sheetClaims = then.claims();
            }
            Snapshot s = snapshot(basis, sheetPeople, sheetClaims, cuts);
            count += s.people().size();
            due = due.add(new BigDecimal(s.dueTotal()));
            overdue = overdue.add(new BigDecimal(s.overdueTotal()));
            Map<Long, List<Claim>> byPerson = sheetClaims.stream().collect(Collectors.groupingBy(Claim::getPersonId));
            Map<Long, Person> holders = sheetPeople.stream().collect(Collectors.toMap(Person::getId, p -> p));
            Map<Long, List<Map.Entry<Claim, BigDecimal>>> held = heldParts(basis, sheetPeople, byPerson, holders);
            for (Person person : sheetPeople) {
                overdueCount += (int) Dues.live(person, byPerson.getOrDefault(person.getId(), List.of()), basis.today())
                        .stream().filter(c -> dues.overdue(basis, person, c)).count();
                Figures personFigures = s.people().get(person.getId());
                String personDue = personFigures.dueTotal();
                List<String> names = new ArrayList<>(List.of(person.getName()));
                names.addAll(person.getAliases());
                List<Claim> theirs = Dues.live(person, byPerson.getOrDefault(person.getId(), List.of()), basis.today());
                List<Map.Entry<Claim, BigDecimal>> theirHeld = held.getOrDefault(person.getId(), List.of());
                members.add(new Member(names, "n/a".equals(personDue) ? null : personDue,
                        personFigures.overdueTotal(),
                        text(statusTotal(basis, person, theirs, holders, theirHeld, c -> c.getStatus() == ClaimStatus.SETTLED)),
                        text(statusTotal(basis, person, theirs, holders, theirHeld, c -> c.getStatus().isOpen())),
                        text(cuts.getOrDefault(person.getId(), BigDecimal.ZERO.setScale(2)))));
            }
            addGradeDues(gradeDue, basis, sheetPeople, s);
            for (Claim claim : sheetClaims) {
                statusCounts.merge(claim.getStatus().name(), 1, Integer::sum);
            }
        }
        Map<String, String> gradeDueTotals = new LinkedHashMap<>();
        gradeDue.forEach((grade, total) -> gradeDueTotals.put(grade, text(total)));
        return new Descendants(count, text(due), text(overdue), gradeDueTotals, statusCounts, members, overdueCount);
    }

    /** The parts each person of a sheet holds of others' claims that count, by holder id, with the
     *  claim each is a part of. */
    private Map<Long, List<Map.Entry<Claim, BigDecimal>>> heldParts(Dues.Basis basis, List<Person> sheetPeople,
            Map<Long, List<Claim>> byPerson, Map<Long, Person> holders) {
        Map<Long, List<Map.Entry<Claim, BigDecimal>>> held = new HashMap<>();
        for (Person person : sheetPeople) {
            for (Claim claim : Dues.live(person, byPerson.getOrDefault(person.getId(), List.of()), basis.today())) {
                if (!claim.getShares().isEmpty()) {
                    dues.parts(basis, claim, dues.figure(basis, person, claim), holders).forEach((holder, part) ->
                            held.computeIfAbsent(holder, h -> new ArrayList<>()).add(Map.entry(claim, part)));
                }
            }
        }
        return held;
    }

    /** A person's figures of their claims that count and are kept (less the parts others hold), and
     *  the parts they hold of others' claims that are kept, added up; those with no due skipped. */
    private BigDecimal statusTotal(Dues.Basis basis, Person person, List<Claim> theirs, Map<Long, Person> holders,
            List<Map.Entry<Claim, BigDecimal>> held, Predicate<Claim> keep) {
        BigDecimal sum = theirs.stream().filter(keep)
                .map(c -> dues.kept(basis, c, dues.figure(basis, person, c), holders)).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return held.stream().filter(e -> keep.test(e.getKey())).map(Map.Entry::getValue).reduce(sum, BigDecimal::add);
    }

    /** Add to the per-grade due-totals those of a sheet's people not retired on the grade in force
     *  on its today, skipping those that are n/a. */
    private static void addGradeDues(Map<String, BigDecimal> gradeDue, Dues.Basis basis,
            List<Person> sheetPeople, Snapshot s) {
        for (Person person : sheetPeople) {
            String personDue = s.people().get(person.getId()).dueTotal();
            String grade = person.gradeOn(basis.today());
            if (!person.isRetired() && grade != null && !"n/a".equals(personDue)) {
                gradeDue.merge(grade, new BigDecimal(personDue), BigDecimal::add);
            }
        }
    }

    /** Another sheet's figures to compare a sheet with: whether it exists, and per grade the
     *  due-totals of its people not retired on it (n/a skipped), added up; a grade missing has none. */
    public record Comparison(boolean found, Map<String, String> gradeDueTotals) {
    }

    /** The other sheet's per-grade due-totals as it stands now; not found, with none, when no such
     *  sheet exists. */
    @Transactional(readOnly = true)
    public Comparison comparison(String otherId) {
        if (!sheetStore.existsById(otherId)) {
            return new Comparison(false, Map.of());
        }
        Dues.Basis basis = dues.now(otherId);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(otherId);
        Snapshot s = snapshot(basis, sheetPeople, claims.findBySheetIdOrderByListedDesc(otherId),
                pools.cutTotals(otherId, null));
        Map<String, BigDecimal> gradeDue = new LinkedHashMap<>();
        addGradeDues(gradeDue, basis, sheetPeople, s);
        Map<String, String> gradeDueTotals = new LinkedHashMap<>();
        gradeDue.forEach((grade, total) -> gradeDueTotals.put(grade, text(total)));
        return new Comparison(true, gradeDueTotals);
    }

    /** A person found by a search of a sheet's tree: their sheet's id, name as the screen shows it
     *  ({@code <name> (<grade in force on that sheet's today>)}) and due-total (two decimals, or
     *  n/a) as that sheet shows it. */
    public record Found(String sheet, String name, String dueTotal) {
    }

    /** Every person (retired included) on the sheet and the other sheets of its tree whose name or
     *  an alias contains the text, case ignored (blank text finds everyone): the sheet's people
     *  first in list order, then the other sheets' by sheet id, each in its list order. */
    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String wanted = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<Found> found = new ArrayList<>();
        for (String id : treeOrder(sheetId)) {
            Map<Long, Figures> figures = snapshot(id).people();
            LocalDate today = sheets.today(id);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                boolean matches = person.getName().toLowerCase(Locale.ROOT).contains(wanted)
                        || person.getAliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(wanted));
                if (matches) {
                    found.add(new Found(id, person.getName() + " (" + person.gradeOn(today) + ")", figures.get(person.getId()).dueTotal()));
                }
            }
        }
        return found;
    }

    /** A person of a sheet's tree as the tree export lists them: their sheet's id, name, grade in
     *  force on that sheet's today, and due-total (two decimals, or n/a) as that sheet shows it. */
    public record TreePerson(String sheet, String name, String grade, String dueTotal) {
    }

    /** Every person (retired included) on the sheet and the other sheets of its tree: the sheet's
     *  people first in list order, then the other sheets' by sheet id, each in its list order. */
    @Transactional(readOnly = true)
    public List<TreePerson> treePeople(String sheetId) {
        List<TreePerson> listed = new ArrayList<>();
        for (String id : treeOrder(sheetId)) {
            Map<Long, Figures> figures = snapshot(id).people();
            LocalDate today = sheets.today(id);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                listed.add(new TreePerson(id, person.getName(), person.gradeOn(today),
                        figures.get(person.getId()).dueTotal()));
            }
        }
        return listed;
    }

    /** The sheet's id, then those of the other sheets of its tree by id. */
    private List<String> treeOrder(String sheetId) {
        List<String> order = new ArrayList<>(List.of(sheetId));
        sheets.tree(sheetId).stream().filter(id -> !id.equals(sheetId)).sorted().forEach(order::add);
        return order;
    }

    private Snapshot snapshot(String sheetId) {
        return snapshot(dues.now(sheetId), people.findBySheetIdOrderByPositionAscIdAsc(sheetId),
                claims.findBySheetIdOrderByListedDesc(sheetId), pools.cutTotals(sheetId, null));
    }

    /** The figures of a sheet's people and claims, worked out on the basis, each person's due-total
     *  capped with their cut total (by person id; none is 0.00). */
    private Snapshot snapshot(Dues.Basis basis, List<Person> sheetPeople, List<Claim> sheetClaims,
            Map<Long, BigDecimal> cuts) {
        Map<Long, List<Claim>> byPerson = sheetClaims.stream().collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        LocalDate today = basis.today();
        Map<Long, Person> holders = sheetPeople.stream().collect(Collectors.toMap(Person::getId, p -> p));
        Map<Long, List<BigDecimal>> held = new HashMap<>();
        for (Person person : sheetPeople) {
            for (Claim claim : Dues.live(person, byPerson.getOrDefault(person.getId(), List.of()), today)) {
                if (!claim.getShares().isEmpty()) {
                    dues.parts(basis, claim, dues.figure(basis, person, claim), holders).forEach((holder, part) ->
                            held.computeIfAbsent(holder, h -> new ArrayList<>()).add(part));
                }
            }
        }
        for (Person person : sheetPeople) {
            List<Claim> all = byPerson.getOrDefault(person.getId(), List.of());
            List<Claim> theirs = Dues.live(person, all, today);
            BigDecimal claimed = all.stream()
                    .filter(c -> c.getCorrects() == null && Dues.countsClaimed(person, c, today)).map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            BigDecimal due = dues.dueTotal(basis, person, theirs, holders,
                    held.getOrDefault(person.getId(), List.of()),
                    cuts.getOrDefault(person.getId(), BigDecimal.ZERO.setScale(2)));
            BigDecimal overdue = theirs.stream().filter(c -> dues.overdue(basis, person, c))
                    .map(c -> dues.figure(basis, person, c)).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            claimedTotal = claimedTotal.add(claimed);
            overdueFigures.add(overdue);
            if (due != null) {
                dueFigures.add(due);
            }
            figures.put(person.getId(), new Figures(person.getName(), text(claimed),
                    due == null ? "n/a" : text(due), text(overdue)));
        }
        return new Snapshot(figures, text(claimedTotal), text(trimmedSum(dueFigures)), text(trimmedSum(overdueFigures)));
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are
     *  three or more. */
    /** Whether a person's due-total is a figure above the threshold. */
    private static boolean above(Figures figures, BigDecimal threshold) {
        return !"n/a".equals(figures.dueTotal()) && new BigDecimal(figures.dueTotal()).compareTo(threshold) > 0;
    }

    private static BigDecimal trimmedSum(List<BigDecimal> figures) {
        List<BigDecimal> kept = new ArrayList<>(figures);
        if (kept.size() >= 3) {
            kept.sort(Comparator.naturalOrder());
            kept = kept.subList(1, kept.size() - 1);
        }
        return kept.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static String text(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
