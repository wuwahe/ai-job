package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.common.ai.CompatibleChatClient;
import com.maple.ai.job.hunting.emums.AIFormatEnum;
import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.frame.cache.CustomOpenAiClientCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserAIConfigMapper;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import com.maple.ai.job.hunting.model.vo.DebugPromptVO;
import com.maple.ai.job.hunting.model.vo.JobSeekerClonedResultVO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * 用户AI配置服务
 *
 * @author gaoping
 * @since 2025/04/11
 */
@Service
public class UserAIConfigService {

    @Resource
    private UserAIConfigMapper userAIConfigMapper;

    @Resource
    private CustomOpenAiClientCache customOpenAiClientCache;

    @Resource
    private DebugJobSeekerClonedService debugJobSeekerClonedService;

    /**
     * 保存配置
     */
    public Boolean save(UserAIConfigDO config) {
        UserAIConfigDO existConfig = userAIConfigMapper.selectByUserId(config.getUserId());
        boolean promptOnly = config.getUserPrompt() != null && config.getProvider() == null
                && config.getApiFormat() == null && config.getModelName() == null
                && config.getApiKey() == null && config.getBaseUrl() == null
                && config.getCompletionsPath() == null && config.getTimeout() == null
                && config.getMaxTokens() == null && config.getStatus() == null && config.getTestPassed() == null;
        if (!promptOnly) {
            // Legacy clients keep OpenAI semantics; protocol-specific validation also runs on save.
            config.setApiFormat(AIFormatEnum.resolve(config.getApiFormat()).name());
            config.setMaxTokens(config.getMaxTokens() == null ? 1024 : config.getMaxTokens());
            config.setTimeout(config.getTimeout() == null ? 60 : config.getTimeout());
            config.setProvider(config.getProvider() == null ? 0 : config.getProvider());
            config.setStatus(config.getStatus() == null ? 0 : config.getStatus());
            config.setTestPassed(config.getTestPassed() == null ? 0 : config.getTestPassed());
            validateConfig(config, Integer.valueOf(1).equals(config.getStatus()));
            // Explicit empty strings clear old overrides instead of being skipped by MyBatis updates.
            config.setBaseUrl(StringUtils.trimToEmpty(config.getBaseUrl()));
            config.setCompletionsPath(StringUtils.trimToEmpty(config.getCompletionsPath()));
        } else if (existConfig == null) {
            // A prompt can be saved before configuring an API; the draft must remain disabled.
            config.setProvider(0);
            config.setApiFormat(AIFormatEnum.OPENAI.name());
            config.setMaxTokens(1024);
            config.setApiKey("");
            config.setStatus(0);
            config.setTestPassed(0);
        }

        boolean saved;
        if (existConfig == null) {
            config.setId(null);
            saved = userAIConfigMapper.insert(config) > 0;
        } else {
            config.setId(existConfig.getId());
            // Preserve partial prompt updates and the existing prompt during API-only saves.
            if (config.getUserPrompt() == null) {
                config.setUserPrompt(existConfig.getUserPrompt());
            }
            saved = userAIConfigMapper.updateById(config) > 0;
        }
        if (saved) {
            customOpenAiClientCache.delete(config.getUserId());
        }
        return saved;
    }

    /**
     * 获取当前用户配置
     */
    public UserAIConfigDO getCurrentConfig() {
        return userAIConfigMapper.selectByUserId(HeaderContext.getHeader().getUserId());
    }

    /**
     * 测试配置
     */
    public String testUserApiConfig(UserAIConfigDO config) {

        try {
            validateConfig(config, false);
            CompatibleChatClient client = buildChatClient(config);

            // 发送测试请求
            String response = client.call("你好，你是");
            if (StringUtils.isBlank(response)) {
                throw new ApplicationException("响应为空");
            }

            return response;
        } catch (ApplicationException e) {
            throw new ApplicationException("测试失败：" + e.getMessage(), BizCodeEnum.AI_CONFIG_TEST_FAILED.getCode());
        } catch (Exception e) {
            // Do not expose provider response bodies, API keys, or internal exception details.
            throw new ApplicationException("测试失败，请检查接口配置", BizCodeEnum.AI_CONFIG_TEST_FAILED.getCode());
        }
    }

    public boolean openCustomApi(Set<Integer> productPowerList) {
        if (!productPowerList.contains(ProductTypeEnum.CUSTOM_API.getCode())) {
            return false;
        }
        UserAIConfigDO currentConfig = this.getCurrentConfig();
        return Optional.ofNullable(currentConfig).filter(c -> Integer.valueOf(1).equals(c.getStatus())).isPresent();
    }

    /**
     * 禁用配置
     */
    public Boolean disable(Long id) {
        UserAIConfigDO config = userAIConfigMapper.selectById(id);
        if (config == null) {
            throw new ApplicationException("配置不存在");
        }
        if (!config.getUserId().equals(HeaderContext.getHeader().getUserId())) {
            throw new ApplicationException("无权修改此配置");
        }

        config.setStatus(0);
        boolean disabled = userAIConfigMapper.updateById(config) > 0;
        if (disabled) {
            customOpenAiClientCache.delete(config.getUserId());
        }
        return disabled;
    }

    public void validateConfig(UserAIConfigDO config, boolean checkTestPassed) {
        CompatibleChatClient.validate(config);
        if (checkTestPassed && !Integer.valueOf(1).equals(config.getTestPassed())) {
            throw new ApplicationException("配置未通过测试，请先测试配置");
        }
        if (config.getStatus() != null && config.getStatus() != 0 && config.getStatus() != 1) {
            throw new ApplicationException("配置状态仅支持0或1");
        }
    }

    public static CompatibleChatClient buildChatClient(UserAIConfigDO config) {
        return new CompatibleChatClient(config);
    }

    public JobSeekerClonedResultVO debugUserPrompt(DebugPromptVO debugPromptVO) {
        return debugJobSeekerClonedService.ask(debugPromptVO);
    }
}
