package net.officefloor.hq.app.plan;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person's plan: a draft claim of its amount on its start date and every so many days after,
 *  up to its end date. */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "every_days", nullable = false)
    private int everyDays;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** The plan dates it has made its claim for. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_date", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "on_date", nullable = false)
    private Set<LocalDate> made = new HashSet<>();

    protected Plan() {
    }

    public Plan(String sheetId, Long personId, BigDecimal amount, LocalDate startDate, int everyDays,
            LocalDate endDate) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.startDate = startDate;
        this.everyDays = everyDays;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Make the plan another person's (the one it was for was merged into them). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public int getEveryDays() {
        return everyDays;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return made.size();
    }

    /** The plan dates up to its end and the day given, oldest first, that it has made no claim for. */
    public List<LocalDate> dueUpTo(LocalDate today) {
        LocalDate last = endDate.isBefore(today) ? endDate : today;
        List<LocalDate> due = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(last); d = d.plusDays(everyDays)) {
            if (!made.contains(d)) {
                due.add(d);
            }
        }
        return due;
    }

    /** Note that the plan has made its claim for the date. */
    public void markMade(LocalDate date) {
        made.add(date);
    }
}
