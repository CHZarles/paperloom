package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.DiscoveryChatMessage;
import io.github.chzarles.paperloom.repository.DiscoveryChatMessageRepository;
import io.github.chzarles.paperloom.repository.DiscoveryMemoryRepository;
import io.github.chzarles.paperloom.service.discovery.ArxivDiscoveryService;
import io.github.chzarles.paperloom.service.discovery.DiscoveryChatService;
import io.github.chzarles.paperloom.service.discovery.DiscoveryRunnerClient;
import io.github.chzarles.paperloom.service.discovery.ExternalPaperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalDiscoveryExternalControllerTest {
    private DiscoveryChatMessageRepository rows;
    private ExternalPaperService external;
    private MockMvc mvc;
    private DiscoveryChatMessage turn;

    @BeforeEach
    void setup() {
        rows = mock(DiscoveryChatMessageRepository.class);
        external = mock(ExternalPaperService.class);
        ObjectMapper mapper = new ObjectMapper();
        DiscoveryChatService chat = new DiscoveryChatService(rows, mock(DiscoveryMemoryRepository.class),
                mock(ArxivDiscoveryService.class), mock(DiscoveryRunnerClient.class), mock(RedisTemplate.class), mapper);
        mvc = MockMvcBuilders.standaloneSetup(new InternalDiscoveryController(chat, mapper, external, "token")).build();
        turn = new DiscoveryChatMessage();
        turn.setTurnId("t1"); turn.setUserId("7"); turn.setRole("ASSISTANT"); turn.setKind("CHAT"); turn.setTurnStatus("RUNNING");
        when(rows.findByTurnId("t1")).thenReturn(Optional.of(turn));
    }

    @Test
    void searchIsTurnBoundAndMetadataNeverEvidence() throws Exception {
        when(external.search("Ding clustering", 5)).thenReturn(Map.of("basis", "metadata_only", "evidence", false, "items", List.of()));
        mvc.perform(post("/internal/v1/discovery/turns/t1/external/search").header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"userId\":\"7\",\"query\":\"Ding clustering\",\"max\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.basis").value("metadata_only"))
                .andExpect(jsonPath("$.evidence").value(false));
        verify(external).search("Ding clustering", 5);
    }

    @Test
    void readDoesNotRequireLibraryOrIngestionAndForwardsExplicitOffsets() throws Exception {
        when(external.read("2309.13420v2", null, 30, 100)).thenReturn(Map.of("status", "partial", "incomplete", true,
                "basis", "external_fulltext", "items", List.of(Map.of("page", 2, "text", "source")), "nextOffset", 130));
        mvc.perform(post("/internal/v1/discovery/turns/t1/external/read").header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"userId\":\"7\",\"arxivId\":\"2309.13420v2\",\"offset\":30,\"maxChars\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextOffset").value(130))
                .andExpect(jsonPath("$.items[0].page").value(2));
        verify(external).read("2309.13420v2", null, 30, 100);
    }

    @Test
    void missingOrWrongInternalTokenCannotFetchExternalContent() throws Exception {
        for (String token : List.of("", "Bearer wrong")) {
            mvc.perform(post("/internal/v1/discovery/turns/t1/external/read").header("Authorization", token)
                            .contentType("application/json").content("{\"userId\":\"7\",\"arxivId\":\"2309.13420\"}"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(external, rows);
    }

    @Test
    void wrongOwnerUnknownOrTerminalTurnIsRejectedBeforeAnyNetwork() throws Exception {
        for (String user : List.of("8", "")) {
            mvc.perform(post("/internal/v1/discovery/turns/t1/external/read").header("Authorization", "Bearer token")
                            .contentType("application/json").content("{\"userId\":\"" + user + "\",\"doi\":\"10.1000/test\"}"))
                    .andExpect(status().isConflict());
        }
        turn.setTurnStatus("COMPLETED");
        mvc.perform(post("/internal/v1/discovery/turns/t1/external/search").header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"userId\":\"7\",\"query\":\"density\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/internal/v1/discovery/turns/unknown/external/read").header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"userId\":\"7\",\"doi\":\"10.1000/test\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(external);
    }

    @Test
    void distillAndUserRoleTurnsCannotFetchExternalContent() throws Exception {
        turn.setKind("DISTILL");
        mvc.perform(post("/internal/v1/discovery/turns/t1/external/read").header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"userId\":\"7\",\"doi\":\"10.1000/test\"}"))
                .andExpect(status().isConflict());
        turn.setKind("CHAT"); turn.setRole("USER");
        mvc.perform(post("/internal/v1/discovery/turns/t1/external/read").header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"userId\":\"7\",\"doi\":\"10.1000/test\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(external);
    }
}
