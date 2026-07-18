package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperReadingElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperReadingElementRepository extends JpaRepository<PaperReadingElement, Long> {
    List<PaperReadingElement> findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(String paperId,
                                                                                                    String modelVersion);

    List<PaperReadingElement> findByPaperIdAndModelVersionAndElementTypeOrderByPageNumberAscReadingOrderAscIdAsc(String paperId,
                                                                                                                   String modelVersion,
                                                                                                                   String elementType);

    Optional<PaperReadingElement> findFirstByPaperIdAndModelVersionAndReadingElementId(String paperId,
                                                                                       String modelVersion,
                                                                                       String readingElementId);

    List<PaperReadingElement> findByPaperIdAndModelVersionAndElementTypeInOrderByPageNumberAscReadingOrderAscIdAsc(String paperId,
                                                                                                                     String modelVersion,
                                                                                                                     List<String> elementTypes);

    List<PaperReadingElement> findByPaperIdAndModelVersionAndSearchableTextContainingOrderByPageNumberAscReadingOrderAscIdAsc(String paperId,
                                                                                                                               String modelVersion,
                                                                                                                               String queryText);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);

    long countByPaperIdAndModelVersionAndElementType(String paperId, String modelVersion, String elementType);

    void deleteByPaperId(String paperId);

    @Query("""
            select element
            from PaperReadingElement element
            where element.paperId = :paperId
              and element.modelVersion = :modelVersion
              and element.searchableText is not null
              and element.searchableText like concat('%', :queryText, '%')
            order by element.pageNumber asc, element.readingOrder asc, element.id asc
            """)
    List<PaperReadingElement> searchByPaperIdAndModelVersion(@Param("paperId") String paperId,
                                                             @Param("modelVersion") String modelVersion,
                                                             @Param("queryText") String queryText);
}
