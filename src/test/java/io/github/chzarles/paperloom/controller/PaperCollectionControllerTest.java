package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.controller.dto.CollectionRequests.AddCollectionPapersRequest;
import io.github.chzarles.paperloom.controller.dto.CollectionRequests.CreateCollectionRequest;
import io.github.chzarles.paperloom.controller.dto.CollectionRequests.UpdateCollectionRequest;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.PaperCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperCollectionControllerTest {

    @Mock
    private PaperCollectionService service;

    private PaperCollectionController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new PaperCollectionController(service);
    }

    @Test
    void listReturns200WithData() {
        List<Map<String, Object>> data = List.of(Map.of(
                "id", 12L,
                "name", "Agent papers",
                "visibility", "PRIVATE",
                "paperCount", 0,
                "searchablePaperCount", 0
        ));
        when(service.listCollections(1L)).thenReturn(data);

        var response = controller.listCollections("1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(200, response.getBody().get("code"));
        assertEquals(data, response.getBody().get("data"));
        verify(service).listCollections(1L);
    }

    @Test
    void createPassesRequestAndUserId() {
        CreateCollectionRequest request = new CreateCollectionRequest(
                "Agent papers",
                "Agent system reading set",
                "PRIVATE",
                null
        );
        Map<String, Object> dto = Map.of(
                "id", 12L,
                "name", "Agent papers",
                "visibility", "PRIVATE",
                "paperCount", 0,
                "searchablePaperCount", 0
        );
        when(service.createCollection(1L, request)).thenReturn(dto);

        var response = controller.createCollection(request, "1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(dto, response.getBody().get("data"));
        verify(service).createCollection(1L, request);
    }

    @Test
    void forbiddenServiceExceptionReturns403() {
        UpdateCollectionRequest request = new UpdateCollectionRequest("Edited", "Nope", "ORG", "lab");
        when(service.updateCollection(2L, 12L, request))
                .thenThrow(new CustomException("Forbidden", HttpStatus.FORBIDDEN));

        var response = controller.updateCollection(12L, request, "2");

        assertEquals(403, response.getStatusCode().value());
        assertEquals(403, response.getBody().get("code"));
        assertEquals("Forbidden", response.getBody().get("message"));
    }

    @Test
    void addPapersPassesStaticPaperIdList() {
        AddCollectionPapersRequest request = new AddCollectionPapersRequest(List.of("paper-1", "paper-2"));
        Map<String, Object> dto = Map.of(
                "id", 12L,
                "name", "Agent papers",
                "paperCount", 2,
                "searchablePaperCount", 2,
                "paperIds", List.of("paper-1", "paper-2")
        );
        when(service.addPapers(1L, 12L, request)).thenReturn(dto);

        var response = controller.addPapers(12L, request, "1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(dto, response.getBody().get("data"));
        verify(service).addPapers(1L, 12L, request);
    }

    @Test
    void deleteMemberMapsToService() {
        doNothing().when(service).removePaper(1L, 12L, "paper-1");

        var response = controller.removePaper(12L, "paper-1", "1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(200, response.getBody().get("code"));
        verify(service).removePaper(1L, 12L, "paper-1");
    }

    @Test
    void invalidUserIdReturns400() {
        var response = controller.listCollections("not-a-number");

        assertEquals(400, response.getStatusCode().value());
        assertEquals(400, response.getBody().get("code"));
    }

    @Test
    void deleteMemberMapsForbiddenException() {
        doThrow(new CustomException("Forbidden", HttpStatus.FORBIDDEN))
                .when(service).removePaper(2L, 12L, "paper-1");

        var response = controller.removePaper(12L, "paper-1", "2");

        assertEquals(403, response.getStatusCode().value());
        assertEquals("Forbidden", response.getBody().get("message"));
    }
}
