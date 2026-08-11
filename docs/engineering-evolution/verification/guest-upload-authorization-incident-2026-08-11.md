# 游客上传入口权限展示不一致排查报告

日期：2026-08-11  
状态：已修复，待部署验证

## 1. 问题摘要

游客进入文献库后可以看到 `Upload` 按钮、选择 PDF，并看到前端生成上传任务，表现得像是游客可以上传论文。
排查确认后端上传接口仍然拒绝 GUEST，问题属于前端能力展示与后端授权规则不一致，不是后端上传越权。

## 2. 最小复现

使用线上真实游客身份，但不提交文件，避免产生测试论文：

```text
GET  /api/v1/users/me             -> role=GUEST
POST /api/v1/papers/upload/chunk  -> 403
POST /api/v1/papers/upload/merge  -> 403
```

这个反馈回路可以区分两类问题：若上传接口返回非 403，说明后端授权可能失效；实际两个写接口都返回 403，
说明请求在进入上传 Controller 前已被 Spring Security 拦截。

## 3. 假设与验证

| 排名 | 假设 | 可证伪预测 | 结果 |
| --- | --- | --- | --- |
| 1 | 后端上传路由错误地允许 GUEST | GUEST 请求上传接口不会返回 403 | 否定：两个写接口均为 403 |
| 2 | 前端没有按角色隐藏上传能力 | 源码中的上传入口没有 GUEST 判断 | 确认：按钮和弹窗均无角色条件 |
| 3 | 浏览器使用了旧 USER Token | `/users/me` 返回 USER | 否定：线上返回 GUEST |
| 4 | 前端本地任务造成“已上传”错觉 | 网络请求前任务已经进入列表 | 确认：`enqueueUpload` 先加入任务队列，失败后标记为中断 |

## 4. 根因

后端 [SecurityConfig](../../../src/main/java/io/github/chzarles/paperloom/config/SecurityConfig.java) 已要求
`/api/v1/papers/upload/**` 只能由 USER 或 ADMIN 访问。文献库页面允许游客进入，以便浏览可访问的公开论文，
但 [knowledge-base/index.vue](../../../frontend/src/views/knowledge-base/index.vue) 中的上传按钮、上传弹窗和本地
文件管理能力没有应用相同的角色规则。

实际链路为：

```text
GUEST 打开文献库
-> 前端无条件显示 Upload
-> 选择 PDF
-> 前端先创建本地上传任务
-> 请求后端分片上传接口
-> Spring Security 返回 403
-> 本地任务显示为中断
```

## 5. 修复

页面增加单一能力判断：

```text
canUploadPapers = userInfo.role != GUEST
```

- GUEST 不显示上传按钮，也不挂载上传弹窗；
- `canManageFile` 统一拒绝 GUEST，覆盖续传和论文管理入口；
- 保留游客对文献库和公开论文的只读访问；
- 后端 403 规则保持不变，继续作为不可绕过的安全边界。

没有给整个文献库路由增加 USER/ADMIN 限制，因为那会错误移除游客的公开论文浏览能力。

## 6. 验证

先增加权限契约测试并观察其因缺少角色判断而失败，再实施最小修复：

```bash
pnpm --dir frontend exec tsx tests/guest-upload-authorization-contract.test.ts
pnpm --dir frontend typecheck
```

结果：权限契约测试由失败转为通过，TypeScript 类型检查通过。部署后仍需验证游客页面不再显示上传入口，
同时 USER/ADMIN 页面继续显示入口，线上 GUEST 上传 API 继续返回 403。

## 7. 面试表达

> 我发现游客在文献库中能选择并“上传”论文。排查时没有直接认定为后端越权，而是先用真实 GUEST Token
> 对分片和合并接口建立无副作用反馈回路，两个接口都返回 403，证明后端安全边界正常。继续沿前端链路检查，
> 发现上传按钮没有角色判断，而且前端会先创建本地任务再请求服务器，所以 403 发生前界面已经表现得像上传
> 开始了。最终保留游客浏览公开论文的权限，只隐藏 GUEST 的上传、续传和管理入口，同时保留后端 USER/ADMIN
> 授权。这个修复解决的是前后端权限模型不一致，而不是用隐藏按钮代替后端鉴权。

不能声称发生了真实数据越权或上传成功；现有证据只证明游客曾看到并触发不应出现的前端写操作入口。
