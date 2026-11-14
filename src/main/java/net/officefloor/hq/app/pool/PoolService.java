package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The pools on a sheet: each a name and its members, people or other pools of the sheet, each with a
 *  share percent. */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** The most pools a chain of pools, each a member of the one above, may hold. */
    private static final int MAX_DEPTH = 3;

    /** A pool with its members, in the order they were added. */
    public record PoolWithMembers(Pool pool, List<PoolMember> members) {
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

    /** The sheet's pools, in the order they were added, each with its members. */
    public List<PoolWithMembers> list(String sheetId) {
        List<Pool> list = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<PoolMember>> byPool = members
                .findByPoolIdInOrderByIdAsc(list.stream().map(Pool::getId).toList()).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
        return list.stream().map(p -> new PoolWithMembers(p, byPool.getOrDefault(p.getId(), List.of()))).toList();
    }

    /** Add a pool, its name trimmed. Refused when the name is blank or already a pool's on the sheet.
     *  The sheet is held locked so the same name added at the same moment is checked against itself. */
    @Transactional
    public PoolWithMembers add(String sheetId, String user, String name) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new PoolRefusedException("A pool needs a name");
        }
        sheets.lock(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, key)) {
            throw new PoolRefusedException("The pool " + key + " is on the sheet already");
        }
        Pool saved = pools.saveAndFlush(new Pool(sheetId, key));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + key);
        return new PoolWithMembers(saved, List.of());
    }

    /** Add a member to a pool with a share percent: more than 0.00 and at most 100.00, with up to two
     *  decimals, and an optional from-date and to-date, both included. The member is a person of the
     *  sheet, or another pool of the sheet (memberPoolId). Refused for such a share, a to-date before
     *  the from-date, a person already in the pool, a pool that is a member of a pool
     *  already, a pool that would contain itself through any chain, or a chain of pools more than
     *  three deep. */
    @Transactional
    public PoolWithMembers addMember(String sheetId, String user, Long poolId, Long personId, Long memberPoolId,
            BigDecimal share, LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new PoolRefusedException("A membership's to-date is not before its from-date");
        }
        if (share == null || share.signum() <= 0 || share.compareTo(HUNDRED) > 0
                || share.stripTrailingZeros().scale() > 2) {
            throw new PoolRefusedException("A share is more than 0.00 and at most 100.00, with two decimals");
        }
        if ((personId == null) == (memberPoolId == null)) {
            throw new PoolRefusedException("A member is a person or a pool");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        sheets.lock(sheetId);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No such pool on the sheet"));
        String name;
        if (personId != null) {
            Person person = people.findByIdAndSheetId(personId, sheetId)
                    .orElseThrow(() -> new PoolRefusedException("No such person on the sheet"));
            if (members.existsByPoolIdAndPersonId(poolId, personId)) {
                throw new PoolRefusedException(person.getName() + " is in the pool " + pool.getName() + " already");
            }
            members.saveAndFlush(new PoolMember(poolId, personId, scaled).dated(from, to));
            name = person.getName();
        } else {
            Pool member = pools.findByIdAndSheetId(memberPoolId, sheetId)
                    .orElseThrow(() -> new PoolRefusedException("No such pool on the sheet"));
            checkNesting(sheetId, pool, member);
            members.saveAndFlush(PoolMember.ofPool(poolId, memberPoolId, scaled).dated(from, to));
            name = member.getName();
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + name + " share=" + scaled.toPlainString() + (from != null ? " from=" + from : "")
                + (to != null ? " to=" + to : ""));
        return new PoolWithMembers(pool, members.findByPoolIdInOrderByIdAsc(List.of(poolId)));
    }

    /** Refuse making the pool member a member of the pool when it is a member of a pool already, when
     *  the pool is the member or lies inside it, or when the chain of pools would be more than three
     *  deep. */
    private void checkNesting(String sheetId, Pool pool, Pool member) {
        if (members.findByMemberPoolId(member.getId()).isPresent()) {
            throw new PoolRefusedException("The pool " + member.getName() + " is a member of a pool already");
        }
        Map<Long, Long> parentOf = new HashMap<>();
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        List<Long> ids = pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(Pool::getId).toList();
        for (PoolMember m : members.findByPoolIdInOrderByIdAsc(ids)) {
            if (m.getMemberPoolId() != null) {
                parentOf.put(m.getMemberPoolId(), m.getPoolId());
                childrenOf.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
            }
        }
        // The pools from the pool up to the top, the pool counted.
        int above = 0;
        for (Long at = pool.getId(); at != null; at = parentOf.get(at)) {
            if (at.equals(member.getId())) {
                throw new PoolRefusedException("The pool " + pool.getName() + " would contain itself");
            }
            above++;
        }
        if (above + height(member.getId(), childrenOf) > MAX_DEPTH) {
            throw new PoolRefusedException("A chain of pools is at most " + MAX_DEPTH + " deep");
        }
    }

    /** The pools in the longest chain down from this pool, the pool counted. */
    private int height(Long poolId, Map<Long, List<Long>> childrenOf) {
        int below = 0;
        for (Long child : childrenOf.getOrDefault(poolId, List.of())) {
            below = Math.max(below, height(child, childrenOf));
        }
        return below + 1;
    }
}
