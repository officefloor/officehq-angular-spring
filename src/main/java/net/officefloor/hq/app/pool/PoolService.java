package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's pools, each name once, and their members: people of the sheet, each at most once in a
 * pool, and other pools, each a member of at most one pool, with a share percent. A person can be in
 * several pools. A pool cannot contain itself through any chain, and a chain of pools is at most three
 * deep. A pool is balanced when its shares
 * total exactly 100.00; a retired member's share (retirement date set, whatever the date) counts toward
 * the total as given. Each member of a balanced pool cuts the pool's pot (none: no cuts) - the sheet's
 * pot for a pool that is a member of none, else its cut in the pool above: the pot
 * times their share over the shares of the members with a cut, rounded half to even to two decimals, a
 * retired member, or a person with a hold in force on the sheet's today, 0.00 (no cut); the remainder, the
 * pot less the cuts, goes to the member with the largest share (one with no cut counting 0.00), the earliest
 * added on a tie, so the cuts total exactly the pot (all 0.00 when no member has a cut). A retired member's
 * share is so spread over the others in proportion to their shares, as a held member's is; a pool whose
 * every member (whose dates include the sheet's today) is retired has no cuts. A hold leaves the
 * total share and balance as they are. A membership may carry a from-date and a to-date (both included);
 * a member whose dates do not include the sheet's today is left out entirely: its share counts 0.00 toward
 * the total and it has no cut.
 */
@Service
public class PoolService {

    static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /**
     * A member as shown: the person or the pool (the other null), its name, its share, whether the person
     * is retired (a pool never), whether the person has a hold in force on the sheet's today (a pool never),
     * whether its dates include the sheet's today, and its cut (null: none).
     */
    public record Member(Long personId, Long poolId, String name, BigDecimal share, boolean retired, boolean held,
            boolean active, BigDecimal cut) {}

    /** A pool as shown: its members in the order added, its total share, and whether it is balanced. */
    public record PoolLine(Long id, String name, List<Member> members, BigDecimal totalShare, boolean balanced) {}

    private final SheetRepository sheets;
    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final Audit audit;

    public PoolService(SheetRepository sheets, PoolRepository pools, PoolMemberRepository members,
            PersonRepository people, Audit audit) {
        this.sheets = sheets;
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<PoolLine> pools(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        BigDecimal pot = sheet == null ? null : sheet.getPot();
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, String> poolNames = all.stream().collect(Collectors.toMap(Pool::getId, Pool::getName));
        Map<Long, List<PoolMember>> byPool = members.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
        // each pool's members as shown (no cuts yet), and the pool each pool is a member of
        Map<Long, PoolLine> uncut = new LinkedHashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();
        for (Pool pool : all) {
            List<Member> shown = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (PoolMember m : byPool.getOrDefault(pool.getId(), List.of())) {
                boolean active = today == null || m.activeOn(today);
                if (m.getMemberPoolId() != null) {
                    String name = poolNames.get(m.getMemberPoolId());
                    if (name == null) continue;
                    parentOf.put(m.getMemberPoolId(), pool.getId());
                    shown.add(new Member(null, m.getMemberPoolId(), name, m.getShare().setScale(2), false, false, active,
                            null));
                    if (active) total = total.add(m.getShare());
                    continue;
                }
                Person p = byId.get(m.getPersonId());
                if (p == null) continue;
                shown.add(new Member(p.getId(), null, p.getName(), m.getShare().setScale(2), p.isRetired(),
                        today != null && p.heldOn(today), active, null));
                if (active) total = total.add(m.getShare());
            }
            uncut.put(pool.getId(), new PoolLine(pool.getId(), pool.getName(), shown, total, total.compareTo(HUNDRED) == 0));
        }
        Map<Long, PoolLine> cutLines = new HashMap<>();
        List<PoolLine> lines = new ArrayList<>();
        for (Long id : uncut.keySet()) lines.add(withCuts(id, uncut, parentOf, pot, cutLines));
        return lines;
    }

    /**
     * The pool with its members' cuts of its pot: the sheet's pot for a pool that is a member of none,
     * else its cut in the pool above (none when that pool has no cuts); a pool unbalanced, or with no
     * pot, has no cuts.
     */
    private static PoolLine withCuts(Long id, Map<Long, PoolLine> uncut, Map<Long, Long> parentOf, BigDecimal sheetPot,
            Map<Long, PoolLine> done) {
        PoolLine known = done.get(id);
        if (known != null) return known;
        PoolLine line = uncut.get(id);
        BigDecimal pot = sheetPot;
        Long parent = parentOf.get(id);
        if (parent != null) {
            pot = withCuts(parent, uncut, parentOf, sheetPot, done).members().stream()
                    .filter(m -> id.equals(m.poolId())).findFirst().map(Member::cut).orElse(null);
        }
        PoolLine out = line.balanced() && pot != null
                ? new PoolLine(line.id(), line.name(), cut(line.members(), pot), line.totalShare(), true)
                : line;
        done.put(id, out);
        return out;
    }

    /**
     * The members with their cuts of the pot: each the pot times their share over the shares of the members
     * with a cut, rounded half to even, a retired or held member 0.00, a member whose dates leave out the
     * sheet's today none; the remainder goes to the largest
     * share, the earliest added on a tie; all 0.00 when no member has a cut, none when every member whose
     * dates include the sheet's today is retired.
     */
    private static List<Member> cut(List<Member> shown, BigDecimal pot) {
        if (shown.stream().filter(Member::active).allMatch(Member::retired)) return shown;
        BigDecimal[] cuts = new BigDecimal[shown.size()];
        BigDecimal[] shares = new BigDecimal[shown.size()];
        BigDecimal cutShares = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < shown.size(); i++) {
            Member m = shown.get(i);
            shares[i] = m.retired() || m.held() || !m.active() ? BigDecimal.ZERO.setScale(2) : m.share();
            cutShares = cutShares.add(shares[i]);
        }
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int largest = -1;
        BigDecimal largestShare = null;
        for (int i = 0; i < shown.size(); i++) {
            BigDecimal share = shares[i];
            cuts[i] = cutShares.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                    : pot.multiply(share).divide(cutShares, 2, RoundingMode.HALF_EVEN);
            sum = sum.add(cuts[i]);
            if (largestShare == null || share.compareTo(largestShare) > 0) {
                largest = i;
                largestShare = share;
            }
        }
        if (largest >= 0 && cutShares.signum() != 0) cuts[largest] = cuts[largest].add(pot.subtract(sum));
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Member m = shown.get(i);
            out.add(new Member(m.personId(), m.poolId(), m.name(), m.share(), m.retired(), m.held(), m.active(),
                    m.active() ? cuts[i] : null));
        }
        return out;
    }

    /** Add a pool, its name trimmed; refused when blank or already a pool of the sheet. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        String text = name == null ? "" : name.strip();
        if (text.isEmpty()) throw new PoolNameBlankException();
        if (pools.existsBySheetIdAndName(sheetId, text)) throw new DuplicatePoolNameException();
        Pool saved = pools.saveAndFlush(new Pool(sheetId, text));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user.strip() + " name=" + text);
        return saved;
    }

    /**
     * Add a person of the sheet to a pool with a share (two decimals, more than 0.00 and at most
     * 100.00), from the optional from-date to the optional to-date; refused when they are already in it,
     * the share is bad, or the to-date is before the from-date.
     */
    @Transactional
    public void addMember(String sheetId, String user, Long poolId, Long personId, String share, LocalDate from,
            LocalDate to) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId).orElseThrow(PoolNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        BigDecimal value = shareOf(share);
        checkDates(from, to);
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) throw new DuplicatePoolMemberException();
        PoolMember member = new PoolMember(pool.getId(), sheetId, person.getId(), value);
        member.setDates(from, to);
        members.saveAndFlush(member);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user.strip() + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + value.toPlainString() + datesField(from, to));
    }

    /**
     * Add another pool of the sheet to a pool with a share; refused when the share is bad, the pool is
     * already a member of a pool, it would contain itself through any chain, or the chain of pools would
     * be more than three deep, or the to-date is before the from-date.
     */
    @Transactional
    public void addPoolMember(String sheetId, String user, Long poolId, Long memberPoolId, String share,
            LocalDate from, LocalDate to) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId).orElseThrow(PoolNotFoundException::new);
        Pool member = pools.findByIdAndSheetId(memberPoolId, sheetId).orElseThrow(PoolNotFoundException::new);
        BigDecimal value = shareOf(share);
        checkDates(from, to);
        if (members.existsByMemberPoolId(member.getId())) throw new PoolNestingException();
        Map<Long, Long> parentOf = new HashMap<>();
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (PoolMember m : members.findBySheetIdOrderByIdAsc(sheetId)) {
            if (m.getMemberPoolId() == null) continue;
            parentOf.put(m.getMemberPoolId(), m.getPoolId());
            childrenOf.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
        }
        // the pool and the pools above it: the member must not be among them
        int above = 0;
        for (Long at = pool.getId(); at != null; at = parentOf.get(at)) {
            if (at.equals(member.getId())) throw new PoolNestingException();
            above++;
        }
        if (above + depth(member.getId(), childrenOf) > 3) throw new PoolNestingException();
        PoolMember added = PoolMember.ofPool(pool.getId(), sheetId, member.getId(), value);
        added.setDates(from, to);
        members.saveAndFlush(added);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user.strip() + " pool=" + pool.getName()
                + " name=" + member.getName() + " share=" + value.toPlainString() + datesField(from, to));
    }

    /** Refused when both dates are given and the to-date is before the from-date. */
    private static void checkDates(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) throw new PoolMemberDatesException();
    }

    /** The audit's from= and to= fields, each only when given. */
    private static String datesField(LocalDate from, LocalDate to) {
        return (from == null ? "" : " from=" + from) + (to == null ? "" : " to=" + to);
    }

    /** How many levels of pools the pool heads: 1 for one with no pools as members. */
    private static int depth(Long poolId, Map<Long, List<Long>> childrenOf) {
        int below = 0;
        for (Long child : childrenOf.getOrDefault(poolId, List.of())) below = Math.max(below, depth(child, childrenOf));
        return below + 1;
    }

    /** The share as two decimals; refused unless at most two decimals, more than 0.00 and at most 100.00. */
    private static BigDecimal shareOf(String share) {
        String text = share == null ? "" : share.strip();
        if (!text.matches("\\d{1,3}(\\.\\d{1,2})?")) throw new PoolShareException();
        BigDecimal value = new BigDecimal(text).setScale(2);
        if (value.signum() <= 0 || value.compareTo(HUNDRED) > 0) throw new PoolShareException();
        return value;
    }
}
