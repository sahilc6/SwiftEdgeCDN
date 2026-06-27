package com.SwiftEdgeCDN.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.SwiftEdgeCDN.service.MetricsService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    @Autowired
    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @RequestMapping(method = RequestMethod.GET)
    public Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("cacheHits", metricsService.getCacheHits());
        metrics.put("cacheMisses", metricsService.getCacheMisses());
        metrics.put("bytesSaved", metricsService.getBytesSaved());
        return metrics;
    }
}
