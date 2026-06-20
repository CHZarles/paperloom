# 「JWT 全栈通关」视频系列文案

> 系列计划见 `/home/charles/.claude/plans/parsed-bubbling-locket.md`
> 配套代码仓库：`jwt-mini-lab/`（独立项目，开源）

## 系列定位

- **观众：** 0-1 年 Java 后端工程师
- **形式：** 7 集独立分集，每集 8-18 分钟，可点看
- **目标：** 从「cookie 是什么」讲到「Access + Refresh 双 token 体系」，含 XSS / CSRF 现场攻击
- **文案格式：** 完整逐字口播稿（时间标记 + 画面提示 + 口播台词）

## 集列表

| 集号 | 标题 | 时长 | 类型 |
|---|---|---|---|
| EP01 | Cookie 是什么 | 8-10 min | 基础 |
| EP02 | Session 是什么 | 8-10 min | 基础 |
| EP03 | JWT 是什么 | 12-15 min | **主集 1** |
| EP04 | Token 存哪？localStorage vs Cookie | 10-12 min | 铺垫 |
| EP05 | XSS 偷走 localStorage | 10-12 min | **名场面 1** |
| EP06 | CSRF 滥用 HttpOnly Cookie | 10-12 min | **名场面 2** |
| EP07 | Access + Refresh 双 token 体系 | 15-18 min | **主集 2 + 收尾** |

## 进度

- [ ] EP01 — Cookie 是什么
- [ ] EP02 — Session 是什么
- [ ] EP03 — JWT 是什么（主集 1）
- [ ] EP04 — Token 存哪？localStorage vs Cookie
- [ ] EP05 — XSS 偷走 localStorage（名场面 1）
- [ ] EP06 — CSRF 滥用 HttpOnly Cookie（名场面 2）
- [ ] EP07 — Access + Refresh 双 token 体系（主集 2 + 收尾）

## MVP 备份

如果时间紧，至少做 **EP01 + EP03 + EP07**，形成"是什么 → 怎么实现 → 怎么用"闭环。

## 录制顺序与依赖

```
EP1 ─→ EP2 ─→ EP3 ─→ EP4 ─→ EP5 ─→ EP6 ─→ EP7
```

## 文案文件格式

每集一个 markdown，结构：

```markdown
# EP0X — {标题}

**时长：** {min} 分钟
**配套代码：** commit `{commit}`

---

## [00:00] Hook
**画面：** {一句话画面提示}
**口播：** 「{完整台词}」

## [01:00] {章节}
**画面：** {一句话}
**口播：** 「{完整台词}」

...

## [14:30] 下集预告
**口播：** 「{台词}」
```

口播台词完整逐字稿——可以直接念出来。

## 配套代码 commit 约定

- `ep1: cookie basics`
- `ep2: session redis store`
- `ep3: jwt manual`（手撸 HS256，不依赖 jjwt 库）
- `ep4: storage compare`
- `ep5: xss vuln demo`（**故意保留 XSS 漏洞**，仅供本地学习）
- `ep6: csrf vuln demo`
- `ep7: dual token final`

## 参考资料（写文案时引用的 PaiSmart 真实代码）

- `src/main/java/com/yizhaoqi/smartpai/utils/JwtUtils.java`
- `src/main/java/com/yizhaoqi/smartpai/config/JwtAuthenticationFilter.java`
- `src/main/java/com/yizhaoqi/smartpai/config/SecurityConfig.java`
- `src/main/java/com/yizhaoqi/smartpai/config/AppAuthProperties.java`
- `docs/jwt-redis-token-management-sequence-diagrams.md`
- bfs-03 同主题教程（"跟着撸"风格，可对照）：`docs/superpowers/plans/2026-06-01-build-paiai-from-scratch.md` 第 220-238 行

**注意：** 视频里**不直接展示** PaiSmart 真实代码（避免业务代码干扰），文案里的代码示例全部用 `jwt-mini-lab` 项目的极简版本。
