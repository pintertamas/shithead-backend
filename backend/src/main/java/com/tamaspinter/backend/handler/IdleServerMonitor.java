package com.tamaspinter.backend.handler;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class IdleServerMonitor {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private volatile LocalDateTime lastActivity = LocalDateTime.now();
    
    @Value("${IDLE_TIMEOUT_MINUTES:15}")
    private int idleTimeoutMinutes;
    
    @PostConstruct
    public void startMonitoring() {
        scheduler.scheduleAtFixedRate(
                this::checkIdleStatus, 1, 1, TimeUnit.MINUTES
        );
    }
    
    public void recordActivity() {
        lastActivity = LocalDateTime.now();
    }
    
    private void checkIdleStatus() {
        Duration idleTime = Duration.between(lastActivity, LocalDateTime.now());
        if (idleTime.toMinutes() >= idleTimeoutMinutes) {
            log.info("Server idle for {} minutes, initiating shutdown", idleTime.toMinutes());
            System.exit(0);  // ECS will handle the restart when needed
        }
    }
}