package com.SwiftEdgeCDN.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cache")
public class PurgeController {
    private static final Logger logger = LoggerFactory.getLogger(PurgeController.class);

    private final CacheManager cacheManager;

    @Autowired
    public PurgeController(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @RequestMapping(value = "/purge", method = RequestMethod.POST)
    public ResponseEntity<String> purgeCache(@RequestParam(required = false) String key) {
        Cache cache = cacheManager.getCache("cdnCache");
        if (cache != null) {
            if (key != null && !key.isEmpty()) {
                logger.info("Purging cache key: {}", key);
                cache.evict(key);
                return ResponseEntity.ok("Evicted key: " + key);
            } else {
                logger.warn("Purging ENTIRE cache");
                cache.clear();
                return ResponseEntity.ok("Cleared entire cache");
            }
        }
        logger.error("Attempted to purge cache but 'cdnCache' was not found in CacheManager");
        return ResponseEntity.status(500).body("Cache 'cdnCache' not found");
    }
}
