package com.maple.ai.job.hunting;

import com.maple.ai.job.hunting.service.ai.helper.MoonshotAiUtils;
import com.maple.ai.job.hunting.service.ai.impl.KimiAIService;
import com.maple.ai.job.hunting.service.biz.ALiPayService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackgroundJobsTest {
    @Test
    void disabledKimiCleanupDoesNotContactTheProvider() {
        KimiAIService service = new KimiAIService();
        MoonshotAiUtils provider = mock(MoonshotAiUtils.class);
        ReflectionTestUtils.setField(service, "moonshotAiUtils", provider);
        assertEquals(true, ReflectionTestUtils.getField(service, "backgroundJobsEnabled"));
        ReflectionTestUtils.setField(service, "backgroundJobsEnabled", false);
        service.init();
        verifyNoInteractions(provider);
    }

    @Test
    void disabledPaymentJobsDoNotScheduleReconciliation() {
        ALiPayService service = new ALiPayService();
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor)
                ReflectionTestUtils.getField(service, "scheduledExecutorService");
        try {
            assertEquals(true, ReflectionTestUtils.getField(service, "backgroundJobsEnabled"));
            ReflectionTestUtils.setField(service, "backgroundJobsEnabled", false);
            service.init();
            assertTrue(executor.getQueue().isEmpty());
            assertEquals(0, executor.getPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }
}
