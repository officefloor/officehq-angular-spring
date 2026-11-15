package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PoolService {

    /** A pool with its members, in the order they were added. */
    public record PoolWithMembers(Pool pool, List<PoolMember> members) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final CrewRepository crews;
    private final SheetService sheets;
    private final Audit audit;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            CrewRepository crews, SheetService sheets, Audit audit) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.crews = crews;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's pools in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        return all.stream().map(p -> new PoolWithMembers(p, byPool.getOrDefault(p.getId(), List.of()))).toList();
    }

    /** Add a pool to the sheet, the sheet created first if it was never loaded; refused (409) when
     *  the sheet has a pool or a crew (a crew is a pool too) of the name already. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (pools.findBySheetIdAndNameIgnoreCase(sheetId, name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet has a pool of that name");
        }
        if (crews.findBySheetIdAndNameIgnoreCase(sheetId, name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet has a crew of that name");
        }
        Pool pool = pools.saveAndFlush(new Pool(sheetId, name));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + name);
        return pool;
    }

    /** Add a person of the sheet to a pool with a share and optional dates; refused (404) for a pool
     *  or person not on the sheet, (409) when the person is in the pool already. */
    @Transactional
    public PoolMember addMember(String sheetId, String user, String poolName, long personId, BigDecimal share,
            LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        Pool pool = pools.findBySheetIdAndNameIgnoreCase(sheetId, poolName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person is in the pool already");
        }
        PoolMember member = new PoolMember(pool.getId(), person.getId(), share);
        member.setDates(from, to);
        member = members.saveAndFlush(member);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + share.setScale(2).toPlainString() + dates(from, to));
        return member;
    }

    /** The membership's dates as the audit records them: from=<date> and to=<date>, each when given. */
    private static String dates(LocalDate from, LocalDate to) {
        return (from == null ? "" : " from=" + from) + (to == null ? "" : " to=" + to);
    }

    /** The deepest a chain of pools may be, counting the pool at its top. */
    static final int MAX_DEPTH = 3;

    /** Add another pool of the sheet to a pool with a share; refused (404) for a pool not on the
     *  sheet, (409) when the member pool is a member of a pool already, (422) when the pool would
     *  contain itself through any chain or the chain of pools would be more than three deep. */
    @Transactional
    public PoolMember addPoolMember(String sheetId, String user, String poolName, String memberName, BigDecimal share,
            LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        Pool pool = pools.findBySheetIdAndNameIgnoreCase(sheetId, poolName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        Pool memberPool = pools.findBySheetIdAndNameIgnoreCase(sheetId, memberName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        if (members.findByMemberPoolId(memberPool.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The pool is a member of a pool already");
        }
        // the pool and the pools above it
        List<Long> above = new ArrayList<>();
        for (Long id = pool.getId(); id != null;
                id = members.findByMemberPoolId(id).map(PoolMember::getPoolId).orElse(null)) {
            above.add(id);
        }
        if (above.contains(memberPool.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A pool cannot contain itself");
        }
        if (above.size() + height(memberPool.getId()) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A chain of pools is at most " + MAX_DEPTH + " deep");
        }
        PoolMember member = PoolMember.ofPool(pool.getId(), memberPool.getId(), share);
        member.setDates(from, to);
        member = members.saveAndFlush(member);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + memberPool.getName() + " share=" + share.setScale(2).toPlainString() + dates(from, to));
        return member;
    }

    /** How deep the chain of pools from this one down is, counting this one. */
    private int height(Long poolId) {
        int below = 0;
        for (PoolMember m : members.findByPoolIdInOrderByIdAsc(List.of(poolId))) {
            if (m.getMemberPoolId() != null) {
                below = Math.max(below, height(m.getMemberPoolId()));
            }
        }
        return 1 + below;
    }
}
