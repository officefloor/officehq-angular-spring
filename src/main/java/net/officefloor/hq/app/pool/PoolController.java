package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's pools and their members. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** A member: a retired one (a retirement date set, whatever the date) counts 0.00 toward the total. */
    public record PoolMemberView(long personId, String name, String share, boolean retired) {
    }

    /** A pool, with its total share and whether that is exactly 100.00. */
    public record PoolView(long id, String name, List<PoolMemberView> members, String totalShare, boolean balanced) {

        static PoolView of(Pool pool, Map<Long, Person> people) {
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            List<PoolMemberView> members = new ArrayList<>();
            for (PoolMember m : pool.getMembers()) {
                Person p = people.get(m.getPersonId());
                boolean retired = p != null && p.isRetired();
                if (!retired) {
                    total = total.add(m.getShare());
                }
                members.add(new PoolMemberView(m.getPersonId(), p == null ? "" : p.getName(),
                        m.getShare().setScale(2).toPlainString(), retired));
            }
            return new PoolView(pool.getId(), pool.getName(), members, total.setScale(2).toPlainString(),
                    total.compareTo(HUNDRED) == 0);
        }
    }

    public record PoolRequest(@Size(max = 200) String name) {
    }

    public record PoolMemberRequest(Long personId, BigDecimal share) {
    }

    private final PoolService service;
    private final PersonRepository people;

    public PoolController(PoolService service, PersonRepository people) {
        this.service = service;
        this.people = people;
    }

    /** The sheet's pools, in the order they were added. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        String sid = sheetId.trim();
        Map<Long, Person> byId = people(sid);
        return service.list(sid).stream().map(p -> PoolView.of(p, byId)).toList();
    }

    /** Add a pool to the sheet. */
    @PostMapping
    public PoolView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PoolRequest request) {
        String sid = sheetId.trim();
        return PoolView.of(service.add(sid, user.trim(), request.name()), people(sid));
    }

    /** Add a person to a pool with a share. */
    @PostMapping("/{poolId}/members")
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PoolMemberRequest request) {
        String sid = sheetId.trim();
        Pool pool = service.addMember(sid, user.trim(), poolId, request.personId(), request.share());
        return PoolView.of(pool, people(sid));
    }

    private Map<Long, Person> people(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
    }

    /** A pool or member that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(PoolRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(PoolRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
