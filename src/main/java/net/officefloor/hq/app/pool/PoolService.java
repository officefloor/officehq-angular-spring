package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewMember;
import net.officefloor.hq.app.crew.CrewMemberRepository;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The pools on a sheet: listed with their members, added, and given members. A crew is also a pool, under
 * its name, its members the crew's lead and members at equal shares: 100.00 split equally, each rounded
 * half to even, the remainder on the lead.
 */
@Service
public class PoolService {

    private static final BigDecimal MAX_SHARE = new BigDecimal("100.00");

    /** How many pools deep a chain of pools may be, the pool that is a member of none counting as one. */
    private static final int MAX_DEPTH = 3;

    /** A pool with its members in the order they were added, each with the person's name. */
    public record PoolWithMembers(Pool pool, List<Member> members) {
    }

    /** A member with its name: the person's, or the member pool's. */
    public record Member(PoolMember member, String name) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final CrewRepository crews;
    private final CrewMemberRepository crewMembers;
    private final SheetService sheets;
    private final Audit audit;
    private final PoolCuts cuts;
    private final Rollups rollups;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            CrewRepository crews, CrewMemberRepository crewMembers, SheetService sheets, Audit audit,
            PoolCuts cuts, Rollups rollups) {
        this.cuts = cuts;
        this.rollups = rollups;
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.crews = crews;
        this.crewMembers = crewMembers;
        this.sheets = sheets;
        this.audit = audit;
    }

    /**
     * The sheet's pools in the order they were added, with their members, then the crews' pools in the order
     * the crews were added.
     */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        return cuts.list(sheetId);
    }

    /** Refused when the pool is a crew's: its members are the crew's. */
    private static void refuseCrewPool(Pool pool) {
        if (pool.getCrewId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A crew's pool has the crew's lead and members as its members.");
        }
    }

    /** Add a pool; refused when its name, trimmed, is blank or already a pool's or a crew's on the sheet. */
    @Transactional
    public PoolWithMembers add(String sheetId, String user, String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A pool needs a name.");
        }
        sheets.lock(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a pool by that name.");
        }
        if (crews.existsBySheetIdAndName(sheetId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a crew by that name.");
        }
        Pool saved = pools.saveAndFlush(new Pool(sheetId, trimmed));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + trimmed);
        return new PoolWithMembers(saved, List.of());
    }

    /**
     * Add a person on the sheet to a pool with a share percent; refused when the share is not more than
     * 0.00 and at most 100.00 with two decimals, the to-date is before the from-date, the person is
     * already in the pool, or the pool is a crew's.
     */
    @Transactional
    public PoolWithMembers addMember(String sheetId, String user, long poolId, long personId, BigDecimal share,
            LocalDate from, LocalDate to) {
        if (share.scale() > 2 || share.signum() <= 0 || share.compareTo(MAX_SHARE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A share is more than 0.00 and at most 100.00, with two decimals.");
        }
        refuseDates(from, to);
        sheets.lock(sheetId);
        Pool pool = pools.findBySheetIdAndId(sheetId, poolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        refuseCrewPool(pool);
        Person person = people.findById(personId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (members.existsByPoolIdAndPersonId(poolId, personId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool.");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        Rollups.Rollup before = rollups.take(sheetId);
        members.saveAndFlush(new PoolMember(poolId, personId, scaled).during(from, to));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + scaled.toPlainString() + dates(from, to));
        rollups.audit(sheetId, user, before);
        return list(sheetId).stream().filter(p -> p.pool().getId().equals(poolId)).findFirst().orElseThrow();
    }

    /**
     * Add another pool on the sheet to a pool with a share percent; refused when the share is bad, the pool
     * would contain itself through any chain, the member pool is already a member of a pool, or the chain of
     * pools would be more than three deep; or the to-date is before the from-date; or the pool is a crew's.
     * A crew's pool may be a member of another pool.
     */
    @Transactional
    public PoolWithMembers addPoolMember(String sheetId, String user, long poolId, long memberPoolId,
            BigDecimal share, LocalDate from, LocalDate to) {
        if (share.scale() > 2 || share.signum() <= 0 || share.compareTo(MAX_SHARE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A share is more than 0.00 and at most 100.00, with two decimals.");
        }
        refuseDates(from, to);
        sheets.lock(sheetId);
        Pool pool = pools.findBySheetIdAndId(sheetId, poolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        refuseCrewPool(pool);
        Pool member = pools.findBySheetIdAndId(sheetId, memberPoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        // Each member pool's parent, and each pool's member pools, across the sheet.
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Long> parents = new HashMap<>();
        for (PoolMember m : members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())) {
            if (m.getMemberPoolId() != null) parents.put(m.getMemberPoolId(), m.getPoolId());
        }
        for (Long up = pool.getId(); up != null; up = parents.get(up)) {
            if (up.equals(member.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A pool cannot contain itself.");
            }
        }
        if (parents.containsKey(member.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already a member of a pool.");
        }
        if (depth(pool.getId(), parents) + height(member.getId(), parents) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A chain of pools is at most three deep.");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        Rollups.Rollup before = rollups.take(sheetId);
        members.saveAndFlush(PoolMember.ofPool(poolId, memberPoolId, scaled).during(from, to));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + member.getName() + " share=" + scaled.toPlainString() + dates(from, to));
        rollups.audit(sheetId, user, before);
        return list(sheetId).stream().filter(p -> p.pool().getId().equals(poolId)).findFirst().orElseThrow();
    }

    /** Refused when a membership's to-date is before its from-date. */
    private static void refuseDates(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A to-date is not before the from-date.");
        }
    }

    /** A membership's dates as audited: from=<date> and to=<date>, each only when given. */
    private static String dates(LocalDate from, LocalDate to) {
        return (from != null ? " from=" + from : "") + (to != null ? " to=" + to : "");
    }

    /** How deep a pool is in its chain: 1 for a pool that is a member of none. */
    private static int depth(Long poolId, Map<Long, Long> parents) {
        int depth = 1;
        for (Long up = parents.get(poolId); up != null; up = parents.get(up)) depth++;
        return depth;
    }

    /** How many pools deep the chain below a pool is, the pool itself counting as one. */
    private static int height(Long poolId, Map<Long, Long> parents) {
        int height = 1;
        for (Map.Entry<Long, Long> e : parents.entrySet()) {
            if (e.getValue().equals(poolId)) height = Math.max(height, 1 + height(e.getKey(), parents));
        }
        return height;
    }
}
