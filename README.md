# SwiftEdgeCDN - Edge Caching Proxy

## 📖 1. Project Overview and Purpose

**SwiftEdgeCDN** is a high-performance edge-caching reverse proxy built with Java and Spring Boot. Inspired by the core edge caching concepts used by enterprise Content Delivery Networks (CDNs) such as Akamai and Cloudflare, it acts as an optimized intermediary between end users and origin servers.

The primary purpose of this project is to:

1. **Cache static assets at the edge** to drastically reduce latency and Time to First Byte (TTFB).
2. **Perform on-the-fly image optimization** (Edge Compute) to reduce bandwidth payloads.
3. **Reduce origin server load** by serving repetitive requests directly from memory.
4. **Protect the infrastructure** from Layer 7 DDoS attacks via a Web Application Firewall (WAF) implementing IP-based rate limiting.

This project serves as a comprehensive demonstration of distributed system concepts, high-concurrency Java engineering, memory management, and HTTP protocol mastery.

---

## 🎯 2. Problem Statement and Motivation

In modern distributed web architecture, serving static assets (especially large images) directly from a primary backend application server introduces severe bottlenecks:

- **High Network Latency**: The speed of light and fiber-optic routing limitations mean a user in Tokyo requesting an image from a server in New York will always experience high latency. Edge nodes solve this by placing the data closer to the user.
- **Bandwidth Exorbitance**: Continuously serving unoptimized, 5MB images to mobile devices consumes expensive server bandwidth and drains the user's mobile data.
- **Thundering Herd & Server Overload**: Sudden traffic spikes (e.g., a viral post) can overwhelm the origin server's thread pool, causing cascading failures and downtime.
- **Security Vulnerabilities**: Publicly exposed origins without rate-limiting are highly susceptible to malicious scraping and Distributed Denial of Service (DDoS) attacks.

**Motivation**: I engineered SwiftEdgeCDN to tackle these exact problems. Building a CDN proxy from scratch provided an opportunity to deeply understand the mechanics of proxying, the intricacies of HTTP caching headers (ETag, If-None-Match), the mathematics behind rate-limiting algorithms, and JVM memory tuning.

---

## 🏗 3. Architecture and System Design

```text
┌─────────────────────────────────────────────────────────┐
│                  Clients & Dashboards                   │
│           (End Users & React Metrics Panel)             │
└────────────────────────┬────────────────────────────────┘
                         │
                     HTTP / REST
                         │
┌────────────────────────▼────────────────────────────────┐
│             Spring Boot Edge Server (Java)              │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │           WAF & Rate Limiting Filter             │   │
│  │           (Bucket4j - Token Bucket)              │   │
│  └──────────────────────┬───────────────────────────┘   │
│                         │                               │
│  ┌──────────────────────▼───────────────────────────┐   │
│  │                  Controllers                     │   │
│  │  ProxyController | PurgeController | MetricsAPI  │   │
│  └──────────────────────┬───────────────────────────┘   │
│                         │                               │
│  ┌──────────────────────▼───────────────────────────┐   │
│  │            Caching & Validation Layer            │   │
│  │          ETag Validator | Caffeine L1 Cache      │   │
│  └───────────────┬───────────────┬──────────────────┘   │
│                  │               │                      │
│            (Cache Hit)     (Cache Miss)                 │
│                  │               │                      │
│  ┌───────────────▼─┐     ┌───────▼───────────────┐      │
│  │  Edge Compute   │     │     Origin Router     │      │
│  │ (Thumbnailator) │◄────┤    (Load Balancer)    │      │
│  └─────────────────┘     └───────┬───────────────┘      │
│                                  │                      │
└──────────────────────────────────┼──────────────────────┘
                                   │
                  (Fetch Resource & Handle Failover)
                                   │
┌──────────────────────────────────▼──────────────────────┐
│                 Upstream Origin Servers                 │
│  Primary (via.placeholder.com) | Secondary (dummyimage) │
└─────────────────────────────────────────────────────────┘
```

The system follows a strict, layered Edge Node architecture. The layers are completely decoupled to adhere to the Single Responsibility Principle (SRP).

1. **Client Request**: The entry point. The Tomcat embedded server accepts the TCP connection and hands off the HTTP request to the Spring `DispatcherServlet`.
2. **Security & Rate Limiting (WAF)**: Before any heavy processing or caching occurs, the IP is evaluated against a concurrent Token Bucket.
3. **ETag Validation**: The system checks if the client already has the latest version of the file, short-circuiting the request if possible to save bandwidth.
4. **L1 Cache Lookup**: The proxy queries the Caffeine cache.
5. **Origin Router (Failover)**: On a cache miss, the system acts as an HTTP client, reaching out to the configured origins. It implements a failover loop (Active-Passive load balancing).
6. **Edge Compute**: The raw bytes retrieved from the origin are manipulated in-memory (resizing/cropping).
7. **Cache & Respond**: The optimized payload is saved in the cache and flushed to the client's output stream.

---

## 💻 4. Tech Stack and Justification

- **Java 17**: Chosen for its robust memory management (G1GC) and threading capabilities, essential for a proxy handling thousands of concurrent connections.
- **Spring Boot 3.2.x (Jakarta EE)**: Provides a production-ready, embedded web server (Tomcat) and dependency injection framework. Spring Boot 3 uses Jakarta EE, ensuring modern, supported servlet specifications.
- **Caffeine Cache**: Replaced Guava as the industry standard for Java in-memory caching. It was chosen specifically for its **W-TinyLFU (Window Tiny Least Frequently Used)** eviction policy, which yields near-optimal hit rates by differentiating between short bursts of traffic and long-term popular assets.
- **Bucket4j**: A lock-free, thread-safe Java implementation of the Token Bucket algorithm. Chosen over Guava RateLimiter because Bucket4j easily supports distributed state (if migrated to Redis later) and handles bursts perfectly.
- **Thumbnailator**: A fast, pure-Java image processing library. Chosen over ImageMagick wrappers because it runs entirely inside the JVM, avoiding expensive JNI (Java Native Interface) calls and OS-level process forks.
- **RestTemplate**: A synchronous HTTP client used for fetching from origins.

---

## 📁 5. Folder Structure with Explanations

```text
src/main/java/com/SwiftEdgeCDN/
├── controller/
│   ├── MetricsController.java   # Exposes internal telemetry (hits, misses) to the dashboard.
│   ├── PurgeController.java     # Admin API to manually evict stale cache entries.
│   └── ServiceController.java   # The core proxy endpoint capturing all /service/v1/cdn/** traffic.
├── exception/
│   └── GlobalExceptionHandler.java # Uses @ControllerAdvice to intercept exceptions, ensuring stack traces don't leak.
├── service/
│   ├── CdnProxyService.java     # Houses the origin failover loop, HTTP client logic, and Thumbnailator execution.
│   ├── MetricsService.java      # Utilizes AtomicLongs for thread-safe, lock-free telemetry tracking.
│   └── RateLimitService.java    # Manages the ConcurrentHashMap of Bucket4j tokens per IP address.
└── ServiceCdnApplication.java   # Spring Boot @SpringBootApplication entry point.
```

---

## 🔄 6. End-to-End Workflow & Request Lifecycle

To understand the system perfectly, here is the lifecycle of a single HTTP GET request:

1. **Ingress**: `ServiceController` receives `GET /service/v1/cdn/logo.png?w=100`.
2. **Identification & Rate Limiting**: `RateLimitService` extracts the `X-Forwarded-For` or `RemoteAddr` IP. It fetches the `Bucket` for this IP. If `bucket.tryConsume(1)` returns false, an immediate `HTTP 429 Too Many Requests` is returned.
3. **Cache Interception**: The controller calls `cdnProxyService.fetchAndTransform(path, w, h)`. This method is annotated with `@Cacheable(value = "cdnCache", key = "...")`.
   - _Under the hood_: Spring AOP (Aspect-Oriented Programming) intercepts this call. It checks Caffeine. If a value exists, the actual method is skipped entirely.
4. **Upstream Fetching**: On a cache miss, the actual method executes. `fetchFromOriginsWithFailover()` iterates over the `cdn.origin-urls`. It uses `RestTemplate` to make an HTTP GET. If it times out or gets a 5xx error, it gracefully swallows the exception and tries the next origin in the list.
5. **Edge Transformation**: The origin's byte array is wrapped in a `ByteArrayInputStream` and fed into Thumbnailator. The image is resized to `w=100` maintaining aspect ratio, written to a `ByteArrayOutputStream`, and extracted as a new byte array.
6. **ETag Generation**: The controller applies an MD5 hash to the final byte array to generate a unique signature (ETag).
7. **Conditional Logic**: It compares the generated ETag with the client's `If-None-Match` header. If they match, it returns `HTTP 304 Not Modified` (0 bytes body).
8. **Egress**: If no match, it sets the `Cache-Control: public, max-age=600` header and returns `HTTP 200 OK` with the image bytes.
9. **Telemetry**: `MetricsService.incrementTotalRequests()` and `.addBytesSaved()` are called using non-blocking CAS (Compare-And-Swap) operations.

---

## 🗄 7. Database Schema (Stateless Design & The CAP Theorem)

**There is no database.**

_Design Decision & Reasoning_: In the context of the CAP Theorem (Consistency, Availability, Partition Tolerance), an Edge CDN heavily prioritizes **Availability** and **Partition Tolerance**. Edge nodes are designed to be deployed globally (e.g., AWS CloudFront edge locations).
If this node relied on a central PostgreSQL database to track cache metadata or rate limits, the latency to query that DB would defeat the purpose of the edge node.
By keeping the node completely stateless (state lives entirely in volatile JVM heap memory), we achieve infinite horizontal scalability. We can place a generic Round-Robin Load Balancer in front of 1,000 instances of this application without worrying about database replication lag or connection pool exhaustion.

---

## 🔌 8. API Documentation

### 1. Proxy Fetch (The CDN Endpoint)

- **Endpoint**: `GET /service/v1/cdn/{path}?w={width}&h={height}`
- **Parameters**:
  - `path` (String): The path relative to the upstream origin.
  - `w` (Integer, Optional): Target width in pixels.
  - `h` (Integer, Optional): Target height in pixels.
- **Headers Evaluated**: `If-None-Match`
- **Responses**:
  - `200 OK`: Contains the image bytes and `ETag` header.
  - `304 Not Modified`: Empty body, saves bandwidth.
  - `429 Too Many Requests`: Triggered by Bucket4j WAF.
  - `404 Not Found`: If upstream returns 404 or fails entirely.

### 2. Cache Purge (Invalidation Webhook)

- **Endpoint**: `POST /api/v1/cache/purge?key={cacheKey}`
- **Parameters**:
  - `key` (String, Optional): The specific cache key to evict (format: `path-w-h`).
- **Behavior**: If `key` is omitted, `cache.clear()` is called, wiping the entire L1 cache.
- **Responses**: `200 OK` ("Cleared entire cache").

### 3. Metrics Telemetry

- **Endpoint**: `GET /api/v1/metrics`
- **Responses**: `200 OK` `{"cacheHits": 1500, "cacheMisses": 24, "bytesSaved": 10485760}`

---

## 🛡 9. Authentication, Authorization & Security Measures

While a CDN proxy typically does not require user authentication (as it serves public static assets), it requires rigid infrastructure security:

- **WAF (Rate Limiting)**: Prevents Layer 7 application DDoS attacks and malicious scraping. Currently configured to 50 requests per minute per IP.
- **Exception Hiding (Security through Obscurity)**: `GlobalExceptionHandler` ensures that internal `NullPointerExceptions` or `SocketTimeoutExceptions` return a generic sanitized JSON/String response. Exposing Java stack traces to the public internet can reveal framework versions and inner workings to attackers.
- **Memory Bounding**: The Caffeine cache is strictly capped at `maximumSize=500` and `expireAfterWrite=10m`. Without an upper bound, a malicious user could request thousands of unique URLs, filling the JVM heap and crashing the server with an `OutOfMemoryError` (OOM).

---

## 🧠 10. Core Business Logic & Algorithms

### The Token Bucket Algorithm (Rate Limiting)

Implemented via Bucket4j. Imagine a bucket that can hold exactly 50 tokens. Every HTTP request requires exactly 1 token to pass. The bucket is continuously refilled at a constant rate (50 tokens every 60 seconds).

- **Advantage**: Unlike a strict "Leaky Bucket", a Token Bucket allows for brief, valid bursts of traffic (up to the bucket's capacity) while still strictly enforcing the long-term average rate.

### W-TinyLFU Algorithm (Cache Eviction)

LRU (Least Recently Used) is historically popular but flawed for CDNs; a sudden burst of one-off requests will push highly popular items out of the cache. LFU (Least Frequently Used) requires huge memory to track counters.

- **Caffeine's W-TinyLFU**: Uses a "Count-Min Sketch" (a probabilistic data structure, similar to a Bloom Filter) to accurately estimate the frequency of requests using very little memory. It admits new items into the cache only if their historic frequency is higher than the item it is about to evict. This guarantees the highest possible hit rate.

---

## ⚖️ 11. Important Design Decisions and Trade-offs

1. **L1 In-Memory Cache (Caffeine) vs L2 Distributed Cache (Redis)**
   - _Decision_: Used Caffeine embedded inside the Spring Boot JVM.
   - _Trade-off_: Caffeine is orders of magnitude faster (nanosecond access time) because data doesn't cross the network, and objects don't need to be serialized/deserialized via TCP. However, if we deploy 5 CDN nodes, they will not share cache state (a cache miss on Node A doesn't populate Node B).
   - _Justification_: For an Edge Node, raw speed is paramount. Modern CDNs use tiered caching (L1 in-memory on the edge, L2 SSDs/Redis regionally).

2. **Blocking `RestTemplate` vs Reactive `WebClient`**
   - _Decision_: Used `RestTemplate` (Thread-per-request model).
   - _Trade-off_: When fetching from a slow origin, the OS thread allocated by Tomcat blocks and waits. In massive scale scenarios (10,000+ concurrent connections), we would exhaust the thread pool.
   - _Justification_: Kept for simplicity and readability. A reactive approach (Spring WebFlux) is the ideal future iteration.

3. **Pull CDN vs Push CDN**
   - _Decision_: This is a "Pull" CDN. The CDN only fetches the image when a user requests it.
   - _Trade-off_: Slower TTFB on the very first request (Cache Miss). A "Push" CDN requires the developer to proactively upload all assets to the CDN beforehand, which uses more storage but guarantees instant responses.

4. **On-the-fly vs Pre-computed Resizing**
   - _Decision_: Images are resized dynamically based on query parameters.
   - _Trade-off_: Computationally heavier on the CPU during a cache miss, but saves immense storage space as we don't need to preemptively generate and store 10 different sizes of the same image.

---

## 🚀 12. Performance Optimizations & Scalability

- **Conditional GETs (ETags)**: Generating an MD5 hash of the payload allows the CDN to utilize HTTP 304. If a user refreshes the page, the browser sends the ETag. The proxy compares it and returns an empty body, saving massive amounts of bandwidth.
- **Atomic Counters**: In `MetricsService`, I used `AtomicLong` instead of `synchronized` methods. A synchronized method locks the thread, causing massive lock contention under load. `AtomicLong` relies on CAS (Compare-And-Swap) hardware-level CPU instructions to update counters without blocking threads.
- **Aggressive HTTP Timeout Tuning**: The `RestTemplate` origin connections are explicitly tuned to timeout after 3s (connect) and 5s (read). Without this, if the origin server hangs, the CDN threads would hang infinitely, eventually crashing the CDN. Fail fast is a core microservices pattern.

---

## 🧗 13. Challenges Faced & Solutions

- **Spring Boot 3 / Jakarta EE Migration API Breakages**: Upgrading to Spring Boot 3 meant the underlying server migrated from Java EE to Jakarta EE. This caused `javax.servlet.http.HttpServletRequest` to become unresolvable.
  - _Solution_: Refactored the codebase to import `jakarta.servlet.*` ensuring compatibility with modern Tomcat 10+ environments.
- **Image Resizing Memory Spikes (GC Pressure)**: Loading huge 10MB images into memory to pass into Thumbnailator caused severe heap allocations and Garbage Collection (GC) pauses.
  - _Solution_: Minimized memory footprint by streaming bytes directly from the HTTP response into a `ByteArrayInputStream`, piping through Thumbnailator, and out through a `ByteArrayOutputStream`, avoiding intermediate large object instantiations.

---

## 🚢 16. Deployment Process and Environment Configuration

To deploy this in a production environment:

1. **Containerization**: Create a `Dockerfile` using a lightweight base image (e.g., `eclipse-temurin:17-jre-alpine`).
2. **Kubernetes Deployment**: Deploy as a stateless `Deployment` in Kubernetes.
3. **Horizontal Pod Autoscaler (HPA)**: Configure HPA to scale the number of pods based on CPU utilization or incoming request rate.
4. **Environment Variables**: Externalize `application.properties` into Kubernetes `ConfigMaps` or `Secrets` to dynamically inject the `cdn.origin-urls` based on the environment (Staging vs Prod) without rebuilding the JAR.

---

## ⚙️ 17. Step-by-Step Guide to Running Locally

1. **Prerequisites**:
   - Java 17 installed.
   - Maven installed.
2. **Configure Origins**: Open `src/main/resources/application.properties` and set a valid image host (do not use HTML pages, Thumbnailator requires images):
   ```properties
   cdn.origin-urls=https://via.placeholder.com
   server.port=8081
   ```
3. **Build the Project**:
   ```bash
   mvn clean install
   ```
4. **Run the Project**:
   ```bash
   mvn spring-boot:run
   ```
5. **Test the Flow**:
   - **Trigger a Cache Miss**: Open Browser to `http://localhost:8081/service/v1/cdn/150` (Fetches from origin).
   - **View Telemetry**: Open `http://localhost:8081/dashboard.html` (Watch cache hits and bytes saved rise upon refreshes).
   - **Test Edge Compute (Resizing)**: Navigate to `http://localhost:8081/service/v1/cdn/150?w=50` (The image is dynamically resized).
   - **Test Cache Invalidation**: Run `curl -X POST http://localhost:8081/api/v1/cache/purge` to wipe the L1 cache.
