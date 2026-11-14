package net.officefloor.hq.app.duplicate;

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
@RequestMapping("/api/sheets/{sheetId}/duplicates")
public class DuplicateClaimController {

    private final DuplicateClaimService service;

    public DuplicateClaimController(DuplicateClaimService service) {
        this.service = service;
    }

    /** The sheet's duplicate claims: per person, each date and amount with two or more live claims. */
    @GetMapping
    public List<DuplicateClaimService.Duplicate> duplicates(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.duplicates(sheetId.trim());
    }
}
