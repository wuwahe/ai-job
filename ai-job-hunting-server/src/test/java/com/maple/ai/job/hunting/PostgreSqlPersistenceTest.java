package com.maple.ai.job.hunting;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.config.MybatisPlusConfig;
import com.maple.ai.job.hunting.emums.AITypeEnum;
import com.maple.ai.job.hunting.emums.MsgSessionStatusEnum;
import com.maple.ai.job.hunting.emums.OrderStatusEnum;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.frame.cache.ProductNotAuthorizedCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.entity.OrderDO;
import com.maple.ai.job.hunting.mapper.*;
import com.maple.ai.job.hunting.model.bo.*;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.biz.ProductService;
import com.maple.ai.job.hunting.service.biz.UserAIConfigService;
import com.maple.ai.job.hunting.service.biz.UserService;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Exercises the production schema and mappers without loading application secrets or external services.
 */
@EnabledIfEnvironmentVariable(named = "AI_JOB_TEST_JDBC_URL", matches = ".+")
class PostgreSqlPersistenceTest {

    private static SqlSessionFactory sessionFactory;
    private SqlSession session;

    @BeforeAll
    static void configureMappers() {
        String url = System.getenv("AI_JOB_TEST_JDBC_URL");
        // Only a dedicated local test database is accepted, without connection-string overrides.
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/ai_job_test"),
                "Use a dedicated local ai_job_test database");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                "ai_job_test", System.getenv().getOrDefault("AI_JOB_TEST_DB_PASSWORD", ""));
        dataSource.setDriverClassName("org.postgresql.Driver");

        // Reuse production plugins and field filling without starting AI, payment, or mail jobs.
        MybatisPlusConfig productionConfig = new MybatisPlusConfig();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("postgresql-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(productionConfig));
        configuration.addInterceptor(productionConfig.mybatisPlusInterceptor());
        configuration.addMappers("com.maple.ai.job.hunting.mapper");
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        HeaderContext.clear();
        session = sessionFactory.openSession(false);
        Connection connection = session.getConnection();
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        // PostgreSQL DDL is transactional: both tables and fixtures disappear on rollback.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET LOCAL search_path TO " + schema);
        }
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }

    @AfterEach
    void rollBackSchemaAndFixtures() {
        // Force rollback even if setup failed before a MyBatis write marked the session dirty.
        if (session != null) {
            try {
                session.rollback(true);
            } finally {
                session.close();
            }
        }
        HeaderContext.clear();
    }

    @Test
    void generatedIdsAuditFieldsAndBooleanSoftDeleteWork() throws Exception {
        UserInfoMapper mapper = session.getMapper(UserInfoMapper.class);
        UserInfoDO user = new UserInfoDO(null, null, "postgres-user");
        assertEquals(1, mapper.insert(user));
        assertNotNull(user.getId());
        UserInfoDO stored = mapper.selectById(user.getId());
        assertEquals(Boolean.TRUE, stored.getIsActive());
        assertNotNull(stored.getCreatedDate());
        assertEquals(1L, stored.getCreatedId());

        // Verify updates and ensure deleted rows are excluded from later writes and reads.
        user.setPreference("{\"language\":\"中文 😀\"}");
        assertEquals(1, mapper.updateById(user));
        assertEquals(user.getPreference(), mapper.selectById(user.getId()).getPreference());
        assertEquals(1, mapper.deleteById(user.getId()));
        assertNull(mapper.selectById(user.getId()));
        assertEquals(0, mapper.updateById(user));
        try (var statement = session.getConnection().prepareStatement(
                "SELECT is_active FROM user_info WHERE id = ?")) {
            statement.setLong(1, user.getId());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1));
            }
        }
    }

    @Test
    void preferenceUpdatesPreserveIntegerSeatStatesAndOtherUsers() {
        UserInfoMapper mapper = session.getMapper(UserInfoMapper.class);
        UserInfoDO user = new UserInfoDO(null, null, "seat-user");
        mapper.insert(user);
        UserInfoDO other = new UserInfoDO(null, null, "other-seat-user");
        other.setAiSeatStatus(0);
        mapper.insert(other);
        UserProductMapper products = mock(UserProductMapper.class);
        when(products.queryUserValidAllProductType(user.getId()))
                .thenReturn(Set.of(ProductTypeEnum.AI_SEAT.getCode()));
        UserService service = preferenceService(mapper, products);
        UserInfoVO request = new UserInfoVO();
        request.setId(user.getId());
        HeaderContext.initHeader(request, "127.0.0.1", "/test/preferences");

        // Exercise the real service and JDBC binding for all three API states.
        Boolean[] inputs = {true, false, null};
        Integer[] expected = {1, 0, null};
        for (int index = 0; index < inputs.length; index++) {
            request.setAiSeatStatus(inputs[index]);
            request.setEmail("seat-" + index + "@example.com");
            service.savePreference(request);
            UserInfoDO stored = mapper.selectById(user.getId());
            assertEquals(expected[index], stored.getAiSeatStatus());
            assertEquals(request.getEmail(), stored.getEmail());
            assertEquals(0, mapper.selectById(other.getId()).getAiSeatStatus());
        }
        // Closing or clearing the status must not require an active product.
        verify(products, times(1)).queryUserValidAllProductType(user.getId());
    }

    @Test
    void unauthorizedSeatEnableCannotChangeStoredPreferences() {
        UserInfoMapper mapper = session.getMapper(UserInfoMapper.class);
        UserInfoDO user = new UserInfoDO(null, "original@example.com", "denied-seat-user");
        user.setAiSeatStatus(0);
        mapper.insert(user);
        UserProductMapper products = mock(UserProductMapper.class);
        when(products.queryUserValidAllProductType(user.getId())).thenReturn(Set.of());
        UserService service = preferenceService(mapper, products);
        UserInfoVO request = new UserInfoVO();
        request.setId(user.getId());
        request.setAiSeatStatus(true);
        request.setEmail("changed@example.com");
        HeaderContext.initHeader(request, "127.0.0.1", "/test/preferences");

        // Denied product, custom API, and trial checks must prevent every database update.
        assertThrows(ApplicationException.class, () -> service.savePreference(request));
        UserInfoDO stored = mapper.selectById(user.getId());
        assertEquals(0, stored.getAiSeatStatus());
        assertEquals("original@example.com", stored.getEmail());
    }

    private UserService preferenceService(UserInfoMapper mapper, UserProductMapper products) {
        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userInfoMapper", mapper);
        ProductService productService = new ProductService();
        ReflectionTestUtils.setField(productService, "userProductMapper", products);
        ReflectionTestUtils.setField(productService, "appBizConfig", new com.maple.ai.job.hunting.config.AppBizConfig());
        ReflectionTestUtils.setField(service, "productService", productService);
        // Keep external integrations isolated while retaining the real database write path.
        ReflectionTestUtils.setField(service, "userAIConfigService", mock(UserAIConfigService.class));
        ReflectionTestUtils.setField(service, "userTrialMapper", mock(UserTrialMapper.class));
        ReflectionTestUtils.setField(service, "productNotAuthorizedCache", mock(ProductNotAuthorizedCache.class));
        return service;
    }

    @Test
    void paginationCountsOnlyActiveRowsAndAppliesOffset() {
        UserInfoMapper mapper = session.getMapper(UserInfoMapper.class);
        // Three active records make the second page exercise both LIMIT and OFFSET.
        for (int index = 0; index < 3; index++) {
            mapper.insert(new UserInfoDO(null, null, "page-" + index));
        }
        UserInfoDO deleted = new UserInfoDO(null, null, "deleted");
        mapper.insert(deleted);
        mapper.deleteById(deleted.getId());

        Page<UserInfoDO> page = mapper.selectPage(new Page<>(2, 2),
                new LambdaQueryWrapper<UserInfoDO>().orderByAsc(UserInfoDO::getId));
        assertEquals(3, page.getTotal());
        assertEquals(1, page.getRecords().size());
        assertEquals("page-2", page.getRecords().get(0).getUniqueId());
    }

    @Test
    void quotedOrderTablePreservesAssignedIdsAndDecimalPrices() {
        OrderMapper mapper = session.getMapper(OrderMapper.class);
        OrderDO order = new OrderDO();
        order.setId(9_000_000_000L);
        order.setUserId(5_000_000_000L);
        order.setStatus(OrderStatusEnum.PRE_GENERATE.getCode());
        order.setPrice(new BigDecimal("123.4567"));
        mapper.insert(order);
        mapper.updateOrderStatus(order.getId(), OrderStatusEnum.PAID);

        OrderDO stored = mapper.selectById(order.getId());
        assertEquals(order.getId(), stored.getId());
        assertEquals(order.getUserId(), stored.getUserId());
        assertEquals(order.getPrice(), stored.getPrice());
        assertEquals(OrderStatusEnum.PAID.getCode(), stored.getStatus());
    }

    @Test
    void quotedDescriptionAndTrialIncrementWork() {
        UserTrialMapper mapper = session.getMapper(UserTrialMapper.class);
        UserTrialDO trial = new UserTrialDO(5_000_000_000L, 1, 0, "trial");
        // Exercise both the insert branch and the in-database counter update.
        mapper.incrTrialCount(trial);
        assertNotNull(trial.getId());
        mapper.incrTrialCount(trial);
        UserTrialDO stored = mapper.selectById(trial.getId());
        assertEquals(2, stored.getTrialCount());
        assertEquals(trial.getDesc(), stored.getDesc());
        assertEquals(1, mapper.getUserTrial(trial.getUserId(), 1).size());
    }

    @Test
    void stringSessionIdsAreBoundAsBigintAndInvalidIdsFailBeforeWriting() {
        MsgSessionMapper mapper = session.getMapper(MsgSessionMapper.class);
        MsgSessionDO message = new MsgSessionDO();
        message.setUserId(5_000_000_000L);
        message.setSessionKey("postgres-session");
        message.setStatus(MsgSessionStatusEnum.IN_USE.getCode());
        mapper.insert(message);
        mapper.updateMsgContextAndAiType(message.getId().toString(), "消息 😀", AITypeEnum.OPENAI);
        assertEquals("消息 😀", mapper.selectBySessionKey(message.getSessionKey()).getMsgContext());
        assertEquals(1, mapper.selectUseListByType(AITypeEnum.OPENAI).size());

        // Reject malformed numeric identifiers without relying on implicit database casts.
        assertThrows(NumberFormatException.class,
                () -> mapper.updateMsgContextAndAiType("invalid", "changed", AITypeEnum.OPENAI));
        assertEquals("消息 😀", mapper.selectById(message.getId()).getMsgContext());
    }

    @Test
    void integerConfigurationFlagsAndLatestQueriesWork() {
        UserAIConfigMapper mapper = session.getMapper(UserAIConfigMapper.class);
        UserAIConfigDO enabled = new UserAIConfigDO();
        enabled.setUserId(5_000_000_000L);
        enabled.setApiKey("test-placeholder");
        enabled.setStatus(1);
        enabled.setTestPassed(1);
        enabled.setApiFormat("ANTHROPIC");
        enabled.setMaxTokens(4096);
        mapper.insert(enabled);

        UserAIConfigDO disabled = new UserAIConfigDO();
        disabled.setUserId(enabled.getUserId());
        disabled.setApiKey("test-placeholder");
        disabled.setStatus(0);
        mapper.insert(disabled);
        assertEquals(enabled.getId(), mapper.getEnabledConfig(enabled.getUserId()).getId());
        assertEquals("ANTHROPIC", mapper.getEnabledConfig(enabled.getUserId()).getApiFormat());
        assertEquals(4096, mapper.getEnabledConfig(enabled.getUserId()).getMaxTokens());
        assertEquals(disabled.getId(), mapper.getLatestConfig(enabled.getUserId()).getId());
        assertEquals(0, mapper.selectByUserId(enabled.getUserId()).getTestPassed());
        assertEquals("OPENAI", mapper.selectByUserId(enabled.getUserId()).getApiFormat());
        assertEquals(1024, mapper.selectByUserId(enabled.getUserId()).getMaxTokens());

        // Soft deletion must also filter the custom LIMIT 1 query.
        mapper.deleteById(enabled.getId());
        assertNull(mapper.getEnabledConfig(enabled.getUserId()));
    }

    @Test
    void resumeTextAndProductValidityRoundTrip() {
        UserResumeMapper resumes = session.getMapper(UserResumeMapper.class);
        UserResumeDO resume = new UserResumeDO(5_000_000_000L, "简历 😀", "test-object", "test-resume");
        resume.setPresetProblem("{\"question\":\"测试\"}");
        resumes.insert(resume);
        assertEquals(resume.getResumeContent(), resumes.selectByUserId(resume.getUserId()).getResumeContent());
        assertEquals(resume.getPresetProblem(), resumes.selectById(resume.getId()).getPresetProblem());

        UserProductMapper products = session.getMapper(UserProductMapper.class);
        UserProductDO product = new UserProductDO();
        product.setUserId(resume.getUserId());
        product.setProductType("[1,2]");
        product.setPeriodOfValidityStartTime(new Date(System.currentTimeMillis() - 60_000));
        product.setPeriodOfValidityEndTime(new Date(System.currentTimeMillis() + 60_000));
        products.insert(product);
        assertEquals(product.getPeriodOfValidityEndTime(), products.selectById(product.getId()).getPeriodOfValidityEndTime());
        assertEquals(Set.of(1, 2), products.queryUserValidAllProductType(product.getUserId()));

        // Expiration is compared using timestamps, independently of the JSON text representation.
        product.setPeriodOfValidityEndTime(new Date(System.currentTimeMillis() - 1_000));
        products.updateById(product);
        assertTrue(products.queryUserValidAllProduct(product.getUserId()).isEmpty());
    }

    @Test
    void inviteUniquenessAndLongIdentifiersArePreserved() {
        UserInvitesMapper mapper = session.getMapper(UserInvitesMapper.class);
        UserInvitesDO invite = new UserInvitesDO("test-invite", 5_000_000_000L,
                5_000_000_001L, "test-user", 1);
        mapper.insert(invite);
        assertNotNull(invite.getId());
        assertEquals(1, mapper.queryBeInviteeList(invite.getToInviterUserId()).size());

        // A duplicate invitee must still violate the original business uniqueness constraint.
        UserInvitesDO duplicate = new UserInvitesDO("other-invite", invite.getToInviterUserId(),
                invite.getBeInviteeUserId(), "other-user", 1);
        PersistenceException error = assertThrows(PersistenceException.class, () -> mapper.insert(duplicate));
        assertInstanceOf(java.sql.SQLException.class, error.getCause());
        assertEquals("23505", ((java.sql.SQLException) error.getCause()).getSQLState());
    }

    @Test
    void upgradesLegacyConfigurationWithoutLosingItsSettings() throws Exception {
        Connection connection = session.getConnection();
        // Recreate the previous PostgreSQL schema in a separate transactional namespace.
        String legacySchema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + legacySchema);
            statement.execute("SET LOCAL search_path TO " + legacySchema);
        }
        String schema;
        try (var input = new ClassPathResource("schema.sql").getInputStream()) {
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.contains("api_format") && !line.contains("max_tokens"))
                    .collect(Collectors.joining("\n"));
        }
        ScriptUtils.executeSqlScript(connection, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO user_ai_config (user_id, api_key, model_name, status, test_passed, is_active) "
                    + "VALUES (42, 'test-placeholder', 'legacy-model', 1, 1, true)");
        }
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("migrations/20261024_add_ai_api_format.sql"));

        UserAIConfigDO upgraded = session.getMapper(UserAIConfigMapper.class).getEnabledConfig(42L);
        assertEquals("OPENAI", upgraded.getApiFormat());
        assertEquals(1024, upgraded.getMaxTokens());
        assertEquals("legacy-model", upgraded.getModelName());
        assertEquals("test-placeholder", upgraded.getApiKey());
        assertEquals(1, upgraded.getTestPassed());
    }
}
