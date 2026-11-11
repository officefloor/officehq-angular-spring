package net.officefloor.hq.app.grade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing the grades of a sheet and those retired, adding one and retiring one. */
@RestController
@RequestMapping("/api/grades")
public class GradeController {

    public record ListGradesRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record RetireGradeRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 10) String grade) {
    }

    public record AddGradeRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 10) String grade) {
    }

    public record MergeGradeRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 10) String from, @NotNull @Size(max = 10) String into) {
    }

    /** How many people were given the grade merged into. */
    public record MergeGradeResponse(int merged) {
    }

    /** The sheet's grades in alphabetical order, and those retired, in A, B, C order. */
    public record GradesResponse(List<String> grades, List<String> retired) {
    }

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public GradesResponse list(@Valid @RequestBody ListGradesRequest req) {
        String sheet = req.sheet().trim();
        return new GradesResponse(service.grades(sheet), service.retired(sheet));
    }

    @PostMapping("/add")
    public GradesResponse add(@Valid @RequestBody AddGradeRequest req) {
        String sheet = req.sheet().trim();
        List<String> grades = service.add(sheet, req.user().trim(), req.grade().trim());
        return new GradesResponse(grades, service.retired(sheet));
    }

    @PostMapping("/retire")
    public GradesResponse retire(@Valid @RequestBody RetireGradeRequest req) {
        String sheet = req.sheet().trim();
        List<String> retired = service.retire(sheet, req.user().trim(), req.grade().trim());
        return new GradesResponse(service.grades(sheet), retired);
    }

    @PostMapping("/merge")
    public MergeGradeResponse merge(@Valid @RequestBody MergeGradeRequest req) {
        return new MergeGradeResponse(service.merge(req.sheet().trim(), req.user().trim(), req.from().trim(),
                req.into().trim()));
    }
}
