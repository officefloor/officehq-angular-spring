package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The grades given to the people on a sheet, each in force from its from-date. */
@Service
public class PersonGradeService {

    /** Grades from the earliest: no from-date first, then by from-date. */
    private static final Comparator<PersonGrade> FROM_EARLIEST =
            Comparator.comparing(PersonGrade::getFrom, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final PersonGradeRepository grades;

    public PersonGradeService(PersonGradeRepository grades) {
        this.grades = grades;
    }

    /** A person's grades, from the earliest. */
    public List<PersonGrade> of(Long personId) {
        return grades.findByPersonId(personId).stream().sorted(FROM_EARLIEST).toList();
    }

    /** Each person's grades on the sheet, from the earliest, by person id. */
    public Map<Long, List<PersonGrade>> bySheet(String sheetId) {
        return grades.findBySheetId(sheetId).stream().sorted(FROM_EARLIEST)
                .collect(Collectors.groupingBy(PersonGrade::getPersonId));
    }

    /** The person's grade in force on a date: the one whose from-date is the latest on or before it
     *  (the first grade applies from the beginning of time). */
    public String gradeOn(Long personId, LocalDate date) {
        return gradeOn(of(personId), date);
    }

    /** The grade in force on a date among a person's grades; null when they have none. */
    public static String gradeOn(List<PersonGrade> theirs, LocalDate date) {
        return theirs.stream()
                .filter(g -> g.appliesOn(date))
                .max(FROM_EARLIEST)
                .map(PersonGrade::getGrade)
                .orElse(null);
    }

    /** Give a person a grade from a date (null: from the beginning of time), replacing the one they
     *  had from that date and keeping the others. */
    public PersonGrade give(Person person, String grade, LocalDate from) {
        Optional<PersonGrade> had = from == null
                ? grades.findByPersonIdAndFromIsNull(person.getId())
                : grades.findByPersonIdAndFrom(person.getId(), from);
        PersonGrade given = had
                .map(existing -> {
                    existing.setGrade(grade);
                    return existing;
                })
                .orElseGet(() -> new PersonGrade(person.getSheetId(), person.getId(), grade, from));
        return grades.saveAndFlush(given);
    }
}
