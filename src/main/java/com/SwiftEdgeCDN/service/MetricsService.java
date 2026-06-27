package com.SwiftEdgeCDN.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsService {
    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong bytesSaved = new AtomicLong(0);

    public void incrementTotalRequests() {
        totalRequests.incrementAndGet();
    }

    public void incrementCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public void addBytesSaved(long bytes) {
        bytesSaved.addAndGet(bytes);
    }

    public long getCacheHits() { return totalRequests.get() - cacheMisses.get(); }
    public long getCacheMisses() { return cacheMisses.get(); }
    public long getBytesSaved() { return bytesSaved.get(); }
    public long getTotalRequests() { return totalRequests.get(); }
}
