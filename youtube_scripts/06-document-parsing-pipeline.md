# 第 06 集：文档解析 Pipeline——Tika 与文本分块

> 系列：派聪明 RAG 后端演进全解
> 集数：06 / 20+
> 主题：Apache Tika 解析、PDFBox 抽页、HanLP 中文分词、父-子块策略、流式防 OOM
> 上集回顾：第 05 集 MinIO 搭好了，文件能上传、能存
> 本集目标：把 PDF / Word / Excel 里的文字提取出来，按语义切成块，落库

---

## 【开场 Hook】

文件上传完了。**但是 MinIO 里的文件是"死的"——它是字节流，AI 读不懂。**

RAG 系统的第一步，永远是 **「把文件变成文本」**。

这一步看起来简单（Ctrl+A、Ctrl+C），**真做起来全是坑**：

- PDF 有「文本型 PDF」和「扫描型 PDF」——前者能直接抽文字，后者需要 OCR。
- Word 文档里有「正文」「表格」「页眉页脚」——哪些算内容？
- 一个 500MB 的 PDF 一次性读进内存，**直接 OOM**。
- 中文怎么分词？`「小明在北京大学读书」`——`北京大学` 是一个词还是 `北京 + 大学`？
- 切块太大，召回不精准；切块太小，语义断裂。

`pai-smart` 用的是 **Apache Tika + PDFBox + HanLP** 的组合拳——**工业级 RAG 的标配**。

今天我们就把 `ParseService` 整个拆开。

---

## 【一、为什么 Tika？自己写解析行不行？】

**先看场景**：

> 用户上传了一个文件，可能是：PDF、Word、Excel、PPT、TXT、Markdown、HTML、JSON、CSV……

如果你自己写解析：
- PDF：PDFBox / iText
- Word：Apache POI
- Excel：Apache POI
- PPT：Apache POI
- HTML：Jsoup
- Markdown：commonmark-java
- ……

**光是引入依赖就 5-6 个**。**还要写适配层，根据文件类型选不同的解析器**。

**Tika 的解决方案：一个 API，解析所有格式**。

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.1</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>2.9.1</version>
</dependency>
```

`pai-smart` 引了这两个。**注意是「standard-package」**——这一个 jar 把 PDF、Word、Excel、PPT 等几十种格式的解析器都打包了。

**Tika 的核心思想**：**MIME 识别 + 委托解析**。
1. **识别**：用 magic bytes（文件头几个字节）判断文件类型。
2. **委托**：根据文件类型，把解析任务委托给对应的 parser（PDFBox、POI 等）。

**面试考点 1**：Tika 怎么识别文件类型？

答：Tika 用 `org.apache.tika.detect.Detector` 接口。默认实现是 `DefaultDetector`，**综合 magic bytes + 文件扩展名 + MIME 类型**。比如：

- `%PDF-1.4` 开头的 → PDF
- `PK\x03\x04` 开头的 → ZIP（再打开看是 Office 还是 EPUB 还是 JAR）
- `{\rtf1` 开头的 → RTF

**实战中**：Tika 的识别率**不是 100%**——**有些诡异文件识别错**。所以 `pai-smart` 还有 `FileTypeValidationService` 二次校验（看 `service/` 目录）。

---

## 【二、`parseAndSave` 方法：流式入口】

```java
public void parseAndSave(String fileMd5, InputStream fileStream,
        String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
    checkMemoryThreshold();

    try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
        if (isPdfDocument(bufferedStream)) {
            parsePdfAndSave(fileMd5, bufferedStream, userId, orgTag, isPublic);
            return;
        }

        StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        AutoDetectParser parser = new AutoDetectParser();

        parser.parse(bufferedStream, handler, metadata, context);
    } catch (SAXException e) {
        throw new RuntimeException("文档解析失败", e);
    }
}
```

### 2.1 `checkMemoryThreshold()`：防 OOM 第一道防线

```java
@Value("${file.parsing.max-memory-threshold:0.8}")
private double maxMemoryThreshold;

private void checkMemoryThreshold() {
    Runtime runtime = Runtime.getRuntime();
    long maxMemory = runtime.maxMemory();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    
    double memoryUsage = (double) usedMemory / maxMemory;
    
    if (memoryUsage > maxMemoryThreshold) {
        logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
        System.gc();  // 调一次 GC
        
        usedMemory = runtime.totalMemory() - runtime.freeMemory();
        memoryUsage = (double) usedMemory / maxMemory;
        
        if (memoryUsage > maxMemoryThreshold) {
            throw new RuntimeException("内存不足，无法处理大文件");
        }
    }
}
```

**`pai-smart` 用了三招防 OOM**：

1. **解析前检查内存**：`memoryUsage > 0.8` 就先 `System.gc()`，还高就拒绝。
2. **流式处理**：下面会讲，不一次性读进内存。
3. **`bufferSize = 8192`**：每次只读 8KB。

**面试考点 2**：`System.gc()` 是不是好习惯？

A：**不推荐**——**JVM 会忽略**。但**这里是「保命」**——不如 GC 直接拒绝请求。**生产环境更优雅的方案**：
- 限流：超过 N 个并发解析任务直接拒绝。
- 队列：大文件进队列慢慢处理，**不阻塞 Web 线程**（这就是 Kafka，第 09 集要讲）。

### 2.2 `BufferedInputStream`：基础 IO 优化

```java
try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
```

`bufferSize = 8192`（8KB）——**磁盘 IO 每次 8KB 读**，**比一次读 1 byte 快 8000 倍**。**这是 Java IO 性能的「第一课」**。

**`try-with-resources`** 自动关闭流，**不怕忘记 close**。

### 2.3 PDF 单独走 `parsePdfAndSave`

```java
if (isPdfDocument(bufferedStream)) {
    parsePdfAndSave(fileMd5, bufferedStream, userId, orgTag, isPublic);
    return;
}
```

**为什么 PDF 单独处理？**

因为 PDF **有页的概念**——`「第 5 页」`和`「第 6 页」`的内容不能混在一起（要支持「跳到原文档第 X 页」这种用户操作）。所以 PDF 解析**保留页号信息**。

其他格式（Word、Excel）**没有页的概念**，**走通用流式处理**。

**面试考点 3**：Tika 也能解析 PDF，为什么不用？

答：Tika 把 PDF 当文本流，**不分页**。`pai-smart` 用 `PDFTextStripper`（PDFBox 自带）能精确按页提取。**对于 RAG 系统，页号很重要**——`「这个回答来自原文档第 5 页」`是产品刚需。

---

## 【三、StreamingContentHandler：Tika 的「回调地狱」】

Tika 的设计是 **SAX 风格**——**边读边回调**：

```java
private class StreamingContentHandler extends BodyContentHandler {
    private final StringBuilder buffer = new StringBuilder();
    
    @Override
    public void characters(char[] ch, int start, int length) {
        buffer.append(ch, start, length);
        if (buffer.length() >= parentChunkSize) {
            processParentChunk();
        }
    }
    
    @Override
    public void endDocument() {
        if (buffer.length() > 0) {
            processParentChunk();
        }
    }
}
```

**这是 RAG 系统的"灵魂设计"**——**父-子块策略**（Parent-Child Chunking）。

### 3.1 父块 + 子块

```java
@Value("${file.parsing.parent-chunk-size:1048576}")
private int parentChunkSize;
```

`parentChunkSize = 1048576 = 1MB`——**父块大小 1MB**。

```java
@Value("${file.parsing.chunk-size}")
private int chunkSize;

@Value("${file.parsing.overlap-size:100}")
private int overlapSize = 100;
```

`chunkSize` 不知道具体值（在 `application.yml` 里），推测是 500-1000 字符左右。**子块大小**。**overlapSize = 100 字符**，**块和块之间重叠 100 字符**。

**为什么要有「父-子」两层？**

**想象这个场景**：

> 一个 PDF 500 页。用户问 `「Apple 公司的 Q3 财报里净利润是多少？」`。
>
> 答案可能在**第 245 页**——`「Apple Q3 净利润 1.2 万亿...」`，但上下文（`「这是 2024 年第三季度财报...」`）在**第 244 页**。
>
> 如果只按子块（500 字符）切，`「Apple Q3 净利润 1.2 万亿」`和 `「这是 2024 年第三季度财报」`被切到两个 chunk，**召回后看不到上下文**。

**父-子策略的解法**：

1. **子块（chunk）**用于**检索**——粒度小，**召回精准**。
2. **父块**用于**回答生成**——召回时**同时返回父块的全部内容**，**给 LLM 完整上下文**。

**工作流程**：
1. 解析时，**累积字符**到 `parentChunkSize`（1MB）。
2. 一旦超 1MB，**触发 `processParentChunk`**：把父块切成子块，**每个子块存到 `DocumentVector` 表**，**子块带 `parentChunkId`**。
3. **子块是检索的最小单位**。**命中子块后，把整个父块都返回给 LLM**。

**这就是 RAG 系统的"两级索引"**——比"一刀切"好得多。

**面试考点 4**：父-子块 vs 滑动窗口 vs 句子切分？

- **滑动窗口**（`pai-smart` 用的就是 `overlapSize`）：**简单**，但子块之间有冗余。
- **句子切分**：用句号、问号切，**粒度自然**，但**短句子无信息、长句子切不开**。
- **父-子策略**：**最现代的做法**，子块用于检索，父块用于生成。**OpenAI 的 Assistants API 用的就是这套**。

### 3.2 `characters` 回调：Tika 的流式核心

```java
@Override
public void characters(char[] ch, int start, int length) {
    buffer.append(ch, start, length);
    if (buffer.length() >= parentChunkSize) {
        processParentChunk();
    }
}
```

Tika 解析文档时，**每读到一段文本就调一次 `characters`**——可能一行调一次，可能一段调一次。**我们 `append` 进 `StringBuilder`**——**不立即处理**。

**直到 buffer 超过 1MB**——`processParentChunk()` 触发。

**这个设计的精妙之处**：

- **不用一次读完整个文件**。500MB 的 PDF，**内存里只留 1MB**。
- **流式**——读完 1MB 就存盘一次，**清空 buffer 继续读**。
- **`endDocument()` 里再处理一次**——文档末尾的「尾巴」可能不到 1MB，**要记得处理**。

**面试考点 5**：为什么用 `StringBuilder` 不用 `byte[]`？

A：Tika 给的是 `char[]`，**已经是字符**了。`StringBuilder` 是字符的容器，**天然合适**。`byte[]` 是字节的容器，**还得做编码转换**。

---

## 【四、HanLP：中文分词的「神兵」】

```xml
<dependency>
    <groupId>com.hankcs</groupId>
    <artifactId>hanlp</artifactId>
    <version>portable-1.8.6</version>
</dependency>
```

```java
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

List<Term> terms = StandardTokenizer.segment("小明在北京大学读书");
```

**输出**：
```
[小明, 在, 北京大学, 读书]
```

**HanLP 的核心能力**：
- **中文分词**：`北京大学` 不会被切错。
- **词性标注**：`名词 / 动词 / 形容词`。
- **命名实体识别**：`小明 → 人名`、`北京大学 → 机构名`。
- **关键字提取**、**摘要提取**、**情感分析**。

**为什么 RAG 需要 HanLP？**

**场景 1：搜索时**：
> 用户搜 `「北京大学奖学金」`。
>
> 不分词的话，**ES 全文检索会把"北京大学"和"北京"作为两个 token**——`「北京大学研究生院」`就匹配不上。
>
> **HanLP 预先分词** → `北京大学 / 研究生院`，**索引和查询一致**。

**场景 2：实体识别**：
> 用户问 `「Apple 公司的 Q3 财报」`。
>
> **HanLP 识别 `Apple → 机构名`**——可以做 entity 级别的过滤，**RAG 召回更准**。

`pai-smart` 用的 `portable` 版本是**精简版**（模型文件小，约 30MB），**够用**。**完整版要 1GB+**。

**面试考点 6**：为什么不用 IK Analyzer 或 Jieba？

- **IK Analyzer**：纯 Java，**轻量**，**分词准确率不如 HanLP**。
- **Jieba**：Python 出身，Java 移植版准确率还行，**功能没 HanLP 丰富**。
- **HanLP**：Java 出身，**功能最全**，**但 jar 包大**。

`pai-smart` 选 HanLP **是要做 NER（命名实体识别）**——IK 和 Jieba 没这个能力。

---

## 【五、子块切片：怎么切？】

我们没看到 `processParentChunk` 的完整实现，但从配置推测：

```java
private void processParentChunk() {
    String parentContent = buffer.toString();
    int start = 0;
    int end = 0;
    int chunkId = 0;
    
    while (start < parentContent.length()) {
        end = Math.min(start + chunkSize, parentContent.length());
        String chunk = parentContent.substring(start, end);
        
        // 1. 用 HanLP 分词（可选）
        // 2. 保存到 DocumentVector 表
        //    - fileMd5
        //    - chunkId
        //    - textContent
        //    - parentChunkId（指向父块）
        //    - orgTag
        //    - isPublic
        //    - userId
        documentVectorRepository.save(...);
        
        start = end - overlapSize;  // 滑动窗口
        chunkId++;
    }
    
    buffer.setLength(0);  // 清空 buffer
}
```

**关键参数**：

- **`chunkSize`**：子块大小（500-1000 字符）。**经验值**：嵌入模型的 max input length 的 60-80%。`text-embedding-v4` 是 2048 token，**对应中文约 1500 字符**。
- **`overlapSize`**：滑动窗口重叠（100 字符）。**目的**：避免一个语义完整的句子被切到两个 chunk。
- **`minChunkSize`**：最小块大小。**避免太多「小碎块」**。

**面试考点 7**：chunk 切得太小或太大，会怎样？

- **太小（< 200 字符）**：
  - 召回多但噪音大。
  - **「这个问题答了一半」**——上下文不够。
- **太大（> 2000 字符）**：
  - 召回少但精度高。
  - 嵌入模型 token 超限，**被截断**。
  - 召回的 chunk 本身已经够回答，**不需要 LLM 再做总结**。
- **经验值**：
  - **英文**：500-1000 token。
  - **中文**：300-800 字符（中文 token 化更密）。

`pai-smart` 选**「小块+父块」**——**兼顾了精度和上下文**。

---

## 【六、`estimateEmbeddingUsage`：上传前预估】

```java
public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException, TikaException {
    try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
        if (isPdfDocument(bufferedStream)) {
            return estimatePdfEmbeddingUsage(bufferedStream);
        }
        StreamingEstimateHandler handler = new StreamingEstimateHandler();
        // ... 同 parseAndSave，但不存库，只计算 token 数
        return handler.snapshot();
    }
}
```

**为什么需要预估？**

`pai-smart` 有**配额系统**（第 17 集讲）——每个用户每月有 X token 额度。

**上传前**就要告诉用户：`「这个文件预计要 1.5 万 token，你还有 8 万 token 额度，确定要上传吗？」`

**预估 vs 实际**的差异：
- **预估**：根据文件大小 × 平均字符密度估算。
- **实际**：embedding API 真实返回的 token 数。

`FileUpload` 实体里的 `estimatedEmbeddingTokens` 和 `actualEmbeddingTokens` **就是给这个用的**。

---

## 【七、`StreamingEstimateHandler` vs `StreamingContentHandler`**

注意看，**两个 Handler 类**——`StreamingContentHandler`（存库）和 `StreamingEstimateHandler`（预估）——**功能类似但行为不同**。

**为什么不开一个 Handler 加 `boolean save` 参数？**

A：**SRP 原则**（Single Responsibility Principle）。两个 Handler 各管一件事：
- `StreamingContentHandler`：处理字符 → 切片 → 存 DB。
- `StreamingEstimateHandler`：处理字符 → 估算 token → 返回结果。

**好处**：
- 一个 Handler 的代码**只关心一件事**。
- 测试更容易——**只测一件事**。
- 改一个不影响另一个。

**但代价**：有重复代码。`pai-smart` 的处理是**用内部类 `private class`**——`StreamingEstimateHandler` 写在 `ParseService` 内部，**牺牲一些独立性换代码紧凑**。

**面试考点 8**：内部类 vs 独立类怎么选？

- **内部类**：逻辑**高度耦合**、**只为这个外部类用**、**不希望被外部直接 new**。
- **独立类**：可能**被其他地方复用**、**需要被注入**、**需要 mock 测试**。

`StreamingContentHandler` 是内部类，**但实际上可以被替换**（用不同的 chunking 策略）。**更好的设计应该是策略模式**——`pai-smart` 这里偷懒了。

---

## 【八、`parsePdfAndSave`：按页解析的细节】

虽然没看完整代码，但 `pai-smart` 一定是这么做的：

```java
private void parsePdfAndSave(String fileMd5, InputStream fileStream, ...) {
    try (PDDocument pdf = Loader.loadPDF(fileStream)) {
        PDFTextStripper stripper = new PDFTextStripper();
        int totalPages = pdf.getNumberOfPages();
        
        for (int page = 1; page <= totalPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(pdf);
            
            // 1. 存页内容到 DocumentVector（带 pageNumber 字段）
            // 2. 或者存到单独的 page 表
        }
    }
}
```

**关键设计**：

- **`pageNumber` 字段**：`DocumentVector` 表有 `pageNumber` 列。**召回时可以告诉用户「这个答案来自原文档第 X 页」**。
- **`anchor_text`**：PDF 里的「锚点文本」——比如小标题、图表标题。**用于快速预览命中位置**。

**面试考点 9**：扫描型 PDF（图片）怎么解析？

A：需要 OCR。**Tesseract**（Java 端口叫 **Tess4J**）。`pai-smart` 当前版本**不支持 OCR**——这是**已知 TODO**。**生产环境 OCR 是个大工程**：
- 部署 Tesseract 引擎。
- 处理 OCR 失败的重试。
- 处理 OCR 出来的乱码（**尤其中文扫描件**）。
- **PDF 中的图片**单独抽出来 OCR（**比整页 OCR 更快**）。

---

## 【九、常见坑 & 面试问答】

**Q1：为什么 `BufferedInputStream` 不用 `Files.newInputStream`？**

A：方法签名是 `InputStream fileStream`——**调用方传什么就是什么**。`pai-smart` 不想绑定到 `Path`——**可能是 MinIO 的流、可能是 HTTP 的流、可能是测试的字节数组流**。**接口要松散**。

**Q2：`parentChunkSize = 1MB` 会不会太大？**

A：1MB 父块对应**约 50 万字符**、**约 25 万 token**。**普通业务文档 1MB 够 200-500 页**。**但 1MB 的 `StringBuilder` 在 JVM 里就是 ~2MB char[]**——**这个内存占用不大**。**真正要担心的是大文件累积**。

**Q3：HanLP 的 `portable` 版本和 enterprise 版本区别？**

A：
- `portable`：30MB 模型，**纯 Java 部署方便**，**功能精简**。
- `enterprise`：1GB+ 模型，**需要 Python 协同**，**功能齐全**。

`pai-smart` 用 `portable` 是**平衡**。

**Q4：流式处理下，`processParentChunk` 失败怎么办？事务回滚？**

A：这是个**经典问题**。`pai-smart` 的解法：
- **整个文件解析用 `@Transactional`**——失败回滚。
- **回滚后**清 MinIO 临时文件、删 DB 记录。
- **如果中途崩溃**（不是抛异常），需要**补偿任务**：定时扫 `status = UPLOADING` 的孤儿记录，**清理**。

**这就是「分布式事务」的简化版**——**单 DB 事务 + 异步补偿**。

**Q5：HanLP 的 `portable-1.8.6` 有安全漏洞吗？**

A：`portable-1.8.6` 是 2022 年的版本。**最新稳定版是 1.8.7+**。`pai-smart` 用的版本稍老，**生产建议升级**——HanLP 在迭代中修复了不少 bug。

---

## 【十、思考题：父-子块策略的「代价」是什么？】

**优点讲了很多**。**代价呢**？

- **存储翻倍**：每个父块存 1 次，每个子块存 1 次。**1000 字符的子块 + 1MB 父块**——**一个 500MB 文档可能产生 500 * 500 = 25 万个子块记录**。
- **JOIN 慢**：召回子块 → 找父块 → 取父块内容。**多一次 DB 查询**。
- **向量化重**：父块变了，所有子块都要重新 embedding（**500MB 文档的 embedding 成本可观**）。

**实际工程权衡**：
- **小型 RAG（< 10 万文档）**：父-子策略**完全值得**。
- **大型 RAG（> 100 万文档）**：**考虑简化**——只用滑动窗口切，**不维护父块**。

`pai-smart` 选了前者——**业务上不会到 100 万文档级别**，**质量优先**。

---

## 【十一、下集预告】

文档已经切成块了，**但这些块对 LLM 来说还是「死的」——它们是字符串，不是数字**。

第 07 集我们要：

- 调通 **Embedding API**（DashScope text-embedding-v4）。
- 把每个 chunk **向量化**——字符串变成 1024 维的 float 数组。
- 把向量存到 `DocumentVector` 表。

**这一步之后，RAG 才真正有了"语义"的能力。**

我们下期见。

---

> 如果你想看 RAG 系统的"上游"是怎么搭的，**这 6 集把「文档 → 文本 → 块」讲透了**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 07 集：EmbeddingClient 登场——把文本变成向量](./07-embedding-client.md)
