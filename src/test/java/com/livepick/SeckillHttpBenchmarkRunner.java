package com.livepick;

import cn.hutool.json.JSONUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SeckillHttpBenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getProperty("benchmark.baseUrl", "http://127.0.0.1:8081");
        long voucherId = Long.getLong("benchmark.voucherId", 7L);
        int totalRequests = Integer.getInteger("benchmark.totalRequests", 500);
        int concurrency = Integer.getInteger("benchmark.concurrency", 100);
        long userIdStart = Long.getLong("benchmark.userIdStart", 1000L);
        int warmupRequests = Integer.getInteger("benchmark.warmupRequests", Math.min(50, totalRequests));

        BenchmarkResult warmup = execute(baseUrl, voucherId, Math.max(warmupRequests, 1), Math.min(concurrency, Math.max(warmupRequests, 1)), userIdStart);
        System.out.println("=== Warmup ===");
        warmup.print();

        BenchmarkResult result = execute(baseUrl, voucherId, totalRequests, concurrency, userIdStart + warmupRequests + 1L);
        System.out.println("=== Formal Run ===");
        result.print();
    }

    private static BenchmarkResult execute(String baseUrl, long voucherId, int totalRequests, int concurrency, long userIdStart)
            throws InterruptedException, ExecutionException {
        ExecutorService executorService = Executors.newFixedThreadPool(concurrency);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        List<Future<RequestMetric>> futures = new ArrayList<>(totalRequests);
        long startNs = System.nanoTime();
        for (int i = 0; i < totalRequests; i++) {
            long userId = userIdStart + i;
            futures.add(executorService.submit(new RequestTask(httpClient, baseUrl, voucherId, userId)));
        }

        List<Long> latencyMs = new ArrayList<>(totalRequests);
        Map<String, Integer> buckets = new TreeMap<>();
        int success = 0;
        int failed = 0;

        for (Future<RequestMetric> future : futures) {
            RequestMetric metric = future.get();
            latencyMs.add(metric.latencyMs);
            if (metric.success) {
                success++;
            } else {
                failed++;
                buckets.merge(metric.reason, 1, Integer::sum);
            }
        }

        long totalDurationNs = System.nanoTime() - startNs;
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);
        return new BenchmarkResult(totalRequests, success, failed, latencyMs, buckets, totalDurationNs);
    }

    private static class RequestTask implements Callable<RequestMetric> {
        private final HttpClient httpClient;
        private final String baseUrl;
        private final long voucherId;
        private final long userId;

        private RequestTask(HttpClient httpClient, String baseUrl, long voucherId, long userId) {
            this.httpClient = httpClient;
            this.baseUrl = baseUrl;
            this.voucherId = voucherId;
            this.userId = userId;
        }

        @Override
        public RequestMetric call() {
            long startNs = System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/voucher-order/seckill/" + voucherId))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Benchmark-User-Id", String.valueOf(userId))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                String body = response.body();
                boolean ok = response.statusCode() == 200 && body != null && body.contains("\"success\":true");
                if (ok) {
                    return new RequestMetric(true, "OK", latencyMs);
                }
                return new RequestMetric(false, extractReason(response.statusCode(), body), latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                return new RequestMetric(false, "InterruptedException", latencyMs);
            } catch (IOException e) {
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                return new RequestMetric(false, e.getClass().getSimpleName(), latencyMs);
            }
        }

        private String extractReason(int statusCode, String body) {
            if (body == null || body.isEmpty()) {
                return "HTTP_" + statusCode;
            }
            try {
                Object errorMsg = JSONUtil.parseObj(body).get("errorMsg");
                if (errorMsg != null) {
                    return String.valueOf(errorMsg);
                }
            } catch (Exception ignored) {
            }
            return "HTTP_" + statusCode;
        }
    }

    private static class RequestMetric {
        private final boolean success;
        private final String reason;
        private final long latencyMs;

        private RequestMetric(boolean success, String reason, long latencyMs) {
            this.success = success;
            this.reason = reason;
            this.latencyMs = latencyMs;
        }
    }

    private static class BenchmarkResult {
        private final int totalRequests;
        private final int success;
        private final int failed;
        private final List<Long> latencyMs;
        private final Map<String, Integer> buckets;
        private final long totalDurationNs;

        private BenchmarkResult(int totalRequests, int success, int failed, List<Long> latencyMs, Map<String, Integer> buckets, long totalDurationNs) {
            this.totalRequests = totalRequests;
            this.success = success;
            this.failed = failed;
            this.latencyMs = latencyMs;
            this.buckets = buckets;
            this.totalDurationNs = totalDurationNs;
        }

        private void print() {
            List<Long> sorted = new ArrayList<>(latencyMs);
            Collections.sort(sorted);
            double seconds = totalDurationNs / 1_000_000_000.0;
            double qps = seconds == 0 ? 0 : totalRequests / seconds;
            double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);

            System.out.println("totalRequests = " + totalRequests);
            System.out.println("success       = " + success);
            System.out.println("failed        = " + failed);
            System.out.println("durationSec   = " + String.format("%.3f", seconds));
            System.out.println("qps           = " + String.format("%.2f", qps));
            System.out.println("avgMs         = " + String.format("%.2f", avg));
            System.out.println("p50Ms         = " + percentile(sorted, 0.50));
            System.out.println("p95Ms         = " + percentile(sorted, 0.95));
            System.out.println("p99Ms         = " + percentile(sorted, 0.99));
            System.out.println("maxMs         = " + (sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1)));
            if (!buckets.isEmpty()) {
                System.out.println("failureBuckets = " + buckets);
            }
        }

        private long percentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) {
                return 0L;
            }
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }
    }
}
