package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's pools and their members. A person is in a pool at most once, with a share percent of more than 0.00 and
 * at most 100.00, and may be in several pools. A pool may be a member of another, with a share like a person's: of at
 * most one pool, never containing itself through any chain, and in a chain of pools at most {@value #MAX_DEPTH} deep.
 */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** The most pools in a chain, the top one counted. */
    static final int MAX_DEPTH = 3;

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

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Pool> list(String sheetId) {
        return pools.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The members of every pool on the sheet, in the order they were added. */
    @Transactional(readOnly = true)
    public List<PoolMember> members(String sheetId) {
        return members.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Adds a pool under its name, trimmed; refused when blank or already a pool's name. */
    @Transactional
    public Pool add(String by, String sheetId, String name) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Enter a pool name.");
        }
        sheets.lock(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, key + " is already a pool.");
        }
        Pool added = pools.saveAndFlush(new Pool(sheetId, key));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + by + " name=" + key);
        return added;
    }

    /**
     * Adds the person to the pool with a share; refused when the person is already in it, or the share is not more
     * than 0.00 and at most 100.00 with up to two decimals.
     */
    @Transactional
    public PoolMember addMember(String by, String sheetId, Long poolId, Long personId, BigDecimal share) {
        BigDecimal scaled = validShare(share);
        sheets.lock(sheetId);
        Pool pool = pool(sheetId, poolId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Choose a person."));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is already in " + pool.getName() + ".");
        }
        PoolMember added = members.saveAndFlush(new PoolMember(sheetId, pool.getId(), person.getId(), scaled));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + by + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + scaled.toPlainString());
        return added;
    }

    /**
     * Adds another pool to the pool with a share; refused when the share is not valid (as for a person), when the
     * member pool is already a member of a pool (this or another), when it is the pool itself or contains it through
     * any chain, or when the chain of pools would be more than {@value #MAX_DEPTH} deep.
     */
    @Transactional
    public PoolMember addPoolMember(String by, String sheetId, Long poolId, Long memberPoolId, BigDecimal share) {
        BigDecimal scaled = validShare(share);
        sheets.lock(sheetId);
        Pool pool = pool(sheetId, poolId);
        Pool member = pools.findById(memberPoolId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Choose a member."));
        // each pool that is a member of another, to the pool it is in
        Map<Long, Long> parent = new HashMap<>();
        for (PoolMember m : members.findBySheetIdOrderByIdAsc(sheetId)) {
            if (m.getMemberPoolId() != null) {
                parent.put(m.getMemberPoolId(), m.getPoolId());
            }
        }
        int above = 0;
        for (Long p = pool.getId(); p != null; p = parent.get(p)) {
            if (p.equals(member.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        member.getName() + " would contain itself through " + pool.getName() + ".");
            }
            above++;
        }
        if (parent.containsKey(member.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, member.getName() + " is already a member of a pool.");
        }
        if (above + height(member.getId(), parent) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A chain of pools may be at most " + MAX_DEPTH + " deep.");
        }
        PoolMember added = members.saveAndFlush(PoolMember.ofPool(sheetId, pool.getId(), member.getId(), scaled));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + by + " pool=" + pool.getName()
                + " name=" + member.getName() + " share=" + scaled.toPlainString());
        return added;
    }

    /** How many pools deep the chain below a pool goes, the pool itself counted. */
    private static int height(Long poolId, Map<Long, Long> parent) {
        int most = 0;
        for (Map.Entry<Long, Long> e : parent.entrySet()) {
            if (e.getValue().equals(poolId)) {
                most = Math.max(most, height(e.getKey(), parent));
            }
        }
        return most + 1;
    }

    private Pool pool(String sheetId, Long poolId) {
        return pools.findById(poolId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
    }

    /** The share at two decimals; refused unless more than 0.00 and at most 100.00 with up to two decimals. */
    private static BigDecimal validShare(BigDecimal share) {
        if (share == null || share.signum() <= 0 || share.compareTo(HUNDRED) > 0
                || share.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Enter a share of more than 0.00 and at most 100.00, with up to two decimals.");
        }
        return share.setScale(2, RoundingMode.UNNECESSARY);
    }
}
