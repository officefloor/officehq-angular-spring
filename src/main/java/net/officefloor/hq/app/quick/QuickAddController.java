package net.officefloor.hq.app.quick;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The quick-add bar: a claim by a person's name, adding the person when the name is new. */
@RestController
@RequestMapping("/api/quick")
public class QuickAddController {

    public record QuickAddRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 200) String name, @NotNull @Pattern(regexp = ClaimController.AMOUNT) String amount) {
    }

    public record PersonResponse(long id, String name, String grade, long version) {
    }

    /** The claim added, and its person; `created` when the person was added for it. */
    public record QuickAddResponse(PersonResponse person, boolean created, ClaimResponse claim) {
    }

    private final QuickAddService service;

    public QuickAddController(QuickAddService service) {
        this.service = service;
    }

    @PostMapping("/add")
    public QuickAddResponse add(@Valid @RequestBody QuickAddRequest req) {
        QuickAddService.Added added = service.add(req.sheet().trim(), req.user().trim(), req.name().trim(),
                ClaimController.amount(req.amount()));
        return new QuickAddResponse(new PersonResponse(added.person().getId(), added.person().getName(),
                added.person().getGrade(), added.person().getVersion()), added.created(),
                ClaimController.response(added.claim()));
    }
}
