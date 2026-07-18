package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.QdrantProperties;
import io.github.chzarles.paperloom.repository.PaperRetrievalControlRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RetrievalIndexContractServiceTest {

    @Test
    void contractIdentifiesEveryRankingAndSchemaInput() {
        QdrantProperties properties = new QdrantProperties();
        RetrievalIndexContractService service = new RetrievalIndexContractService(
                properties, mock(PaperRetrievalControlRepository.class));

        String contract = service.contractFor(123.5);

        assertTrue(contract.contains("schema=sparse-only-v1"));
        assertTrue(contract.contains("projection=canonical-location-v2"));
        assertTrue(contract.contains("analyzer=unicode-nfkc-lower-min2-v1"));
        assertTrue(contract.contains("term-id=sha256-int31-v1"));
        assertTrue(contract.contains("scorer=bm25-tf-norm-v1"));
        assertTrue(contract.contains("avgdl=123.5"));
        assertTrue(contract.contains("qdrant=1.15.5"));
        assertTrue(contract.contains("modifier=idf"));
        assertTrue(contract.length() <= 255);
    }
}
