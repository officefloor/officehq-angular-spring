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

/** Listing the grades retired on a sheet and retiring one. */
@RestController
@RequestMapping("/api/grades")
public class GradeController {

    public record ListGradesRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record RetireGradeRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 10) String grade) {
    }

    public record MergeGradeRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 10) String from, @NotNull @Size(max = 10) String into) {
    }

    /** How many people were given the grade merged into. */
    public record MergeGradeResponse(int merged) {
    }

    /** The grades retired on the sheet, in A, B, C order. */
    public record GradesResponse(List<String> retired) {
    }

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public GradesResponse list(@Valid @RequestBody ListGradesRequest req) {
        return new GradesResponse(service.retired(req.sheet().trim()));
    }

    @PostMapping("/retire")
    public GradesResponse retire(@Valid @RequestBody RetireGradeRequest req) {
        return new GradesResponse(service.retire(req.sheet().trim(), req.user().trim(), req.grade().trim()));
    }

    @PostMapping("/merge")
    public MergeGradeResponse merge(@Valid @RequestBody MergeGradeRequest req) {
        return new MergeGradeResponse(service.merge(req.sheet().trim(), req.user().trim(), req.from().trim(),
                req.into().trim()));
    }
}
