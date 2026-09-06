package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.service.ArxivClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArxivSearchControllerTest {

    @Test
    void authenticatedSearchReturnsMetadataOnlyCandidates() {
        ArxivClient client = mock(ArxivClient.class);
        Authentication authentication = mock(Authentication.class);
        when(client.search(org.mockito.ArgumentMatchers.any())).thenReturn(new ArxivClient.SearchResult(
                List.of(new ArxivClient.Paper(
                        "2401.01234v2", "Paper A", List.of("Ada"),
                        "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z",
                        "Abstract.", "https://arxiv.org/pdf/2401.01234v2")),
                1, "bounded"));

        ArxivSearchController controller = new ArxivSearchController(client);
        Map<String, Object> response = controller.search(
                authentication,
                "request-1",
                new ArxivSearchController.ArxivSearchRequest(
                        "transformer", List.of(), List.of(), null, null, Map.of()));

        assertEquals(1, response.get("returned_count"));
        assertEquals("bounded", response.get("coverage"));
        assertEquals("2401.01234v2", ((Map<?, ?>) ((List<?>) response.get("results")).get(0)).get("arxiv_id"));
        verify(client).search(org.mockito.ArgumentMatchers.any());
    }
}
