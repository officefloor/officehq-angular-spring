package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
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

    /** The sheet's pools in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        return all.stream().map(p -> new PoolWithMembers(p, byPool.getOrDefault(p.getId(), List.of()))).toList();
    }

    /** Add a pool to the sheet, the sheet created first if it was never loaded; refused (409) when
     *  the sheet has a pool of the name already. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (pools.findBySheetIdAndNameIgnoreCase(sheetId, name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet has a pool of that name");
        }
        Pool pool = pools.saveAndFlush(new Pool(sheetId, name));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + name);
        return pool;
    }

    /** Add a person of the sheet to a pool with a share; refused (404) for a pool or person not on
     *  the sheet, (409) when the person is in the pool already. */
    @Transactional
    public PoolMember addMember(String sheetId, String user, String poolName, long personId, BigDecimal share) {
        sheets.lock(sheetId);
        Pool pool = pools.findBySheetIdAndNameIgnoreCase(sheetId, poolName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person is in the pool already");
        }
        PoolMember member = members.saveAndFlush(new PoolMember(pool.getId(), person.getId(), share));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + share.setScale(2).toPlainString());
        return member;
    }
}
