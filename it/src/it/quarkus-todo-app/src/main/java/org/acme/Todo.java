package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "todos")
public class Todo extends PanacheEntity {
    @Column(nullable = false)
    public String title;

    public boolean completed;
}
