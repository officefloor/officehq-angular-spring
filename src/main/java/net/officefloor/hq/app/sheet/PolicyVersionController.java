package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{id}/policy-versions")
@Validated
public class PolicyVersionController {

    /**
     * What the screen is told about a policy version: its number, the date it is in force from (null when
     * always), the setting it changed and the value it set (null: none), and the count of the sheet's
     * claims whose policy version it is.
     */
    public record PolicyVersionView(int number, LocalDate from, String key, String value, long claims) {

        static PolicyVersionView of(PolicyVersionService.Counted counted) {
            PolicyVersion v = counted.version();
            return new PolicyVersionView(v.getNumber(), v.getFromDate(), v.getKey(), v.getValue(), counted.claims());
        }
    }

    private final PolicyVersionService service;

    public PolicyVersionController(PolicyVersionService service) {
        this.service = service;
    }

    /** The sheet's policy versions, oldest first. */
    @GetMapping
    public List<PolicyVersionView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id) {
        return service.versions(id).stream().map(PolicyVersionView::of).toList();
    }
}
