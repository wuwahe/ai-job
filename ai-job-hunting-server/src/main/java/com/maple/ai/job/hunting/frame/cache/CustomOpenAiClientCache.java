package com.maple.ai.job.hunting.frame.cache;

import com.maple.ai.job.hunting.common.ai.CompatibleChatClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * User-configured chat clients for both supported wire protocols.
 *
 * @author gaoping
 */
@Component
public class CustomOpenAiClientCache extends AbsMemoryCache<Long, CompatibleChatClient> {

    public CustomOpenAiClientCache() {
        // 设置1小时过期时间，重置过期时间
        super(6, TimeUnit.HOURS, true);
    }
}
