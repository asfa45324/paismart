# 文件上传模块面试题库

## 项目背景

PaiSmart 的企业级 AI 知识管理系统中，文件上传模块是核心功能之一，负责处理用户上传的各种格式文档，并通过 RAG（Retrieval-Augmented Generation）技术使其可被智能检索。

---

## 一、架构理解类问题

### Q1: 请描述一下用户上传文件的完整流程？

**参考答案：**

整个文件上传流程分为四个阶段：

```
前端分片上传 -> 后端接收合并 -> Kafka 异步处理 -> ES 索引入库
```

**详细流程：**

1. **前端分片上传** (`UploadController.uploadChunk` - 第 82-173 行)
   - 大文件按 5MB/片进行分片
   - 第一个分片时进行文件类型验证（`FileTypeValidationService`）
   - 每个分片计算 MD5，存储到 MinIO `uploads/chunks/{fileMd5}/{chunkIndex}`
   - 使用 Redis Bitmap 标记已上传分片：`upload:{userId}:{fileMd5}`

2. **合并文件** (`UploadController.mergeFile` - 第 242-340 行)
   - 检查所有分片是否上传完成
   - 调用 `MinioClient.composeObject()` 合并分片到 `merged/{fileName}`
   - 生成 1 小时有效期的预签名 URL
   - 发送 `FileProcessingTask` 到 Kafka topic
   - 清理临时分片文件和 Redis 状态

3. **Kafka 异步处理** (`FileProcessingConsumer.processTask` - 第 36-78 行)
   - 监听 `file-processing-topic`
   - 下载预签名 URL 中的文件
   - 调用 `ParseService.parseAndSave()` 解析并分块
   - 调用 `VectorizationService.vectorize()` 向量化

4. **ES 索引** (`ElasticsearchService.bulkIndex` - 第 33-69 行)
   - 批量索引到 `knowledge_base` index
   - 存储字段包括：fileMd5, chunkId, content, vector, userId, orgTag, isPublic

**关键点：**
- 分片大小：5MB（硬编码在 `UploadService.getTotalChunks` - 第 489 行）
- 预签名 URL 有效期：1 小时（`UploadService.mergeChunks` - 第 651 行）
- 多租户隔离通过 `orgTag` 和 `isPublic` 字段实现

---

### Q2: 为什么选择 Redis Bitmap 来跟踪分片上传状态？

**参考答案：**

**空间效率：**
- 传统 Set 方案：每个分片占用一个字符串键值
- Bitmap 方案：每个分片仅占 1 bit
- 例如：1000 个分片，Set 需要 ~50KB，Bitmap 仅需 125 字节

**代码实现：**
```java
// UploadService.java:346-347
String redisKey = "upload:" + userId + ":" + fileMd5;
boolean isUploaded = redisTemplate.opsForValue().getBit(redisKey, chunkIndex);
```

**位运算优化：**
```java
// UploadService.java:422-424 - 一次性获取所有分片状态
byte[] bitmapData = redisTemplate.execute((RedisCallback<byte[]>) connection -> {
    return connection.get(redisKey.getBytes());
});
```

**潜在改进：**
- 可以设置 TTL 自动清理过期上传记录
- 当前代码中没有设置过期时间，可能导致僵尸数据累积

---

### Q3: 分片上传的幂等性如何保证？

**参考答案：**

幂等性主要通过以下机制实现（`UploadService.uploadChunk` - 第 98-155 行）：

1. **Redis 去重检查：**
```java
if (chunkUploaded) {
    logger.warn("分片已在 Redis 中标记为已上传");
    if (!chunkInfoExists) {
        // 只补充分片信息，不重复上传 MinIO
    } else {
        return; // 完全跳过处理
    }
}
```

2. **数据库分片信息校验：** 检查 `chunk_info` 表是否已存在

3. **MinIO 文件存在性检查：** 如果 Redis 标记但数据库无记录，检查 MinIO 中文件是否存在

**存在的问题：**
- 如果 Redis 先于数据库写入崩溃，可能导致分片重复上传到 MinIO
- 建议使用事务或先写数据库后更新 Redis

---

## 二、异步处理与消息队列

### Q4: 为什么选择 Kafka 而不是直接同步处理或其他 MQ？

**参考答案：**

**选择 Kafka 的原因：**

1. **高吞吐需求：** 文件处理是耗时操作（解析 + 向量化），需要缓冲峰值流量

2. **容错性：**
   ```java
   // FileProcessingConsumer.java:64-67
   } catch (Exception e) {
       log.error("Error processing task: {}", task, e);
       throw new RuntimeException("Error processing task", e); // 触发重试/死信
   }
   ```

3. **解耦设计：** 上传服务无需关心下游处理逻辑

**为什么不选 RabbitMQ：**
- Kafka 更适合日志/事件流的场景
- RabbitMQ 更侧重复杂路由，这里只需要简单的主题订阅

**为什么不直接同步处理：**
- 避免 HTTP 请求超时（大文件处理可能超过 30 秒）
- 提升用户体验，上传后立即返回

**潜在改进：**
- 当前没有实现消费者组主动 rebalance 的优化
- 可以添加处理进度追踪机制

---

### Q5: 如果消费者在处理过程中崩溃了，如何保证数据不丢失？

**参考答案：**

**当前实现的分析：**

1. **Kafka 消费偏移量：** 代码中没有显式配置 offset 提交策略
   - 默认可能是自动提交
   - 建议改为手动提交：`enable.auto.commit=false`

2. **异常处理：**
```java
// FileProcessingConsumer.java:64-68
catch (Exception e) {
    log.error("Error processing task: {}", task, e);
    throw new RuntimeException("Error processing task", e); // 让 DefaultErrorHandler 处理
}
```

**问题场景：**

| 崩溃时机 | 影响 | 恢复方式 |
|---------|------|----------|
| 下载文件后崩溃 | 任务重试，重新下载 | 浪费带宽 |
| 解析完成后崩溃 | 向量未入库，数据不一致 | 需清理脏数据 |
| 向量化中途崩溃 | 部分 chunk 已索引 | ES 中有残差数据 |

**改进建议：**

1. **两阶段提交：** 先标记处理中，成功后再标记完成
2. **Checkpoint 机制：** 长任务分阶段提交 offset
3. **死信队列监控：** 配置 DLQ 并定期人工介入

---

### Q6: 多个用户同时上传大量文件时，如何保证负载均衡？

**参考答案：**

**当前分区策略分析：**

```java
// UploadController.java:311-314 - 发送到 Kafka
kafkaTemplate.executeInTransaction(kt -> {
    kt.send(kafkaConfig.getFileProcessingTopic(), task);
    return true;
});
```

**问题：** 没有指定 partition key，依赖轮询分发

**负载不均风险：**
- 大文件和小文件混在一起，处理时长差异巨大
- 某些消费者可能堆积大量大文件任务

**改进方案：**

1. **基于 fileMd5 分区：** 保证同一文件的所有操作到同一分区
```java
kt.send(topic, fileMd5, task); // 第二个参数作为 key
```

2. **增加消费者实例：** 根据 CPU/内存使用情况横向扩展

3. **优先级队列：** 小文件走快速通道（可能需要多个 topic）

---

## 三、数据一致性与可靠性

### Q7: 文件上传、ES 索引、向量化三个环节的事务边界怎么设计？

**参考答案：**

**当前实现的问题：**

```java
// 1. mergeChunks 方法有@Transactional
@Transactional
@PostMapping("/merge")
public ResponseEntity<Map<String, Object>> mergeFile(...) {
    uploadService.mergeChunks(...);  // MinIO 操作
    kafkaTemplate.executeInTransaction(...); // Kafka 发送
    // 数据库更新
}

// 2. Consumer 中各步骤之间没有事务保护
public void processTask(FileProcessingTask task) {
    InputStream fileStream = downloadFileFromStorage(...);  // 步骤 1
    parseService.parseAndSave(...);                          // 步骤 2 - 写入 MySQL
    vectorizationService.vectorize(...);                     // 步骤 3 - 写入 ES
}
```

**故障场景分析：**

| 故障点 | 影响 | 一致性状态 |
|-------|------|-----------|
| 解析成功，向量化失败 | MySQL 有数据，ES 无数据 | 不一致 |
| 向化化部分成功 | ES 只有部分 chunk | 不完整 |
| Kafka 消费成功但实际失败 | offset 已提交，无法重试 | 数据丢失 |

**改进建议：**

1. **补偿机制：** 定时扫描 `document_vector` 表，查找未向量的记录
2. **Saga 模式：** 每个步骤都有对应的回滚操作
3. **最终一致性：** 接受短暂不一致，通过定时对账修复

---

### Q8: MinIO 存储成功了但 ES 索引失败，如何清理脏数据？

**参考答案：**

**当前问题：** 代码中没有任何清理逻辑

**需要处理的脏数据：**
1. MinIO 上的合并文件 `merged/{fileName}`
2. `file_upload` 表记录（status 应为 1）
3. `document_vector` 表的解析结果
4. `chunk_info` 表的分片元数据

**改进方案：**

```java
// 方案 1: Consumer 中添加 finally 清理
@KafkaListener(...)
public void processTask(FileProcessingTask task) {
    try {
        // 处理逻辑
    } catch (Exception e) {
        cleanupFailedTask(task);
        throw e;
    }
}

private void cleanupFailedTask(FileProcessingTask task) {
    // 删除 MinIO 文件
    minioClient.removeObject(...);
    // 更新文件状态
    fileUpload.setStatus(2); // 2 = 处理失败
    // 删除解析结果
    documentVectorRepository.deleteByFileMd5(task.getFileMd5());
}
```

**方案 2: 定时对账任务**
```java
@Scheduled(cron = "0 0 */6 * * ?")
public void reconcileFailedFiles() {
    // 查找 status=1 但 ES 中没有数据的文件
    // 重新触发处理或标记为失败
}
```

---

## 四、性能与扩展性

### Q9: 并发上传量激增时，系统如何做背压控制？

**参考答案：**

**当前瓶颈点：**

1. **HTTP 层：** `spring.servlet.multipart.max-file-size` 限制单个文件
2. **Kafka 生产端：** `buffer.memory` 和 `linger.ms` 影响吞吐
3. **Kafka 消费端：** `max.poll.records` 和单线程消费模型
4. **ES 索引：** Bulk 大小和刷新间隔

**当前代码分析：**

```java
// VectorizationService.java:57 - 全量向量化后再批量索引
List<float[]> vectors = embeddingClient.embed(texts); // 可能在内存中堆积
elasticsearchService.bulkIndex(esDocuments);
```

**问题：**
- 大文件可能有数千个 chunk，一次性调用 embedding API 可能超限
- Embedding API 通常有单次请求数量限制（如 100 条）

**改进建议：**

1. **流式向量化：**
```java
int batchSize = 100;
for (int i = 0; i < texts.size(); i += batchSize) {
    List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
    List<float[]> batchVectors = embeddingClient.embed(batch);
    elasticsearchService.bulkIndex(buildDocs(batch, batchVectors));
}
```

2. **Kafka 消费速率控制：**
```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 10  # 减少每次拉取数量
      fetch-min-size: 1kb   # 降低网络 IO
```

3. **Redis 限流：** 上传前检查系统负载

---

### Q10: ParseService 中流式解析的设计要点是什么？

**参考答案：**

**核心设计：** 父子文档切分策略（`ParseService.java:122-166`）

```java
parentChunkSize: 1MB   // 父块大小
chunkSize: 512B        // 子块大小
bufferSize: 8KB        // IO 缓冲区
```

**流式处理流程：**
1. Tika 解析器逐个字符/单词调用 `characters(char[], start, length)`
2. `StreamingContentHandler` 累积到 parentChunkSize 后触发处理
3. 将父块语义分割成子块（段落 -> 句子 -> 词语）
4. 立即保存子块到数据库，清空缓冲区

**内存保护机制：**
```java
// ParseService.java:93-115
private void checkMemoryThreshold() {
    double memoryUsage = (double) usedMemory / maxMemory;
    if (memoryUsage > maxMemoryThreshold) {
        System.gc(); // 触发 GC
        // 再次检查仍超限则抛出异常
    }
}
```

**优点：**
- O(1) 内存复杂度，可处理任意大小文件
- 边解析边入库，缩短端到端延迟

**潜在问题：**
- 按固定字节数切分可能打断语义（已在子块层面优化）
- 每保存一个 chunk 就 flush 到数据库，IO 频繁
  - 改进：batch 保存，每 50 个 chunk 批量 insert

---

## 五、多租户隔离

### Q11: 组织级别的隔离如何在各个层次实现？

**参考答案：**

**1. 上传阶段：**
```java
// UploadController.java:123-138 - 获取主组织标签
if (orgTag == null || orgTag.isEmpty()) {
    String primaryOrg = userService.getUserPrimaryOrg(userId);
    orgTag = primaryOrg;
}
```

**2. 数据存储：**
```java
// DocumentVector 实体包含租户字段
vector.setUserId(userId);
vector.setOrgTag(orgTag);
vector.setPublic(isPublic);
```

**3. Elasticsearch 查询过滤：**
```java
// 需要在 SearchController 中添加 filter
QueryFilterBuilder filter = QueryBuilders.boolQuery()
    .should(QueryBuilders.termQuery("orgTag", userOrgTag))
    .should(QueryBuilders.boolQuery()
        .must(QueryBuilders.termQuery("isPublic", true)));
```

**潜在安全问题：**
- 当前代码中搜索权限过滤未在所见代码中体现
- 需要确保所有 ES 查询都携带租户过滤器
- 公开文档仍然应该经过访问控制列表（ACL）校验

---

### Q12: 如果用户的 orgTag 变更了，历史数据如何处理？

**参考答案：**

**当前实现：** 没有提供 orgTag 变更的迁移逻辑

**需要考虑的场景：**
1. 用户转移到新组织
2. 组织合并
3. 公开文档转为私有

**解决方案：**

```java
@Service
public class OrgMigrationService {

    public void migrateUserOrg(String userId, String oldOrgTag, String newOrgTag) {
        // 1. 更新 User 表关联
        userRepository.updateOrgTag(userId, newOrgTag);

        // 2. 批量更新 document_vector
        documentVectorRepository.updateOrgTagByUserAndOldOrg(
            userId, oldOrgTag, newOrgTag
        );

        // 3. 重建 ES 索引（或使用 update script）
        esClient.updateByQuery(update -> update
            .index("knowledge_base")
            .query(q -> q.bool(b -> b
                .must(t -> t.field("userId").value(userId))
                .must(t -> t.field("orgTag").value(oldOrgTag))
            ))
            .script(s -> s.source("ctx._source.orgTag = params.newOrg")
                .param("newOrg", newOrgTag)
            )
        );
    }
}
```

---

## 六、错误处理与监控

### Q13: 死信队列是如何配置的？如何处理消费失败的消息？

**参考答案：**

**当前实现分析：**

```java
// FileProcessingConsumer.java:64-68
try {
    // 处理逻辑
} catch (Exception e) {
    log.error("Error processing task: {}", task, e);
    throw new RuntimeException("Error processing task", e); // 抛出异常
}
```

**问题：** 代码中抛出异常但没有看到 Kafka 的 Error Handler 配置

**推荐配置：**

```java
@Configuration
public class KafkaErrorHandlerConfig {

    @Bean
    public ConcurrentMessageListenerContainer<String, FileProcessingTask> container(
            ConsumerFactory<String, FileProcessingTask> cf) {

        ConcurrentMessageListenerContainer<String, FileProcessingTask> container =
            new ConcurrentMessageListenerContainer<>(cf);

        container.setErrorCodeHandler(new DeadLetterPublishingRecoverer(
            template(dlqTemplate())));

        // 重试配置：最多重试 3 次，指数退避
        container.setRetryListener(RetryListener.retry(3, Duration.ofSeconds(1), 2.0));

        return container;
    }
}
```

**死信处理策略：**
1. 自动重试 3 次
2. 进入死信 topic `file-processing-topic-DLQ`
3. 告警通知运维人员
4. 提供管理界面人工重放或丢弃

---

### Q14: 如何监控"文件上传到可检索"这个指标的耗时？

**参考答案：**

**当前已有：** `LogUtils.PerformanceMonitor` 用于单次 API 耗时

```java
// UploadController.java:93, 150
LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("UPLOAD_CHUNK");
monitor.end("分片上传成功");
```

**端到端耗时监控方案：**

**方案 1: 链路追踪**
```java
// 在 Controller 层添加 traceId
MDC.put("traceId", UUID.randomUUID().toString());

// FileProcessingTask 携带 traceId
public record FileProcessingTask(
    String traceId,
    String fileMd5,
    ...
)

// 各阶段打点
log.info("[{}] Merge completed, traceId: {}", traceId);
log.info("[{}] Parsing completed, traceId: {}", traceId);
log.info("[{}] Vectorization completed, traceId: {}", traceId);
```

**方案 2: 数据库埋点**
```java
// FileUpload 实体增加时间字段
@Column(name = "merged_at")
private LocalDateTime mergedAt;

@Column(name = "indexed_at")
private LocalDateTime indexedAt;

// 定时统计
SELECT AVG(TIMESTAMPDIFF(SECOND, merged_at, indexed_at)) as avg_latency
FROM file_upload WHERE indexed_at IS NOT NULL;
```

**方案 3: Prometheus + Grafana**
```java
// 定义 Histogram 指标
private static final Histogram UPLOAD_LATENCY = Metrics.httpServerRequestLatency(
    "file_upload_latency_seconds",
    "文件上传到可检索的耗时",
    List.of("stage")
);

// 在各个阶段打点
UPLOAD_LATENCY.labels("merged").observe(millisToSeconds(System.currentTimeMillis() - startTime));
```

---

## 七、安全类问题

### Q15: 如何防止恶意文件上传（如伪装扩展名、超大文件）？

**参考答案：**

**当前安全措施：**

1. **扩展名白名单：**
```java
// FileTypeValidationService.validateFileType
if (!validationResult.isValid()) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("message", validationResult.getMessage()));
}
```

2. **文件大小限制：** 通过 `spring.servlet.multipart.max-file-size` 配置

**增强建议：**

1. **内容类型校验：**
```java
// 不仅检查 contentType，还要检查文件魔数
public boolean validateMagicBytes(MultipartFile file) throws IOException {
    byte[] header = new byte[16];
    file.getInputStream().read(header);

    // PDF: %PDF
    // DOCX: PK (zip format)
    // 根据实际魔数判断
}
```

2. **沙箱解析：**
```java
// 在独立容器中运行 Tika 解析，设置资源限制
DockerRunCommand.run("--memory=512m --cpus=1 tika-parser ...");
```

3. **病毒扫描：**
```java
// 合并后触发 ClamAV 扫描
if (clamAV.scan(objectUrl).hasVirus()) {
    minioClient.removeObject(...);
    throw new SecurityException("File contains virus");
}
```

---

## 八、深度优化问题

### Q16: 如果一个 10GB 的 PDF 被上传，会生成多少 Embedding API 调用？

**参考答案：**

**估算：**
- 假设 PDF 平均 1000 字/页
- 10GB ≈ 10^7 KB ≈ 500 万页（纯文本估算，实际 PDF 含图片会更少）
- 按 `chunkSize = 512 字节` 切分 → 约 2000 万个 chunk

**当前实现问题：**
```java
// VectorizationService.java:52-57
List<String> texts = chunks.stream()...toList();  // 全部加载到内存！
List<float[]> vectors = embeddingClient.embed(texts);  // 一次性调用
```

这会导致：
1. **OOM：** 2000 万条文本在内存中可能占用 GB 级内存
2. **API 限流：** 超出 Embedding API 的单次请求限制

**优化方案：**

```java
public void vectorizeBatched(String fileMd5, ...) {
    long total = documentVectorRepository.countByFileMd5(fileMd5);
    int pageSize = 100; // 每次 100 个 chunk

    for (long offset = 0; offset < total; offset += pageSize) {
        List<DocumentVector> page = repository.findByFileMd5Offset(fileMd5, offset, pageSize);
        List<String> texts = extractTexts(page);
        List<float[]> vectors = embeddingClient.embed(texts);
        elasticsearchService.bulkIndex(buildDocs(texts, vectors));

        // 可选：添加延迟避免 API 限流
        Thread.sleep(100);
    }
}
```

---

### Q17: Chunk 的切分策略是否可以优化？

**参考答案：**

**当前策略（ParseService.java:201-245）：**
1. 按 `\n\n` 段落分割
2. 长段落按句子符号 `。！？；.!?;` 分割
3. 超长句子用 HanLP 分词

**问题：**
- 英文长句没有逗号也可能很长
- 代码注释、表格等特殊结构处理不当
- 数字列表被切断

**改进方向：**

1. **语义感知切分：**
```java
// 检测列表结构
if (text.matches("(\\d+\\.|\\*|-)\\s+.+")) {
    // 按列表项边界切割
}
```

2. **重叠窗口（Overlap）：**
```java
// 相邻 chunk 保留 10% 的重叠，保持上下文
String overlap = chunk.substring(0, (int)(chunk.length() * 0.1));
nextChunk = overlap + nextChunk;
```

3. **层级化切分：**
```
Level 0: 章节（标题开头）
Level 1: 段落（\n\n 分割）
Level 2: 句子（标点分割）
```

搜索时使用对应层级的 chunk 召回。

---

## 总结

文件上传模块的核心挑战在于：

1. **大规模数据处理：** 流式解析、分批向量化
2. **分布式一致性：** Kafka 消息、ES 索引、MySQL 记录的最终一致性
3. **多租户隔离：** 权限控制的完整性
4. **可观测性：** 链路追踪、指标监控

面试官可能会继续追问具体的代码实现细节，建议熟悉关键类的源码位置和方法签名。
