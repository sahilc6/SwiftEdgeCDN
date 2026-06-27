package com.SwiftEdgeCDN.controller;

import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.SwiftEdgeCDN.service.CdnProxyService;
import com.SwiftEdgeCDN.service.MetricsService;
import com.SwiftEdgeCDN.service.RateLimitService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class ServiceController {
    private static final Logger logger = LoggerFactory.getLogger(ServiceController.class);

    private final CdnProxyService cdnProxyService;
    private final RateLimitService rateLimitService;
    private final MetricsService metricsService;

    @Autowired
    public ServiceController(CdnProxyService cdnProxyService, RateLimitService rateLimitService,
            MetricsService metricsService) {
        this.cdnProxyService = cdnProxyService;
        this.rateLimitService = rateLimitService;
        this.metricsService = metricsService;
    }

    @RequestMapping(value = { "/service/v1/cdn", "/service/v1/cdn/**" }, method = RequestMethod.GET)
    public ResponseEntity<byte[]> proxyRequest(HttpServletRequest request,
            @RequestParam(required = false) Integer w,
            @RequestParam(required = false) Integer h) throws Exception {

        metricsService.incrementTotalRequests();

        // Rate Limiting
        String ip = request.getRemoteAddr();
        Bucket bucket = rateLimitService.resolveBucket(ip);
        if (!bucket.tryConsume(1)) {
            logger.warn("Rate limit exceeded for IP: {}", ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        String requestUri = request.getRequestURI();
        String path = requestUri.substring(requestUri.indexOf("/service/v1/cdn") + "/service/v1/cdn".length());

        if (path.isEmpty() || path.equals("/")) {
            path = "/150";
        }

        byte[] content = cdnProxyService.fetchAndTransform(path, w, h);
        if (content == null || content.length == 0) {
            logger.info("Asset not found or upstream failed for path: {}", path);
            return ResponseEntity.notFound().build();
        }

        // ETag Generation for Conditional GET
        String etag = "\"" + DigestUtils.md5DigestAsHex(content) + "\"";
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);

        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        headers.setCacheControl("public, max-age=600"); // 10 minutes cache control

        if (etag.equals(ifNoneMatch)) {
            metricsService.addBytesSaved(content.length);
            logger.debug("ETag matched. Returning 304 Not Modified for path: {}", path);
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }

        if (path.endsWith(".png")) {
            headers.setContentType(MediaType.IMAGE_PNG);
        } else if (path.endsWith(".gif")) {
            headers.setContentType(MediaType.IMAGE_GIF);
        } else {
            headers.setContentType(MediaType.IMAGE_JPEG);
        }

        logger.debug("Successfully served asset for path: {}", path);
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }
}
