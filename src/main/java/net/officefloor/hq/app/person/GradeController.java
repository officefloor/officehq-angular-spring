package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/grades")
@Validated
public class GradeController {

    /** What the screen shows of a grade: whether it is retired. */
    public record GradeView(Grade grade, boolean retired) {
    }

    /** The grade to retire, as chosen on the screen. */
    public record RetireForm(@NotNull Grade grade) {
    }

    /** The code of a grade to add, as typed on the screen. */
    public record AddForm(@Size(max = 100) String grade) {
    }

    /** The grade to merge and the grade to merge it into, as chosen on the screen. */
    public record MergeForm(@NotNull Grade from, @NotNull Grade into) {
    }

    /** How many people a merge gave the other grade. */
    public record MergeResult(int merged) {
    }

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    /** Every grade, in order, with whether it is retired on the sheet. */
    @GetMapping
    public List<GradeView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        Set<Grade> retired = service.retired(sheetId.trim());
        return service.gradesOf(sheetId.trim()).stream().map((g) -> new GradeView(g, retired.contains(g))).toList();
    }

    /** Add a grade to the sheet. */
    @PostMapping
    public List<GradeView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid AddForm form) {
        service.add(sheetId.trim(), SignedInUser.name(user), form.grade());
        return list(sheetId);
    }

    /** Retire a grade on the sheet. */
    @PostMapping("/retire")
    public List<GradeView> retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RetireForm form) {
        service.retire(sheetId.trim(), SignedInUser.name(user), form.grade());
        return list(sheetId);
    }

    /** Merge a grade into another on the sheet. */
    @PostMapping("/merge")
    public MergeResult merge(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid MergeForm form) {
        return new MergeResult(service.merge(sheetId.trim(), SignedInUser.name(user), form.from(), form.into()).size());
    }
}
