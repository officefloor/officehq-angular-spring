package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;

/** A person on the sheet: a name, three optional whole numbers, an on-hold flag and patrons. */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name = "";

    private Integer alpha;

    private Integer beta;

    private Integer gamma;

    private boolean hold;

    @ManyToMany
    @JoinTable(name = "person_patron",
            joinColumns = @JoinColumn(name = "person_id"),
            inverseJoinColumns = @JoinColumn(name = "patron_id"))
    private Set<Person> patrons = new LinkedHashSet<>();

    protected Person() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getAlpha() {
        return alpha;
    }

    public Integer getBeta() {
        return beta;
    }

    public Integer getGamma() {
        return gamma;
    }

    public boolean isHold() {
        return hold;
    }

    public Set<Person> getPatrons() {
        return patrons;
    }
}
