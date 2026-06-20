# 第 02 集：第一个 JPA 实体——User 与 Repository

> 系列：派聪明 RAG 后端演进全解
> 集数：02 / 20+
> 主题：Spring Data JPA、Hibernate 自动建表、@Entity 设计哲学、User 模型演进
> 上集回顾：第 01 集我们搭好了 Spring Boot 3.4.2 骨架，`pom.xml` 里有 `spring-boot-starter-data-jpa` 这个依赖
> 本集目标：让 `User` 这个实体落库，能通过 `UserRepository` 查出来

---

## 【开场 Hook】

上集我们讲了，`pom.xml` 里 `data-jpa` 这个依赖**暗示了接下来的子系统**。今天我们就要把它点亮。

很多同学学 JPA，**上来就被一堆注解劝退**：`@Entity`、`@Table`、`@Id`、`@GeneratedValue`、`@Column`、`@Enumerated`、`@CreationTimestamp`、`@UpdateTimestamp`……

我告诉你，**在 `pai-smart` 这个项目里，User 实体把这堆注解全用上了**。它是后面所有实体（`FileUpload`、`Conversation`、`OrganizationTag`……）的模板。看懂这一个，后面就是复制粘贴。

我们今天就**逐行拆解**：

```java
@Data
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "org_tags")
    private String orgTags; // 用户所属组织标签，多个用逗号分隔

    @Column(name = "primary_org")
    private String primaryOrg; // 用户主组织标签

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Role {
        USER, ADMIN
    }
}
```

总共不到 50 行。**别小看它，背后的设计决策一个比一个有讲究。**

---

## 【一、`@Data` 是 Lombok，不是 Spring】

第一个坑：很多同学看到 `@Data` 就以为是 Spring 的注解。**不是**。它是 Lombok 提供的。

Lombok 通过 **annotation processor** 在编译期生成代码：
- `@Data` ≈ `@Getter` + `@Setter` + `@ToString` + `@EqualsAndHashCode` + `@RequiredArgsConstructor`

也就是说，**编译后的字节码里**，`User` 类会有 `getId()`、`setId()`、`getUsername()` 等方法——但你的源码里**一行都没写**。

**面试考点 1**：Lombok 的原理是什么？

答案：Java 的 `JSR-269` Pluggable Annotation Processing API。Lombok 注册一个 annotation processor，**在 `.java` 编译成 `.class` 之前**修改抽象语法树（AST），加上 getter/setter。**所以 Lombok 不会出现在运行时**——这点对性能敏感的场景（比如 Spring Native Image）是个坑。

`pom.xml` 里要加：

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

并且在 `maven-compiler-plugin` 的 `annotationProcessorPaths` 里配置——`pai-smart` 已经配置好了，版本 `1.18.30`。

---

## 【二、`@Entity` 和 `@Table` 拆开看】

```java
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User { ... }
```

**为什么要拆成两个？**

- `@Entity`：告诉 JPA「这是一个持久化实体」，JPA 会为它**生成代理类**（用于懒加载），并且把它纳入**持久化上下文**（Persistence Context）。
- `@Table`：告诉 Hibernate **数据库表**长什么样。表名、唯一约束、索引，全在这里。

**如果只写 `@Entity`，不写 `@Table`，行不行？**

行。JPA 会默认用**类名当表名**。也就是说，你的表就叫 `User`（注意：大小写由数据库决定，MySQL 默认是 `user`）。但是 `user` 在 MySQL 里是**保留字**——`SELECT * FROM user WHERE ...` 会报错。

所以作者**老老实实写了 `@Table(name = "users")`**，避开这个雷。

**`uniqueConstraints` 干啥的？**

声明 `username` 列有唯一约束。JPA 在生成 DDL（`ddl-auto=update`）时会自动加 `UNIQUE` 索引。生产环境一般不用 Hibernate 自动建表，但开发期非常方便。

**面试考点 2**：`ddl-auto` 的几个值？

- `none`：什么都不做。
- `validate`：只校验实体和表结构是否一致，不一致启动报错。
- `update`：缺啥补啥（**会加列，但不会删列、不会改类型**）。
- `create`：启动时删表重建。
- `create-drop`：关闭应用时删表。

**生产环境必须用 `validate` 或 `none`**，否则 Hibernate 一个 update 把你线上数据改了都不知道。

---

## 【三、`@Id` 和 `@GeneratedValue` 的策略问题】

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

- `@Id`：主键。
- `@GeneratedValue(strategy = GenerationType.IDENTITY)`：用**数据库自增 ID**。

有 4 种策略：

| 策略 | 含义 | 优点 | 缺点 |
|---|---|---|---|
| `AUTO` | JPA 自己挑 | 省事 | 不可控 |
| `IDENTITY` | 数据库自增（`AUTO_INCREMENT`） | 简单、性能好 | 批量插入时不预编译，**无法用 JDBC batch** |
| `SEQUENCE` | 数据库序列（Oracle/PostgreSQL） | 高性能、预编译 | MySQL 不支持 |
| `TABLE` | 用一张表模拟序列 | 跨数据库 | 慢 |

`pai-smart` 选 `IDENTITY` 是合理的——MySQL 单库场景，简单粗暴。

**但是！**看 `DocumentVector` 实体（向量表）：

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long vectorId;
```

**大量插入向量数据时，`IDENTITY` 会成为性能瓶颈**。这是后面讲 Elasticsearch 那集会聊到的优化点。

**面试考点 3**：为什么 MySQL 用 `IDENTITY` 不适合大批量插入？

答：`IDENTITY` 必须**立刻拿到数据库返回的自增 ID** 才能继续，每次插入都要等数据库反馈，**无法攒一批再发送**。`SEQUENCE` 是「先问数据库要 100 个 ID 自己用」，所以可以攒批。`pai-smart` 这里为了简单牺牲了性能，业务量大了以后要优化。

---

## 【四、`@Column` 注解里的「坑」】

```java
@Column(nullable = false, unique = true)
private String username;

@Column(nullable = false)
private String password;
```

- `nullable = false`：**Hibernate 生成的 DDL 会加 `NOT NULL` 约束**。
- `unique = true`：**DDL 会加 `UNIQUE` 约束**。

注意：**这两个约束只对自动建表有效**！如果你是用 `Flyway` / `Liquibase` 管理的 DDL，要自己在 SQL 里写。

**为什么 `password` 不加 `unique`？** 因为理论上密码可以重复（虽然不应该）。

**为什么 `orgTags` 和 `primaryOrg` 字段名和列名不一致？**

```java
@Column(name = "org_tags")
private String orgTags;

@Column(name = "primary_org")
private String primaryOrg;
```

这是 Java 命名（驼峰）和 SQL 命名（下划线）的**约定问题**。Hibernate 5+ 默认有 `PhysicalNamingStrategyStandardImpl` 会自动转换，但 `pai-smart` 显式指定了 `name`——**稳健做法**。

**面试考点 4**：`orgTags` 为什么是 `String`，不是 `List<String>`？

答：**作者偷懒了**。`orgTags` 字段存的是「多个组织标签用逗号分隔」。这种设计叫 **「弱设计」**：

- 优点：简单、好查（`LIKE '%xxx%'`）。
- 缺点：更新一个标签要 `REPLACE`，关联查询要 `LIKE`，性能差；字段长度不可控（万一标签很多就超长了）。

更好的设计应该是**多对多关联表** `user_org_tag(user_id, org_tag)`。但是看项目里后来用 `OrgTagCacheService` 在内存里维护映射关系，**用空间换时间**——这个设计是后话，第 11 集讲。

---

## 【五、`@Enumerated(EnumType.STRING)`——字符串存枚举】

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false)
private Role role;
```

**为什么是 `STRING` 不是 `ORDINAL`？**

- `ORDINAL`：存枚举的**下标**（0、1、2……）。`USER = 0`，`ADMIN = 1`。
- `STRING`：存枚举的**名字**（"USER"、"ADMIN"）。

如果用 `ORDINAL`：
```java
public enum Role {
    USER, ADMIN, GUEST  // 新加 GUEST 在中间，USER=0, GUEST=1, ADMIN=2
}
```

**`ADMIN` 从 1 变成 2，老数据全错！**

`pai-smart` 用 `STRING` 是正确选择。

**面试考点 5**：枚举的最佳实践？

- 一定要用 `STRING`。
- 一定要 `nullable = false`（如果有默认值）。
- 业务上「禁用」枚举值不要直接删，加一个 `DELETED` 或 `ARCHIVED` 状态。**软删除**比硬删除安全。

---

## 【六、`@CreationTimestamp` 和 `@UpdateTimestamp`】

```java
@CreationTimestamp
private LocalDateTime createdAt;

@UpdateTimestamp
private LocalDateTime updatedAt;
```

这是 Hibernate 的注解（不是 Spring Data 的）。它的作用：

- 实体**第一次保存**时，自动把 `createdAt` 设为当前时间。
- 每次实体**被更新**时，自动把 `updatedAt` 设为当前时间。

**注意**：它依赖 Hibernate 的 dirty checking。**直接 `entity.setXxx()`，事务提交时 Hibernate 会自动 UPDATE**——`updatedAt` 也会自动变。

**面试考点 6**：为什么用 Hibernate 注解，不用 MySQL 的 `DEFAULT CURRENT_TIMESTAMP`？

答：跨数据库可移植性。MySQL 有，PostgreSQL 有，Oracle 用法不一样。**写在 Java 侧，应用启动时在哪都能跑**。同时应用侧维护时间戳便于在业务代码里直接读取。

---

## 【七、`UserRepository`——为什么是接口不是类？】

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
```

**这是 Spring Data JPA 最「魔法」的地方。**

你写的是一个**接口**。Spring Data JPA 在启动时**自动生成实现类**（代理），叫 `SimpleJpaRepository`。

代理生成的逻辑：
- `JpaRepository<User, Long>` 自带 `findAll()`、`findById()`、`save()`、`deleteById()` 等等。
- `findByUsername(String username)` 没有现成实现，**Spring Data 看方法名解析**：前缀 `findBy` + 属性名 `username` + 参数 `String username`，自动生成 `SELECT * FROM users WHERE username = ?` 的 JPQL。

**这就是 Spring Data 的「方法名查询」**。

**面试考点 7**：方法名查询的解析规则？

- `findBy`、`readBy`、`queryBy`、`getBy`：SELECT。
- `countBy`：COUNT。
- `deleteBy`、`removeBy`：DELETE。
- `existsBy`：EXISTS。
- 关键字：`And`、`Or`、`Between`、`LessThan`、`GreaterThan`、`Like`、`In`、`OrderBy`、`Top`、`Distinct`……

举例：

```java
List<User> findTop10ByCreatedAtAfterOrderByIdDesc(LocalDateTime time);
List<User> findByOrgTagsContainingAndRole(String orgTag, Role role);
long countByRole(Role role);
```

**不用写实现**。

**但是！**注意这个细节：

```java
Optional<User> findByUsername(String username);
```

返回 `Optional<User>` 而不是 `User`。这是 **Java 8 之后的最佳实践**——`Optional` 强制调用方处理「找不到」的情况。

**面试考点 8**：`Optional` 有什么坑？

- 不能作为字段类型（序列化、性能）。
- 不能作为方法参数（应该用 `@Nullable`）。
- `orElse(null)` 和 `orElseGet(() -> null)` 在「值不存在」时**没区别**，但 `orElseGet` 里的 lambda 只在值不存在时才执行——性能场景要注意。

---

## 【八、什么时候加 `@Transactional`？】

`UserRepository` 的方法**没有 `@Transactional`**。这是 Spring Data 的设计——**只读方法自动加只读事务，写方法自动加读写事务**。

`@Transactional` 的位置原则：
- **Service 层**加，**Repository 层**不加（Spring Data 帮你加）。
- **类级别** vs **方法级别**：类级别粒度粗，方法级别粒度细。
- **`@Transactional` 失效的常见坑**：
  - **同类的内部调用**（`this.foo()`，绕过 Spring 代理）。
  - **异常被 catch 掉**了，没抛出（Spring 默认只对 `RuntimeException` 回滚）。
  - **方法不是 public**。

第 17 集讲充值系统的时候，**我们会反复用 `@Transactional`**，会重点讲。

---

## 【九、`AuthController`——`record` 登场】

```java
@PostMapping("/refreshToken")
public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) { ... }

record RefreshTokenRequest(String refreshToken) {}
```

`RefreshTokenRequest` 用 Java 14+ 的 `record` 语法。**一行就把 DTO 写完了**。

`record` 的本质：
- 编译器自动生成 `getter`（注意是 `refreshToken()` 不是 `getRefreshToken()`）、`equals`、`hashCode`、`toString`。
- **字段默认是 `final`**——不可变对象。
- 适合做 DTO、值对象。

**面试考点 9**：什么场景用 `record`，什么场景用 `class`？

- `record`：DTO、值对象、Query、Command。**没有继承、没有可变状态**。
- `class`：实体（要支持 Hibernate 懒加载的代理）、Service、Controller（要加 Spring 注解的）。

---

## 【十、思考题：为什么 `AuthController` 单独放一个包？】

看包名是 `controller/AuthController.java`，但它**只做 token 刷新**。登录、注册在 `UserController`。

**这是项目演化的痕迹**——可能一开始登录/注册都在 `AuthController`，后来抽到 `UserController`，但 token 刷新这个相对独立的逻辑**没搬走**。

**没有银弹的架构**：项目早期怎么顺手怎么来，**等业务复杂了再做合理的边界划分**。这是 KISS + YAGNI 原则的体现。

---

## 【十一、常见坑 & 面试问答】

**Q1：`@Data` 和 `@Getter @Setter` 怎么选？**

A：实体类用 `@Data` 方便；DTO 不可变用 `record`；如果你想做 `equals` 排除某个字段（懒加载的关联），**用 `@Getter @Setter @ToString(exclude = "xxx")` 显式控制**。

**Q2：JPA 的 N+1 查询问题遇到过吗？**

A：`@OneToMany`、`@ManyToOne` 默认懒加载。查询 1 个用户触发 1 次 SQL，访问他的订单再触发 N 次。**用 `JOIN FETCH` 一次性查出来**：

```java
@Query("SELECT u FROM User u JOIN FETCH u.orders WHERE u.id = :id")
User findWithOrders(@Param("id") Long id);
```

`pai-smart` 里 `FileUpload` 和 `DocumentVector` 的关系，**第 05、08 集会详细讲**。

**Q3：MySQL 的 `users` 表名是复数还是单数？**

A：看团队规范。`pai-smart` 用复数（`users`、`organizations`、`files`）。我推荐**复数**——表是「集合」的概念，描述一类实体。

**Q4：为什么 `User` 字段都用包装类型 `Long` 不是基本类型 `long`？**

A：因为包装类型可以为 `null`。新建一个 `User` 对象没设 ID 时，`long` 默认是 0，**会污染业务**（用户 ID = 0 不合法）；`Long` 默认是 `null`，更安全。

---

## 【十二、练习题（建议动手做）**

1. 给 `User` 加一个 `email` 字段，类型 `String`，要求非空、唯一。
2. 在 `UserRepository` 加一个方法 `findByEmail(String email)`，返回 `Optional<User>`。
3. 写一个测试 `UserRepositoryTest`，插入 3 个用户，验证 `findByUsername` 找得到。

---

## 【十三、下集预告】

第 02 集我们有了 `User` 实体，能存能查了。

**但是！现在任何人都能调任何接口，没有任何鉴权。**

第 03 集，我们要：

- 引入 `JwtUtils`，手写 JWT 的签发和校验。
- 写 `JwtAuthenticationFilter`，拦截所有 HTTP 请求。
- 密码用 `PasswordUtil` 加密存储。

JWT 是现在 80% 的 Java 后端面试必考点。**我们下期深入。**

---

> 评论区有同学问：「为什么不用 MyBatis-Plus？」——好问题，我们第 14 集对比 JPA 和 MyBatis 时会专门讲。
> 想看哪一集的深度展开？留言告诉我。

下一集链接：[第 03 集：JWT 鉴权登场——从「裸奔」到「有身份的请求」](./03-jwt-authentication.md)
