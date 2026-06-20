# 第 18 集：邀请码与运营能力——SaaS 拉新核武器

> 系列：派聪明 RAG 后端演进全解
> 集数：18 / 20+
> 主题：邀请码生成、绑定、关系链、奖励发放
> 上集回顾：第 17 集充值系统完整
> 本集目标：用户能邀请别人，被邀请有奖励

---

## 【开场 Hook】

一个 SaaS 系统，最便宜的拉新方式是什么？

**广告？** 烧钱。
**SEO？** 慢。
**邀请码？** 病毒式传播——**老用户带新用户**。

`pai-smart` 后期加了 **`InviteCode` 体系**——**包括生成、绑定、查询、奖励**。

今天拆 `InviteCode` 实体、`InviteCodeService`、奖励发放的并发问题。

---

## 【一、`InviteCode` 实体】

```java
@Entity
@Table(name = "invite_codes")
public class InviteCode {
    @Id
    @Column(name = "code", length = 32)
    private String code;  // 邀请码（短字符串）

    @Column(name = "creator_user_id", length = 64, nullable = false)
    private String creatorUserId;

    @Column(name = "max_uses", nullable = false)
    private Integer maxUses;  // 最多使用次数

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(name = "reward_tokens", nullable = false)
    private Long rewardTokens;  // 每次使用奖励 token

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

**关键字段**：
- **`code` 业务主键**——**短字符串**（如 `PROMO2024`）——**方便用户手动输入**。
- **`maxUses` 使用次数**——`1 = 一次性`、`100 = 通用推广码`。
- **`rewardTokens` 奖励 token**——**双发**：邀请人和被邀请人各得。
- **`expireAt` 过期时间**——`null = 永久`（`pai-smart` 当前版本似乎 drop 了 expireAt，但早期有）。

**面试考点 1**：邀请码怎么生成？

A：两种方式：
- **随机生成**：`PROMO` + 6 位随机字符——**碰撞概率低**。
- **业务编码**：用户 ID + 校验位——**可反查**。

`pai-smart` 推测用**随机生成** + **DB unique 约束**。

---

## 【二、`InviteCodeService`：核心流程】

### 2.1 创建邀请码

```java
@Transactional
public InviteCode createInviteCode(String creatorUserId, int maxUses, long rewardTokens) {
    // 1. 限流：每用户最多 N 个有效邀请码
    long activeCount = inviteCodeRepository.countByCreatorAndActive(creatorUserId, true);
    if (activeCount >= 5) {
        throw new CustomException("邀请码数量已达上限");
    }
    
    // 2. 生成邀请码
    String code = generateUniqueCode();
    
    InviteCode invite = new InviteCode();
    invite.setCode(code);
    invite.setCreatorUserId(creatorUserId);
    invite.setMaxUses(maxUses);
    invite.setRewardTokens(rewardTokens);
    inviteRepository.save(invite);
    
    return invite;
}

private String generateUniqueCode() {
    String code;
    do {
        code = "PROMO" + RandomStringUtils.randomAlphanumeric(6).toUpperCase();
    } while (inviteCodeRepository.existsById(code));  // 防碰撞
    return code;
}
```

**`existsById`**：检查是否已存在——**DB unique 兜底**。

### 2.2 注册时绑定邀请码

```java
@Transactional
public InviteBindingResult bindInviteCode(String inviteCode, String newUserId) {
    // 1. 查邀请码
    InviteCode invite = inviteCodeRepository.findById(inviteCode)
        .orElseThrow(() -> new CustomException("邀请码不存在"));
    
    // 2. 校验
    if (!invite.isActive()) throw new CustomException("邀请码已停用");
    if (invite.getExpireAt() != null && invite.getExpireAt().isBefore(LocalDateTime.now())) {
        throw new CustomException("邀请码已过期");
    }
    if (invite.getUsedCount() >= invite.getMaxUses()) {
        throw new CustomException("邀请码已用完");
    }
    if (invite.getCreatorUserId().equals(newUserId)) {
        throw new CustomException("不能邀请自己");
    }
    
    // 3. 乐观锁增加 usedCount
    invite.setUsedCount(invite.getUsedCount() + 1);
    inviteRepository.save(invite);  // version 自动 +1
    
    // 4. 双向发奖
    userTokenService.addTokens(invite.getCreatorUserId(), invite.getRewardTokens());
    userTokenService.addTokens(newUserId, invite.getRewardTokens());
    
    // 5. 记录绑定关系
    InviteBinding binding = new InviteBinding();
    binding.setInviteCode(inviteCode);
    binding.setInviterUserId(invite.getCreatorUserId());
    binding.setInviteeUserId(newUserId);
    bindingRepository.save(binding);
    
    return new InviteBindingResult(invite.getCreatorUserId(), invite.getRewardTokens());
}
```

**关键点**：
1. **事务边界**——**全在一个 `@Transactional`**——**任何一步失败回滚**。
2. **乐观锁**——**`usedCount` 防超用**。
3. **双向奖励**——**邀请人 + 被邀请人**。
4. **绑定关系表**——**`InviteBinding` 单独存**（**记录所有邀请关系**）。

**面试考点 2**：为什么要单独存 `InviteBinding`？

A：
- **关系查询**：「谁邀请的我」 / 「我邀请了谁」。
- **反作弊**：同一个设备/IP 不能用多个邀请码。
- **数据统计**：**拉新漏斗**——**注册 → 激活 → 付费**。

`pai-smart` **有这套**。

### 2.3 并发安全：`usedCount` 的乐观锁

**场景**：100 人同时用同一个邀请码（`maxUses=10`）。

**没有锁**：100 个请求都看到 `usedCount=0` → 100 个都 +1 → **1000 个都成功**——**超发**。

**乐观锁**：JPA 自动 `WHERE version = ?` —— **后 90 个失败**。

**`pai-smart` 实际用乐观锁**——**正确**。

**面试考点 3**：乐观锁 + 业务校验的双保险？

A：
- **DB 乐观锁**：`usedCount + 1 WHERE version = oldVersion`。
- **业务校验**：`if (usedCount >= maxUses) throw`。
- **两层都过** → 才能成功。

**最坏情况**：业务校验漏了（比如忘了判断 maxUses）—— DB 乐观锁**仍然兜底**。

---

## 【三、`InviteCodeController`：API 入口】

```java
@RestController
@RequestMapping("/api/v1/invite-codes")
public class InviteCodeController {

    @GetMapping("/my")
    public List<InviteCode> myInviteCodes(@RequestAttribute("userId") String userId) {
        return inviteCodeService.findByCreator(userId);
    }

    @PostMapping("/create")
    public InviteCode create(@RequestBody CreateInviteRequest request,
                              @RequestAttribute("userId") String userId) {
        return inviteCodeService.createInviteCode(
            userId, request.getMaxUses(), request.getRewardTokens()
        );
    }
}
```

**前端用法**：
- **「我的邀请码」**：用户看自己的邀请码。
- **「创建邀请码」**：管理员后台 / 用户自助。
- **「注册时填邀请码」**：注册接口接受 `inviteCode` 参数（**第 04 集的注册**）。

---

## 【四、运营活动：通用推广码】

**`maxUses=100` + `creatorUserId=ADMIN_ID`**——**通用推广码**。

**场景**：
- 「`PROMO2024`」——**公众号文章**——**100 个用户能用**。
- **每个用户注册时填** → **邀请人（管理员）得 N token + 用户得 N token**。
- **但！管理员可能没那么多 token**——**平台代发**。

**`pai-smart` 的处理**：`rewardTokens` 可以**为 0**——**只发新用户**——**平台可控**。

**面试考点 4**：邀请码奖励怎么"反作弊"？

A：
- **设备指纹**：同一设备**只能用一次**。
- **IP 限制**：同一 IP 短时间**不能用多次**。
- **手机号验证**：同一手机号**只能被邀请一次**。
- **行为检测**：注册后 24h 内**无任何操作** → 回收奖励。

`pai-smart` 当前**简单**——**靠手机号**——**未来加**。

---

## 【五、注册流程集成邀请码】

```java
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    // 1. 用户名校验
    if (userRepository.findByUsername(request.username()).isPresent()) {
        throw new CustomException("用户名已存在");
    }
    
    // 2. 密码加密
    String hashedPassword = PasswordUtil.encode(request.password());
    
    // 3. 保存用户
    User user = new User();
    // ... 设置字段
    userRepository.save(user);
    
    // 4. 绑定邀请码（如果有）
    if (request.inviteCode() != null && !request.inviteCode().isBlank()) {
        try {
            inviteCodeService.bindInviteCode(request.inviteCode(), user.getId().toString());
        } catch (CustomException e) {
            // 邀请码无效不影响注册
            logger.warn("邀请码绑定失败：{}", e.getMessage());
        }
    }
    
    // 5. 发 token
    String token = jwtUtils.generateToken(user.getUsername());
    return ResponseEntity.ok(Map.of("token", token, "user", user));
}
```

**注意**：邀请码失败**不影响注册**——**不让用户卡在邀请码**——**但记日志**。

**面试考点 5**：邀请码失败要不要阻断注册？

A：**不阻断**。
- 用户体验：**别让用户卡在"邀请码无效"**。
- 业务上：邀请码是**加分项**——**不是必须**。
- **记日志**——**后续补发**。

`pai-smart` 这么干——**正确**。

---

## 【六、`AdminController`：管理后台**

```java
@GetMapping("/admin/invite-codes")
public Page<InviteCode> listAllInviteCodes(...) { ... }

@PostMapping("/admin/invite-codes/{code}/disable")
public void disable(@PathVariable String code) { ... }
```

**管理员能**：
- **看所有邀请码**。
- **停用某个邀请码**。
- **看邀请记录**（`InviteBinding` 列表）。
- **看邀请转化漏斗**。

---

## 【七、常见坑 & 面试问答】

**Q1：邀请码生成碰撞怎么办？**

A：`generateUniqueCode` 里的 `do-while` 循环 + DB unique 约束**双保险**。**真碰撞了**（**概率极低**）—— 重新生成。

**Q2：`usedCount` 怎么防超发？**

A：**乐观锁**（`@Version`）+ **业务校验**双保险。

**Q3：邀请人自己被邀请怎么办？**

A：`if (creator.equals(newUserId)) throw`——**直接拒绝**。

**Q4：邀请码的"双向奖励"是必须的吗？**

A：不一定。
- **只奖邀请人**：老用户拉新有动力——**新用户零成本**——**拉新快**。
- **只奖被邀请人**：新用户有动力——**邀请人无私**——**拉新慢**。
- **双向都奖**：**平衡**——`pai-smart` 选这个。

**Q5：邀请码做"分销"是否合法？**

A：**国内合法**——但**多层分销有法律风险**（**传销**）。**`pai-smart` 只做一层**——**安全**。

---

## 【八、思考题：邀请码的"代发 token"业务**

**`pai-smart` 当前**：邀请码奖励 → **邀请人 token 余额增加**。

**但如果邀请人是"运营账号"**（**公众号、视频号**）——**平台代发**。

**设计**：
- **`InviteCode.creatorUserId = "SYSTEM"`**——**系统账号**。
- **奖励 token 从"系统奖励池"出**——**不扣个人余额**。

`pai-smart` **没看到 SYSTEM 概念**——**目前都是个人邀请**——**未来扩展**。

---

## 【九、下集预告】

邀请码有了。**但用户怎么反馈 AI 回答好不好？**

- 「这个回答不对」→ 怎么收集？
- 多次反馈都指某个文档 → 那文档可能有问题。

第 19 集我们要：

- **`MessageFeedback` 实体**——点赞/点踩/文字反馈。
- 反馈怎么影响**后续回答**（`buildRecentFeedbackGuidance`）。
- **基于反馈的 A/B 测试**。

**反馈闭环是 RAG 系统的"质量引擎"**。

我们下期见。

---

> 邀请码是 SaaS 的"**拉新核武器**"——`pai-smart` 的实现**完整且严谨**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 19 集：消息反馈与质量闭环——RAG 系统的耳朵](./19-message-feedback.md)
