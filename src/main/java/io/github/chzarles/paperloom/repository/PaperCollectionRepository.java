package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperCollectionRepository extends JpaRepository<PaperCollection, Long> {

    @Query("SELECT c FROM PaperCollection c WHERE c.owner.id = :ownerId ORDER BY c.updatedAt DESC")
    List<PaperCollection> findByOwnerIdOrderByUpdatedAtDesc(@Param("ownerId") Long ownerId);

    List<PaperCollection> findAllByOrderByUpdatedAtDesc();

    @Query("SELECT c FROM PaperCollection c WHERE c.visibility='ORG' AND c.orgTag IN :orgTags ORDER BY c.updatedAt DESC")
    List<PaperCollection> findOrgVisibleCollections(@Param("orgTags") List<String> orgTags);

    @Query("SELECT c FROM PaperCollection c WHERE c.id = :id AND c.owner.id = :ownerId")
    Optional<PaperCollection> findByIdAndOwnerId(@Param("id") Long id, @Param("ownerId") Long ownerId);
}
