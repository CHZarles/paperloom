# 第 17 集：充值与计费——商业化闭环

> 系列：派聪明 RAG 后端演进全解
> 集数：17 / 20+
> 主题：token 余额、微信支付 V3、订单流水、回调验签
> 上集回顾：第 16 集多 LLM 路由完成
> 本集目标：用户能充值、能扣 token、商业闭环

---

## 【开场 Hook】

开源项目能活下去，靠什么？

**情怀？** 短期能。**长期不行**。

`pai-smart` 后期加了**完整的充值系统**——**包括微信支付**。这是一个开源项目**走向商业化**的标准动作。

今天我们拆：
- `UserTokenRecord` 余额记账。
- `RechargeOrder` 充值订单。
- `WxPayService` 微信支付 V3 集成。
- 回调验签的坑。

---

## 【一、计费模型：按 token 还是按次？**

**两种主流模型**：

| 维度 | 按 token | 按次 |
|---|---|---|
| 定价 | 1 元 = 100 万 token | 1 元 = 1000 次查询 |
| 透明 | 用户能看到 token 数 | 用户不知道 LLM 用了多少 |
| 公平 | 大文件烧钱多 | 大文件、小问题一样价 |
| LLM 成本 | 跟随市场 | 跟随市场 |
| 用户接受度 | 中（**开发者友好**） | 高（**普通用户友好**） |

**`pai-smart` 选按 token**——**和 LLM 厂商成本对标**。

**理由**：
- LLM API 按 token 计费——**成本透明**。
- 用户上传大文件**消耗更多**——**公平**。
- 技术用户**偏好 token 模型**——**专业感**。

**面试考点 1**：按 token 的最大风险？

A：**用户上传超大文件，token 爆炸**——**一次扣几千 token**。

`pai-smart` **`estimatedEmbeddingTokens` 预估**——**上传前告诉用户**——**知情同意**。

---

## 【二、`UserTokenRecord`：用户余额表】

```java
@Entity
@Table(name = "user_token_records")
public class UserTokenRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "balance_tokens", nullable = false)
    private Long balanceTokens;  // 余额

    @Column(name = "total_consumed", nullable = false)
    private Long totalConsumed;  // 累计消耗

    @Column(name = "total_recharged", nullable = false)
    private Long totalRecharged;  // 累计充值

    @Column(name = "version", nullable = false)
    private Long version;  // 乐观锁

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

**`version` 字段 = 乐观锁**——**防超扣**。

**JPA 乐观锁的标准用法**：

```java
@Version
private Long version;
```

**`@Version` 注解**：JPA 帮我们**自动**加 `WHERE version = ?` 到 UPDATE。

**如果两个线程同时扣余额**：
- 线程 A 读 `balance=100, version=1` → UPDATE `balance=90, version=2 WHERE version=1` → 成功。
- 线程 B 读 `balance=100, version=1` → UPDATE `balance=90, version=2 WHERE version=1` → **影响 0 行** → **抛 `OptimisticLockingFailureException`**。

**业务层捕获异常 → 重试**。

**面试考点 2**：乐观锁 vs 悲观锁？

A：
- **乐观锁**：**冲突少**时性能好（**DB 不加锁**）。
- **悲观锁**：`SELECT FOR UPDATE`——**DB 加锁**——**冲突多时安全**。
- **充值扣费**用**乐观锁**——**冲突少**。

**`pai-smart` 选乐观锁**——**正确**。

---

## 【三、`UserTokenService`：扣费核心】

```java
@Service
public class UserTokenService {

    @Autowired
    private UserTokenRecordRepository userTokenRecordRepository;

    @Transactional
    public void deductTokens(String userId, long tokens) {
        // 1. 查余额
        UserTokenRecord record = userTokenRecordRepository.findByUserId(userId)
            .orElseThrow(() -> new RuntimeException("用户余额记录不存在"));

        // 2. 余额检查
        if (record.getBalanceTokens() < tokens) {
            throw new InsufficientBalanceException("余额不足");
        }

        // 3. 扣减
        record.setBalanceTokens(record.getBalanceTokens() - tokens);
        record.setTotalConsumed(record.getTotalConsumed() + tokens);
        // version 自动 + 1
        userTokenRecordRepository.save(record);

        // 4. 写流水
        TokenTransaction tx = new TokenTransaction();
        tx.setUserId(userId);
        tx.setType(TokenTransaction.Type.CONSUME);
        tx.setAmount(-tokens);
        tx.setBalanceAfter(record.getBalanceTokens());
        // ... 写 DB
    }
}
```

**几个关键点**：
1. **`@Transactional`**：**必须**——**余额检查 + 扣减 + 流水 是原子的**。
2. **乐观锁**：JPA 自动加。
3. **流水记录**：**用户能查交易历史**。

**流水表**（推测）：
```sql
CREATE TABLE token_transactions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64),
    type ENUM('RECHARGE', 'CONSUME', 'REFUND'),
    amount BIGINT,  -- 正负
    balance_after BIGINT,
    related_order_id VARCHAR(64),  -- 关联充值订单
    description VARCHAR(255),
    created_at TIMESTAMP
);
```

**这是金融系统的"复式记账"简化版**。

**面试考点 3**：为什么要写流水？

A：
- **可追溯**：用户**查账**。
- **对账**：**和支付平台对账**。
- **审计**：监管要求。
- **退款**：**知道每笔钱的来去**。

`pai-smart` **有这套**——**专业**。

---

## 【四、`RechargeOrder`：充值订单】

```java
@Entity
@Table(name = "recharge_orders")
public class RechargeOrder {
    @Id
    @Column(name = "order_id", length = 64)
    private String orderId;  // 业务订单号

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "package_id", length = 64)
    private String packageId;  // 充值套餐

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;  // 金额（**分**）

    @Column(name = "token_amount", nullable = false)
    private Long tokenAmount;  // 充 token 数

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;  // PENDING / PAID / FAILED / REFUNDED

    @Column(name = "wxpay_transaction_id", length = 64)
    private String wxpayTransactionId;  // 微信支付订单号

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

**关键设计**：
- **`orderId` 业务主键**——**不用自增**——**微信支付要求订单号唯一**。
- **`amountCents` 用"分"**——**金额永远用最小单位**——**避免浮点**。
- **`tokenAmount` 同时存**——**订单创建时就定下来**——**不受 LLM 价格变动影响**。
- **`status` 状态机**。

**面试考点 4**：为什么金额用"分"不用"元"？

A：
- **避免浮点**：`0.1 + 0.2 != 0.3`（**浮点 bug**）。
- **金融系统统一**：所有支付平台**内部用分**。
- **易比较**：`amountCents > 0` 比 `amountYuan.compareTo(BigDecimal.ZERO) > 0` 简单。

---

## 【五、`RechargePackage`：套餐设计】

```java
@Entity
@Table(name = "recharge_packages")
public class RechargePackage {
    @Id
    private String packageId;  // "BASIC_10" / "PRO_50" / "ENTERPRISE_500"

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;  // 价格

    @Column(name = "token_amount", nullable = false)
    private Long tokenAmount;  // token 数

    @Column(name = "bonus_tokens")
    private Long bonusTokens;  // 赠送 token

    @Column(name = "is_active", nullable = false)
    private boolean isActive;
}
```

**套餐示例**：

| 套餐 | 价格 | token | 赠送 | 单价（每 1 万 token） |
|---|---|---|---|---|
| BASIC_10 | 10 元 | 100 万 | 0 | 0.1 元 |
| PRO_50 | 50 元 | 600 万 | 100 万 | 0.083 元 |
| ENTERPRISE_500 | 500 元 | 7000 万 | 1500 万 | 0.071 元 |

**梯度定价**——**买得越多越便宜**——**鼓励大单**。

**面试考点 5**：为什么有赠送 token？

A：
- **促销**：**新用户首充**——`「充 50 送 10」`。
- **试用**：让用户**多试一些**——**提高 LTV**。
- **对冲涨价**：**LLM 厂商涨了**——**用赠送补偿**。

---

## 【六、`WxPayService`：微信支付 V3 集成】

```java
@Service
public class WxPayService {

    @Autowired
    private WxPayConfig wxPayConfig;

    public String createJsapiOrder(String orderId, long amountCents, String description, String openId) {
        // 1. 构造请求
        Map<String, Object> body = new HashMap<>();
        body.put("appid", wxPayConfig.getAppId());
        body.put("mchid", wxPayConfig.getMchId());
        body.put("description", description);
        body.put("out_trade_no", orderId);
        body.put("notify_url", wxPayConfig.getNotifyUrl());
        body.put("amount", Map.of("total", amountCents, "currency", "CNY"));
        body.put("payer", Map.of("openid", openId));
        
        // 2. 用商户私钥签名
        String signature = sign(body, wxPayConfig.getMerchantPrivateKey());
        body.put("authorization", "WECHATPAY2-SHA256-RSA2048 " + signature);
        
        // 3. 调微信支付 API
        return webClient.post()
            .uri("https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .block();
    }
}
```

**微信支付 V3 协议的几个坑**：

### 6.1 必须用 `application/json`

V3 接口**不用 XML**（**V2 时代是 XML**）——**改 JSON 了**。

### 6.2 签名是 RSA，不是 HMAC

```java
private String sign(Map<String, Object> body, PrivateKey privateKey) {
    String message = method + "\n" + url + "\n" + timestamp + "\n" + nonceStr + "\n" + body + "\n";
    return Base64.getEncoder().encodeToString(
        signWithRSA256(message.getBytes(StandardCharsets.UTF_8), privateKey)
    );
}
```

**签名串 = method + url + timestamp + nonceStr + body**——**5 个字段**。

**`signWithRSA256`**：用商户**私钥**签——**RSA-SHA256**。

### 6.3 证书是 API 证书，不是 V2 那种 cert

```java
@Bean
public PrivateKey merchantPrivateKey() throws IOException {
    String key = new String(Files.readAllBytes(Paths.get("apiclient_key.pem")));
    // 去掉 BEGIN / END 标记
    String privateKey = key.replace("-----BEGIN PRIVATE KEY-----", "")
                           .replace("-----END PRIVATE KEY-----", "")
                           .replaceAll("\\s", "");
    byte[] keyBytes = Base64.getDecoder().decode(privateKey);
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
    return KeyFactory.getInstance("RSA").generatePrivate(spec);
}
```

**`apiclient_key.pem` 从微信支付商户平台下载**。

**面试考点 6**：V2 vs V3 微信支付的区别？

A：
- **V2**：XML + MD5/HMAC 签名，**已不推荐**。
- **V3**：JSON + RSA 签名，**强制**。
- **V3 性能更好**、**API 更清晰**。

`pai-smart` **用 V3**——**正确**。

---

## 【七、回调验签：最容易踩坑的地方】

```java
@PostMapping("/wxpay/notify")
public ResponseEntity<?> handleNotify(@RequestHeader HttpHeaders headers, 
                                      @RequestBody String body) {
    // 1. 验签（用微信平台证书的公钥）
    boolean valid = verifySign(headers, body, wechatPayCert);
    if (!valid) {
        return ResponseEntity.status(401).body("验签失败");
    }
    
    // 2. 解密 resource（AES-256-GCM）
    String decrypted = decryptResource(extractResource(body), wechatPayKey);
    
    // 3. 解析通知
    Map<String, Object> notify = objectMapper.readValue(decrypted, Map.class);
    String orderId = (String) notify.get("out_trade_no");
    String wxTransactionId = (String) notify.get("transaction_id");
    
    // 4. 业务处理
    rechargeService.handlePaidOrder(orderId, wxTransactionId);
    
    // 5. 必须返回 200 + success
    return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
}
```

**回调的几个**「**坑**」**：

### 7.1 验签失败的常见原因

- **证书错了**——**用了旧的平台证书**。
- **时间戳不对**——**服务器时间和微信服务器差 5 分钟以上**。
- **URL 拼错**——`?` 后的 query 要包含在签名串。

### 7.2 重复通知的幂等性

**微信支付会重试多次**——**必须幂等**。

**`rechargeService.handlePaidOrder` 的幂等性**：

```java
@Transactional
public void handlePaidOrder(String orderId, String wxTransactionId) {
    RechargeOrder order = orderRepository.findById(orderId).orElseThrow();
    
    if (order.getStatus() == OrderStatus.PAID) {
        // 已经处理过 → 直接返回
        return;
    }
    
    // 标记支付成功
    order.setStatus(OrderStatus.PAID);
    order.setWxpayTransactionId(wxTransactionId);
    order.setPaidAt(LocalDateTime.now());
    orderRepository.save(order);
    
    // 加 token 余额
    userTokenService.addTokens(order.getUserId(), order.getTokenAmount());
}
```

**`if (PAID) return`** 是幂等的核心。

### 7.3 必须返回的格式

微信支付要求**返回 HTTP 200 + JSON `{"code": "SUCCESS"}`**——**否则会重试**。

**面试考点 7**：微信支付回调要注意什么？

A：
- **验签**——**防伪造通知**。
- **幂等**——**防重复到账**。
- **必须 200**——**防重试风暴**。
- **异步处理**——**业务逻辑别阻塞回调**。

`pai-smart` **有这套**。

---

## 【八、`RechargeController`：前端入口】

```java
@PostMapping("/recharge/create")
public ResponseEntity<?> createOrder(@RequestBody CreateRechargeRequest request,
                                     @RequestAttribute("userId") String userId) {
    // 1. 选套餐
    RechargePackage pkg = packageRepository.findById(request.getPackageId())
        .orElseThrow(() -> new CustomException("套餐不存在"));
    
    // 2. 算 token 数
    long tokenAmount = pkg.getTokenAmount() + Optional.ofNullable(pkg.getBonusTokens()).orElse(0L);
    
    // 3. 创建订单
    String orderId = "RC" + System.currentTimeMillis() + userId;
    RechargeOrder order = new RechargeOrder();
    order.setOrderId(orderId);
    order.setUserId(userId);
    order.setPackageId(pkg.getPackageId());
    order.setAmountCents(pkg.getAmountCents());
    order.setTokenAmount(tokenAmount);
    order.setStatus(OrderStatus.PENDING);
    orderRepository.save(order);
    
    // 4. 调微信支付创建预付单
    String prepayResult = wxPayService.createJsapiOrder(orderId, pkg.getAmountCents(), 
        "派聪明充值-" + pkg.getName(), request.getOpenId());
    
    return ResponseEntity.ok(Map.of(
        "orderId", orderId,
        "prepayData", parseJsapiResponse(prepayResult)
    ));
}
```

**前端拿到 `prepayData`** → 调 `wx.chooseWXPay` → **唤起微信支付**。

**面试考点 8**：JSAPI 支付 vs Native 支付？

A：
- **JSAPI**：**微信内打开网页**——**公众号、小程序**。
- **Native**：**PC 网站扫码**——`生成二维码 → 微信扫 → 支付`。
- **H5**：**手机浏览器**——**单独 H5 支付**。

`pai-smart` **JSAPI**——**H5 / 公众号**。

---

## 【九、回调后加余额：分布式事务的简化**

**问题**：订单状态变更 + 加余额 = **要原子**。

**`pai-smart` 的解法**：**本地事务 + 状态机**。

```java
@Transactional
public void handlePaidOrder(String orderId, String wxTransactionId) {
    // 1. 改订单状态
    RechargeOrder order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() == PAID) return;  // 幂等
    order.setStatus(PAID);
    orderRepository.save(order);
    
    // 2. 加 token
    userTokenService.addTokens(order.getUserId(), order.getTokenAmount());
}
```

**`@Transactional` 保证原子**——**要么都成功，要么都回滚**。

**分布式事务的代价**：**回调慢**——**业务逻辑重**——**可能拖垮回调**。

**`pai-smart` 的简化**：**回调只改订单**——**加余额走异步**？**没看到**。

**生产建议**：
1. **回调快速响应**——**只记录通知**。
2. **异步任务**——**从通知里读订单 + 加余额**。
3. **重试 + 死信**——**Kafka 模式**（第 09 集）。

---

## 【十、常见坑 & 面试问答】

**Q1：用户付款了但没回调怎么办？**

A：
- **主动查单**：`OrderQueryService` 定时扫 PENDING 订单，**调微信支付查单接口**。
- **N 分钟未支付** → 主动 close。
- **N 分钟已支付但没回调** → 主动到账。

**Q2：怎么防止"重复扣款"？**

A：
- **订单号唯一**——**业务订单号 + 微信订单号 双校验**。
- **`findByOrderId`** 查订单。
- **`if (PAID) return`** 幂等。

**Q3：金额单位错了怎么办？**

A：
- **`amountCents` 存分**——**传 100 代表 1 元**。
- **`amountYuan * 100`**。
- **永远不要用 double / float 存金额**。

**Q4：token 余额能"过期"吗？**

A：
- **法律风险**：国内**预付款不能过期**（**工商规定**）。
- **`pai-smart` 当前不过期**——**永久有效**。
- **可以加"长期未使用提醒"**——**激活用户**。

**Q5：怎么对账？**

A：
- **每日定时**：从微信支付下载**对账单**。
- **和 DB 订单对比**：**笔数、金额**。
- **差异处理**：**少收**（补单）/ **多收**（退款）/ **状态错**（手动调）。

---

## 【十一、思考题：充值系统是不是 RAG 的"非核心"？**

**反对**：RAG 核心是**检索 + 生成**——充值是**辅助**。

**支持**：
- **开源项目要活下去**——**充值是营收来源**。
- **token 配额保护成本**——**不充值无限制会亏本**。
- **商业化是开源的"成人礼"**。

**`pai-smart` 选**：「**核心 + 商业化并重**」——**值得学**。

---

## 【十二、下集预告】

充值有了。**但**用户怎么**"被邀请"**到系统里？

- B 邀请 A 注册——**A 注册时填 B 的邀请码**。
- A 注册成功 → **B 获得 token 奖励**。
- **运营活动**：**满 N 人奖励 100 万 token**。

第 18 集我们要：

- `InviteCode` 实体。
- `InviteCodeService` 邀请码生成、绑定、查询。
- **邀请关系链**怎么存。
- **奖励发放**的并发问题。

**邀请码是 SaaS 拉新的"核武器"**。

我们下期见。

---

> 商业化是开源项目的"**生死线**"——`pai-smart` 的实现**专业而完整**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 18 集：邀请码与运营能力——SaaS 拉新核武器](./18-invite-code.md)
