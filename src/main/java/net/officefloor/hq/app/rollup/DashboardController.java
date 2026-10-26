package net.officefloor.hq.app.rollup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.asof.AsOf;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** The signed-in user's steward dashboard over the sheet and its descendants; empty unless they are a
     *  steward of the sheet. Read from a screen set to an as-of date, every sheet of the tree as it stood
     *  then, with the role the user had then. */
    @GetMapping("/{id}/dashboard")
    public DashboardService.Dashboard dashboard(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(name = SignedInUser.HEADER, required = false) String user, HttpServletRequest request) {
        return service.dashboardOf(id.trim(), user == null ? null : SignedInUser.name(user),
                AsOf.of(request).orElse(null));
    }
}
