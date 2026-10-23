package net.officefloor.hq.app.grade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.SignedIn;
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

    /** What the screen is told about a grade: whether it is retired on the sheet. */
    public record GradeView(String grade, boolean retired) {
    }

    /** A grade to merge into another. */
    public record MergeForm(@NotBlank @Pattern(regexp = GradeService.CODE) String from,
            @NotBlank @Pattern(regexp = GradeService.CODE) String into) {
    }

    /** A grade to add: its code, trimmed. */
    public record AddForm(@NotNull @Size(max = 20) String grade) {
    }

    /** What the screen is told of a merge: the grades and how many people were given the other. */
    public record MergeView(String from, String into, int people) {
    }

    private final GradeService service;
    private final GradeMergeService merges;

    public GradeController(GradeService service, GradeMergeService merges) {
        this.service = service;
        this.merges = merges;
    }

    /** The sheet's grades, in order. */
    @GetMapping
    public List<GradeView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        Set<String> retired = service.retired(sheetId);
        return service.grades(sheetId).stream().map((g) -> new GradeView(g, retired.contains(g))).toList();
    }

    /** Add a grade to the sheet: the sheet's grades as they are now. */
    @PostMapping
    public List<GradeView> add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid AddForm form) {
        service.add(sheetId, SignedIn.name(user), form.grade());
        return list(sheetId);
    }

    /** Retire a grade on the sheet: the sheet's grades as they are now. */
    @PostMapping("/{grade}/retire")
    public List<GradeView> retire(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = GradeService.CODE) String grade,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        service.retire(sheetId, SignedIn.name(user), grade);
        return list(sheetId);
    }

    /** Merge a grade into another on the sheet; a steward only. */
    @PostMapping("/merge")
    public MergeView merge(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid MergeForm form) {
        int people = merges.merge(sheetId, SignedIn.name(user), form.from(), form.into());
        return new MergeView(form.from(), form.into(), people);
    }
}
