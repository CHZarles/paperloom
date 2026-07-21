# 07 JVM 篇

来源：`面渣逆袭 JVM篇 V2.1.pdf`。原书共 54 道题。PaperLoom 使用 Java 17，但仓库没有固定 GC 参数，也没有可证明的线上 JVM 调优收益；所有调优回答都必须先拿监控证据。

## 项目事实边界

| 事实 | 代码证据 | 面试边界 |
| --- | --- | --- |
| Java 版本 | `pom.xml` 的 `java.version=17` | 可以说编译目标是 Java 17，不能猜生产 JVM 启动参数 |
| 线程和内存风险 | Python harness 使用 `newCachedThreadPool`；多个进程内 Map | 能指出无硬上限风险，不能说已完成压测调优 |
| 临时状态 | Redis 生成态 TTL 30 分钟；Trace 队列有界 | 可解释泄漏边界，不等于 JVM 内存已安全 |
| 文件/网络资源 | Qdrant、MinIO、HTTP client | `Too many open files` 先查 FD/Socket，不先改堆或 GC |
| 排障工具 | 书中 `jcmd/jstack/jmap/jstat/JFR` | 工具是排查方法，不是项目已经运行过的证据 |

## 54 题总表

| 题号 | PDF 页 | 原题 | 取舍 | PaperLoom 关联 |
| --- | ---: | --- | --- | --- |
| Q1 | 5 | 什么是 JVM | 必背基础 | Java 字节码运行环境 |
| Q2 | 8 | JVM 的组织架构 | 选背 | 类加载、运行时数据区、执行引擎 |
| Q3 | 9 | JVM 的内存区域 | 必背 | 堆、栈、元空间、直接内存 |
| Q4 | 15 | JDK 1.6/1.7/1.8 内存区域变化 | 了解 | 版本差异，不写项目实践 |
| Q5 | 17 | 为什么用元空间替代永久代 | 选背 | Java 17 版本边界 |
| Q6 | 18 | 对象创建过程 | 必背 | 请求对象、任务对象生命周期 |
| Q7 | 19 | 堆内存如何分配 | 选背 | TLAB、指针碰撞/空闲列表 |
| Q8 | 21 | new 对象时堆会发生抢占吗 | 选背 | 并发分配与 TLAB |
| Q9 | 23 | 对象的内存布局 | 了解 | Mark Word、类型指针、对齐 |
| Q10 | 30 | JVM 怎么访问对象 | 了解 | 句柄与直接指针 |
| Q11 | 31 | 对象有哪几种引用 | 选背 | 强/软/弱/虚引用 |
| Q12 | 33 | Java 堆的内存分区 | 必背 | 新生代、老年代、Region |
| Q13 | 34 | 新生代区域划分 | 必背 | Eden、Survivor |
| Q14 | 35 | 对象什么时候进入老年代 | 必背 | 年龄、空间担保、大对象 |
| Q15 | 36 | STW 了解吗 | 必背 | 停顿与请求延迟 |
| Q16 | 37 | 对象一定分配在堆中吗 | 必背 | 逃逸分析、标量替换、栈上分配可能性 |
| Q17 | 40 | 内存溢出和内存泄漏 | 必背项目 | Map、缓存、线程、直接内存分类 |
| Q18 | 41 | 手写内存溢出例子 | 了解 | 只会最小复现，不在项目里制造故障 |
| Q19 | 42 | 内存泄漏原因 | 必背 | 生命周期和引用链 |
| Q20 | 43 | 处理过内存泄漏吗 | 必背诚实版 | 讲排查闭环，不虚构线上事故 |
| Q21 | 48 | 处理过内存溢出吗 | 必背诚实版 | 讲证据分类，不虚构修复收益 |
| Q22 | 49 | 什么情况下栈溢出 | 必背 | 递归、栈帧、线程数 |
| Q23 | 50 | JVM 垃圾回收机制 | 必背 | GC Roots、标记、清除/整理 |
| Q24 | 52 | 如何判断对象仍然存活 | 必背 | 可达性分析与引用处理 |
| Q25 | 53 | 哪些引用可作 GC Roots | 必背 | 栈、静态、常驻和同步锁引用 |
| Q26 | 56 | finalize 方法 | 了解 | 已不推荐，不能依赖资源释放 |
| Q27 | 57 | 垃圾收集算法 | 必背 | 标记清除、复制、整理、分代 |
| Q28 | 60 | Minor/Major/Mixed/Full GC | 必背 | 术语按收集器解释 |
| Q29 | 61 | Young GC 什么时候触发 | 必背 | Eden 不足等 |
| Q30 | 61 | 什么时候触发 Full GC | 必背排障 | 老年代、元空间、显式 GC 等 |
| Q31 | 61 | 知道哪些垃圾收集器 | 必背 | Serial、Parallel、CMS、G1、ZGC |
| Q32 | 66 | CMS 垃圾收集过程 | 了解 | Java 17 不再使用 CMS |
| Q33 | 67 | G1 垃圾收集器 | 必背 | Region、Remembered Set、停顿目标 |
| Q34 | 69 | 有 CMS 为什么还引入 G1 | 选背 | 碎片、停顿和大堆 |
| Q35 | 69 | 线上用什么垃圾收集器 | 必背边界 | 先查参数，不能直接声称 G1 |
| Q36 | 70 | 垃圾收集器如何选择 | 必背 | 延迟、吞吐、堆大小、版本 |
| Q37 | 70 | 用过哪些性能监控命令行工具 | 必背 | jcmd、jstack、jmap、jstat |
| Q38 | 72 | 可视化性能监控工具 | 选背 | JFR、VisualVM、MAT、GCViewer |
| Q39 | 74 | JVM 常见参数配置 | 必背 | Xms/Xmx/Xss、HeapDump、GC log |
| Q40 | 75 | 做过 JVM 调优吗 | 必背诚实版 | 证据→假设→改动→回归 |
| Q41 | 76 | CPU 占用过高怎么排查 | 必背排障 | top、线程 ID、jstack/JFR |
| Q42 | 78 | 内存飙高怎么排查 | 必背排障 | 堆、Native、线程、FD 分类 |
| Q43 | 79 | 频繁 Minor GC 怎么办 | 必背 | 分配率、新生代、对象存活 |
| Q44 | 79 | 频繁 Full GC 怎么办 | 必背 | 泄漏、老年代、元空间、显式 GC |
| Q45 | 80 | 类的加载机制 | 必背 | 加载、链接、初始化 |
| Q46 | 81 | 类加载器有哪些 | 选背 | Bootstrap/Platform/Application |
| Q47 | 81 | 类的生命周期 | 必背 | 加载到卸载 |
| Q48 | 81 | 类装载过程 | 选背 | 加载、验证、准备、解析、初始化 |
| Q49 | 83 | 什么是双亲委派模型 | 必背 | 核心类安全与唯一性 |
| Q50 | 83 | 为什么用双亲委派 | 必背 | 防止核心类被伪造 |
| Q51 | 84 | 如何破坏双亲委派 | 了解 | SPI、线程上下文、容器类加载 |
| Q52 | 85 | 破坏双亲委派的典型例子 | 选背 | Tomcat、SPI、模块化 |
| Q53 | 85 | Tomcat 类加载机制 | 了解 | Webapp 隔离、父子加载器 |
| Q54 | 88 | 解释执行和编译执行区别 | 选背 | 解释器、JIT、热点代码 |

## 第一轮必须拿下

Q1-Q3、Q6、Q12-Q17、Q19-Q25、Q28-Q31、Q33、Q35-Q37、Q39-Q44、Q45、Q49-Q50。JVM 题的关键不是背参数，而是能把症状归类后用工具验证。

## 重点背诵稿

### Q1-Q5：JVM 架构和内存区域

JVM 是执行 Java 字节码的虚拟机，负责类加载、运行时数据管理、字节码解释/JIT 编译和垃圾回收。组织上可按这条链记：

```text
Java 源码 → javac → .class 字节码
                         ↓
类加载器 → 运行时数据区 → 执行引擎（解释器/JIT）
                         ↓
                    本地方法接口
```

线程私有区域是程序计数器、Java 虚拟机栈和本地方法栈；线程共享区域是堆和方法区。HotSpot 从 JDK 8 起用本地内存的 Metaspace 保存类元数据，直接内存不属于 Java 堆，但 NIO、网络库和 JNI 可能使用它。JDK 版本差异只能按版本回答，不能把永久代、元空间和 Java 堆混为一谈。

PaperLoom 使用 Java 17，服务包含 HTTP、WebSocket、Redis、Kafka 和 Qdrant 客户端；排查内存时必须把堆对象、线程栈、Metaspace、直接内存、Socket/FD 分开统计。

### Q6-Q16：对象创建、分配与布局

对象创建可以背成：类是否已加载 → 检查并分配内存 → 零值初始化 → 设置对象头 → 执行构造器。连续空闲空间可用指针碰撞，碎片化时用空闲列表；多线程分配可通过 TLAB、CAS 或线程本地缓冲减少抢占。

对象布局通常包含 Mark Word、类型指针、实例数据和对齐填充；具体大小受 JVM、压缩指针、字段类型和对齐影响，不能只凭 `new Object()` 说固定字节数。对象访问可通过句柄或直接指针，HotSpot 常见是直接指针路径。

引用分四类记：强引用只要可达就不会回收；软引用在内存紧张时可能回收，适合可丢缓存；弱引用在下一次 GC 发现后回收；虚引用不能直接取对象，主要用于跟踪回收和清理。不要用 `finalize()` 管业务资源。

堆可按新生代/老年代理解；新生代通常有 Eden、From Survivor、To Survivor。对象经过多次 Young GC 仍存活、Survivor 放不下、年龄达到阈值或大对象分配等情况下可能进入老年代，具体策略由收集器和参数决定。

STW 是 Stop-The-World，某些 GC 阶段暂停应用线程；并发收集器也不是“完全没有停顿”。对象不一定物理分配在堆：逃逸分析可能进行标量替换或消除分配，但不要把“栈上分配”说成每个局部对象都在栈上。

### Q17-Q22：泄漏、OOM 与栈溢出

内存泄漏是对象仍被 GC Roots 可达但业务已经不需要；OOM 是某个内存区域无法满足下一次分配。常见类别：无界 Map/List、永不过期缓存、监听器未注销、ThreadLocal 未 remove、线程数无限增长、直接内存耗尽、Metaspace 类加载器泄漏和文件映射/FD 泄漏。

PaperLoom 的防线和风险要同时说：研究完成后从 `activeRequests` 清理；Redis 生成态有 30 分钟 TTL；Trace 使用有界 `ArrayBlockingQueue`；但 Python harness 的 `newCachedThreadPool` 没有硬最大线程数，任务高峰可能放大线程、栈和下游连接压力。不能把“有 TTL”说成整个应用不会泄漏。

**Q20/Q21 诚实回答：** 当前仓库没有可核验的生产 OOM/泄漏事故和调优收益。可以回答排查流程：先确认 RSS 与堆是否同步增长，再看 GC 日志、类直方图、Heap Dump Dominator Tree、引用链、线程数、Native Memory、FD 和连接池；修复后用相同流量回归，而不是虚构“调大 Xmx 后解决”。

栈溢出通常来自无限递归、递归深度过大、单个栈帧局部变量过多或线程栈过小；增加 `-Xss` 只能改变阈值，不能修复无限递归。大量线程还会消耗每线程栈内存，即使堆没有增长也可能 OOM。

### Q23-Q30：GC 可达性、算法和触发

GC 从 GC Roots 做可达性分析，不是简单引用计数。常见 Roots 包括线程栈中的局部变量、静态字段、常驻类加载器、JNI 引用和同步锁持有的对象。对象经过标记后，按引用类型和收集器处理；`finalize()` 不可靠且已被弃用，不应作为资源释放机制。

算法取舍：标记清除简单但有碎片；复制适合存活对象少的区域但需要额外空间；标记整理减少碎片但移动对象成本高；分代假设大多数对象朝生夕死。Young/Minor GC 通常处理新生代，Major 常指老年代（不同收集器定义不完全一致），Mixed 是 G1 同时回收部分老年代和新生代，Full GC 通常范围更大、停顿风险更高。

Young GC 常见触发是 Eden 无法分配新对象；Full GC 可能由老年代分配失败、空间担保失败、Metaspace 压力、显式 `System.gc()`、晋升失败或收集器特定条件触发。术语要结合实际收集器和 GC 日志，不要把每次老年代回收都叫 Full GC。

### Q31-Q36：收集器选择

Serial 单线程、适合小堆或单核；Parallel 追求吞吐；CMS 以低老年代停顿为目标但有碎片和并发失败问题，已在新 JDK 移除；G1 把堆划分为 Region，按收益选择回收集合，并以停顿目标做预测；ZGC/Shenandoah 进一步降低大堆停顿，但需要匹配 JDK、内存和运维能力。

CMS 的流程只需会初始标记、并发标记、重新标记、并发清除；并发阶段应用仍运行，需要写屏障/重新标记处理引用变化。G1 的记忆集记录跨 Region 引用，Mixed GC 回收收益高的老年代 Region。G1 不是“永远最好”，选择要看堆大小、延迟目标、吞吐、JDK 版本和压测。

PaperLoom 的唯一可核实版本事实是 Java 17；没有启动参数、GC 日志或线上延迟对比，不能说“线上一定用 G1”或“已经调成 ZGC”。面试回答应是“先查看 `jcmd VM.flags`/启动命令和 GC 日志，再决定”。

### Q37-Q44：监控、CPU 和内存排障

命令行工具按问题选择：`jcmd` 看 VM flags、类直方图、线程和 JFR；`jstack`/`jcmd Thread.print` 看线程栈与死锁；`jmap` 生成堆信息/Heap Dump；`jstat` 观察 GC 计数和容量；JFR 记录低开销运行事件。可视化可用 JDK Mission Control、VisualVM、MAT、GCViewer，工具输出必须和时间线、流量、版本对应。

常见参数包括 `-Xms/-Xmx` 堆初始/最大值、`-Xss` 线程栈、`-XX:MaxMetaspaceSize`、HeapDumpOnOutOfMemoryError、统一 GC 日志 `-Xlog:gc*`。参数本身不是调优结果；不能复制一串 JVM 参数就算完成调优。

**Q41 CPU 过高：**

```text
top/监控确认 Java PID
        ↓
top -H -p PID 找高 CPU 线程
        ↓
线程 ID 转十六进制
        ↓
jstack PID / jcmd PID Thread.print 匹配栈
        ↓
连续采样：区分死循环、锁竞争、GC、序列化、下游重试
        ↓
必要时用 JFR/async-profiler 验证热点
```

**Q42 内存飙高：** 先看进程 RSS 与堆使用是否同步；再区分 Java 堆、Metaspace、直接内存、线程栈、文件映射和 FD。堆增长用类直方图/Heap Dump 的 Dominator Tree 找持有者；RSS 增长但堆稳定要查 Native/直接内存/线程/库；Qdrant `Too many open files` 先查 `lsof`、Socket 和连接池，不是 JVM 堆泄漏。

**Q43 Minor GC 频繁：** 看分配速率、新生代大小、对象平均寿命和 Survivor 晋升，确认是正常高吞吐还是短命对象过多；不要只调大堆。**Q44 Full GC 频繁：** 看老年代占用、晋升失败、Metaspace、显式 GC、泄漏和大对象，结合 GC 日志的原因字段；修复引用链/分配模式后再调整收集器或堆。

### Q45-Q54：类加载、双亲委派与执行

类生命周期可背成：加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载。加载器有 Bootstrap、Platform、Application，应用还可自定义 ClassLoader。双亲委派是子加载器先请求父加载器，父找不到时子再加载，避免核心类重复和伪造，维护类型唯一性。

打破双亲委派的常见场景包括 SPI 需要线程上下文类加载器、Tomcat 为每个 Web 应用隔离依赖、OSGi/插件系统的子优先加载，以及模块化容器的自定义策略。打破不是“破坏安全”，而是明确的隔离/扩展设计；要说明加载器边界和类冲突风险。

Tomcat 通常有公共/容器/应用层级，Web 应用之间隔离自己的类和依赖；热部署可用新 ClassLoader 加载新版本、切换引用、停止旧实例并等待旧线程结束，但静态状态、线程、连接和 ClassLoader 引用未清理会造成 Metaspace 泄漏。PaperLoom 没有 Tomcat 热部署实现证据。

解释执行由解释器逐条执行字节码，启动快；JIT 识别热点方法编译为机器码并进行内联、逃逸分析等优化，预热后更快。JIT 优化受运行时数据影响，压测要预热、固定版本和关注分位延迟。

## 项目排障总图（ASCII）

```text
症状
 ├─ CPU 高 ──────> 高 CPU 线程 → jstack/JFR → 死循环/锁/GC/重试
 ├─ RSS 高 ──────> 堆？Native？线程栈？直接内存？FD？
 ├─ Minor GC 高 ─> 分配率/新生代/晋升/短命对象
 ├─ Full GC 高 ──> 老年代/泄漏/Metaspace/显式 GC/晋升失败
 ├─ OOM ─────────> 看异常类型与 Heap Dump，不先盲目加 Xmx
 └─ Too many FD -> lsof/Socket/连接池/Qdrant client，不先改 GC
```

## 对应代码

- `../pom.xml`：`java.version=17`，证明 Java 版本边界。
- `../src/main/java/io/github/chzarles/paperloom/service/PythonResearchHarnessClient.java`：cached thread pool、活动请求 Map、FutureTask 取消和 HTTP 流任务。
- `../src/main/java/io/github/chzarles/paperloom/service/ChatHandler.java`：generation 进程内 Map、CompletableFuture、异步监控和清理。
- `../src/main/java/io/github/chzarles/paperloom/service/AsyncDiskProductTraceSink.java`：有界队列、fixed writer、关闭等待和中断处理。
- `../src/main/java/io/github/chzarles/paperloom/service/ChatSessionRegistry.java`：Session 集合和发送锁，可用于线程/连接排障。
- `../src/main/java/io/github/chzarles/paperloom/service/QdrantClient.java`：HTTP 连接/文件描述符问题应从客户端与 OS 资源排查。

## 最后背一遍：项目版 JVM 自我介绍

> PaperLoom 使用 Java 17，但我不会在没有启动参数和 GC 日志的情况下声称线上用了某个收集器或完成了 JVM 调优。JVM 排障先分堆、Metaspace、直接内存、线程栈、Socket/FD，再用 jcmd、jstack、jmap、jstat 或 JFR 取证。项目里研究请求结束后会从活动 Map 清理，生成态在 Redis 有 30 分钟 TTL，Trace 使用有界队列；同时 Python harness 当前是没有硬最大线程数的 cached thread pool，这是需要关注的线程和 Native 内存风险。遇到 CPU 高先定位线程和栈，遇到 RSS 高先区分堆外资源，Qdrant 的 Too many open files 不能直接当成堆泄漏。GC、类加载和双亲委派我能讲原理，但不会把书中调优案例包装成 PaperLoom 的线上收益。
