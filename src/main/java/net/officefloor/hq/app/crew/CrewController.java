package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The crews of a sheet and their members. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/crews")
@Validated
public class CrewController {

    /** A crew as shown: its lead's id and its members' ids in the order they were added. */
    public record CrewView(Long id, String name, Long leadId, List<Long> memberIds) {

        static CrewView of(Crew c) {
            return new CrewView(c.getId(), c.getName(), c.getLeadId(),
                    c.getMembers().stream().map(CrewMember::getPersonId).toList());
        }
    }

    /** The crew's name (surrounding spaces are removed, and it must not then be blank) and its lead. */
    public record CrewRequest(@NotNull @NotBlank @Size(max = 255) String name, @NotNull Long leadId) {
    }

    /** A person to add to the crew. */
    public record CrewMemberRequest(@NotNull Long personId) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    /** The sheet's crews, in the order they were added. */
    @GetMapping
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(CrewView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CrewRequest request) {
        return CrewView.of(service.add(sheetId.trim(), user.trim(), request.name().trim(), request.leadId()));
    }

    @PostMapping("/{crewId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long crewId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CrewMemberRequest request) {
        return CrewView.of(service.addMember(sheetId.trim(), user.trim(), crewId, request.personId()));
    }
}
