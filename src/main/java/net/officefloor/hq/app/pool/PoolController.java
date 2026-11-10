package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing the pools on a sheet, adding a pool and adding a member to one. */
@RestController
@RequestMapping("/api/pools")
public class PoolController {

    public record ListPoolsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddPoolRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 200) String name) {
    }

    public record AddMemberRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long pool, @NotNull Long person, @NotNull @Size(max = 20) String share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<PoolService.PoolView> list(@Valid @RequestBody ListPoolsRequest req) {
        return service.list(req.sheet().trim());
    }

    @PostMapping("/add")
    public List<PoolService.PoolView> add(@Valid @RequestBody AddPoolRequest req) {
        String sheet = req.sheet().trim();
        service.add(sheet, req.user().trim(), req.name());
        return service.list(sheet);
    }

    @PostMapping("/members/add")
    public List<PoolService.PoolView> addMember(@Valid @RequestBody AddMemberRequest req) {
        String sheet = req.sheet().trim();
        service.addMember(sheet, req.user().trim(), req.pool(), req.person(), req.share());
        return service.list(sheet);
    }
}
