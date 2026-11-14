package net.officefloor.hq.app.policy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/policy-versions")
public class PolicyViewController {

    private final PolicyViewService service;

    public PolicyViewController(PolicyViewService service) {
        this.service = service;
    }

    /** The sheet's policy versions, oldest first, each with the claims it is the policy version of. */
    @GetMapping
    public List<PolicyViewService.Line> lines(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.lines(sheetId.trim());
    }
}
