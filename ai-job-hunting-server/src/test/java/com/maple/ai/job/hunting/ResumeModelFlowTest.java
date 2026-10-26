package com.maple.ai.job.hunting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.common.document.ResumeTextExtractor;
import com.maple.ai.job.hunting.frame.cache.CustomOpenAiClientCache;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserAIConfigMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.impl.CustomOpenAIService;
import com.maple.ai.job.hunting.service.ai.AbstractAIService;
import com.maple.ai.job.hunting.service.biz.UserAIConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResumeModelFlowTest {
    @ParameterizedTest
    @ValueSource(strings = {"OPENAI", "ANTHROPIC"})
    void extractedResumeIsDeliveredAsTextToTheConfiguredModel(String format) throws Exception {
        var extracted = new ResumeTextExtractor().extract(new ByteArrayInputStream(
                ResumeDocuments.docx("测试简历：后端工程师，三年Java经验")));
        AtomicReference<JsonNode> request = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", exchange -> {
            request.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            path.set(exchange.getRequestURI().getPath());
            String response = format.equals("OPENAI") ? "{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}"
                    : "{\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        endpoint.start();
        CustomOpenAIService service = new CustomOpenAIService();
        String sessionId = "resume-test-" + UUID.randomUUID();
        try {
            UserInfoVO user = new UserInfoVO();
            user.setId(42L);
            HeaderContext.initHeader(user, "127.0.0.1", "/test");
            UserAIConfigDO config = new UserAIConfigDO();
            config.setUserId(42L);
            config.setApiFormat(format);
            config.setBaseUrl("http://127.0.0.1:" + endpoint.getAddress().getPort() + "/v1");
            config.setApiKey("local-test-placeholder");
            config.setModelName("selected-resume-model");
            config.setTestPassed(1);
            config.setStatus(1);
            UserAIConfigMapper configs = mock(UserAIConfigMapper.class);
            when(configs.getEnabledConfig(42L)).thenReturn(config);
            when(configs.selectByUserId(42L)).thenReturn(config);
            UserResumeMapper resumes = mock(UserResumeMapper.class);
            when(resumes.selectOne(any())).thenReturn(new UserResumeDO(42L, extracted.text(), "test.docx", "test"));
            // Exercise the real prompt builder and protocol adapter, mocking only persistence.
            ReflectionTestUtils.setField(service, "userAIConfigMapper", configs);
            ReflectionTestUtils.setField(service, AbstractAIService.class, "userAIConfigMapper", configs, null);
            ReflectionTestUtils.setField(service, "userAIConfigService", new UserAIConfigService());
            ReflectionTestUtils.setField(service, "clientCache", mock(CustomOpenAiClientCache.class));
            ReflectionTestUtils.setField(service, "userResumeMapper", resumes);
            ReflectionTestUtils.setField(service, "userInfoMapper", mock(UserInfoMapper.class));
            ReflectionTestUtils.setField(service, "msgSessionMapper", mock(MsgSessionMapper.class));
            service.sessionChat(sessionId, "请介绍你的项目经历");
            assertEquals("selected-resume-model", request.get().path("model").asText());
            JsonNode system = format.equals("OPENAI") ? request.get().path("messages").get(0).path("content")
                    : request.get().path("system");
            assertTrue(system.asText().contains(extracted.text()));
            assertFalse(request.get().has("file_id"));
            assertEquals(format.equals("OPENAI") ? "/v1/chat/completions" : "/v1/messages", path.get());
        } finally {
            service.getMsgSessionMap().remove(sessionId);
            HeaderContext.clear();
            endpoint.stop(0);
        }
    }
}
