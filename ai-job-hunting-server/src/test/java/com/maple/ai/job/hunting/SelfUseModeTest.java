package com.maple.ai.job.hunting;

import cn.hutool.core.lang.Pair;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.config.AppBizConfig;
import com.maple.ai.job.hunting.config.ProductPermissionConfig;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.frame.cache.ProductNotAuthorizedCache;
import com.maple.ai.job.hunting.frame.cache.SysSessionCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.frame.filter.LoginFilter;
import com.maple.ai.job.hunting.frame.filter.ProductFilter;
import com.maple.ai.job.hunting.mapper.*;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.AIService;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import com.maple.ai.job.hunting.service.biz.*;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SelfUseModeTest {
    private final AppBizConfig config = new AppBizConfig();
    private final UserProductMapper products = mock(UserProductMapper.class);

    private ProductService productService(boolean selfUse) {
        ReflectionTestUtils.setField(config, "selfUseEnabled", selfUse);
        ProductService service = new ProductService();
        ReflectionTestUtils.setField(service, "appBizConfig", config);
        ReflectionTestUtils.setField(service, "userProductMapper", products);
        return service;
    }

    private void login() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.initHeader(user, "127.0.0.1", "/test");
    }

    @AfterEach
    void clearContext() {
        HeaderContext.clear();
    }

    @Test
    void defaultModeStillUsesPurchasedCapabilities() {
        assertFalse(config.isSelfUseEnabled());
        ProductService service = productService(false);
        when(products.queryUserValidAllProductType(42L)).thenReturn(Set.of(ProductTypeEnum.AI_SEAT.getCode()));
        assertTrue(service.hasProductAbility(42L, ProductTypeEnum.AI_SEAT.getCode()));
        assertFalse(service.hasProductAbilityAll(42L, ProductTypeEnum.AI_SEAT, ProductTypeEnum.AI_FILTER));
    }

    @Test
    void selfUseGrantsCapabilitiesOnlyToAnAuthenticatedUserWithoutReadingPurchases() {
        ProductService service = productService(true);
        assertTrue(service.hasProductAbilityAll(42L, ProductTypeEnum.AI_SEAT, ProductTypeEnum.AI_FILTER,
                ProductTypeEnum.CUSTOM_API, ProductTypeEnum.MODEL_FINE_TUNING));
        assertFalse(service.hasProductAbility(null, ProductTypeEnum.AI_SEAT.getCode()));
        verifyNoInteractions(products);
    }

    @Test
    void permissionFilterDoesNotConsumeTrialsInSelfUseMode() throws Exception {
        login();
        ProductFilter filter = new ProductFilter();
        ProductPermissionConfig permissions = new ProductPermissionConfig();
        permissions.initDefaultPermissions();
        UserTrialMapper trials = mock(UserTrialMapper.class);
        ReflectionTestUtils.setField(filter, "productService", productService(true));
        ReflectionTestUtils.setField(filter, "productPermissionConfig", permissions);
        ReflectionTestUtils.setField(filter, "userTrialMapper", trials);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(new MockHttpServletRequest("POST", "/api/user/ai/config/debug"),
                new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
        verifyNoInteractions(trials, products);
    }

    @Test
    void paidModeStillRejectsMissingEntitlementsAndExhaustedTrials() {
        login();
        ProductFilter filter = new ProductFilter();
        ProductPermissionConfig permissions = new ProductPermissionConfig();
        permissions.initDefaultPermissions();
        UserTrialMapper trials = mock(UserTrialMapper.class);
        ReflectionTestUtils.setField(filter, "productService", productService(false));
        ReflectionTestUtils.setField(filter, "appBizConfig", config);
        ReflectionTestUtils.setField(filter, "productPermissionConfig", permissions);
        ReflectionTestUtils.setField(filter, "userTrialMapper", trials);
        ReflectionTestUtils.setField(filter, "userAIConfigService", mock(UserAIConfigService.class));
        ReflectionTestUtils.setField(filter, "productNotAuthorizedCache", mock(ProductNotAuthorizedCache.class));
        when(products.queryUserValidAllProductType(42L)).thenReturn(Set.of());
        when(trials.canTrialGet(any(), any(), any())).thenReturn(Pair.of(false, null));
        FilterChain chain = mock(FilterChain.class);
        assertThrows(ApplicationException.class, () -> filter.doFilter(
                new MockHttpServletRequest("POST", "/api/user/ai/config/debug"), new MockHttpServletResponse(), chain));
        verifyNoInteractions(chain);
    }

    @Test
    void selfUseDoesNotBypassLogin() {
        productService(true);
        LoginFilter filter = new LoginFilter();
        ReflectionTestUtils.setField(filter, "appBizConfig", config);
        ReflectionTestUtils.setField(filter, "freeLoginList", List.of());
        ReflectionTestUtils.setField(filter, "sysSessionCache", mock(SysSessionCache.class));
        FilterChain chain = mock(FilterChain.class);
        assertThrows(ApplicationException.class, () -> filter.doFilter(
                new MockHttpServletRequest("POST", "/api/user/save/preference"), new MockHttpServletResponse(), chain));
        verifyNoInteractions(chain);
    }

    @Test
    void newUserSeatDefaultsToOffAndCanBeEnabledWithoutATrial() {
        login();
        // Initialize MyBatis metadata without loading application configuration or a database.
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "self-use-test"),
                UserInfoDO.class);
        UserService service = new UserService();
        UserInfoMapper users = mock(UserInfoMapper.class);
        UserTrialMapper trials = mock(UserTrialMapper.class);
        UserInfoDO stored = new UserInfoDO();
        stored.setId(42L);
        stored.setPreference("{}");
        when(users.selectById(42L)).thenReturn(stored);
        ReflectionTestUtils.setField(service, "productService", productService(true));
        ReflectionTestUtils.setField(service, "appBizConfig", config);
        ReflectionTestUtils.setField(service, "userInfoMapper", users);
        ReflectionTestUtils.setField(service, "userResumeMapper", mock(UserResumeMapper.class));
        ReflectionTestUtils.setField(service, "userTrialMapper", trials);
        UserInfoVO result = service.getUserInfo();
        assertTrue(result.isSelfUseMode());
        assertEquals(false, result.getAiSeatStatus());
        result.setAiSeatStatus(true);
        // Client mode hints cannot change the server's entitlement decision.
        result.setSelfUseMode(false);
        service.savePreference(result);
        verify(users).update(any(UserInfoDO.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verifyNoInteractions(products, trials);
        stored.setAiSeatStatus(1);
        assertEquals(true, service.getUserInfo().getAiSeatStatus());
    }

    @Test
    void configuredServerModelRemainsAvailableWithoutAPurchasedPlan() {
        login();
        AIServiceFacade facade = new AIServiceFacade();
        AIService model = mock(AIService.class);
        when(model.askAndAnswer("test prompt")).thenReturn("local test response");
        ReflectionTestUtils.setField(facade, "productService", productService(true));
        ReflectionTestUtils.setField(config, "sceneUseAiMap", Map.of("ask", "test"));
        ReflectionTestUtils.setField(facade, "appBizConfig", config);
        ReflectionTestUtils.setField(facade, "aiServiceMap", Map.of("test", model));
        ReflectionTestUtils.setField(facade, "userAIConfigMapper", mock(UserAIConfigMapper.class));
        assertEquals("local test response", facade.askAndAnswer("test prompt"));
        verify(model).askAndAnswer("test prompt");
        verifyNoInteractions(products);
    }

    @Test
    void selfUseDisablesOrderCreationAndPaymentJobsBeforeCallingExternalSystems() {
        productService(true);
        ALiPayService service = new ALiPayService();
        ReflectionTestUtils.setField(service, "appBizConfig", config);
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor)
                ReflectionTestUtils.getField(service, "scheduledExecutorService");
        try {
            // Deliberately leave payment dependencies unset: none may be reached in this mode.
            assertTrue(service.generateOrderGroup(null).isEmpty());
            assertThrows(ApplicationException.class, () -> service.generateOrder(1, "test"));
            service.init();
            assertTrue(executor.getQueue().isEmpty());
            assertEquals(0, executor.getPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }
}
