# Folio ChatShell 前端改造计划

## Summary

将现有 Soybean Admin 前端保留为基础框架，但把聊天体验产品化为面向科研论文解读的
独立 ChatShell。`/chat` 不再套传统后台 Layout，不显示顶部后台栏、面包屑、标签页
和大侧边菜单；论文问答、会话历史、引用预览都在 ChatShell 内完成。

同时执行全前端 Folio 品牌覆盖：浏览器标题、全局 logo、登录页、聊天页、加载
页、页脚、主色和关键文案统一为 Folio。非聊天页只做品牌与主题统一，不重做所有
后台管理页面布局。

## Key Changes

- 路由与布局
  - 将 `/chat` 从 `layout.base$view.chat` 调整为脱离后台 chrome 的 ChatShell 入口，
    优先使用 `layout.blank$view.chat` 或等效专用聊天布局。
  - `/chat-history` 从菜单中隐藏，但保留管理员直接访问能力，现阶段不并入主体体验、
    不删除审计功能。
  - 管理员从 ChatShell 左栏底部点击“后台”默认进入 `/user`，再通过原后台菜单访问
    其他管理页。
- ChatShell 结构
  - 左侧窄会话栏：Folio 标识、新对话按钮、会话搜索、单列表会话历史、可用操作
    仅包括切换、归档、取消归档。
  - 中间主区：轻量顶栏、居中欢迎页、消息列表、底部大输入框。
  - 右侧抽屉：默认隐藏；点击来源引用时以分栏方式打开，压缩聊天区，展示“证据详情 +
    文件/PDF 预览”，并保留新窗口打开能力。
  - 移动端：默认只显示聊天区，左侧会话栏使用抽屉覆盖方式打开。
- 逻辑复用
  - 继续复用 `frontend/src/store/modules/chat/index.ts` 中的会话、WebSocket、重连、
    生成状态同步、限流倒计时等逻辑。
  - 不改 Java 后端，不新增接口。会话搜索仅做前端本地过滤已有 session 列表，不实现
    服务端全文搜索。
  - 引用点击从当前 `window.open('/chat?preview=reference')` 改为向 ChatShell 提交预
    览状态，由右侧抽屉渲染现有 FilePreview 能力。
- Folio 品牌与视觉
  - 定位为 evidence-grounded paper analysis / 科研论文解读 RAG 工作台。
  - 使用 Journal Ink 期刊墨色体系：主色 `#26364a`，纸灰底色 `#eeeae1`，论文纸卡片
    `#fbfaf6`，暗酒红 `#7e3f46` 只做 citation/evidence 小面积强调。
  - 全局 logo 使用 woven paper mark，暗示把论文、claim 和 evidence 编织成结构化理
    解；中文界面 + 英文品牌，按钮、菜单、提示语保留中文。
  - 登录页做品牌化轻改：替换 logo、标题、主色、少量文案，保留现有登录/注册流程。
  - 后台管理页保留 Soybean Admin 布局，只统一 logo、标题、主色、页脚、加载页和明显
    的旧品牌文案。

## Interfaces And Compatibility

- 后端 API 不变。
- 前端公开路由保留：
  - `/chat`：新的 ChatShell 主体验。
  - `/chat-history`：隐藏菜单入口，但管理员旧链接仍可访问旧审计页。
  - `/knowledge-base`、`/personal-center`、`/user` 等路由继续存在。
- 前端组件交互调整：
  - ChatMessage 点击引用时不再直接打开新窗口，而是触发父级 ChatShell 打开右侧预览
    抽屉。
  - ReferencePreviewPage 可保留为兼容旧预览链接或新窗口打开场景，不作为主聊天内预
    览路径。
- 存储前缀暂不从 `CiteWeave_` 改为 Folio，避免本地用户登录态被无意义清空。

## Validation Checklist

- 路由与布局
  - 打开 `http://localhost:9527/#/chat`，确认无后台 Header、面包屑、Tab、大侧边菜单。
  - 普通用户登录后能进入聊天页、文献库、个人中心、退出。
  - 管理员登录后左栏显示后台入口，点击进入 `/user`。
  - `/chat-history` 不出现在菜单中；管理员直接访问仍能打开旧审计页。
- 聊天核心链路
  - 新建会话、切换会话、加载历史消息、归档、取消归档正常。
  - WebSocket 自动连接、断线重连、生成中状态、停止生成、限流提示不回归。
  - 空会话显示 Folio 论文问答欢迎页和居中输入框。
  - 输入框 Enter 发送、Shift+Enter 换行保持现有行为。
- 引用与预览
  - 助手回答中点击来源引用，右侧抽屉打开。
  - 抽屉显示引用片段、检索信息、文件/PDF 预览。
  - PDF 页码定位、文本高亮、新窗口打开能力可用。
  - 关闭抽屉后聊天状态和滚动位置不丢失。
- 品牌与主题
  - 浏览器标题、登录页、全局 logo、加载页、页脚、聊天消息头像/名称、输入框
    placeholder 不再出现旧品牌。
  - 明暗主题都可用，浅色为主视觉，深色无明显对比度问题。
  - 主色和选中态统一使用 Folio Journal Ink 期刊墨色体系。

## Assumptions

- 本阶段只做前端改造，不改后端接口、数据库、WebSocket 协议或鉴权逻辑。
- Folio 是论文 RAG 项目前端品牌名，不与 arXiv 强绑定；视觉上强调纸张、证据、阅读流。
- 会话搜索为本地过滤，不承诺搜索历史消息正文。
- `/chat-history` 的管理员审计能力先保留但隐藏入口，后续可单独规划“对话审计”页面。
- 非聊天页不做深度重设计，只做品牌、主题和显性文案统一。
