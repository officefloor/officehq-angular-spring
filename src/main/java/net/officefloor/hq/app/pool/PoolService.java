package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.crew.SheetCrew;
import net.officefloor.hq.app.crew.SheetCrewMember;
import net.officefloor.hq.app.crew.SheetCrewMemberRepository;
import net.officefloor.hq.app.crew.SheetCrewRepository;
import net.officefloor.hq.app.history.SheetAsOf;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The pools on a sheet and their members: people, or other pools. A pool's total share adds up its members'
 * shares, a retired person's (one with a retirement date set, whatever the date) counted as given; the pool is
 * balanced when the total is exactly 100.00. A pool that is a member of none has the sheet's pot; a pool that is
 * a member of another has its cut in that pool as its pot (none when that pool has no cuts). Each member of a
 * balanced pool with a pot cuts the pot times their share over the shares of the members with a cut added up,
 * rounded half to even to two decimals (a retired person, or one with a hold in force on the sheet's today, 0.00),
 * and the remainder of the pot over the cuts goes to the member with the smallest non-zero cut, the latest
 * added on a tie (a member with no cut gets none; a pool with no non-zero cut gives it to the last member with a
 * share), so the cuts add up to the pot exactly; a hold changes neither the total share nor the balance; an unbalanced pool, or one with no pot, has no
 * cuts, nor has a pool whose every member is retired. A pool is a member of at most one pool, never contains itself through any chain, and a chain of pools is
 * at most three deep. A membership may carry a from-date and a to-date (each optional, both included); a member
 * whose dates do not include the sheet's today is left out entirely: not in the total share, not in the balance,
 * and no cut. Worked out as of an earlier date, the pot and the people are as they stood then.
 * <p>A crew is also a pool, listed after the pools in the order the crews were added, under the crew's name: its
 * lead and then its members, in the order they were added, are its members at equal shares, 100.00 split equally,
 * each rounded half to even to two decimals, the remainder on the lead; it has the sheet's pot and its cuts are
 * worked out as any pool's. Its members are the crew's, so none is added to it, and it is a member of no pool. A
 * pool is never added with a crew's name (nor a crew with a pool's). */
@Service
public class PoolService {

    /** A share as typed: a percent with at most two decimals. */
    private static final Pattern SHARE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** The most pools in a chain, the outermost counted. */
    private static final int DEPTH = 3;

    /** A member of a pool as shown: the person (or, for a pool, null), the pool (or, for a person, null), the
     * name now, the share and the cut (null when none). */
    public record Member(Long personId, Long poolId, String name, BigDecimal share, BigDecimal cut) {
    }

    /** A pool as shown: its id (a crew's id for a crew), the crew (null for a pool), its name, its members in the
     * order they were added, its total share and whether it is balanced. */
    public record Pooled(long id, Long crewId, String name, List<Member> members, BigDecimal totalShare,
            boolean balanced) {
    }

    private final SheetRepository sheets;
    private final SheetPoolRepository pools;
    private final SheetPoolMemberRepository members;
    private final SheetCrewRepository crews;
    private final SheetCrewMemberRepository crewMembers;
    private final PersonRepository people;
    private final SheetHistory history;
    private final Audit audit;

    public PoolService(SheetRepository sheets, SheetPoolRepository pools, SheetPoolMemberRepository members,
            SheetCrewRepository crews, SheetCrewMemberRepository crewMembers, PersonRepository people,
            SheetHistory history, Audit audit) {
        this.sheets = sheets;
        this.pools = pools;
        this.members = members;
        this.crews = crews;
        this.crewMembers = crewMembers;
        this.people = people;
        this.history = history;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Pooled> pools(String sheetId) {
        return pools(sheetId, null);
    }

    /** The sheet's pools, in the order they were added, worked out on {@code on} (the sheet's today when null):
     * as of that date, with the pot and the people as they stood at its end. */
    @Transactional(readOnly = true)
    public List<Pooled> pools(String sheetId, LocalDate on) {
        List<SheetPool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        List<SheetCrew> crewed = crews.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty() && crewed.isEmpty()) return List.of();
        BigDecimal pot;
        LocalDate today;
        List<Person> listed;
        if (on != null) {
            SheetAsOf then = history.asOf(sheetId, on);
            pot = then.pot();
            today = on;
            listed = then.people();
        } else {
            Sheet sheet = sheets.findById(sheetId).orElse(null);
            pot = sheet == null ? null : sheet.getPot();
            today = sheet == null ? null : sheet.getToday();
            listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        }
        Map<Long, Person> byId = listed.stream().collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, SheetPool> poolsById = all.stream().collect(Collectors.toMap(SheetPool::getId, Function.identity()));
        Map<Long, List<SheetPoolMember>> byPool = new LinkedHashMap<>();
        Map<Long, SheetPoolMember> memberOf = new HashMap<>();
        for (SheetPoolMember m : members.findByPoolIdInOrderByIdAsc(poolsById.keySet())) {
            if (m.getMemberPoolId() != null) {
                if (!poolsById.containsKey(m.getMemberPoolId())) continue;
                memberOf.put(m.getMemberPoolId(), m);
            } else if (!byId.containsKey(m.getPersonId())) {
                continue;
            }
            byPool.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m);
        }
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (SheetPool pool : all) {
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (SheetPoolMember m : byPool.getOrDefault(pool.getId(), List.of())) {
                if (m.isOn(today)) total = total.add(m.getShare());
            }
            totals.put(pool.getId(), total.setScale(2, RoundingMode.UNNECESSARY));
        }
        Map<Long, List<BigDecimal>> cutsByPool = new HashMap<>();
        List<Pooled> found = new ArrayList<>();
        for (SheetPool pool : all) {
            List<SheetPoolMember> kept = byPool.getOrDefault(pool.getId(), List.of());
            BigDecimal total = totals.get(pool.getId());
            List<BigDecimal> cuts = cutsOf(pool.getId(), pot, today, byPool, memberOf, totals, byId,
                    cutsByPool);
            List<Member> shown = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                SheetPoolMember m = kept.get(i);
                BigDecimal cut = cuts == null ? null : cuts.get(i);
                if (m.getMemberPoolId() != null) {
                    shown.add(new Member(null, m.getMemberPoolId(), poolsById.get(m.getMemberPoolId()).getName(),
                            m.getShare().setScale(2), cut));
                } else {
                    Person person = byId.get(m.getPersonId());
                    shown.add(new Member(person.getId(), null, person.getName(), m.getShare().setScale(2), cut));
                }
            }
            found.add(new Pooled(pool.getId(), null, pool.getName(), shown, total, total.compareTo(HUNDRED) == 0));
        }
        found.addAll(crewPools(crewed, all, pot, today, byId));
        return found;
    }

    /** The crews as pools, in the order they were added: each the lead and the members (those among the
     * people) at equal shares, the remainder on the lead, with the sheet's pot. A crew whose lead is not among
     * the people, or whose name a pool has, is left out. */
    private List<Pooled> crewPools(List<SheetCrew> crewed, List<SheetPool> all, BigDecimal pot, LocalDate today,
            Map<Long, Person> byId) {
        if (crewed.isEmpty()) return List.of();
        Map<Long, List<Long>> byCrew = new HashMap<>();
        for (SheetCrewMember m : crewMembers.findByCrewIdInOrderByIdAsc(crewed.stream().map(SheetCrew::getId).toList())) {
            if (byId.containsKey(m.getPersonId())) {
                byCrew.computeIfAbsent(m.getCrewId(), k -> new ArrayList<>()).add(m.getPersonId());
            }
        }
        List<Pooled> found = new ArrayList<>();
        for (SheetCrew crew : crewed) {
            if (!byId.containsKey(crew.getLeadPersonId())) continue;
            if (all.stream().anyMatch(p -> p.getName().equals(crew.getName()))) continue;
            List<Long> ids = new ArrayList<>();
            ids.add(crew.getLeadPersonId());
            ids.addAll(byCrew.getOrDefault(crew.getId(), List.of()));
            BigDecimal share = HUNDRED.divide(BigDecimal.valueOf(ids.size()), 2, RoundingMode.HALF_EVEN);
            BigDecimal leadShare = HUNDRED.subtract(share.multiply(BigDecimal.valueOf(ids.size() - 1)));
            List<SheetPoolMember> kept = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                kept.add(new SheetPoolMember(null, ids.get(i), i == 0 ? leadShare : share));
            }
            List<BigDecimal> cuts = pot == null ? null : cuts(pot, kept, byId, today);
            List<Member> shown = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                Person person = byId.get(ids.get(i));
                shown.add(new Member(person.getId(), null, person.getName(), kept.get(i).getShare().setScale(2),
                        cuts == null ? null : cuts.get(i)));
            }
            found.add(new Pooled(crew.getId(), crew.getId(), crew.getName(), shown, HUNDRED, true));
        }
        return found;
    }

    /** The cuts of a pool's members, in their order, or null when it is unbalanced or has no pot: its pot is the
     * sheet's when it is a member of no pool, else its cut in the pool it is a member of. */
    private static List<BigDecimal> cutsOf(long poolId, BigDecimal sheetPot, LocalDate today, Map<Long, List<SheetPoolMember>> byPool,
            Map<Long, SheetPoolMember> memberOf, Map<Long, BigDecimal> totals, Map<Long, Person> byId,
            Map<Long, List<BigDecimal>> cutsByPool) {
        if (cutsByPool.containsKey(poolId)) return cutsByPool.get(poolId);
        cutsByPool.put(poolId, null);
        BigDecimal pot = sheetPot;
        SheetPoolMember membership = memberOf.get(poolId);
        if (membership != null) {
            List<BigDecimal> above = cutsOf(membership.getPoolId(), sheetPot, today, byPool, memberOf, totals, byId,
                    cutsByPool);
            pot = above == null ? null : above.get(byPool.get(membership.getPoolId()).indexOf(membership));
        }
        List<BigDecimal> cuts = pot != null && totals.get(poolId).compareTo(HUNDRED) == 0
                ? cuts(pot, byPool.getOrDefault(poolId, List.of()), byId, today)
                : null;
        cutsByPool.put(poolId, cuts);
        return cuts;
    }

    private static boolean retired(SheetPoolMember m, Map<Long, Person> byId) {
        return m.getPersonId() != null && byId.get(m.getPersonId()).isRetired();
    }

    /** Whether the member is a person with a hold in force on {@code today}. */
    private static boolean held(SheetPoolMember m, Map<Long, Person> byId, LocalDate today) {
        return m.getPersonId() != null && today != null && byId.get(m.getPersonId()).isHeldOn(today);
    }

    /** The cuts of a balanced pool's members, in their order: the pot times each share over the shares of the
     * members with a cut, half to even to two decimals (a retired or held person's 0.00), the remainder going to the
     * smallest non-zero cut, the latest added on a tie (to the last member with a share when no cut is non-zero);
     * a member whose dates leave out {@code today} has none (null); null when every member is retired. */
    private static List<BigDecimal> cuts(BigDecimal pot, List<SheetPoolMember> kept, Map<Long, Person> byId,
            LocalDate today) {
        if (kept.stream().filter(m -> m.isOn(today)).allMatch(m -> retired(m, byId))) return null;
        BigDecimal shares = BigDecimal.ZERO.setScale(2);
        for (SheetPoolMember m : kept) {
            if (m.isOn(today) && !retired(m, byId) && !held(m, byId, today)) shares = shares.add(m.getShare());
        }
        List<BigDecimal> cuts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int smallest = -1;
        int last = -1;
        for (int i = 0; i < kept.size(); i++) {
            SheetPoolMember m = kept.get(i);
            if (!m.isOn(today)) {
                cuts.add(null);
                continue;
            }
            if (retired(m, byId) || held(m, byId, today)) {
                cuts.add(BigDecimal.ZERO.setScale(2));
                continue;
            }
            BigDecimal cut = pot.multiply(m.getShare()).divide(shares, 2, RoundingMode.HALF_EVEN);
            cuts.add(cut);
            sum = sum.add(cut);
            last = i;
            if (cut.signum() != 0 && (smallest < 0 || cut.compareTo(cuts.get(smallest)) <= 0)) smallest = i;
        }
        int to = smallest >= 0 ? smallest : last;
        if (to >= 0) cuts.set(to, cuts.get(to).add(pot.setScale(2).subtract(sum)));
        return cuts;
    }

    /** Add a pool named {@code name} (trimmed, not blank); refused when the sheet has a pool or a crew by that
     * name. */
    @Transactional
    public SheetPool add(String sheetId, String by, String name) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (name.isEmpty()) throw new PoolBlankException();
        if (pools.existsBySheetIdAndName(sheetId, name) || crews.existsBySheetIdAndName(sheetId, name)) {
            throw new PoolTakenException();
        }
        SheetPool added = pools.saveAndFlush(new SheetPool(sheetId, name));
        audit.change("POOL_ADDED", sheetId, by, "name=" + name);
        return added;
    }

    /** Add the person to the pool with the share and the optional from- and to-dates (as typed, blank for none);
     * refused when the person is already in the pool, the share is not more than 0.00 and at most 100.00 with at
     * most two decimals, or the to-date is before the from-date. */
    @Transactional
    public SheetPoolMember addMember(String sheetId, String by, long poolId, long personId, String share, String from,
            String to) {
        SheetPool pool = pools.findByIdAndSheetId(poolId, sheetId).orElseThrow(PoolNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        BigDecimal amount = shareOf(share);
        LocalDate fromDate = dateOf(from);
        LocalDate toDate = dateOf(to);
        datesCheck(fromDate, toDate);
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) throw new PoolMemberTakenException();
        SheetPoolMember added = members.saveAndFlush(new SheetPoolMember(pool.getId(), person.getId(), amount)
                .dated(fromDate, toDate));
        audit.change("POOL_MEMBER_ADDED", sheetId, by, datedFields(fromDate, toDate, "pool=" + pool.getName(),
                "name=" + person.getName(), "share=" + amount.toPlainString()));
        return added;
    }

    /** Add the pool {@code memberPoolId} to the pool with the share (as typed); refused when it is already a
     * member of a pool, when it is the pool or contains it through any chain, when the chain of pools would be
     * more than three deep, for a bad share, or for a to-date before the from-date. */
    @Transactional
    public SheetPoolMember addPoolMember(String sheetId, String by, long poolId, long memberPoolId, String share,
            String from, String to) {
        SheetPool pool = pools.findByIdAndSheetId(poolId, sheetId).orElseThrow(PoolNotFoundException::new);
        SheetPool member = pools.findByIdAndSheetId(memberPoolId, sheetId).orElseThrow(PoolNotFoundException::new);
        BigDecimal amount = shareOf(share);
        LocalDate fromDate = dateOf(from);
        LocalDate toDate = dateOf(to);
        datesCheck(fromDate, toDate);
        Map<Long, Long> above = new HashMap<>();
        Map<Long, List<Long>> below = new HashMap<>();
        List<Long> ids = pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(SheetPool::getId).toList();
        for (SheetPoolMember m : members.findByPoolIdInOrderByIdAsc(ids)) {
            if (m.getMemberPoolId() == null) continue;
            above.put(m.getMemberPoolId(), m.getPoolId());
            below.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
        }
        int depth = 1;
        for (Long at = pool.getId(); at != null; at = above.get(at), depth++) {
            if (at.equals(member.getId())) throw new PoolCycleException();
        }
        if (above.containsKey(member.getId())) throw new PoolNestedException();
        if (depth - 1 + height(member.getId(), below) > DEPTH) throw new PoolDepthException();
        SheetPoolMember added = members.saveAndFlush(SheetPoolMember.ofPool(pool.getId(), member.getId(), amount)
                .dated(fromDate, toDate));
        audit.change("POOL_MEMBER_ADDED", sheetId, by, datedFields(fromDate, toDate, "pool=" + pool.getName(),
                "name=" + member.getName(), "share=" + amount.toPlainString()));
        return added;
    }

    /** How many pools deep the chain below a pool goes, the pool counted. */
    private static int height(long poolId, Map<Long, List<Long>> below) {
        int most = 0;
        for (Long child : below.getOrDefault(poolId, List.of())) most = Math.max(most, height(child, below));
        return most + 1;
    }

    /** A date as typed (yyyy-mm-dd), or null when blank. */
    private static LocalDate dateOf(String typed) {
        String date = typed == null ? "" : typed.trim();
        if (date.isEmpty()) return null;
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new PoolDatesException();
        }
    }

    private static void datesCheck(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) throw new PoolDatesException();
    }

    /** The audit fields, with from=<date> and to=<date> at the end when given. */
    private static String[] datedFields(LocalDate from, LocalDate to, String... fields) {
        List<String> all = new ArrayList<>(List.of(fields));
        if (from != null) all.add("from=" + from);
        if (to != null) all.add("to=" + to);
        return all.toArray(String[]::new);
    }

    private static BigDecimal shareOf(String share) {
        String typed = share == null ? "" : share.trim();
        if (!SHARE.matcher(typed).matches()) throw new PoolShareException();
        BigDecimal amount = new BigDecimal(typed).setScale(2);
        if (amount.signum() <= 0 || amount.compareTo(HUNDRED) > 0) throw new PoolShareException();
        return amount;
    }
}
