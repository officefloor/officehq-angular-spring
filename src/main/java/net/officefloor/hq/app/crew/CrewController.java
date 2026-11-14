package net.officefloor.hq.app.crew;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/crews")
public class CrewController {

    /** A crew as the screen shows it: its lead and its members' person ids, in the order they were added. */
    public record CrewView(Long id, String name, Long leadId, List<Long> memberIds) {
        static CrewView of(CrewService.CrewWithMembers crew) {
            return new CrewView(crew.crew().getId(), crew.crew().getName(), crew.crew().getLeadId(),
                    crew.members().stream().map(CrewMember::getPersonId).toList());
        }
    }

    /** A crew: its name, trimmed as it is added, and its lead, a person of the sheet. */
    public record CrewRequest(@NotBlank @Size(max = 255) String name, @NotNull Long leadId) {
    }

    /** A member: a person of the sheet. */
    public record CrewMemberRequest(@NotNull Long personId) {
    }

    private final CrewService service;
    private final RollupService rollups;

    public CrewController(CrewService service, RollupService rollups) {
        this.service = service;
        this.rollups = rollups;
    }

    /** The sheet's crews, in the order they were added. */
    @GetMapping
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(CrewView::of).toList();
    }

    /** Add a crew to the sheet; a crew is a pool, so the figures its cuts move are followed in the audit. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated CrewRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return CrewView.of(rollups.track(sheet, by, () -> service.add(sheet, by, request.name(), request.leadId())));
    }

    /** Add a member to one of the sheet's crews; the figures its cuts move are followed in the audit. */
    @PostMapping("/{crewId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long crewId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated CrewMemberRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return CrewView.of(rollups.track(sheet, by, () -> service.addMember(sheet, by, crewId, request.personId())));
    }

    /** A blank or used name, a person not of the sheet, or a member who leads the crew or is in it
     *  already, is refused. */
    @ExceptionHandler({CrewRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
