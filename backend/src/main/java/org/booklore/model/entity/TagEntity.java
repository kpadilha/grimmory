package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.util.BookUtils;
import org.hibernate.Hibernate;
import org.hibernate.annotations.BatchSize;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tag")
public class TagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @ManyToMany(mappedBy = "tags", fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    @Builder.Default
    private Set<BookMetadataEntity> bookMetadataEntityList = new HashSet<>();

    /** Renames; books are refreshed only when their normalised search text can change. */
    public void rename(String newName) {
        if (Objects.equals(name, newName)) return;
        boolean searchTextChanges = !Objects.equals(BookUtils.normalizeForSearch(name), BookUtils.normalizeForSearch(newName));
        this.name = newName;
        if (searchTextChanges) {
            bookMetadataEntityList.forEach(BookMetadataEntity::updateSearchText);
        }
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        TagEntity that = (TagEntity) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
