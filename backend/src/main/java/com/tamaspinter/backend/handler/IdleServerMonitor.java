package com.tamaspinter.backend.handler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdleServerMonitor {
    private final ConfigurableApplicationContext applicationContext;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AtomicLong lastActivityEpochMillis = new AtomicLong(System.currentTimeMillis());
    
    @Value("${IDLE_TIMEOUT_MINUTES:15}")
    private int idleTimeoutMinutes;
    
    @PostConstruct
    public void startMonitoring() {
        scheduler.scheduleAtFixedRate(
                this::checkIdleStatus, 1, 1, TimeUnit.MINUTES
        );
    }
    
    public void recordActivity() {
        lastActivityEpochMillis.set(System.currentTimeMillis());
    }

    private void checkIdleStatus() {
        long idleMillis = System.currentTimeMillis() - lastActivityEpochMillis.get();
        Duration idleTime = Duration.ofMillis(idleMillis);
        if (idleTime.toMinutes() >= idleTimeoutMinutes) {
            log.info("Server idle for {} minutes, initiating shutdown", idleTime.toMinutes());
            scheduler.shutdown();
            applicationContext.close();
        }
    }
}
