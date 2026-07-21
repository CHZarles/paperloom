# 12 MyBatis 篇：项目实际使用 JPA，只准备对比题

来源：`面渣逆袭 MyBatis.pdf`。原书共 20 道题，Q1 下面还展开了 ORM、半自动映射和 JDBC 缺点等小问。PaperLoom 的持久层是 Spring Data JPA/Hibernate，不是 MyBatis 或 MyBatis-Plus；因此本篇不把 MyBatis 代码改名后塞进项目经历，而是先按原书逐题准备，再回答“为什么项目没用 MyBatis”。

## 项目事实边界

- `pom.xml` 只有 `spring-boot-starter-data-jpa`，实体和 Repository 使用 JPA/Hibernate。
- 论文、会话、用户、阅读模型、分片等权威事实由 MySQL + JPA Repository 保存；部分查询使用 JPQL/native query、条件更新和 `@EntityGraph`。
- 项目没有 `SqlSessionFactory`、Mapper XML、MyBatis 一级/二级缓存、Executor 插件或分页插件。
- 复杂 SQL、批量写入或明确热点以后可以局部评估 MyBatis，但这是选型方向，不是当前实现。

## 原书 20 题总表

| 题号 | PDF 页 | 原题 | 取舍 | PaperLoom 关联 |
| --- | ---: | --- | --- | --- |
| Q1 | 1-3 | 什么是 MyBatis？ORM、半自动映射、JDBC 缺点 | 必背对比 | JPA 与 MyBatis 的持久化边界 |
| Q2 | 3 | Hibernate 和 MyBatis 有什么区别？ | 必背项目题 | 为什么当前选择 Spring Data JPA |
| Q3 | 5 | MyBatis 使用过程/生命周期？ | 必背对照 | JPA EntityManager/Repository 生命周期类比 |
| Q4 | 9 | Mapper 中如何传递多个参数？ | 选背 | `@Param`、Map、对象；项目不使用 Mapper |
| Q5 | 11 | 实体属性名和表字段名不同怎么办？ | 必背对照 | JPA `@Column`/命名策略 |
| Q6 | 11 | MyBatis 能映射 Enum 吗？ | 选背 | JPA `@Enumerated`/转换器 |
| Q7 | 12 | `#{}` 和 `${}` 区别？ | 必背安全题 | 参数绑定与 SQL 注入；JPA 参数同样不能拼接输入 |
| Q8 | 12 | 模糊查询 LIKE 怎么写？ | 选背 | JPQL/native query 参数绑定 |
| Q9 | 12 | 能做一对一、一对多关联查询吗？ | 必背 | JPA 关联、EntityGraph、N+1 |
| Q10 | 15 | 支持延迟加载吗？原理？ | 必背 | JPA LAZY 与事务边界 |
| Q11 | 15 | 如何获取生成的主键？ | 必背对照 | JPA `@GeneratedValue` 保存后回填 ID |
| Q12 | 16 | 支持动态 SQL 吗？ | 选背 | JPA 派生查询/JPQL/Specification 对照 |
| Q13 | 18 | 如何执行批量操作？ | 必背 | JPA 批处理边界；项目未验证无限批量 |
| Q14 | 21 | 一级、二级缓存？ | 必背边界 | 不把 MyBatis 缓存套到 JPA 或 Redis |
| Q15 | 23 | MyBatis 工作原理？ | 选背对照 | 不认领 SqlSession 链路 |
| Q16 | 30 | MyBatis 功能架构是什么？ | 了解 | 配置、映射、执行、插件分层 |
| Q17 | 30 | 为什么 Mapper 接口不需要实现类？ | 选背 | 动态代理；项目 Repository 由 Spring Data 代理 |
| Q18 | 33 | MyBatis 有哪些 Executor？ | 了解 | SIMPLE/REUSE/BATCH；项目无 Executor 配置 |
| Q19 | 35 | 插件运行原理，如何编写？ | 了解 | 拦截器扩展点；项目未写 MyBatis 插件 |
| Q20 | 38 | 如何分页，分页插件原理？ | 必背对照 | JPA Pageable/游标分页；不能说用了 PageHelper |

## 第一轮必须拿下

Q1-Q3、Q5、Q7、Q9-Q11、Q13-Q14、Q20，以及“为什么项目不用 MyBatis”。重点不是背 XML，而是能说清 SQL 控制、ORM 映射、事务和缓存的取舍。

## Q1-Q3：MyBatis、ORM 和生命周期

### Q1 什么是 MyBatis？

MyBatis 是对 JDBC 的 SQL 映射框架：开发者写 SQL，框架负责连接管理、参数绑定、执行和结果集到 Java 对象的映射。它常被称为半自动 ORM，因为多表关联和查询 SQL 仍由开发者明确编写；Hibernate/JPA 更偏全自动对象关系映射，由实体关系和 ORM 生成更多 SQL。

原书列出的 JDBC 痛点及 MyBatis 解决方式：连接创建释放可交给连接池；SQL 从 Java 代码分离到 XML/注解；参数对象与占位符绑定；结果集自动映射 POJO。代价是 XML/注解 SQL 数量和维护成本增加，并且 SQL 更依赖数据库方言。

PaperLoom 的对应回答：项目需要表达论文、会话、用户、Reading Model、分片等领域实体和状态事务，Spring Data JPA 的 Entity/Repository 已覆盖大量 CRUD 与关系映射；项目没有 MyBatis Mapper XML，不能说自己写过 MyBatis SQL 调优。

### Q2 Hibernate/JPA 与 MyBatis

| 维度 | MyBatis | Hibernate/JPA | PaperLoom |
| --- | --- | --- | --- |
| 抽象 | SQL 映射，SQL 由开发者控制 | 实体关系和持久化上下文 | Spring Data JPA/Hibernate |
| SQL | 灵活、容易针对热点改写 | 由 ORM 生成，也可 JPQL/native query | Repository 派生查询 + `@Query` |
| 关系 | 结果映射清晰，但多表 SQL 自己维护 | 关系、级联、LAZY/EAGER 要管理 | `@EntityGraph` 处理部分关联加载 |
| 成本 | SQL 多，数据库耦合明显 | 隐式 SQL、N+1、脏检查和 flush 需理解 | 事务边界和查询计划需核对 |

正确的项目话术是：当前实体和事务边界多，JPA 减少基础 CRUD 样板；复杂报表、稳定 SQL 热点或批量导入若需要精细控制，再评估局部 MyBatis，而不是全量替换。

### Q3 MyBatis 使用过程和生命周期

原书流程：构建全局复用的 `SqlSessionFactory`；每次请求/事务打开 `SqlSession`；通过 `SqlSession` 或 Mapper 执行 SQL；更新后 commit/rollback；最后 close。`SqlSession` 不是线程安全对象，不能跨请求共享；Mapper 实例也绑定于 Session 生命周期。与 Spring 集成后，框架会代管 Session 和事务。

PaperLoom 的 JPA 类比：Spring 管理 EntityManager 和事务上下文，业务层注入 `JpaRepository`；Repository 代理不是 MyBatis `SqlSession`，不能混用两套缓存和生命周期说法。

## Q4-Q11：参数、映射、关联、主键

### Q4 Mapper 如何传多个参数

MyBatis 常见方式是按顺序使用 `#{param1}`/`#{param2}`，给参数加 `@Param("name")`，或把参数封装成 Java 对象/Map。项目不用 Mapper；JPA Repository 通过方法参数、命名参数 `@Param` 和实体/DTO 表达查询条件。

### Q5 属性名和字段名不同

MyBatis 可以在 SQL 中起别名，或用 `resultMap` 的 `<result column="..." property="...">` 显式映射。JPA 对应使用 `@Column(name = "...")`、嵌入对象或命名策略。项目实体中的数据库列映射属于 JPA 注解边界，不能回答成 XML `resultMap`。

### Q6 Enum 映射

MyBatis 可以用内置或自定义 `TypeHandler` 在枚举和数据库值之间转换。JPA 常用 `@Enumerated(EnumType.STRING)`，需要自定义值时用 `AttributeConverter`。项目中状态字段有明确字符串/数值语义，回答时以实际实体注解为准，不把 MyBatis TypeHandler 说成项目代码。

### Q7 `#{}` 和 `${}`

`#{}` 会使用预编译占位符绑定参数，能避免把普通输入直接拼成 SQL；`${}` 是文本替换，适合经过白名单校验的表名、列名等结构片段，不能直接放用户输入。两者最重要的区别不是“一个快一个慢”，而是参数绑定与文本拼接的安全边界。

项目使用 JPA/JPQL/native query 也必须遵守同一原则：值使用绑定参数，动态排序字段、表名等结构必须白名单化。不能因为没有 `${}` 就说项目天然没有 SQL 注入风险。

### Q8 LIKE 模糊查询

MyBatis 可以在参数中带 `%`，或使用 `concat('%', #{name}, '%')`；关键是仍然用 `#{}` 绑定值。JPA 对应 JPQL `like concat('%', :keyword, '%')` 或由 Repository 方法表达。项目查询若使用 native query，仍要绑定参数并考虑索引失效、大小写和转义。

### Q9 一对一、一对多关联查询

MyBatis 用 `association` 映射一对一、`collection` 映射一对多，可以嵌套查询或一次 JOIN 后用 `resultMap` 组装。JPA 用 `@OneToOne`、`@OneToMany` 等实体关联；查询时要控制 fetch 策略和返回 DTO，避免一次加载整个对象图。

PaperLoom 的 `ConversationRepository` 对部分查询使用 `@EntityGraph(attributePaths = "user")`，显式加载需要的关联，说明项目面对的是同类的 N+1/事务边界问题，但不是 MyBatis `association` 实现。

### Q10 延迟加载原理

MyBatis 可为关联对象创建代理，第一次访问属性时再执行关联 SQL；Session 关闭后访问可能失败。Hibernate/JPA 也会用代理或字节码增强实现 LAZY，访问未初始化关联时需要仍处于有效持久化上下文。

项目回答：关联默认策略、`@EntityGraph` 和事务边界要结合具体 Repository 查询确认；延迟加载不是“免费性能优化”，可能造成 N+1 或懒加载异常，不能笼统说全部关系都 LAZY。

### Q11 获取生成主键

MyBatis 在 insert 上配置 `useGeneratedKeys="true" keyProperty="id"`，执行后把数据库生成键回填到对象。JPA 使用 `@Id` + `@GeneratedValue`，`save`/flush 后实体 ID 通常可用，具体时点受 ID 策略和事务 flush 影响。项目的实体主键由 JPA 管理，不使用 MyBatis `keyProperty`。

## Q12-Q14：动态 SQL、批量和缓存

### Q12 动态 SQL

MyBatis 通过 OGNL 和 `<if>`、`<choose>`、`<where>`、`<set>`、`<foreach>` 等标签按参数生成 SQL。优势是条件组合灵活，风险是分支多、可能生成无条件更新/删除，必须覆盖测试。

JPA 的对照方式是派生查询、JPQL、native query、Criteria/Specification；项目实际代码使用 Repository 方法和 `@Query`，没有 MyBatis XML 动态标签。动态查询同样要确保空条件不会误更新全表。

### Q13 批量操作

MyBatis 可用 `<foreach>` 生成批量语句，或使用 `ExecutorType.BATCH`。要控制单批大小、事务大小、SQL 长度、内存占用和失败重试；批量不是无限增大一个事务。

PaperLoom 通过 JPA Repository/事务完成状态和实体写入，项目没有经过压测验证的“百万条一次提交”方案。若出现大批量分片、阅读模型或 token 记录导入，应按 flush/clear、批次拆分、索引和数据库锁竞争重新设计，不能套 MyBatis Batch Executor 经验。

### Q14 一级缓存和二级缓存

MyBatis 一级缓存默认属于一个 `SqlSession`，同一 Session 重复查询可命中；二级缓存按 Mapper Namespace 跨 Session 共享，更新会使相关缓存失效。缓存配置不当会造成旧数据或跨事务一致性问题。

项目不能这样回答：JPA 的一级缓存是持久化上下文/EntityManager 级别，Hibernate 二级缓存是否启用要看配置，Redis 也不是 MyBatis 二级缓存。PaperLoom 的 Redis 主要保存限流、上传 Bitmap、生成态等短期状态，MySQL/JPA 仍是权威事实；没有证据就不要说启用了 Hibernate 二级缓存或查询缓存。

## Q15-Q20：工作原理、代理、执行器、插件和分页

### Q15 MyBatis 工作原理

原书链路：读取配置和映射文件，构建 `SqlSessionFactory`；创建 `SqlSession`；Mapper 或 statement ID 找到 SQL；`Executor` 组织执行；`StatementHandler` 创建/执行 JDBC Statement；`ParameterHandler` 绑定参数；`ResultSetHandler` 把结果映射成对象；最后提交或回滚并关闭 Session。

这是 MyBatis 对 JDBC 的封装链路，不是 PaperLoom 的运行链路。项目应该说 Spring Data JPA 由 EntityManager、Hibernate Session、Repository 代理和事务拦截器完成持久化，不能把 `StatementHandler`、`ResultSetHandler` 写进项目经历。

### Q16 MyBatis 功能架构

可按四层记忆：接口层（SqlSession/Mapper）、数据处理层（参数映射、SQL 执行、结果映射）、框架支撑层（事务、连接、缓存、配置）、基础支持层（反射、日志、插件、类型处理）。架构题只需说明职责，不必背每个类的源码细节。

### Q17 Mapper 接口为什么不需要实现类

MyBatis 通过 `MapperProxyFactory` 为接口创建 JDK 动态代理；调用方法时以 namespace + method 组成 statement ID，代理把参数交给 `SqlSession` 执行对应 SQL。项目的 Spring Data `JpaRepository` 也常由 Spring Data 生成代理实现接口方法，但它查找的是 Repository 元数据/派生查询/`@Query`，不是 MyBatis XML statement。

### Q18 MyBatis Executor

原书常见实现是：`SimpleExecutor` 每次执行创建新 Statement；`ReuseExecutor` 复用 Statement；`BatchExecutor` 批量执行更新。还可以通过配置指定 Executor 类型。PaperLoom 没有 MyBatis Executor，也没有证据证明 JPA Hibernate 使用了某个同名策略，回答到概念对比即可。

### Q19 插件运行原理

MyBatis 插件通过 `@Intercepts`/`@Signature` 声明拦截 `Executor`、`StatementHandler`、`ParameterHandler` 或 `ResultSetHandler` 方法；框架用动态代理包装目标对象，调用前后执行自定义逻辑，可用于分页、审计、SQL 改写等。插件必须控制范围和性能，不能任意改写所有 SQL。

项目没有 MyBatis 插件。JPA 的 Hibernate `Interceptor`、Spring AOP 和数据库审计是不同扩展点，不能混为同一套插件原理。

### Q20 分页与分页插件

MyBatis 分页插件通常拦截待执行 SQL，按数据库方言追加 `limit/offset`，并可能另发 `count` 查询；深分页会扫描并丢弃大量行，可改用有序索引上的 Keyset/Cursor Pagination。分页要稳定排序，否则翻页期间数据变化会导致重复/漏项。

PaperLoom 目前使用 Spring Data/JPA 的分页或显式查询边界（以具体 Repository 为准），没有 PageHelper、MyBatis Interceptor 或“自动解决深分页”的实现。面试时可以讲同样的 `limit/offset` 成本和游标改进，但不要说项目已经做过 Keyset 压测。

## 面试官问“为什么项目不用 MyBatis”

> PaperLoom 当前主要是论文、会话、用户、阅读模型和分片这些领域实体，状态更新和事务边界比大量手写 SQL 更突出，所以使用 Spring Data JPA/Hibernate：Repository 能减少基础 CRUD，实体关系和 `@EntityGraph` 也能表达部分关联查询。代价是生成 SQL 不如 MyBatis 直观，N+1、flush 时机和复杂查询计划需要主动检查。MyBatis 更适合需要完全控制 SQL、稳定复杂报表或批量热点的局部场景；如果后续出现这种证据，我会先在单个 Repository/模块评估，而不是为了丰富技术栈全量替换。项目没有 MyBatis、MyBatis-Plus、Mapper XML、PageHelper 或 MyBatis 二级缓存实践。

## 项目对照速记

```text
MyBatis：Mapper/XML → Executor → JDBC → ResultMap
项目：   Repository → JPA/Hibernate → EntityManager → MySQL
```

| 追问 | 项目可以说 | 不可以说 |
| --- | --- | --- |
| ORM 选择 | JPA 减少实体 CRUD；复杂 SQL 再局部评估 MyBatis | 项目同时使用 MyBatis-Plus |
| SQL 安全 | 查询值用绑定参数；结构动态值做白名单 | `${}` 可直接放用户输入 |
| 关联加载 | `@EntityGraph`/事务边界，关注 N+1 | 使用了 MyBatis `association` |
| 缓存 | MySQL 是权威；Redis 是短期状态；JPA 一级缓存按上下文理解 | 开启了 MyBatis 二级缓存 |
| 批量 | 控制批次、事务、内存和索引压力 | 一次事务无限批量 |
| 分页 | 关注稳定排序、深分页和游标改进 | 用了 PageHelper 或已验证深分页优化 |

## 对应代码

- `../pom.xml:62`：`spring-boot-starter-data-jpa`，没有 MyBatis 依赖。
- `../src/main/java/io/github/chzarles/paperloom/repository/PaperRepository.java`：JPA `JpaRepository`、`@Query` 和条件更新。
- `../src/main/java/io/github/chzarles/paperloom/repository/ConversationRepository.java:26`：`@EntityGraph(attributePaths = "user")` 关联加载示例。
- `../src/main/java/io/github/chzarles/paperloom/repository/PaperReadingModelRepository.java`、`PaperRetrievalControlRepository.java`：JPA 查询、事务和状态更新。
- `../src/main/resources/application.yml:15-22`：Hibernate/JPA 配置边界。

## 最后背一遍：项目版 MyBatis 对照回答

> MyBatis 是 SQL 映射框架，开发者控制 SQL，框架负责参数绑定、执行和结果映射；`#{}` 是预编译参数，`${}` 是文本拼接，不能把不可信输入放进去。JPA/Hibernate 更偏实体关系和持久化上下文，代价是生成 SQL、N+1 和 flush 时机需要关注。PaperLoom 只使用 Spring Data JPA，Repository 和 `@EntityGraph` 已覆盖当前领域实体与事务需求，没有 Mapper XML、SqlSessionFactory、MyBatis 缓存、Executor 插件或 PageHelper。若后续出现稳定复杂 SQL 或批量热点，我会局部评估 MyBatis，而不是把没有做过的框架经验写成项目实践。
