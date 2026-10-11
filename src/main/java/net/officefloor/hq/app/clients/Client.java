package net.officefloor.hq.app.clients;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A client invoices are raised for. */
@Entity
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    /** Payment terms in days; null means the usual end of the following month. */
    private Integer termsDays;

    protected Client() {
    }

    public Client(String name, String email, Integer termsDays) {
        this.name = name;
        this.email = email;
        this.termsDays = termsDays;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Integer getTermsDays() {
        return termsDays;
    }
}
