package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.UserService;
import io.github.chzarles.paperloom.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private UserRepository userRepository;

    private UserController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
    }

    @Test
    void guestLoginReusesGuestFromSessionCookie() {
        User guest = new User();
        guest.setUsername("guest_existing");
        guest.setRole(User.Role.GUEST);
        when(jwtUtils.validateRefreshToken("guest-session")).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken("guest-session")).thenReturn("guest_existing");
        when(userRepository.findByUsername("guest_existing")).thenReturn(Optional.of(guest));
        when(jwtUtils.generateToken("guest_existing")).thenReturn("access-token");

        ResponseEntity<?> response = controller.guestLogin("guest-session");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("guest-session", data(response).get("refreshToken"));
        verify(userService, never()).createGuestUser();
        verify(jwtUtils, never()).generateRefreshToken("guest_existing");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<?> response) {
        return (Map<String, Object>) ((Map<String, Object>) response.getBody()).get("data");
    }
}
