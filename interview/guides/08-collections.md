# 08 集合框架篇

来源：`面渣逆袭集合框架篇V2.1.pdf`。原书共 30 道题。集合题要从“访问模式和并发边界”回答，而不是只背复杂度；PaperLoom 里最有证据的类型是 `ConcurrentHashMap`、`CopyOnWriteArrayList`、`LinkedHashMap`、`LinkedHashSet` 和 `ArrayBlockingQueue`。

## 项目真实用法

| 类型 | PaperLoom 用途 | 取舍 |
| --- | --- | --- |
| `ConcurrentHashMap` | `generationId -> ActiveRequest`、用户到 Session 列表、ChatHandler 的进程内状态 | 单次操作线程安全，不代表跨 JVM 或多步流程原子 |
| `CopyOnWriteArrayList` | 一个用户的 WebSocket Session 列表 | 广播读多、连接增删少；写会复制数组 |
| `LinkedHashMap` | 组装 JSON/工具 Payload，保持稳定输出顺序 | 顺序是内存对象属性，不是数据库排序保证 |
| `LinkedHashSet` | 去重且保留论文/Evidence/引用的发现顺序 | 需要顺序时比 HashSet 更准确表达意图 |
| `ArrayBlockingQueue` | Trace 写盘的有界缓冲 | 满时 `offer` 失败，Trace 丢弃并告警 |

## 30 题总表

| 题号 | PDF 页 | 原题 | 取舍 | PaperLoom 关联 |
| --- | ---: | --- | --- | --- |
| Q1 | 5 | 说说有哪些常见的集合框架 | 必背 | List/Set/Map/Queue 总览 |
| Q2 | 12 | ArrayList 和 LinkedList 有什么区别 | 必背 | 选择访问模式 |
| Q3 | 16 | ArrayList 扩容机制 | 选背 | 数组复制和容量成本 |
| Q4 | 17 | ArrayList 怎么序列化 | 了解 | transient、writeObject/readObject |
| Q5 | 18 | 快速失败 fail-fast | 必背 | 迭代器检测，不是线程安全 |
| Q6 | 19 | 实现 ArrayList 线程安全的方法 | 必背 | COW、同步包装、外部锁 |
| Q7 | 20 | CopyOnWriteArrayList 了解多少 | 必背项目 | Session 列表 |
| Q8 | 21 | HashMap 底层数据结构 | 必背 | 数组 + 链表/红黑树 |
| Q9 | 22 | 红黑树了解多少 | 选背 | 树化后的桶结构 |
| Q10 | 23 | 红黑树如何保持平衡 | 了解 | 旋转、变色、五条性质 |
| Q11 | 25 | HashMap put 流程 | 必背 | hash、定位、冲突和扩容 |
| Q12 | 27 | HashMap 如何查找元素 | 必背 | hash、equals、桶内查找 |
| Q13 | 28 | HashMap hash 函数如何设计 | 必背 | 高位扰动 |
| Q14 | 29 | hash 函数如何减少冲突 | 选背 | 混合高低位，不能消灭冲突 |
| Q15 | 30 | HashMap 容量为什么是 2 的幂 | 必背 | `(n-1)&hash` 和扩容迁移 |
| Q16 | 32 | 初始化容量传 17 会怎样 | 选背 | 延迟初始化并向上取 2 的幂 |
| Q17 | 34 | 还知道哪些哈希函数构造方法 | 了解 | 除法、乘法、平方取中等 |
| Q18 | 34 | 解决哈希冲突的方法 | 必背 | 链地址、开放寻址、再哈希 |
| Q19 | 36 | 链表转红黑树阈值为什么是 8 | 必背 | 概率、树化容量阈值 64 |
| Q20 | 36 | HashMap 什么时候扩容 | 必背 | size 超过 threshold |
| Q21 | 38 | HashMap 扩容机制 | 必背 | 翻倍、lo/hi 迁移 |
| Q22 | 41 | JDK 8 对 HashMap 做了哪些优化 | 必背 | 红黑树、迁移和尾插 |
| Q23 | 43 | 能自己设计 HashMap 吗 | 选练 | 说接口、冲突、扩容和边界 |
| Q24 | 47 | HashMap 线程安全吗 | 必背 | 不安全，多步操作有竞态 |
| Q25 | 49 | 如何解决 HashMap 线程不安全 | 必背项目 | CHM、不可变、外部锁 |
| Q26 | 52 | HashMap 内部节点有序吗 | 必背 | 默认无序，不要依赖遍历顺序 |
| Q27 | 53 | LinkedHashMap 如何实现有序 | 必背项目 | 双向链表维护插入/访问顺序 |
| Q28 | 53 | TreeMap 如何实现有序 | 选背 | 红黑树、Comparator、范围查询 |
| Q29 | 54 | TreeMap 和 HashMap 区别 | 必背 | O(logN) 排序 vs 平均 O(1) |
| Q30 | 55 | HashSet 底层实现 | 必背 | 以 HashMap 的 key 存元素 |

## 第一轮必须拿下

Q1-Q2、Q5-Q8、Q11-Q16、Q19-Q22、Q24-Q30。重点是能解释“为什么项目的 Session 用 COW，为什么活动任务用 CHM，为什么 Trace 用有界队列”。

## 重点背诵稿

### Q1-Q7：List、fail-fast 与 COW

List 有序可重复，Set 关注去重，Map 保存 key-value，Queue 表示排队访问。`ArrayList` 是动态数组，按下标访问快，尾部追加均摊 O(1)，中间插入/删除需要搬移元素；`LinkedList` 是双向链表，已经定位节点时改指针便宜，但查找位置仍可能 O(n)，且节点对象和指针有额外内存成本。不能机械背“LinkedList 插入快”。

ArrayList 扩容时分配更大的数组并复制元素，具体增长比例是实现细节；预估容量可以减少复制，但容量过大会浪费内存。序列化会通过自定义 `writeObject/readObject` 只写实际元素，不把内部容量空槽全部写出。

fail-fast 迭代器记录结构修改次数，遍历期间若检测到非迭代器修改可能抛 `ConcurrentModificationException`。它是尽力而为的错误检测，不是线程安全机制；并发修改不能靠“看见异常”来解决。

实现 ArrayList 线程安全有四类思路：线程封闭、外部锁、`Collections.synchronizedList`、`CopyOnWriteArrayList`。COW 写时复制新数组，读遍历无锁快照，适合读多写少、列表规模可控；写频繁或列表很大时复制成本高，还可能读到旧快照。

PaperLoom 的 `ChatSessionRegistry` 每个 userId 对应一个 `CopyOnWriteArrayList<WebSocketSession>`；消息广播遍历多，注册/注销相对少，符合 COW 的使用条件。它只解决进程内列表并发访问，不能让多副本服务器共享 Session。

### Q8-Q10：HashMap 桶和红黑树

JDK 8 HashMap 可记成：

```text
table[n]
  ├─ 空桶
  ├─ 链表：Node -> Node -> ...
  └─ 冲突严重且容量足够：TreeNode（红黑树）
```

红黑树的核心性质是每个节点红/黑、根黑、空叶视为黑、红节点不能有红孩子、从任意节点到叶子的黑节点数相同。插入/删除通过变色和左旋/右旋恢复性质，换取 O(logN) 的最坏查找。

HashMap 的桶树化阈值是 8，但还要满足 table 容量至少 64；容量不足时优先扩容。阈值是时间、空间和树维护成本的折中，不是“链表第 8 个节点必然立刻成树”。

### Q11-Q16：hash、put、查找与二次幂

`put` 流程：计算扰动后的 hash → 延迟初始化 table → `(n - 1) & hash` 定位桶 → 空桶直接放入 → 相同 key 替换 value → 冲突时遍历链表/红黑树 → size 超过 threshold 时扩容。`get` 先用 hash 定位，再比较 hash 和 `equals`；因此重写 `equals` 必须同时满足相等对象的 hashCode 相同。

JDK 8 常见扰动是把高位 hash 异或到低位（如 `h ^ (h >>> 16)`），让高位信息参与低位桶定位；它减少冲突但不能消灭冲突。哈希构造还可以用除留余数、乘法、平方取中等，工程实现更关注分布、速度和攻击抵抗。

容量用 2 的幂是为了让 `(n-1)&hash` 代替取模，并使扩容翻倍时节点只有两种去向：原索引，或原索引 + oldCap，由 hash 的新增位决定。传入初始容量 17 时，HashMap 不一定立即分配数组，真正初始化时会向上取到合适的 2 的幂（通常为 32），再根据负载因子计算 threshold。

**Q17-Q18 冲突：** 链地址法把冲突节点挂在桶里；开放寻址在数组中探测下一个位置；再哈希使用另一套 hash 函数。HashMap 采用链地址并在冲突严重时树化；`hashCode` 好只影响分布，不能替代 `equals`。

### Q19-Q23：扩容和 JDK 8 优化

默认负载因子约 0.75，size 超过 `capacity * loadFactor` 时扩容，通常容量翻倍。JDK 8 迁移时不重新计算完整 hash，而按新增高位把链表拆为 low/high 两条，分别留在原索引或移动 oldCap；尾插和树化改进减少了部分并发扩容链表成环风险，但 HashMap 仍不是线程安全。

自己设计 HashMap 至少要说：接口和 key 语义、hash 扰动、桶定位、冲突结构、扩容阈值、null 规则、迭代顺序、并发边界和测试。不要在面试中写一个没有扩容、删除、equals 或内存边界处理的“能跑 demo”。

### Q24-Q30：线程安全、有序 Map 和 Set

HashMap 不是线程安全容器；并发 put 可能丢更新，扩容/遍历也可能观察到不一致。解决方案按场景选：不共享或只读就线程封闭/不可变 Map；少量同步访问可外部锁或 `synchronizedMap`；高并发独立 key 用 `ConcurrentHashMap`；需要复合原子操作则使用 `compute`、`putIfAbsent` 或显式锁包住完整流程。

PaperLoom 的 `PythonResearchHarnessClient.activeRequests` 用 `ConcurrentHashMap`，`putIfAbsent(generationId, active)` 原子拒绝同一 generation 重复提交；`ChatSessionRegistry` 用 `compute`/`computeIfPresent` 注册与注销。这些是进程内并发语义，不是跨节点任务锁。

HashMap 默认不保证节点顺序，不能依赖遍历顺序生成稳定 JSON。`LinkedHashMap` 在 HashMap 基础上维护双向链表，可按插入顺序或访问顺序排列；PaperLoom 用它组装 Payload/状态对象，便于日志和测试输出稳定，但稳定输出不等于协议排序。

`TreeMap` 基于红黑树，按自然顺序或 Comparator 排序，查找/插入/删除 O(logN)，支持范围视图；HashMap 平均 O(1) 但无序。没有排序/范围需求不要为了“高级”使用 TreeMap。

HashSet 底层可理解为 HashMap：元素作为 key，value 是统一占位对象；去重依赖 hashCode/equals。`LinkedHashSet` 在此基础上维护插入顺序，适合“去重但保留发现顺序”的论文 ID、Evidence ID 和引用编号集合。

## 项目版集合选择图（ASCII）

```text
需要什么？
 ├─ 下标随机读 / 常规顺序 -> ArrayList
 ├─ 读多写少的 Session 快照 -> CopyOnWriteArrayList
 ├─ key 查找 + 并发活动状态 -> ConcurrentHashMap
 ├─ 去重且保留输入顺序 -> LinkedHashSet
 ├─ key 查找且保留组装顺序 -> LinkedHashMap
 ├─ 按 key 排序 / 范围查询 -> TreeMap
 └─ 生产者消费者 + 容量上限 -> ArrayBlockingQueue
```

## 对应代码与边界

- `../src/main/java/io/github/chzarles/paperloom/service/ChatSessionRegistry.java`：ConcurrentHashMap、CopyOnWriteArrayList、Session 列表和发送锁。
- `../src/main/java/io/github/chzarles/paperloom/service/PythonResearchHarnessClient.java`：ConcurrentHashMap、`putIfAbsent`、FutureTask 活动请求。
- `../src/main/java/io/github/chzarles/paperloom/service/ChatHandler.java`：LinkedHashMap/LinkedHashSet 风格的稳定 Payload 与进程内状态。
- `../src/main/java/io/github/chzarles/paperloom/service/AsyncDiskProductTraceSink.java`：ArrayBlockingQueue、`offer`、满队列丢 Trace。

## 最后背一遍：项目版集合自我介绍

> 我会先按访问模式选集合。PaperLoom 的 WebSocket Session 列表是读多写少，所以用 CopyOnWriteArrayList；用户和 generation 的活动状态需要按 key 并发读写，所以用 ConcurrentHashMap，并通过 putIfAbsent 或 compute 表达重复归属和注册注销。组装 JSON/Payload 时需要稳定顺序用 LinkedHashMap，论文或 Evidence 需要去重并保留发现顺序用 LinkedHashSet。Trace 写盘需要明确内存上限，用 ArrayBlockingQueue，offer 失败就丢弃并告警。HashMap 仍然不是线程安全的，ConcurrentHashMap 的单次操作也不等于整个业务流程原子，更不提供跨 JVM 一致性。
