package net.officefloor.hq.app.grade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** A grade as the screen shows it: its code and whether it is retired. */
    public record GradeView(String grade, boolean retired) {
    }

    /** The retire form: the grade to retire. */
    public record RetireForm(@NotNull @Size(max = 10) String grade) {
    }

    /** The add form: the code of the grade to add. */
    public record AddForm(@NotNull @Size(max = 10) String grade) {
    }

    /** The merge form: the grade merged and the grade it is merged into. */
    public record MergeForm(@NotNull @Size(max = 10) String from, @NotNull @Size(max = 10) String into) {
    }

    /** How many people a merge gave the other grade. */
    public record Merged(int merged) {
    }

    private final GradeService service;
    private final PersonService people;

    public GradeController(GradeService service, PersonService people) {
        this.service = service;
        this.people = people;
    }

    @GetMapping
    public List<GradeView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        Set<String> retired = service.retired(sheetId.trim()).stream()
                .filter(r -> asOf == null || !r.getRetiredOn().isAfter(asOf))
                .map(RetiredGrade::getGrade).collect(Collectors.toSet());
        return service.grades(sheetId.trim()).stream().map(g -> new GradeView(g, retired.contains(g))).toList();
    }

    /** Add a grade to the sheet (see {@link GradeService#add}); every grade, as it is now. */
    @PostMapping
    public List<GradeView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AddForm form) {
        service.add(sheetId.trim(), user.trim(), form.grade().trim());
        return list(sheetId, null);
    }

    @PostMapping("/retire")
    public List<GradeView> retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RetireForm form) {
        service.retire(sheetId.trim(), user.trim(), form.grade().trim());
        return list(sheetId, null);
    }

    /** Merge a grade into another: the people on it are given the other (see
     *  {@link PersonService#mergeGrade}). */
    @PostMapping("/merge")
    public Merged merge(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MergeForm form) {
        return new Merged(people.mergeGrade(sheetId.trim(), user.trim(), form.from().trim(), form.into().trim()).size());
    }
}
