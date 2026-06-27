package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgTagCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ListOperations<String, Object> listOperations;

    @Mock
    private OrganizationTagRepository organizationTagRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrgTagCacheService orgTagCacheService;

    @Test
    void fallsBackToUserOrgTagsFromDatabaseWhenRedisCacheMisses() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());

        User user = new User();
        user.setUsername("eval-litsearch-user");
        user.setOrgTags("eval-litsearch");
        lenient().when(userRepository.findByUsername("eval-litsearch-user")).thenReturn(Optional.of(user));
        lenient().when(organizationTagRepository.findByTagId("eval-litsearch")).thenReturn(Optional.empty());

        List<String> tags = orgTagCacheService.getUserEffectiveOrgTags("eval-litsearch-user");

        assertTrue(tags.contains("eval-litsearch"));
        assertTrue(tags.contains("DEFAULT"));
    }
}
