package net.officefloor.hq.app.person;

import net.officefloor.hq.app.claim.Claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.setting.OpenToViewer;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /**
     * A person as the screen shows them: grade is the one in force on the sheet's today, grades every
     * grade recorded, earliest first (the first from null, the beginning of time); retiredFrom is null
     * while not retired; retirements their past ones, earliest first; held whether they have any hold, holds every hold, earliest first; patronIds
     * in the order named; watched whether the signed-in user watches them; terms their own in force on the sheet's today, null for none,
     * and baseTerms their own from the beginning of time, null for none;
     * contact theirs, null for none; aliases theirs, sorted as names are compared; active whether they are active on the sheet's today ({@link Person#activeOn});
     * clashing whether their name or an alias is used on another sheet of the sheet's tree ({@link PersonService#clashingIds});
     * origin the sheet they were transferred from, null when never transferred; tags theirs, sorted (empty when none);
     * snapshots the SNAPSHOT lines written for them, oldest first; flag the reason of their flag in force on the sheet's today, null for none;
     * approver the user who may approve or reject their PENDING claims, null for none.
     */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds, List<Long> patronIds,
            boolean watched, Integer terms, boolean active, String contact, List<String> aliases,
            boolean clashing, String origin, List<RetirementView> retirements, List<String> tags,
            List<SnapshotView> snapshots, Integer baseTerms, String flag, String approver) {
        public static PersonView of(Person person, LocalDate today, List<Long> patronIds, boolean watched,
                boolean active, boolean clashing) {
            List<GradeView> grades = new ArrayList<>();
            grades.add(new GradeView(null, person.getGrade()));
            person.getDatedGrades().forEach((from, grade) -> grades.add(new GradeView(from, grade)));
            return new PersonView(person.getId(), person.getName(),
                    today == null ? person.getGrade() : person.gradeOn(today), grades, person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(h -> new HoldView(h.getFromDate(), h.getToDate())).toList(),
                    patronIds, watched, today == null ? person.getTerms() : person.termsOn(today), active, person.getContact(),
                    person.getAliases(), clashing, person.getTransferredFrom(),
                    person.getRetirements().stream().map(r -> new RetirementView(r.getFromDate(), r.getToDate())).toList(),
                    person.getTags(),
                    person.getSnapshots().stream().map(s -> new SnapshotView(s.getDay(),
                            s.getDueTotal() == null ? null : s.getDueTotal().setScale(2).toPlainString())).toList(),
                    person.getTerms(), person.flagOn(today), person.getApprover());
        }
    }

    /** A past retirement, from its date to the day they were unretired. */
    public record RetirementView(LocalDate from, LocalDate to) {}

    /** A SNAPSHOT line: the day being left and their due-total on it (null: n/a). */
    public record SnapshotView(LocalDate day, String dueTotal) {}

    /** A grade in force from a date (null: the beginning of time). */
    public record GradeView(LocalDate from, String grade) {}

    /** A hold from a date (null: the beginning of time) to a date (null: open-ended), both included. */
    public record HoldView(LocalDate from, LocalDate to) {}

    /** A flag to put on a person: its reason (blank is refused) and the date it is in force until, inclusive. */
    public record FlagRequest(@Size(max = 200) String reason, LocalDate until) {}

    /** The user to name as a person's approver (blank: none). */
    public record ApproverRequest(@Size(max = 100) String user) {}

    /** A hold to put on a person: from the date (blank: the sheet's today) to the optional to-date. */
    public record HoldRequest(LocalDate from, LocalDate to) {}

    /** Whether the signed-in user is to watch the person. */
    public record WatchRequest(@NotNull Boolean watched) {}

    /** An alias to give a person. */
    public record AliasRequest(@NotBlank @Size(max = 200) String alias) {}

    /** The person to merge a person into. */
    public record MergeRequest(@NotNull Long intoId) {}

    /** The sheet to transfer a person to, by its id. */
    public record TransferRequest(@NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String toSheetId) {}

    /** The person to name as a patron. */
    public record PatronRequest(@NotNull Long patronId) {}

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @Pattern(regexp = "\\d{0,4}") String terms,
            @Size(max = 200) String contact,
            @Size(max = 1000) String tags) {}

    /** An edit, made from the version of the person the form loaded. */
    public record PersonEditRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[A-Z]{1,3}") String grade,
            LocalDate gradeFrom,
            @Pattern(regexp = "\\d{0,4}") String terms,
            LocalDate termsFrom,
            @Size(max = 200) String contact,
            @Size(max = 1000) String tags,
            @NotNull Long version) {}

    /** The people to give the grade, from the date (blank: the sheet's today). */
    public record BulkGradeRequest(
            @NotEmpty List<@NotNull Long> ids,
            @NotNull @Pattern(regexp = "[A-Z]{1,3}") String grade,
            LocalDate from) {}

    /** How many people a bulk grade changed. */
    public record BulkGradeResult(int graded) {}

    /** The grade to merge into another. */
    public record GradeMergeRequest(
            @NotNull @Pattern(regexp = "[A-Z]{1,3}") String from,
            @NotNull @Pattern(regexp = "[A-Z]{1,3}") String into) {}

    /** How many people a grade merge gave the other grade. */
    public record GradeMergeResult(int merged) {}

    /** The people to retire together, from a date (blank: the sheet's today). */
    public record BulkRetireRequest(@NotEmpty List<@NotNull Long> ids, LocalDate from) {}

    /** How many people a bulk retire retired. */
    public record BulkRetireResult(int retired) {}

    /** The people to hold together, from a date (blank: the sheet's today) to an optional to-date. */
    public record BulkHoldRequest(@NotEmpty List<@NotNull Long> ids, LocalDate from, LocalDate to) {}

    /** How many people a bulk hold held. */
    public record BulkHoldResult(int held) {}

    /** Pasted lines of name,grade,terms,contact, one person each. */
    public record ImportRequest(@NotNull @Size(max = 200000) String text) {}

    /** The people an import added, and those it updated, each in the order of their lines. */
    public record ImportView(int added, int updated, List<PersonView> people, List<PersonView> updatedPeople) {}

    /** Terms in days as the form gave them; blank or null: none. */
    private static Integer termsOf(String terms) {
        return terms == null || terms.isEmpty() ? null : Integer.valueOf(terms);
    }

    /** Which way to move a person in the list: up or down. */
    public record MoveRequest(@NotNull @Pattern(regexp = "up|down") String direction) {}

    /** The date a person is retired from (blank: the sheet's today). */
    public record RetireRequest(LocalDate from) {}

    private final PersonService service;
    private final PersonImportService importer;
    private final SheetHistory history;

    public PersonController(PersonService service, PersonImportService importer, SheetHistory history) {
        this.service = service;
        this.importer = importer;
        this.history = history;
    }

    @GetMapping
    public List<PersonView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, List<Long>> patrons = service.patronIds(sheetId);
        Set<Long> watched = service.watchedIds(sheetId, user);
        LocalDate today = service.today(sheetId);
        Set<Long> active = service.activeIds(sheetId);
        Set<Long> clashing = service.clashingIds(sheetId);
        return service.list(sheetId).stream()
                .map(p -> PersonView.of(p, today, patrons.getOrDefault(p.getId(), List.of()),
                        watched.contains(p.getId()), active.contains(p.getId()), clashing.contains(p.getId())))
                .toList();
    }

    @PostMapping
    public PersonView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(service.add(sheetId, user, request.name(), request.grade(), termsOf(request.terms()),
                request.contact(), Claim.tagsOf(request.tags())), user);
    }

    /** Add, or update the person named, per pasted line, in order; a line refused refuses them all. */
    @PostMapping("/import")
    public ImportView importPeople(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ImportRequest request) {
        PersonImportService.Result result = importer.importPeople(sheetId, user, request.text());
        List<PersonView> added = result.added().stream().map(p -> view(p, user)).toList();
        List<PersonView> updated = result.updated().stream().map(p -> view(p, user)).toList();
        return new ImportView(added.size(), updated.size(), added, updated);
    }

    @PutMapping("/{id}")
    public PersonView edit(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonEditRequest request) {
        return view(service.edit(sheetId, user, id, request.version(), request.name(), request.grade(),
                request.gradeFrom(), termsOf(request.terms()), request.termsFrom(), request.contact(),
                request.tags() == null ? null : Claim.tagsOf(request.tags())), user);
    }

    /** Give each selected person the grade from the date (blank: the sheet's today), in list order. */
    @OpenToViewer
    @PostMapping("/grade")
    public BulkGradeResult bulkGrade(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkGradeRequest request) {
        return new BulkGradeResult(service.bulkGrade(sheetId, user, Set.copyOf(request.ids()), request.grade(),
                request.from()));
    }

    /** Merge a grade into another: a steward only; everyone on it today has every record of it made the other. */
    @PostMapping("/grade-merge")
    public GradeMergeResult mergeGrade(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GradeMergeRequest request) {
        return new GradeMergeResult(service.mergeGrade(sheetId, user, request.from(), request.into()));
    }

    @OpenToViewer
    @PostMapping("/retire")
    public BulkRetireResult bulkRetire(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkRetireRequest request) {
        return new BulkRetireResult(service.bulkRetire(sheetId, user, Set.copyOf(request.ids()), request.from()));
    }

    /** Hold each selected person from the date (blank: the sheet's today) to the optional to-date, in list order. */
    @OpenToViewer
    @PostMapping("/hold")
    public BulkHoldResult bulkHold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkHoldRequest request) {
        return new BulkHoldResult(service.bulkHold(sheetId, user, Set.copyOf(request.ids()), request.from(),
                request.to()));
    }

    /** Swap the person with the one above or below them in the list. */
    @PostMapping("/{id}/move")
    public PersonView move(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MoveRequest request) {
        return view(service.move(sheetId, user, id, request.direction().equals("up")), user);
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireRequest request) {
        return view(service.retire(sheetId, user, id, request.from()), user);
    }

    /** Unretire the retired person on the sheet's today. */
    @PostMapping("/{id}/unretire")
    public PersonView unretire(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.unretire(sheetId, user, id), user);
    }

    /** Put the person on hold from a date to an optional to-date. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody HoldRequest request) {
        return view(service.hold(sheetId, user, id, request.from(), request.to()), user);
    }

    /** Flag the person with a reason until a date: a steward only. */
    @PostMapping("/{id}/flag")
    public PersonView flag(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FlagRequest request) {
        return view(service.flag(sheetId, user, id, request.reason(), request.until()), user);
    }

    /** Name the user who may approve or reject the person's PENDING claims. */
    @PostMapping("/{id}/approver")
    public PersonView setApprover(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ApproverRequest request) {
        return view(service.setApprover(sheetId, user, id, request.user()), user);
    }

    /** Give the person an alias. */
    @PostMapping("/{id}/aliases")
    public PersonView addAlias(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AliasRequest request) {
        return view(service.addAlias(sheetId, user, id, request.alias()), user);
    }

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PatronRequest request) {
        return view(service.addPatron(sheetId, user, id, request.patronId()), user);
    }

    /** Merge the person into another on the sheet; answers with the person merged into. */
    @PostMapping("/{id}/merge")
    public PersonView merge(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MergeRequest request) {
        return view(service.merge(sheetId, user, id, request.intoId()), user);
    }

    /** Transfer the person to another sheet by its id; that sheet is recorded in its history too. */
    @PostMapping("/{id}/transfer")
    public void transfer(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TransferRequest request) {
        service.transfer(sheetId, user, id, request.toSheetId().strip());
        history.capture(request.toSheetId().strip());
    }

    /** Undo the transfer that brought the person to this sheet, sending them back to the sheet they came from. */
    @PostMapping("/{id}/transfer-undo")
    public void undoTransfer(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        String to = service.originOf(sheetId, id);
        service.undoTransfer(sheetId, user, id);
        if (to != null) history.capture(to);
    }

    /** Start or stop watching the person as the signed-in user. */
    @PutMapping("/{id}/watch")
    public PersonView watch(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid WatchRequest request) {
        return view(service.watch(sheetId, user, id, request.watched()), user);
    }

    private PersonView view(Person person, String user) {
        return PersonView.of(person, service.today(person.getSheetId()), service.patronIdsOf(person.getId()),
                service.watches(person.getId(), user), service.isActive(person), service.isClashing(person));
    }
}
