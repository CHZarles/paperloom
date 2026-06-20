# 第 01 集：派聪明 RAG 系统开篇——从一个 Spring Boot 骨架开始

> 系列：派聪明 RAG 后端演进全解
> 集数：01 / 20+
> 主题：项目起点、为什么是 Spring Boot 3、Maven 结构、最小启动类
> 适合人群：Java 后端、想理解 RAG 系统全貌的工程师

---

## 【开场 Hook】

很多同学问我：「我想自己写一个 RAG 系统，第一步应该干什么？」

我反问他们：「你能不能先把它**跑起来**？哪怕只是返回一个 Hello World？」

这一步，看似无用，**实际上它决定了 90% 的项目能不能活到第二集**。

我们这一季，会用 20 多期的长视频，逐集把 `pai-smart`（派聪明）这个企业级 RAG 知识库系统，**从最初的空 Maven 项目**开始，一期一期加需求、加模块、加设计，**一直演进到它今天的样子**——一个跑着 Kafka、Elasticsearch、MinIO、DeepSeek、WebSocket、微信支付的复杂分布式系统。

我们要做的不是「事后看代码讲解」，而是**站在原作者的肩膀上，看他当年踩了哪些坑、做了哪些妥协、又怎么重构**。

今天第一期，我们就从最最基础的那一步开始：**Spring Boot 项目骨架**。

---

## 【一、为什么要从「空项目」开始？】

很多教程一上来就贴一段 `@RestController`，告诉你「看，这就是 API」。但**真实项目不是这么长起来的**。

真实项目的演化路径大致是这样的：

1. **验证期**：Demo 跑通，先用最简的方式把流程串起来。
2. **单兵期**：1-2 个人开发，所有东西塞一个工程里，无所谓分层。
3. **团队期**：3-5 个人开始有规范，分包、抽象、基础组件。
4. **生产期**：要考虑安全、可观测、可扩展、可运维。

`pai-smart` 这个项目在 GitCode 上是开源的（仓库地址：https://gitcode.com/javabetter/PaiSmart ），名字取自「派（π）」+「Smart」，定位是企业级 AI 知识管理。它**不是一开始就是企业级**的——看 git log，最早的 commit 也只是一个 Spring Boot 初始化模板。

我们今天就回到那个最朴素的版本。

---

## 【二、看一眼最终的 `pom.xml`】

虽然现在项目已经演进了很多代，但是**最原始的依赖**几乎都没变。我把 `pom.xml` 拆开讲：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.2</version>
</parent>

<properties>
    <java.version>17</java.version>
    <lombok.version>1.18.30</lombok.version>
</properties>
```

**两个关键点**：

1. **Spring Boot 3.4.2**：这个版本号不是随便选的。Spring Boot 3.x 是**基于 Jakarta EE 9+** 的，包名从 `javax.*` 全部迁移到了 `jakarta.*`。这是 2022 年底到 2023 年的分水岭。如果你还在用 Spring Boot 2.7.x，很多新库的 API 不兼容。
2. **Java 17**：LTS 版本，`var` 关键字、`sealed class`、`record`、`switch` 模式匹配全部能用。后面我们写 DTO 的时候会大量用到 `record`。

再看核心 starter：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

这 6 个 starter，已经**暗示了后面 20 集我们要解决的问题**：

| Starter | 暗示的子系统 | 对应后续集数 |
|---|---|---|
| data-jpa | 用户/文件/会话的元数据存储 | 第 02 集 |
| data-redis | 限流、缓存、分布式会话 | 第 13 集 |
| security | JWT 鉴权、组织标签授权 | 第 03、11 集 |
| web | 传统的 REST 控制器 | 第 02 集 |
| websocket | 流式聊天推送 | 第 10 集 |
| webflux | DeepSeek 流式响应 | 第 10、16 集 |

**看出规律了吗？** 一个项目的依赖文件，**就是它的架构蓝图**。面试的时候，让你「讲一下你们项目用了什么技术栈」，最简单的方法就是读一遍 `pom.xml` 然后按图索骥。

后面还会加进来：
- `elasticsearch-java` 8.10.0 → 第 08 集
- `minio` 8.5.12 → 第 05 集
- `spring-kafka` 3.2.1 → 第 09 集
- `hanlp` portable-1.8.6 → 第 06 集
- `tika-parsers-standard-package` 2.9.1 → 第 06 集
- `wechatpay-java` 0.2.17 → 第 17 集

每一个依赖都是**一个被解决的痛点**，不是技术炫技。我们后面会一期一期地讲。

---

## 【三、最小启动类：一行注解的事】

最初的启动类**简单到让人想笑**：

```java
package com.yizhaoqi.smartpai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SmartPaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartPaiApplication.class, args);
    }
}
```

就这？**对，就这。**

但是要真的讲明白这里面的道道，我可以讲半小时。

### 3.1 `@SpringBootApplication` 到底是什么？

它是三个注解的组合：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public @interface SpringBootApplication {
    // ...
}
```

**面试考点 1**：`@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`。

**面试考点 2**：默认的 `@ComponentScan` **不会扫描其他包**。所以 `SmartPaiApplication` 必须放在根包 `com.yizhaoqi.smartpai` 下，否则 `controller`、`service` 包里的 bean 全部不会被发现。

这是非常常见的坑，**项目跑起来报 404 找不到 bean，多半是包结构不对**。

### 3.2 `SpringApplication.run` 内部做了什么？

大概 7 步：
1. 推断应用类型（Servlet 还是 Reactive）。
2. 加载并初始化 `ApplicationContextInitializer`。
3. 加载并初始化 `ApplicationListener`。
4. **推断并设置 `main` 方法所在的类**（这决定了 `@SpringBootApplication` 的扫描起点）。
5. **加载 `spring.factories` / `AutoConfiguration.imports` 文件**，做自动装配。
6. 创建 `ApplicationContext`，实例化所有单例 bean。
7. 启动内嵌的 Tomcat / Netty。

**这一步的 5 是关键**。`pai-smart` 后面会引入：
- `RedisConfig`、`SecurityConfig`、`MinioConfig` 这些 `@Configuration` 类，
- 还有 Spring Boot 自动装配的 `RedisAutoConfiguration`、`SecurityAutoConfiguration` 等等。

它们都通过第 5 步被加载。这就是 Spring Boot「**约定大于配置**」的精髓。

---

## 【四、为什么包结构是 `com.yizhaoqi.smartpai`？】

看现在的源码目录：

```
src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java
├── client/        # 外部 API 客户端（DeepSeek、Embedding）
├── config/        # 配置类
├── consumer/      # Kafka 消费者
├── controller/    # REST 控制器
├── entity/        # JPA 实体
├── exception/     # 自定义异常
├── handler/       # WebSocket 处理器
├── model/         # 领域模型
├── repository/    # 数据访问层
├── service/       # 业务逻辑
├── test/          # 测试代码
└── utils/         # 工具类
```

这就是典型的 **Spring Boot 三层架构 + 配置/横切层** 的布局：
- `controller`：入口
- `service`：业务
- `repository`：持久化
- `entity` / `model`：数据模型
- `client` / `config` / `consumer` / `handler` / `exception` / `utils`：横切关注点

**面试考点 3**：为什么 `entity` 和 `model` 拆成两个？

这是项目里**很经典的一个区分**：
- `entity/`：JPA `@Entity`，**直接和数据库表对应**，带 `@Id` 字段、生命周期回调。
- `model/`：纯领域模型（POJO），**不依赖持久化框架**，是 service / controller 之间流转的对象。

但是！说实话，这种区分很多时候**是过度设计的产物**。如果项目不大、团队人少，合并成一个包完全没问题。我们这个项目走到现在，区分了，那么就要遵守——别一会儿把 DTO 塞 `entity`，一会儿塞 `model`。

---

## 【五、运行起来：Maven 命令】

```bash
# 第一次跑
mvn spring-boot:run

# 打包
mvn clean package

# 跑测试
mvn test
```

**Maven Wrapper（mvnw）** 的好处是不用全局装 Maven，新机器 clone 下来直接 `./mvnw spring-boot:run` 就行。但是看项目里没用 mvnw，估计是为了和 IDEA 配合好——这点因团队而异。

---

## 【六、这一期就这些了？**

是的。**第一期故意只讲这么多**。

为什么？

因为很多同学学到后面越来越懵，**根本原因就是跳过了这种「看起来没用」的铺垫**。当你看到 `ChatHandler` 里有 10 个 `@Autowired` 依赖的时候，你不知道它们从哪来；当你看到 `OrgTagAuthorizationFilter` 的时候，你不知道它怎么被加载的。

**一切答案都在 `@SpringBootApplication` 的扫描 + 自动装配 + `application.yml` 的配置绑定。**

---

## 【七、常见坑 & 面试问答】

**Q1：为什么启动类要放在根包下？**
A：因为 `@SpringBootApplication` 默认 `@ComponentScan` 的 basePackage 就是**启动类所在的包**。子包里的 `@Component`、`@Service`、`@Controller`、`@Repository` 才会被注册成 bean。

**Q2：`@SpringBootApplication` 的 exclude 怎么用？**
A：比如项目里有 Redis 但是本地没装，可以 `exclude = {RedisAutoConfiguration.class}`，但更好的做法是用 profile。

**Q3：Spring Boot 2.x 升 3.x 最大的坑是什么？**
A：javax → jakarta。`javax.servlet.*` 全部要改成 `jakarta.servlet.*`。很多老库没升级就会报错。

**Q4：你们的 `pom.xml` 一上来就这么多依赖，会不会启动很慢？**
A：会。`spring-boot-starter-web` + `data-jpa` + `data-redis` + `security` + `webflux` + `websocket` 一起，冷启动大约 5-8 秒。生产环境一般用 `spring-boot-maven-plugin` 打成 fat jar + `SpringApplication` 延迟初始化（`spring.main.lazy-initialization=true`）来缓解。

---

## 【八、下集预告】

第二期，我们要**真正开始写业务代码**了。

我们要做的第一件事是——**加一个 User 实体**。

- `User.java` 怎么设计字段？
- `UserRepository.java` 怎么继承 `JpaRepository`？
- 为什么密码要单独放 `utils/PasswordUtil` 加密？
- 数据库连接配置写在哪？

带着这些问题，**我们下期见**。

---

> 如果你觉得这种「演化式」讲解比直接贴代码更好理解，点赞、投币、一键三连。
> 评论区告诉我你最想看哪一集的深度展开，我优先讲。

下一集链接：[第 02 集：第一个 JPA 实体——User 与 Repository](./02-first-jpa-entity.md)
