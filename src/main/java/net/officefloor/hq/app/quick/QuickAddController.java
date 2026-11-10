package net.officefloor.hq.app.quick;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimResponse;
import net.officefloor.hq.app.person.PersonController;
import net.officefloor.hq.app.person.PersonService;
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

    /** The claim added, and its person; `created` when the person was added for it. */
    public record QuickAddResponse(PersonController.PersonResponse person, boolean created, ClaimResponse claim) {
    }

    private final QuickAddService service;
    private final PersonService people;

    public QuickAddController(QuickAddService service, PersonService people) {
        this.service = service;
        this.people = people;
    }

    @PostMapping("/add")
    public QuickAddResponse add(@Valid @RequestBody QuickAddRequest req) {
        QuickAddService.Added added = service.add(req.sheet().trim(), req.user().trim(), req.name().trim(),
                ClaimController.amount(req.amount()));
        boolean clashing = people.clashing(req.sheet().trim(), List.of(added.person())).contains(added.person().getId());
        return new QuickAddResponse(PersonController.response(added.person(), clashing,
                people.today(req.sheet().trim())), added.created(),
                ClaimController.response(added.claim()));
    }
}
