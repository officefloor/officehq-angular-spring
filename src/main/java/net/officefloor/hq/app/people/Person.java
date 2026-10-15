package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person on the list: a name and whole-number "alpha", "beta" and "gamma" (each absent until
 * given), and the derived "thrust" (alpha × beta), "drift" (gamma − alpha) and "momentum"
 * (thrust + 3 × drift), which are absent until all three raw values are given. A person may have
 * any number of "patrons": other people on the list, whose thrust added together is the person's
 * "backing". "Net" is momentum plus backing (backing counted as zero without patrons). */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Integer alpha;

    private Integer beta;

    private Integer gamma;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "person_patron", joinColumns = @JoinColumn(name = "person_id"),
            inverseJoinColumns = @JoinColumn(name = "patron_id"))
    private Set<Person> patrons = new HashSet<>();

    protected Person() {
    }

    public Person(String name, Integer alpha, Integer beta, Integer gamma) {
        this.name = name;
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
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

    public void setAlpha(Integer alpha) {
        this.alpha = alpha;
    }

    public Integer getBeta() {
        return beta;
    }

    public void setBeta(Integer beta) {
        this.beta = beta;
    }

    public Integer getGamma() {
        return gamma;
    }

    public void setGamma(Integer gamma) {
        this.gamma = gamma;
    }

    /** The patrons, sorted by name. */
    public List<Person> getPatrons() {
        return patrons.stream()
                .sorted(Comparator.comparing(Person::getName).thenComparing(Person::getId))
                .toList();
    }

    public void setPatrons(Set<Person> patrons) {
        this.patrons = new HashSet<>(patrons);
    }

    /** The derived values exist only once alpha, beta and gamma are all given. */
    private boolean complete() {
        return alpha != null && beta != null && gamma != null;
    }

    /** Thrust is alpha multiplied by beta; absent until alpha, beta and gamma are all given. */
    public Integer getThrust() {
        return complete() ? alpha * beta : null;
    }

    /** Drift is gamma minus alpha; absent until alpha, beta and gamma are all given. */
    public Integer getDrift() {
        return complete() ? gamma - alpha : null;
    }

    /** Momentum is thrust plus three times drift; absent while either is absent. */
    public Integer getMomentum() {
        Integer thrust = getThrust();
        Integer drift = getDrift();
        return thrust == null || drift == null ? null : thrust + 3 * drift;
    }

    /** Backing is the sum of the patrons' thrust; absent without patrons or while any patron's thrust is absent. */
    public Integer getBacking() {
        if (patrons.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (Person patron : patrons) {
            Integer thrust = patron.getThrust();
            if (thrust == null) {
                return null;
            }
            sum += thrust;
        }
        return sum;
    }

    /** Net is momentum plus backing, counting backing as zero without patrons; absent with momentum, or with a patron whose thrust is absent. */
    public Integer getNet() {
        Integer momentum = getMomentum();
        if (momentum == null) {
            return null;
        }
        if (patrons.isEmpty()) {
            return momentum;
        }
        Integer backing = getBacking();
        return backing == null ? null : momentum + backing;
    }
    /** Tier classifies momentum: SINK below zero, STEADY up to 199, SURGE from 200; absent with momentum. */
    public String getTier() {
        Integer momentum = getMomentum();
        if (momentum == null) {
            return null;
        }
        return momentum < 0 ? "SINK" : momentum <= 199 ? "STEADY" : "SURGE";
    }
}
