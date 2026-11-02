package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The pools of a sheet and their members. */
@Service
public class PoolService {

    /** A share as entered: a number with at most two decimals. */
    private static final Pattern SHARE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    /** The total a pool's shares must come to for it to be balanced. */
    public static final BigDecimal WHOLE = new BigDecimal("100.00");

    /** The most levels a chain of pools may have, the top pool counting as one. */
    public static final int MAX_DEPTH = 3;

    /** A member as the pool shows them: the person, or the pool (exactly one of them), and their share;
     *  whether the member is a person with a hold in force on the sheet's today; and whether the
     *  membership's dates include the sheet's today (a member out of force is left out entirely:
     *  not in the total share, not in the balance, no cut). */
    public record Member(PoolMember member, Person person, Pool pool, boolean held, boolean inForce) {

        /** Whether the member is a person with a retirement day set: they then get no cut, their
         *  share going to the other members with a cut in proportion to their shares. */
        public boolean retired() {
            return person != null && person.isRetired();
        }
    }

    /** A pool with its members in the order they were added, and its pot (null: none): the sheet's
     *  pot for a pool that is a member of none, its cut in the pool above for one that is. */
    public record Listed(Pool pool, List<Member> members, BigDecimal pot) {

        /** The shares of the members added up, a retired member's (one with a retirement day set,
         *  whatever the day) counting as given and a member out of force left out. */
        public BigDecimal totalShare() {
            return members.stream()
                    .filter(Member::inForce)
                    .map(m -> m.member().getShare())
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        }

        /** Whether the shares total exactly 100.00. */
        public boolean balanced() {
            return totalShare().compareTo(WHOLE) == 0;
        }

        /** Each member's cut of the pot, in the members' order: the pot times their share divided by
         *  the shares of the members with a cut added up, rounded half to even to two decimals (a
         *  retired or held member getting no cut: 0.00, their share spread over the others), with the
         *  remainder of the pot going to the member with a cut with the largest share (the earliest
         *  added on a tie), so the cuts total exactly the pot (each 0.00 when no member has a cut);
         *  none (an empty list) when there is no pot, the pool is not balanced or every member in
         *  force is retired. A member out of
         *  force has no cut at all: its entry is null. */
        public List<BigDecimal> cuts() {
            if (pot == null || !balanced()
                    || members.stream().filter(Member::inForce).allMatch(Member::retired)) {
                return List.of();
            }
            List<BigDecimal> shares = members.stream()
                    .map(m -> m.retired() || m.held() || !m.inForce() ? BigDecimal.ZERO.setScale(2)
                            : m.member().getShare())
                    .toList();
            BigDecimal withCut = shares.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            List<BigDecimal> cuts = new ArrayList<>(shares.size());
            for (int i = 0; i < shares.size(); i++) {
                BigDecimal sh = shares.get(i);
                cuts.add(!members.get(i).inForce() ? null
                        : withCut.signum() == 0 ? sh
                        : pot.multiply(sh).divide(withCut, 2, RoundingMode.HALF_EVEN));
            }
            if (withCut.signum() == 0) {
                return cuts;
            }
            int largest = 0;
            for (int i = 1; i < shares.size(); i++) {
                if (shares.get(i).compareTo(shares.get(largest)) > 0) {
                    largest = i;
                }
            }
            BigDecimal remainder = pot.subtract(cuts.stream().filter(c -> c != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            cuts.set(largest, cuts.get(largest).add(remainder).setScale(2));
            return cuts;
        }
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, Audit audit) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The pools of the sheet in the order they were added, each with its members and its pot. */
    @Transactional(readOnly = true)
    public List<Listed> list(String sheetId) {
        return list(sheetId, null);
    }

    /** The pools as {@link #list(String)} has them, with holds and memberships' dates taken on a day
     *  (null: the sheet's today): the sheet as of that day. */
    @Transactional(readOnly = true)
    public List<Listed> list(String sheetId, LocalDate asOf) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Pool> poolsById = all.stream().collect(Collectors.toMap(Pool::getId, Function.identity()));
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<PoolMember> memberships = members.findByPoolIdInOrderByIdAsc(poolsById.keySet());
        Map<Long, List<PoolMember>> byPool = memberships.stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        Map<Long, List<Member>> membersOf = new HashMap<>();
        for (Pool p : all) {
            membersOf.put(p.getId(), byPool.getOrDefault(p.getId(), List.of()).stream()
                    .filter(m -> m.getPersonId() != null ? byId.containsKey(m.getPersonId())
                            : poolsById.containsKey(m.getMemberPoolId()))
                    .map(m -> new Member(m, m.getPersonId() == null ? null : byId.get(m.getPersonId()),
                            m.getMemberPoolId() == null ? null : poolsById.get(m.getMemberPoolId()),
                            m.getPersonId() != null && byId.get(m.getPersonId()).isHeldOn(today),
                            m.isInForceOn(today)))
                    .toList());
        }
        Map<Long, Long> above = memberships.stream().filter(m -> m.getMemberPoolId() != null)
                .collect(Collectors.toMap(PoolMember::getMemberPoolId, PoolMember::getPoolId, (a, b) -> a));
        BigDecimal sheetPot = sheets.pot(sheetId);
        Map<Long, Listed> listed = new HashMap<>();
        return all.stream().map(p -> listed(p.getId(), poolsById, membersOf, above, sheetPot, listed, 0)).toList();
    }

    /** The pool listed with its pot: the sheet's when it is a member of no pool, else its cut in the
     *  pool above (none when that pool shows no cuts). */
    private static Listed listed(Long poolId, Map<Long, Pool> poolsById, Map<Long, List<Member>> membersOf,
            Map<Long, Long> above, BigDecimal sheetPot, Map<Long, Listed> done, int depth) {
        Listed known = done.get(poolId);
        if (known != null) {
            return known;
        }
        BigDecimal pot = sheetPot;
        Long parentId = above.get(poolId);
        if (parentId != null) {
            pot = null;
            if (depth <= poolsById.size()) {
                Listed parent = listed(parentId, poolsById, membersOf, above, sheetPot, done, depth + 1);
                List<BigDecimal> cuts = parent.cuts();
                for (int i = 0; i < cuts.size(); i++) {
                    if (poolId.equals(parent.members().get(i).member().getMemberPoolId())) {
                        pot = cuts.get(i);
                    }
                }
            }
        }
        Listed l = new Listed(poolsById.get(poolId), membersOf.get(poolId), pot);
        done.put(poolId, l);
        return l;
    }

    /** Add a pool with a (trimmed) name; refused when blank or already a pool of the sheet. */
    @Transactional
    public synchronized Listed add(String sheetId, String user, String name) {
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a pool name");
        }
        sheets.load(sheetId, user);
        if (pools.existsBySheetIdAndName(sheetId, text)) {
            throw alreadyAPool();
        }
        Pool saved;
        try {
            saved = pools.saveAndFlush(new Pool(sheetId, text));
        } catch (DataIntegrityViolationException e) {
            throw alreadyAPool();
        }
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + text);
        return new Listed(saved, List.of(), sheets.pot(sheetId));
    }

    /** Add a pool of the sheet to another pool with a share; refused when the share is bad, the pool
     *  is already a member of a pool, it would contain itself through any chain, or the chain of
     *  pools would be more than {@link #MAX_DEPTH} deep. */
    @Transactional
    public synchronized Listed addPoolMember(String sheetId, String user, long poolId, long memberPoolId,
            String share, LocalDate from, LocalDate to) {
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        Pool member = pools.findByIdAndSheetId(memberPoolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        BigDecimal amount = share(share);
        checkDates(from, to);
        List<PoolMember> all = members.findByPoolIdInOrderByIdAsc(
                pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(Pool::getId).toList());
        Map<Long, Long> above = all.stream().filter(m -> m.getMemberPoolId() != null)
                .collect(Collectors.toMap(PoolMember::getMemberPoolId, PoolMember::getPoolId, (a, b) -> a));
        if (above.containsKey(memberPoolId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already a member of a pool");
        }
        // The pool and the pools above it: the member may be none of them.
        int levelsAbove = 0;
        for (Long p = poolId; p != null && levelsAbove <= all.size(); p = above.get(p)) {
            if (p == memberPoolId) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A pool cannot contain itself");
            }
            levelsAbove++;
        }
        if (levelsAbove + height(memberPoolId, all, 0) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A chain of pools is at most " + MAX_DEPTH + " deep");
        }
        try {
            PoolMember m = PoolMember.ofPool(poolId, memberPoolId, amount);
            m.setDates(from, to);
            members.saveAndFlush(m);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already a member of a pool");
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + member.getName() + " share=" + amount.toPlainString() + dates(from, to));
        return list(sheetId).stream().filter(l -> l.pool().getId().equals(poolId)).findFirst().orElseThrow();
    }

    /** The levels of pools from the pool down, itself counting as one. */
    private static int height(long poolId, List<PoolMember> all, int depth) {
        if (depth > all.size()) {
            return depth;
        }
        int below = 0;
        for (PoolMember m : all) {
            if (m.getPoolId() == poolId && m.getMemberPoolId() != null) {
                below = Math.max(below, height(m.getMemberPoolId(), all, depth + 1));
            }
        }
        return 1 + below;
    }

    /** Add a person of the sheet to a pool with a share (two decimals, above 0.00 and at most
     *  100.00); refused when the share is bad or the person is already in the pool. */
    @Transactional
    public synchronized Listed addMember(String sheetId, String user, long poolId, long personId, String share,
            LocalDate from, LocalDate to) {
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        BigDecimal amount = share(share);
        checkDates(from, to);
        if (members.existsByPoolIdAndPersonId(poolId, personId)) {
            throw alreadyAMember();
        }
        try {
            PoolMember m = new PoolMember(poolId, personId, amount);
            m.setDates(from, to);
            members.saveAndFlush(m);
        } catch (DataIntegrityViolationException e) {
            throw alreadyAMember();
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + amount.toPlainString() + dates(from, to));
        return list(sheetId).stream().filter(l -> l.pool().getId().equals(poolId)).findFirst().orElseThrow();
    }

    /** Refused when both dates are given and the to-date is before the from-date. */
    private static void checkDates(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date");
        }
    }

    /** The membership's dates as the audit records them: from=<date> and to=<date>, each when given. */
    private static String dates(LocalDate from, LocalDate to) {
        return (from == null ? "" : " from=" + from) + (to == null ? "" : " to=" + to);
    }

    /** A share as entered, to two decimals; refused unless above 0.00 and at most 100.00. */
    static BigDecimal share(String text) {
        String t = text == null ? "" : text.trim();
        if (!SHARE.matcher(t).matches()) {
            throw badShare();
        }
        BigDecimal amount = new BigDecimal(t).setScale(2);
        if (amount.signum() <= 0 || amount.compareTo(WHOLE) > 0) {
            throw badShare();
        }
        return amount;
    }

    private static ResponseStatusException badShare() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A share is a percent with two decimals, more than 0.00 and at most 100.00");
    }

    private static ResponseStatusException alreadyAPool() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already a pool");
    }

    private static ResponseStatusException alreadyAMember() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool");
    }
}
