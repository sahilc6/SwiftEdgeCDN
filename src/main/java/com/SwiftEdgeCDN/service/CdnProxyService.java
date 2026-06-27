package com.SwiftEdgeCDN.service;

import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

@Service
public class CdnProxyService {
    private static final Logger logger = LoggerFactory.getLogger(CdnProxyService.class);

    @Value("#{'${cdn.origin-urls}'.split(',')}")
    private List<String> originUrls;

    private final RestTemplate restTemplate;
    private final MetricsService metricsService;

    @Autowired
    public CdnProxyService(MetricsService metricsService) {
        this.metricsService = metricsService;
        
        // Configure RestTemplate with proper timeouts (3s connect, 5s read)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Cacheable(value = "cdnCache", key = "#path + '-' + (#width != null ? #width : '') + '-' + (#height != null ? #height : '')")
    public byte[] fetchAndTransform(String path, Integer width, Integer height) throws Exception {
        metricsService.incrementCacheMiss();
        logger.info("Cache Miss for path '{}'. Fetching from origin...", path);
        
        byte[] original = fetchFromOriginsWithFailover(path);

        if (original == null) {
            return new byte[0];
        }

        if (width == null && height == null) {
            return original;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(original);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Thumbnails.Builder<? extends InputStream> builder = Thumbnails.of(bais);
            if (width != null && height != null) {
                builder.size(width, height);
            } else if (width != null) {
                builder.width(width);
            } else {
                builder.height(height);
            }

            builder.keepAspectRatio(true).outputFormat("jpg").toOutputStream(baos);
            return baos.toByteArray();
        }
    }

    private byte[] fetchFromOriginsWithFailover(String path) {
        for (String origin : originUrls) {
            try {
                String url = origin.trim() + path;
                logger.debug("Trying origin: {}", url);
                ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    return response.getBody();
                }
            } catch (Exception e) {
                logger.warn("Origin failed: {}. Reason: {}. Trying next...", origin, e.getMessage());
            }
        }
        logger.error("All origins exhausted for path: {}", path);
        return null;
    }
}
