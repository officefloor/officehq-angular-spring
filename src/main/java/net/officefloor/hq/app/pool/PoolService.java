package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The pools on a sheet: each a name and its members, people of the sheet with a share percent. */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

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

    /** Add a person of the sheet to a pool with a share percent: more than 0.00 and at most 100.00,
     *  with up to two decimals. Refused for a person already in the pool, or such a share. */
    @Transactional
    public PoolWithMembers addMember(String sheetId, String user, Long poolId, Long personId, BigDecimal share) {
        if (share == null || share.signum() <= 0 || share.compareTo(HUNDRED) > 0
                || share.stripTrailingZeros().scale() > 2) {
            throw new PoolRefusedException("A share is more than 0.00 and at most 100.00, with two decimals");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        sheets.lock(sheetId);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No such pool on the sheet"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No such person on the sheet"));
        if (members.existsByPoolIdAndPersonId(poolId, personId)) {
            throw new PoolRefusedException(person.getName() + " is in the pool " + pool.getName() + " already");
        }
        members.saveAndFlush(new PoolMember(poolId, personId, scaled));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + scaled.toPlainString());
        return new PoolWithMembers(pool, members.findByPoolIdInOrderByIdAsc(List.of(poolId)));
    }
}
