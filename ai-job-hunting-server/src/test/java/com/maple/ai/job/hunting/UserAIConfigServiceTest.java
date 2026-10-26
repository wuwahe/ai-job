package com.maple.ai.job.hunting;

import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.common.ai.CompatibleChatClient;
import com.maple.ai.job.hunting.frame.cache.CustomOpenAiClientCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserAIConfigMapper;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.impl.CustomOpenAIService;
import com.maple.ai.job.hunting.service.biz.UserAIConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAIConfigServiceTest {
    private UserAIConfigMapper mapper;
    private CustomOpenAiClientCache cache;
    private UserAIConfigService service;

    @BeforeEach
    void configureServiceWithoutApplicationContext() {
        mapper = mock(UserAIConfigMapper.class);
        cache = mock(CustomOpenAiClientCache.class);
        service = new UserAIConfigService();
        ReflectionTestUtils.setField(service, "userAIConfigMapper", mapper);
        ReflectionTestUtils.setField(service, "customOpenAiClientCache", cache);
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.initHeader(user, "127.0.0.1", "/test");
    }

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void savesSelectedProtocolAndInvalidatesClientAfterDatabaseSuccess() {
        UserAIConfigDO previous = configuration();
        previous.setUserPrompt("preserved prompt");
        when(mapper.selectByUserId(42L)).thenReturn(previous);
        when(mapper.updateById(any())).thenReturn(1);
        UserAIConfigDO replacement = configuration();
        replacement.setApiFormat("ANTHROPIC");
        assertTrue(service.save(replacement));
        assertEquals("ANTHROPIC", replacement.getApiFormat());
        assertEquals(1024, replacement.getMaxTokens());
        assertEquals("preserved prompt", replacement.getUserPrompt());
        var order = inOrder(mapper, cache);
        order.verify(mapper).updateById(replacement);
        order.verify(cache).delete(42L);
    }

    @Test
    void promptOnlyUpdatePreservesProtocolCredentialsAndTestState() {
        when(mapper.selectByUserId(42L)).thenReturn(configuration());
        when(mapper.updateById(any())).thenReturn(1);
        UserAIConfigDO prompt = new UserAIConfigDO();
        prompt.setUserId(42L);
        prompt.setUserPrompt("changed prompt");
        assertTrue(service.save(prompt));
        assertNull(prompt.getApiFormat());
        assertNull(prompt.getApiKey());
        assertNull(prompt.getStatus());
        assertNull(prompt.getTestPassed());
        assertEquals(7L, prompt.getId());
    }

    @Test
    void firstPromptCreatesDisabledDraftAndLegacyApiSaveDefaultsToOpenAi() {
        when(mapper.insert(any())).thenReturn(1);
        UserAIConfigDO prompt = new UserAIConfigDO();
        prompt.setUserId(42L);
        prompt.setUserPrompt("first prompt");
        assertTrue(service.save(prompt));
        assertEquals(0, prompt.getStatus());
        assertEquals(0, prompt.getTestPassed());
        assertEquals("", prompt.getApiKey());

        UserAIConfigDO legacy = configuration();
        legacy.setApiFormat(null);
        assertTrue(service.save(legacy));
        assertEquals("OPENAI", legacy.getApiFormat());
        assertNull(legacy.getId());
    }

    @Test
    void untestedEnabledConfigAndInvalidFormatsCannotBeSaved() {
        UserAIConfigDO config = configuration();
        config.setTestPassed(null);
        assertThrows(ApplicationException.class, () -> service.save(config));
        config.setTestPassed(1);
        config.setApiFormat("unsupported");
        assertThrows(ApplicationException.class, () -> service.save(config));
        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void clientLookupRebuildsStaleCacheAndRejectsDisabledConfig() {
        UserAIConfigDO config = configuration();
        CompatibleChatClient stale = new CompatibleChatClient(config);
        when(cache.get(42L)).thenReturn(stale);
        config.setApiFormat("ANTHROPIC");
        when(mapper.getEnabledConfig(42L)).thenReturn(config);
        CustomOpenAIService custom = new CustomOpenAIService();
        ReflectionTestUtils.setField(custom, "clientCache", cache);
        ReflectionTestUtils.setField(custom, "userAIConfigMapper", mapper);
        ReflectionTestUtils.setField(custom, "userAIConfigService", service);
        CompatibleChatClient refreshed = ReflectionTestUtils.invokeMethod(custom, "getClient");
        assertNotSame(stale, refreshed);
        assertTrue(refreshed.matches(config));
        verify(cache).set(42L, refreshed);

        when(mapper.getEnabledConfig(42L)).thenReturn(null);
        assertThrows(ApplicationException.class, () -> ReflectionTestUtils.invokeMethod(custom, "getClient"));
    }

    private UserAIConfigDO configuration() {
        UserAIConfigDO config = new UserAIConfigDO();
        config.setId(7L);
        config.setUserId(42L);
        config.setProvider(0);
        config.setApiFormat("OPENAI");
        config.setBaseUrl("https://example.com/v1");
        config.setApiKey("test-placeholder");
        config.setModelName("arbitrary-model");
        config.setStatus(1);
        config.setTestPassed(1);
        return config;
    }
}
