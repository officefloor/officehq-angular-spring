package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person on the list: a name and a whole-number "alpha" (absent until given). */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Integer alpha;

    protected Person() {
    }

    public Person(String name, Integer alpha) {
        this.name = name;
        this.alpha = alpha;
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
}
