package net.officefloor.hq.app.claim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class DuplicateClaimsController {

    private final DuplicateClaimsService service;

    public DuplicateClaimsController(DuplicateClaimsService service) {
        this.service = service;
    }

    /** The sheet's duplicate claims: each person, date and amount shared by two or more claims. */
    @GetMapping("/{id}/duplicate-claims")
    public List<DuplicateClaimsService.Duplicate> duplicates(@PathVariable @NotBlank @Size(max = 100) String id) {
        return service.duplicates(id.trim());
    }
}
