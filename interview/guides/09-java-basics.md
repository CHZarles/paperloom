# 09 Java 基础篇

来源：`面渣逆袭Java基础篇V2.1.pdf`。原书共 56 道题。项目回答只把 Java 基础概念落到真实的接口、集合、异常、IO、JSON 和 Stream 代码，不把“了解”说成“线上深度实现”。

## 56 题总表

| 题号 | PDF 页 | 原题 | 取舍 | PaperLoom 关联 |
| --- | ---: | --- | --- | --- |
| Q1 | 5 | 什么是 Java | 必背 | 字节码和 JVM 运行环境 |
| Q2 | 8 | Java 语言有哪些特点 | 选背 | 面向对象、跨平台、自动内存管理 |
| Q3 | 9 | JVM、JDK 和 JRE 区别 | 必背 | Java 17 开发/运行边界 |
| Q4 | 9 | 什么是跨平台，原理是什么 | 必背 | `.class` 与 JVM |
| Q5 | 10 | 什么是字节码，优点是什么 | 选背 | 编译与运行分离 |
| Q6 | 10 | Java 为什么编译与解释并存 | 选背 | javac + 解释器/JIT |
| Q7 | 11 | Java 有哪些数据类型 | 必背 | 基本类型、引用类型、泛型 |
| Q8 | 13 | 自动/强制类型转换 | 必背基础 | 参数和数值边界 |
| Q9 | 14 | 自动装箱/拆箱 | 必背 | 集合泛型与 NPE 风险 |
| Q10 | 15 | `&` 和 `&&` 区别 | 选背 | 位运算与短路逻辑 |
| Q11 | 15 | switch 能否用 byte/long/String | 选背 | 编译限制和 String 支持 |
| Q12 | 15 | break、continue、return | 了解 | 循环和方法控制 |
| Q13 | 16 | 最高效计算 2×8 | 了解 | 位移只适用于明确整数语义 |
| Q14 | 16 | 自增自减运算 | 选背 | `i++` 与并发原子性要分开 |
| Q15 | 17 | float 如何表示小数 | 选背 | 精度和金额边界 |
| Q16 | 18 | 数据准确性如何保证 | 必背项目 | BigDecimal、整数单位、校验 |
| Q17 | 19 | 面向对象与面向过程区别 | 选背 | 服务/接口职责拆分 |
| Q18 | 20 | 面向对象有哪些特性 | 必背项目 | 封装、继承、多态、抽象 |
| Q19 | 25 | 多态解决什么问题 | 必背 | Retriever/TraceSink 接口实现 |
| Q20 | 26 | 重载和重写区别 | 必背 | 编译期签名与运行期 dispatch |
| Q21 | 30 | public/private/protected/默认 | 必背 | 封装服务边界 |
| Q22 | 31 | this 有什么作用 | 了解 | 当前对象、构造器委托 |
| Q23 | 31 | 抽象类和接口区别 | 必背项目 | 小接口能力契约 |
| Q24 | 35 | 成员变量与局部变量区别 | 必背 | 默认值、作用域、线程安全 |
| Q25 | 35 | static 关键字 | 必背 | 类级状态和初始化边界 |
| Q26 | 36 | final 关键字 | 必背 | 常量、不可继承/覆盖 |
| Q27 | 37 | final/finally/finalize 区别 | 必背 | 资源释放不能靠 finalize |
| Q28 | 38 | `==` 和 equals 区别 | 必背 | ID、Map/Set key 语义 |
| Q29 | 39 | 为什么重写 equals 必须重写 hashCode | 必背 | 去重、HashMap 查找 |
| Q30 | 41 | Java 是值传递还是引用传递 | 必背 | 方法参数和对象修改 |
| Q31 | 41 | 深拷贝和浅拷贝 | 选背 | DTO/Map 引用共享风险 |
| Q32 | 44 | Java 创建对象的方式 | 选背 | 构造器、反射、反序列化 |
| Q33 | 47 | String 是基本类型吗，能继承吗 | 必背 | String final/不可变 |
| Q34 | 48 | String、StringBuilder、StringBuffer | 必背 | 内容拼接和线程边界 |
| Q35 | 48 | `new String("abc")` 与字符串字面量 | 必背 | 堆、字符串池 |
| Q36 | 49 | String 不可变与字符串拼接 | 必背 | key 稳定、Builder 拼接 |
| Q37 | 52 | intern 作用 | 了解 | 字符串池和内存风险 |
| Q38 | 52 | Integer 127/128 比较 | 必背 | 包装缓存与 `==` 陷阱 |
| Q39 | 56 | String 转 Integer 原理 | 选背 | parseInt/valueOf、异常 |
| Q40 | 57 | Object 常见方法 | 必背 | equals/hashCode/toString/wait |
| Q41 | 62 | Java 异常处理体系 | 必背 | Error/Exception/业务异常 |
| Q42 | 64 | 异常处理方式 | 必背项目 | cause、try-with-resources、重试 |
| Q43 | 66 | 三道经典异常处理代码题 | 选练 | finally、return、资源关闭 |
| Q44 | 68 | Java IO 流分几种 | 必背 | 字节/字符、输入/输出 |
| Q45 | 71 | 为什么有字节流还要字符流 | 必背 | 编码与文本读取 |
| Q46 | 71 | BIO、NIO、AIO 区别 | 必背项目 | HttpClient 阻塞 NDJSON |
| Q47 | 74 | 序列化和反序列化 | 必背 | Jackson JSON、Kafka、Redis |
| Q48 | 76 | 有几种序列化方式 | 选背 | JSON、Java 原生、二进制协议 |
| Q49 | 77 | Socket 网络套接字 | 了解 | HTTP/WebSocket 底层边界 |
| Q50 | 80 | Java 泛型 | 必背 | 泛型容器、TypeReference |
| Q51 | 82 | 注解 | 必背项目 | Spring/JPA/Kafka 注解 |
| Q52 | 83 | 反射及应用/原理 | 必背 | Spring、JPA、Jackson |
| Q53 | 85 | JDK 1.8 新特性 | 必背 | Lambda、Stream、Optional |
| Q54 | 87 | Lambda 表达式 | 必背 | 回调、排序、Consumer |
| Q55 | 88 | Optional | 选背 | Repository 空值链 |
| Q56 | 88 | Stream 流 | 必背项目 | 集合转换和聚合，非自动并行 |

## 第一轮必须拿下

Q1-Q9、Q16、Q18-Q21、Q23-Q30、Q33-Q36、Q40-Q47、Q50-Q54、Q56。基础题不要只讲语法，要落到“为什么这个项目选择这个类型/边界”。

## 重点背诵稿

### Q1-Q16：语言、类型、转换与准确性

Java 源码先由 `javac` 编译成字节码，再由不同平台的 JVM 解释或 JIT 编译执行，因此“编译与解释并存”；JDK 是开发工具和运行时的集合，JRE 是运行 Java 程序所需的运行环境，JVM 是执行字节码的虚拟机。跨平台不是 `.class` 在所有机器上直接执行，而是每个平台提供对应 JVM。

Java 类型分基本类型和引用类型。基本类型包括 byte、short、int、long、float、double、char、boolean；引用类型包括类、接口、数组、枚举和记录等。自动类型转换只能从表示范围较小/精度较低到较大方向，强制转换可能溢出或丢失精度；数值边界不能靠强转“修好”。

自动装箱把基本类型包装为对象，拆箱反向取值；拆箱 null 会 NPE，包装类 `==` 比较还会受缓存影响。`&` 既能做位运算也能对 boolean 两边都求值，`&&` 是短路逻辑与；`switch` 支持 byte/short/char/int、枚举、String 等，long 不直接支持。`break` 跳出循环/switch，`continue` 进入下一次循环，`return` 结束方法。

2×8 可用左移 3 位表达，但只适用于明确的整数语义，不应为了“位运算更快”牺牲可读性。`i++` 的前后值差异与并发原子性是两道不同问题。float 使用 IEEE 754 近似表示小数，存在精度误差；金额或需要精确十进制的值使用整数最小单位或 `BigDecimal`，不能用 float/double。

### Q17-Q27：面向对象、接口、修饰符和 finally

面向对象通过封装、抽象、继承和多态组织变化；面向过程更强调按步骤操作。PaperLoom 的真实落点是小接口：`ReadingLocationRetriever`、`ProductTraceSink` 等把调用方和具体 Qdrant/磁盘实现解耦，多态解决“同一能力可以替换实现”，而不是为了凑继承层级。

重载发生在同一作用域的参数列表不同，编译器决定调用哪个方法；重写要求子类方法签名兼容，运行时根据实际对象分派。`public` 全可见，`private` 只在类内，`protected` 对包内和子类可见，默认只包内可见。`this` 指当前对象、解决参数同名和委托构造器；`static` 属于类，不依赖实例，静态共享状态必须考虑初始化和并发。

接口定义能力契约，可多实现，适合 PaperLoom 的服务边界；抽象类可以保存实例状态、构造逻辑和部分实现，但 Java 只能单继承。`final` 变量不能再次赋值、方法不能重写、类不能继承；`finally` 是异常清理块；`finalize` 是不可靠且已弃用的回收钩子，不可用于关闭文件、Socket 或数据库连接。

### Q28-Q32：相等、参数传递、拷贝和对象创建

基本类型 `==` 比值，引用类型 `==` 比是否同一对象；`equals` 可定义业务相等。重写 equals 时必须重写 hashCode：相等对象必须有相同 hash，才能在 HashMap/HashSet 中落到可查找的同一桶。PaperLoom 的 paperId、locationRef、generationId 常作为 Map/Set key，必须保持稳定值语义。

Java 只有值传递。传对象时复制的是引用值，所以方法可以通过复制后的引用修改同一个对象，但不能让调用方变量改指向另一个对象。浅拷贝复制外层、内部引用仍共享；深拷贝递归复制可变对象。JSON 序列化再反序列化可以得到结构副本，但成本和类型语义要明确。

创建对象可以用 new、反射、clone、反序列化或工厂/依赖注入。Spring Bean、Jackson 和 JPA 会在框架层创建对象，业务代码更应通过构造器和工厂表达不变量，而不是到处反射。

### Q33-Q40：String、包装类和 Object

String 不是基本类型，属于 final 类，内容不可变。字面量通常进入字符串池，`new String("abc")` 会新建对象并复制/引用池中内容；不要用 `==` 比较字符串内容。String 不可变带来稳定 hash、线程安全和可共享性，也意味着循环拼接会产生中间对象；单线程使用 StringBuilder，StringBuffer 通过同步支持线程安全但开销更大。

`intern()` 尝试返回字符串池中的规范引用，可能减少重复字符串但会增加池管理和内存压力，不能当通用去重工具。Integer 等包装类有缓存，`Integer a=127` 与 `b=127` 可能引用相同对象，而 128 不保证；业务比较用 `equals` 或拆箱后的值。

String 转 Integer 可用 `Integer.parseInt` 得到基本类型，或 `Integer.valueOf` 得到包装对象（可能利用缓存），非法输入抛 `NumberFormatException`。Object 常见方法：`equals`、`hashCode`、`toString`、`getClass`、`wait/notify/notifyAll`；wait/notify 必须在正确的对象监视器内使用。

### Q41-Q49：异常、IO、BIO/NIO/AIO、序列化

Throwable 下有 Error 和 Exception；Exception 分 checked 和 unchecked。Error 通常不应由业务捕获后继续运行；checked 异常要求调用方处理/声明，RuntimeException 常表示参数、状态或编程错误。捕获原则是能恢复才捕获，保留原始 cause，合适层转换业务异常，不能 catch 后只打印。

PaperLoom 的 Kafka Consumer 处理失败会重新抛出，让 `DefaultErrorHandler` 重试并进入 DLT；如果吞掉异常，Kafka 可能认为消费成功。Python HTTP 读取捕获 InterruptedException 后恢复中断标志并转成取消。资源用 try-with-resources 关闭，finally 不要覆盖原始异常或吞掉 return。

Java IO 可按字节/字符和输入/输出分类：字节流处理 PDF、图片等任意二进制；字符流按编码处理文本。BIO 是调用阻塞线程；NIO 用 Channel/Buffer/Selector 让一个线程管理多个连接；AIO 由系统完成后回调。PaperLoom 的 Java `HttpClient` 读取 Python NDJSON 是独立线程上的阻塞 InputStream，不是 Netty NIO，也不是 AIO。

序列化把对象转成可传输/存储的格式，反序列化恢复对象。项目使用 Jackson JSON 连接 Java、Python、Redis、Kafka 和 WebSocket，跨语言可读但体积和类型约束不如二进制协议；没有把 Java 原生序列化当服务协议。Socket 是网络编程端点抽象，WebSocket/HTTP 在更高层定义消息和握手。

### Q50-Q56：泛型、注解、反射和 Java 8

泛型把类型检查提前到编译期，通常通过类型擦除实现；运行时拿不到完整泛型参数时，Jackson 需要 `TypeReference` 或显式 `JavaType`。PaperLoom 用 `TypeReference<Map<String,Object>>` 等方式解析生成态 JSON，避免原始 Map 的强转风险。

注解是附着在类、方法、字段等元素上的元数据，可保留在源码、class 或运行时。Spring 用 `@Service`、`@Configuration`、`@Bean`、`@Transactional`、`@KafkaListener` 等装配和声明行为，JPA 用实体/列/索引注解。注解本身不执行逻辑，框架读取它后才产生效果。

反射可在运行时获取类、字段、方法并实例化/调用；Spring IOC、JPA 映射、Jackson 都会使用反射或生成代码。代价是运行时错误、封装边界被绕过和一定性能成本，业务代码不要反射代替类型安全接口。

JDK 8 重点特性：Lambda、函数式接口、Stream、Optional、接口默认方法、新日期时间 API。Lambda 是行为值，可作为 Consumer/Function/Predicate 传入；Optional 表达“可能为空”的返回值，但不要把字段全改成 Optional 或用 `get()` 逃避空值处理。

Stream 是惰性流水线，`filter/map/flatMap/sorted` 等中间操作在终止操作前不执行；`collect/reduce/forEach` 才触发计算。并行 Stream 需要拆分收益、线程池、数据量和无共享副作用，遇到阻塞 IO 或小集合可能更慢。PaperLoom 主要用 Stream 做内存集合转换和聚合，不把它说成自动并行。

## 项目版 Java 选择图（ASCII）

```text
遇到问题先问：
  跨模块能力契约 -> 接口 / 多态
  需要严格数值   -> BigDecimal / 整数最小单位
  需要稳定 key   -> String + equals/hashCode 契约
  资源必须关闭   -> try-with-resources
  跨语言结构化   -> Jackson JSON + TypeReference
  可空返回       -> 明确 Optional/空值策略
  集合转换       -> Stream（先看数据量和副作用）
```

## 对应代码与边界

- `../pom.xml`：Java 17 版本声明。
- `../src/main/java/io/github/chzarles/paperloom/service/ReadingLocationRetriever.java`、相关 Retriever 实现：接口和多态边界。
- `../src/main/java/io/github/chzarles/paperloom/service/ProductTraceSink.java`、`AsyncDiskProductTraceSink.java`：接口、IO、异常和队列。
- `../src/main/java/io/github/chzarles/paperloom/service/PythonResearchHarnessClient.java`：HttpClient、InputStream、JSON、Future 取消。
- `../src/main/java/io/github/chzarles/paperloom/consumer/PaperProcessingConsumer.java`：异常重新抛出触发 Kafka 重试/DLT。
- `../src/main/java/io/github/chzarles/paperloom/service/ChatGenerationStateService.java`：Jackson `TypeReference`、String/Map 结构化数据。

## 最后背一遍：项目版 Java 基础自我介绍

> 我在项目里最常用的 Java 基础不是背语法，而是把边界表达清楚。PaperLoom 用小接口和多态隔离 Retriever、Trace Sink 等能力；paperId、locationRef、generationId 作为 Map/Set key 时依赖 String 的不可变性以及 equals/hashCode 契约；跨 Java、Python、Redis、Kafka 的结构化数据用 Jackson JSON 和 TypeReference，不用 Java 原生序列化。Python harness 的 HTTP 流是阻塞 InputStream，取消时恢复中断标志；Kafka Consumer 失败会重新抛出交给重试/DLT，不能 catch 后只打印。Stream 只用于明确的集合转换和聚合，不等于自动并发；金额和精度场景不使用 float，资源用 try-with-resources 关闭。
