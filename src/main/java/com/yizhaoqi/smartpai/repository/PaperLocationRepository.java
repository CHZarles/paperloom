package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperLocationRepository extends JpaRepository<PaperLocation, Long> {
    Optional<PaperLocation> findFirstByLocationRef(String locationRef);

    List<PaperLocation> findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(String paperId, String modelVersion);

    List<PaperLocation> findByPaperIdAndModelVersionAndLocationTypeOrderByPageNumberAscIdAsc(String paperId,
                                                                                             String modelVersion,
                                                                                             PaperLocationType locationType);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);
}
