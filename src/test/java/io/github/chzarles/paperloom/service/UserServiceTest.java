package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.AppAuthProperties;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.RegistrationMode;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.utils.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AppAuthProperties appAuthProperties;

    @Mock
    private AppAuthProperties.Registration registration;

    @Mock
    private AppAuthProperties.Guest guestProperties;

    @Mock
    private InviteCodeService inviteCodeService;

    @Mock
    private UsageQuotaService usageQuotaService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(appAuthProperties.getRegistration()).thenReturn(registration);
        when(appAuthProperties.getGuest()).thenReturn(guestProperties);
        when(registration.getMode()).thenReturn(RegistrationMode.OPEN);
        when(registration.isInviteRequired()).thenReturn(false);
        when(guestProperties.getDailyLoginAttemptLimit()).thenReturn(100);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), anyString()
        )).thenReturn(1L);
    }

    @Test
    void testRegisterUserSuccessWhenOpenRegistration() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        userService.registerUser("testuser", "password123", null);

        verify(userRepository, atLeastOnce()).save(any(User.class));
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("testuser", savedUser.getUsername());
        verify(inviteCodeService, never()).consume(anyString(), anyString());
    }

    @Test
    void testRegisterUserClosed() {
        when(registration.getMode()).thenReturn(RegistrationMode.CLOSED);

        CustomException exception = assertThrows(CustomException.class,
                () -> userService.registerUser("testuser", "password123", null));

        assertEquals("REGISTRATION_CLOSED", exception.getMessage());
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void testRegisterUserInviteRequired() {
        when(registration.getMode()).thenReturn(RegistrationMode.INVITE_ONLY);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        userService.registerUser("testuser", "password123", "INVITE-001");

        verify(inviteCodeService, times(1)).consume("INVITE-001", "testuser");
    }

    @Test
    void testRegisterUserUsernameExists() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(new User()));

        CustomException exception = assertThrows(CustomException.class,
                () -> userService.registerUser("testuser", "password123", null));

        assertEquals("Username already exists", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void testAuthenticateUserSuccess() {
        String rawPassword = "password123";
        String encodedPassword = PasswordUtil.encode(rawPassword);

        User user = new User();
        user.setUsername("testuser");
        user.setPassword(encodedPassword);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        String username = userService.authenticateUser("testuser", rawPassword);
        assertEquals("testuser", username);
    }

    @Test
    void testAuthenticateUserInvalidCredentials() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class,
                () -> userService.authenticateUser("testuser", "wrongpassword"));

        assertEquals("Invalid username or password", exception.getMessage());
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }

    @Test
    void testCreateGuestUserCreatesIndependentUsers() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User first = userService.createGuestUser();
        User second = userService.createGuestUser();

        assertNotEquals(first.getUsername(), second.getUsername());
        assertEquals(User.Role.GUEST, first.getRole());
        assertEquals(User.Role.GUEST, second.getRole());
        verify(userRepository, times(2)).save(any(User.class));
    }

    @Test
    void testCreateGuestUserRejectsAttemptsAboveDailyLimit() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), anyString()
        )).thenReturn(101L);

        CustomException exception = assertThrows(CustomException.class, userService::createGuestUser);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        verify(userRepository, never()).save(any(User.class));
    }






    @Test
    void testGetUserListKeepsTotalCountAcrossPages() {
        List<User> users = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(index -> {
                    User user = new User();
                    user.setId((long) index);
                    user.setUsername("user-" + index);
                    user.setRole(User.Role.USER);
                    user.setCreatedAt(LocalDateTime.of(2026, 3, 1, 0, 0).minusMinutes(index));
                    return user;
                })
                .toList();

        when(userRepository.findAll(any(Sort.class))).thenReturn(users);
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        Map<String, Object> result = userService.getUserList(null, null, 2, 10);

        assertEquals(25L, result.get("totalElements"));
        assertEquals(3, result.get("totalPages"));
        assertEquals(10, result.get("size"));
        assertEquals(2, result.get("number"));
        assertEquals(10, ((List<?>) result.get("content")).size());
        verify(userRepository).findAll(any(Sort.class));
    }

    @Test
    void testGetUserListDoesNotTreatAdminRoleAsPaused() {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        admin.setCreatedAt(LocalDateTime.of(2026, 3, 1, 0, 0));

        when(userRepository.findAll(any(Sort.class))).thenReturn(List.of(admin));
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        Map<String, Object> result = userService.getUserList(null, null, 1, 10);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("content")).get(0);

        assertEquals(1, row.get("status"));
        assertEquals(User.Role.ADMIN, row.get("role"));
    }
}
