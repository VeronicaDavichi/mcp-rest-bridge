# MCP REST Bridge 对外 MCP Server 接口设计（v0.1）

| 项 | 内容 |
| --- | --- |
| 文档版本 | v0.1（接口级设计，待评审；**同日多次修订，见变更记录**） |
| 编写日期 | 2026-10-14 |
| 协议基线 | **全兼容 `2024-11-05` ~ `2026-07-28`（共 5 个修订版）** —— M1 已定案为"方案③"，服务端同时提供 **HTTP+SSE 双端点**、**旧版 Streamable HTTP**、**现代无状态 Streamable HTTP** 三种形态（见第 3 章、10.3 矩阵） |
| 配套文档 | [前后端交互接口设计.md](./前后端交互接口设计.md) —— 管理面（控制台自己的 REST），本文是**产品面**（对外暴露的 MCP Server） |
| 本轮范围 | 只做**接口/方法级设计**：三种形态的端点与行为、版本协商、鉴权与可见范围、方法清单、控制台配置 → MCP 定义的映射、调用链与错误模型 |
| 本轮不做 | 具体 JSON Schema 字段设计、OAuth 授权服务器实现、`stdio` 传输、非 REST 上游（GraphQL/SOAP/WebSocket/gRPC） |

## 变更记录

| 版本 | 日期 | 变更人 | 说明 |
| --- | --- | --- | --- |
| v0.1 | 2026-10-14 | 鱼鱼 | 首次整理。已按 `2026-07-28` 规范原文核对传输行为、请求头、版本协商与错误码；据此提出「二进制内容必须走 Resource 通道」这一影响上架类型的设计结论 |
| v0.1 修订1 | 2026-10-14 | 鱼鱼 | 补读 `2026-07-28` changelog 与 `2024-11-05` 传输原文后**自查修正 5 处协议事实**：① `ping` / `logging/setLevel` 已被**移除**（不是"可选"）；② 结果新增强制字段 `resultType`，5 个可缓存方法强制 `ttlMs` + `cacheScope`；③ 补 `-32021 MissingRequiredClientCapability`；④ 补**修订版时间线**与 **2024-11-05 的第三形态**；⑤ 补明 Roots / Sampling / Logging 已弃用，不要实现。同时修正 2 处内部章节交叉引用错号 |
| v0.1 修订2 | 2026-10-14 | 鱼鱼 | **M1 定案：兼容到 `2024-11-05`（方案③）**。据此把第 3 章从"单一 Streamable HTTP"改写为**三种传输形态**（3.1 HTTP+SSE 双端点 / 3.2 旧版 Streamable HTTP / 3.3 现代无状态），新增 **3.4 era 识别与分流**、**3.5 跨形态通用行为**；第 4 章改为**分 era 的协商与元数据**（现代无握手 / 旧版 `initialize` 握手）；新增 **10.3 era 差异矩阵**与 **10.4 会话状态与部署影响**；待确认清单新增 M12–M15 |
| v0.1 修订4 | 2026-10-14 | 鱼鱼 | **`2025-03-26` / `2025-06-18` / `2025-11-25` 三版传输逐页原文核对完成**。据此**修正 1 处方向性错误**：形态二**不能**靠"关闭流"取消 —— 原文明确 "Disconnection SHOULD NOT be interpreted as the client cancelling its request"，取消必须显式发 `notifications/cancelled`（这与现代版"关流即取消"**正好相反**）。另补实：会话 ID 全生命周期（下发/缺失 400/过期 404/`DELETE` 405/安全要求）、SSE 续传（事件 id 为 MAY、补发为 MAY 且禁止跨流）、`GET /mcp` 多流并存与"禁止广播同一消息"、`MCP-Protocol-Version` 头缺失时"服务端应假定 `2025-03-26`"、请求体在 `2025-03-26` 允许数组批而 `2025-06-18` 起收窄、规范原文明确"MCP Servers MUST NOT use sessions for authentication"；矩阵新增 3 行 |
| v0.1 修订5 | 2026-10-14 | 鱼鱼 | 落两条新设计决策：① **惰性校验** —— 上架/保存工具与资源时**不探测上游**，只在**被调用时**确认存在性（新增 9.2，与管理面"导入步骤② 100% 前端"一致；上游不可达一律转 `isError: true`）；② **长连接上游留位** —— 为上游是流式 Streamable HTTP / WebSocket 的情况预留数据位与调用链位（新增 9.3，关键依据是 `2026-07-28` 原文"跨调用状态须用服务端铸造的句柄当普通工具参数传递"）；据此第 9 章改名为"调用链、惰性校验与长连接余量"并拆成 9.1/9.2/9.3，矩阵补通知通道可用性，待确认新增 M19 / M20 |
| v0.1 修订6 | 2026-10-14 | 鱼鱼 | **M20 定案**：本期只留数据位与调用链位、不实现长连接上游（9.3 顶部写明定案）。另把 **M19 的场景讲清楚**：新增 **6.4 变更通知怎么送到客户端** —— 用一个具体操作串起来（控制台停用工具 → 已连接的客户端仍在菜单里显示它 → 服务端要主动发 `notifications/tools/list_changed` → 但客户端此刻没有活跃请求可搭车），据此说明三形态的通道差异（形态一天然有常驻流 / 形态三需客户端 `subscriptions/listen` 订阅 / 形态二需客户端主动开 `GET /mcp` 而规范未规定何时开），并给出三个选项与"不得撒谎声明 `listChanged`"的约束 |
| v0.1 修订7 | 2026-10-14 | 鱼鱼 | **M19 定案**：三形态**都不主动推**变更通知 —— 客户端自己来请求（`*/list` 或重新触发 `/mcp`），**不一致的代价由用户承担**。据此改写 6.4（顶置定案 + 场景推演保留备查）、能力表改为**不声明** `listChanged` / `subscribe`、`subscriptions/listen` 与 `resources/subscribe` 标记为**不实现**（客户端调用 → `-32601` 并给原因）。新增 **M21**：审计日志必须带"工具 / 资源 / 所属服务"**检索维度**，为未来改进「删除时按调用历史提醒」预留（管理面同步新增 A22）。**并清扫全文 6 处与定案冲突的旧表述**：3.1 / 3.3 的通知能力句补注"M19 不推"、3.5 补注 `resources/subscribe` 不实现、7 章映射表"启用状态→发 `list_changed`"改为"不推通知"、矩阵新增"通知能力是否使用/是否实现订阅方法"两行、9.3 的 WS-Resource 方案标注与 M19 冲突 |
| v0.1 修订8 | 2026-10-14 | 鱼鱼 | **三个定案 + 一个场景说明**：① **M12 定案** —— 会话存储后端可配置三选一（Redis / DB / **本进程全局变量，明确不支持分布式**）；② **M17 定案** —— 缺 `MCP-Protocol-Version` 头时照规范假定 `2025-03-26`；③ **M5 定案** —— 第 8 章整章重写为输入侧（`url→blob` 服务端代理下载 + **必做 SSRF 防护** / `base64→blob`）与输出侧（`base64` 内嵌 + 尺寸警告 / `uri` + 本机存储或 S3 二选一），并新增 **8.4 本机存储缺的接口**（`GET /artifacts/{id}` 流下载、URI 形态、生命周期、管理面配置）；④ **M16 场景说明** —— 10.4 新增"会话是谁和谁之间的"（**我们 ↔ 调用我们的 MCP 客户端**，与上游无关；会话 ID = 握手机柜钥匙）与"强制 / 不强制"对照表，结论是**我们并不依赖握手结果** → 推荐不强制、不下发。新增 **M22**（URI 形态与下载端点） |
| v0.1 修订9 | 2026-10-14 | 鱼鱼 | **二进制内容块逐字核对完成**（含官方 JSON Schema），产出**一条必须实现的新硬规则**：新增 **8.6 内容块类型必须按客户端版本裁剪** —— `2024-11-05` 的 `CallToolResult.content` **只有 3 种**（`text`/`image`/内嵌 `resource`，**无 `audio`、无 `resource_link`**），`audio` 于 `2025-03-26` 引入、`resource_link` 于 `2025-06-18` 引入、内容块 `_meta` 于 `2025-06-18` 引入 → **绝不能给老客户端返回它那版不存在的类型**。8.3 补实：`resource_link` 必填 `uri`+`name`+`type`、**`resources/read` 不能只回 uri（必然内嵌）**、`size` 语义（base64 **之前**的原始字节数，仅 Resource/ResourceLink 上有）、**规范无任何大文件上限或禁令**（尺寸阈值是我们的产品决策）、`resources/read` **不能**返回 `image`/`audio`。矩阵新增 4 行；10.1 加"`2026-07-28` 发布状态待确认"与"文档页与 Schema 不一致以 Schema 为准"；M22 补推荐与原文依据 |
| v0.1 修订10 | 2026-10-14 | 鱼鱼 | **两个定案**：① **M16 定案** —— 形态二**不下发会话 ID**（不强制）→ 形态二**无状态**，`initialize` 仍必须应答但不保存结果；客户端带不明 `Mcp-Session-Id` 则忽略（不回 `404`）。连带同步 **3.2**（会话段标注为"协议能力存档，我们只识别并忽略"）、**3.4 era 分流表**（三行改为：`initialize` 应答但不建会话 / 有会话头→忽略 / **绝不因缺会话头而拒绝**）、**3.5**（协商结果存放：形态一绑连接、形态二逐请求看版本头）、**10.3 矩阵**、**10.4**、**M12**（只有形态一需要存储）。② **M22 定案** —— `uri` 方案 = **`https://` + 短 TTL 签名 URL 让客户端直接抓取**（依 `https://` scheme 原文取舍原则）；`resource_link` 只在 **`2025-06-18` 及以上**使用，更低版本降级为文本 URL；仍需补 8.4 下载端点与签名 URL 签发/时钟偏差 |
| v0.1 修订11 | 2026-10-14 | 鱼鱼 | **新增 10.5 目标客户端兼容性**（四个目标客户端：Dify / Codex / OpenClaw / WorkBuddy；本轮已核到 OpenClaw 与 WorkBuddy 两家，Dify / Codex 标 ⏳）。**三条硬结论**：① **形态一必须做** —— OpenClaw **省略 transport 时默认走 `sse`**，「when omitted, OpenClaw uses `sse`」→ 直接验证 M1 选方案③；② **Streamable HTTP 必须做** —— OpenClaw 支持 `streamable-http` 且示例路径就是 `/mcp`，WorkBuddy 企业连接器 Transport = `streamable_http`；③ **自定义 header / Bearer Token 必须支持** —— 两家均可带自定义头（WorkBuddy 的 API Key 可把 Header Name 设为 `Authorization`），故 M3「静态 Token 先行」成立，但 WorkBuddy 企业侧首选 MCP OAuth 2.1、长期要补。另两条：**两家都未声明协议修订版**（无 `protocolVersion` / `MCP-Protocol-Version` / `Mcp-Session-Id` 字样）→ 印证"只能按开场方式分流"（3.4 / 8.6）；**CodeBuddy 侧 `MAX_MCP_OUTPUT_TOKENS` 默认 20000 tokens（≈80KB）会截断工具输出** → 强化 M22 签名 URL 方向，并给出尺寸警告阈值的默认量级。新增 **M23**（真实客户端实测，重点验形态二不下发会话） |
| v0.1 修订12 | 2026-10-14 | 鱼鱼 | **四个目标客户端全部核完，10.5 补齐 Dify / Codex**，并产出 **5 条新硬结论**：⑥ **⚠️ 端点路径必须以 `/mcp` 或 `/sse` 结尾** —— Dify 客户端**按 URL 最后一个路径段选传输**（源码 `if method_name in {"mcp","sse"}`，否则先试 SSE 再回退，且 1.15.0 回退路径有未捕获 `httpx.ReadTimeout` 会**卡到 300s**）→ **M2 从"建议"升级为硬约束**；⑦ **必须实现 `initialize` 握手** —— Codex 明确读 `initialize` 响应的 `instructions` 且要求**前 512 字符自包含**，只做形态三会让 Codex 失效；⑧ **客户端超时即我们的硬上限** —— Dify 30s / SSE 读 300s、Codex 启动 10s / **工具 60s** → **M9 给出推荐值：上游默认 30s**；⑨ **⚠️ Dify 协议上限就是 `2025-06-18`**（源码常量 `LATEST_PROTOCOL_VERSION`，支持集 `{2024-11-05, 2025-03-26, 2025-06-18}`），**没有任何已核客户端声明支持 `2025-11-25` / `2026-07-28`** → 优先级 = **形态二 > 形态一 > 形态三**（M1 方案③不变）；⑩ Codex **未列旧式 HTTP+SSE** → 形态一主要服务 Dify / OpenClaw；另记录"Dify 自家 MCP Server 是单 POST、无 GET / 无 session 的极简实现"（侧证无状态可行，但**不能反推客户端兼容要求**）。M23 扩到"GET 流回 `405`"的实测 |
| v0.1 修订13 | 2026-10-14 | 鱼鱼 | **新增 10.6「按接入 MCP 时间反推版本窗口」+ 新增 M24 + 修一处缺陷**：① 用"接入 MCP 的时间"补"官方不声明协议版本"的缺口 —— Dify 插件期 2025-04-01（⚠️ **该日期已被修订 14 修正为 2025-03-14**）/ 原生 2025-07-10、Codex ≤ 2025-09-30（`0.42.0` 仅 stdio）、OpenClaw `2026.3.31`、WorkBuddy ≤ 2026-07-15 → **四家共同可及窗口 = `2025-03-26` ~ `2025-06-18`（"四家共同语言"）**；而 `2026-07-28` 在四家接入时**都还没发布**（最晚接入的 WorkBuddy 早 13 天）→ **形态三无接入期证据，排到最后做**。② **新增 M24**：M17（缺版本头假定 `2025-03-26`）× M16（不保存握手结果）→ 净效果是**形态二下客户端被当成 `2025-03-26`**，`resource_link` / 内容块 `_meta` / `audio` 会被 8.6 **全量降级**（Dify 客户端疑似根本不发该头）→ 推荐 **Token 维度"最近协商版本"轻缓存**。③ **修复**：**M23 行此前误插到本变更记录表内**（编辑锚点撞车，且校验只数了总数没查位置），已归位到第 11 章 M 表 |
| v0.1 修订14 | 2026-10-14 | 鱼鱼 | **10.6 时间线按更早证据修正（三条修正）**：① **Codex 起点前推 4.5 个月** —— 不是 `0.42.0`（2025-09-26），而是 **2025-05-02** 的 commit `83961e0` / PR #787「introduce mcp-types crate」，它**直接把 `2025-03-26/schema.json` vendor 进代码** → **Codex 的版本下限从"推断"升级为硬证据 = `2025-03-26`**（`0.42.0` 降级为"RMCP 客户端切换点"；stdio 客户端 = PR #829 / 2025-05-06；npm 用户可用版 = `rust-v0.20.0` / 2025-08-09）。② **OpenClaw 起点前推 4 个月** —— 不是 `2026.3.31`，而是 **2025-11-27** 的 commit `85f53a4`（当时项目还叫 clawdbot）；`2026.3.31` 只是"remote HTTP/SSE 传输 + `streamable-http` 选择"落地。③ **Dify 与 WorkBuddy 前推** —— Dify 插件时代起点 **2025-03-14**（`dify-plugins` PR #138 `mcp_sse`，**此时 `2025-03-26` 尚未发布** → 起点版本就是 `2024-11-05`，形态一 = Dify 的起点形态）；WorkBuddy 本体 **2026-03-09 上线日即内置 MCP**（官方 changelog 4.6.0 / 2026-03-19），CodeBuddy 侧 **2025-05-13**（"中国首个支持 MCP 的代码助手"）。**净结论**：交集窗口不变（`2025-03-26`~`2025-06-18`）；**`2025-11-25` 从"推断"升级为"很可能真会有客户端说"**（OpenClaw 发布后 2 天、WorkBuddy 发布后 3.5 个月接入）；**M24 的受损方精确为 Dify**（Codex 既然 vendored `2025-03-26`，被当 `2025-03-26` 也毫无损失） |
| v0.1 修订15 | 2026-10-14 | 鱼鱼 | **M24 定案 + 形态三定位**：① **M24 定案 = (b) + 配置兜底** —— Token 维度记"最近协商版本"轻缓存（不下发会话 ID、不用于鉴权），**并新增用户配置项「是否接入生成文件的接口」来定方言上限：是 → `2025-06-18`，否 → `2025-03-26`**；生效规则 **`有效版本 = min(客户端协商版本, 配置方言上限)`**（例：Codex 说 `2025-03-26` 就绝不给它发 `resource_link`；Dify 说 `2025-06-18` 但没开文件接口 → 按 `2025-03-26` 输出）→ 配置归属待确认（管理面新增 A24）。② **形态三（`2026-07-28`）"设计保留、本期不排期"**（优先级最低，但文档不裁剪）→ 已同步 M1 行与 10.6 交集结论表 |
| v0.1 修订16 | 2026-10-14 | 鱼鱼 | **A24 定案 → 新增 §4.4「配置项（`env`）：方言上限怎么定」**：配置来源 = **`env` 文件（控制台不新增接口）**，并**假设将来会有「设置配置页」**→ 要求读取侧**先做配置源抽象**。给出 env 键（`MCP_FILE_INTERFACE_ENABLED` / `MCP_DIALECT_MAX` / `MCP_NEGOTIATED_VERSION_TTL`）、`有效版本 = min(客户端协商版本, 配置方言上限)` 的两个例子，以及运维注意（**重启生效、启动日志必须打印生效方言上限**）。⚠️ **同时修一处格式事故**：上一版把 ```ini 代码块**塞进了 M24 表格行内**，已把该块移出表格、归位到 4.4，M24 行改为一行指针 |

## 规范依据

**已逐页核对（我读过原文）**：

- [Streamable HTTP（2026-07-28）](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [Transports（2026-07-28）](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports)
- [Versioning（2026-07-28）](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
- [Changelog（2026-07-28）](https://modelcontextprotocol.io/specification/2026-07-28/changelog)
- [Server / Tools（2026-07-28）](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- [Server / Resources（2026-07-28）](https://modelcontextprotocol.io/specification/2026-07-28/server/resources)
- [Transports（2024-11-05）](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)

**已逐页核对（第二批：`2025-*` 传输层）**：

- [Transports（2025-03-26）](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports) · [Changelog（2025-03-26）](https://modelcontextprotocol.io/specification/2025-03-26/changelog)
- [Transports（2025-06-18）](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) · [Changelog（2025-06-18）](https://modelcontextprotocol.io/specification/2025-06-18/changelog) · [Lifecycle（2025-06-18）](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)
- [Transports（2025-11-25）](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) · [Changelog（2025-11-25）](https://modelcontextprotocol.io/specification/2025-11-25/changelog) · [安全最佳实践（2025-11-25）](https://modelcontextprotocol.io/docs/2025-11-25/tutorials/security/security_best_practices)

**已逐页核对（第一批：`2024-11-05` 与 `2026-07-28`）**：见上方列表，共 7 页 + schema。

**⚠️ 规范自身的 5 处空白（原文没写 —— 实现时要我们自己定，别当成既有约定）**：

| # | 规范空白 | 我们的处理 |
| --- | --- | --- |
| 1 | 客户端"**何时才应**打开 `GET /mcp` 流" | 三版原文都未规定 → 我们只在不请自来通知等必要场景用，文档标注为规范空白 |
| 2 | `DELETE /mcp` 的**成功响应码** | 未规定（只规定不支持时 MAY `405`）→ 见 M18 |
| 3 | **端点方法要求只写了 POST + GET**，`DELETE` 未列入 | 按"可选支持"实现，不支持则回 `405` |
| 4 | 会话头在 `2025-11-25` 写作 `MCP-Session-Id`，前两版写作 `Mcp-Session-Id`（changelog 未记录） | **HTTP 头名大小写不敏感**，二者是同一个头 → 按不敏感匹配，**不视为兼容问题**（引用规范时注明差异） |
| 5 | 会话 ID 安全措辞的**修订版归属**（两个 spec 路径都 302 到同一 `/docs/2025-11-25/` 页） | 按更严的 `2025-11-25` 要求实现 |

---

## 1. 目标、边界与不做什么

**目标**：把控制台里配好的「服务 / 工具 / 资源 / 提示词」以标准 MCP Server 的形态对外提供，让外部 MCP 客户端（AI 应用、IDE 插件）能发现并调用；**客户端不论落在哪个修订版，都能连上**。

**做**：

- **三种传输形态**并存：HTTP+SSE 双端点（`2024-11-05`）、旧版 Streamable HTTP（`2025-03-26`~`2025-11-25`）、现代无状态 Streamable HTTP（`2026-07-28`）
- 按客户端开场自动分流（3.4）、分 era 的版本协商（第 4 章）
- Token 鉴权与可见范围（第 5 章）
- 方法清单与能力声明（第 6 章）
- **控制台配置 → MCP 定义的映射关系**（第 7 章，本文核心）
- 二进制内容的通道选择结论（第 8 章）
- **惰性校验**：上架/保存工具与资源时**不**探测上游接口是否存在，只在**被调用时**确认（见 9.2）
- 为**长连接上游**（上游是流式 Streamable HTTP / WebSocket）预留设计余量（见 9.3）

**不做（本轮）**：

- 字段级 Schema 设计（`inputSchema` / `outputSchema` 的具体字段与类型）
- OAuth 授权服务器的完整流程（先按静态 Token 走，见 M3）
- `stdio` 传输（本地进程形态，本期不提供）
- **已弃用特性一律不实现**：Roots、Sampling、Logging（`2026-07-28` 起弃用，规范明确建议新实现不要采用）；日志改为写 stderr 或走 OpenTelemetry
- **服务端主动发起的 JSON-RPC 请求**（sampling / elicitation / roots）：三种形态都不发，见 3.5
- **长连接上游本期不接**（WebSocket / gRPC / SOAP / GraphQL 都不做），但**数据结构与调用链要留位**，不做成"以后加不进去"（见 9.3）

---

## 2. 与管理面（v0.2）的关系

| | 管理面 | 产品面（本文） |
| --- | --- | --- |
| 消费者 | 控制台前端 | 外部 MCP 客户端 |
| 协议 | REST + JSON | MCP（JSON-RPC 2.0；三种传输形态） |
| 文档 | `前后端交互接口设计.md` | 本文 |
| 作用 | **产出**定义：服务、工具、资源、提示词、标签、访问令牌 | **消费**定义：把上面的配置暴露成 MCP 能力 |

一句话：**控制台负责"配"，MCP Server 负责"对外供"**；两边共用同一份数据，不重复定义。

---

## 3. 三种传输形态与端点（M1 定案：兼容到 `2024-11-05`）

> 服务端同时提供三种形态，**按客户端如何开场自动分流**（见 3.4）。

**代价先说清楚**：形态一与形态二都是**会话制**（服务端有状态）→ 多实例部署需要共享会话存储或粘性路由；只有形态三是无状态的。这是选择全兼容的必然成本（见 10.4 与 M12）。

### 3.1 形态一：HTTP+SSE（`2024-11-05`）

- **两个端点**（老规范强制）：
  - `GET /sse` —— 客户端建连、接收消息；
  - `POST /messages?sessionId=<会话ID>` —— 客户端发送消息。
- 客户端一连上，服务端**必须最先发一个 `endpoint` 事件**，data 里给出客户端应当 POST 的 URI；我们实现为 `POST /messages?sessionId=<会话ID>`。
- **响应不走 POST 的 HTTP 响应体**：POST 收到 JSON-RPC 请求后，HTTP 侧只回 `202 Accepted`，真正的响应通过该会话的 SSE 流以 `message` 事件下发。→ **这条最容易实现漏**，且直接决定服务端要能"把 POST 关联到正确的 SSE 流"。
- 一个 `GET /sse` 连接即一个会话，连接断开即会话结束（服务端要清理）。
- 版本协商靠 `initialize` 握手（4.2），协商结果绑定到该连接。
- 变更通知（`notifications/tools/list_changed` 等）**可直接在这条流上推**，不需要订阅。（这是**协议能力**；**M19 定案：本轮三形态都不推**，见 6.4）
- 老规范允许服务端在这条流上发 JSON-RPC 请求（sampling / roots）；**我们不发**（3.5）。
- **取消**：老规范里**唯一有原文依据**的取消机制是客户端发 `notifications/cancelled`（`utilities/cancellation` 页是传输无关的）；**"关闭 SSE 流即取消"在这版没有任何原文依据**（该版传输章节全文不含 cancel 字样），属未定义行为 → 我们按通知实现。
- **关闭与超时**：这版**没有定义 shutdown 消息**，原文说 HTTP 传输下的关闭就是"关闭关联的 HTTP 连接"；超时只写了"应当为所有请求实现合理超时"，**没有具体秒数**。
- 该传输**已被规范弃用**：只为老客户端兼容而实现，**不作为推荐接入方式**。

### 3.2 形态二：Streamable HTTP（`2025-03-26` ~ `2025-11-25`）

**已按三版原文逐页核对**（下文中未特别标注的条目为三版一致）：

- **端点与方法**：原文要求 MCP 端点**支持 `POST` 与 `GET`**；客户端的每条 JSON-RPC 消息都是一次 `POST`；`GET /mcp` 用于服务端→客户端推流，服务端若不接受 GET，**必须**回 `405`。`DELETE` **只在会话章节**出现（客户端 SHOULD 发它结束会话），端点方法要求里**没有**列 DELETE。
- **`Accept` 头**：POST **必须**同时列 `application/json` 与 `text/event-stream`；GET **必须**列 `text/event-stream`。
- **请求体**：`2025-03-26` 允许"单条 request / notification / **response**"或"**数组批处理**"；**`2025-06-18` 起收窄为单条**（原文 MUST，changelog 记为"移除 JSON-RPC 批处理"）→ 我们只实现单条。
  - 注意：老形态**允许客户端 POST *response***（因为服务端可以主动发请求），现代形态**不允许**。
- **响应**：二选一 —— `text/event-stream`（该请求专属的 SSE 流）或 `application/json`（单对象）；客户端**必须**两者都支持。
- **SSE 流上服务端可以发请求与通知**：原文 "The server MAY send JSON-RPC requests and notifications before sending the JSON-RPC response"，且 SHOULD 与本请求相关 → **我们选择不发**（见 3.5）。
- 服务端**不应**在"每个收到的请求都还没回响应"之前关闭该 SSE 流，除非会话过期。
- 只发 notification 的 POST：接受则 `202 Accepted` 且无 body；不接受则 HTTP 错误码（可带无 `id` 的 JSON-RPC 错误）。
- **协议级会话 `Mcp-Session-Id`（核心，已逐字核对）** —— ⚠️ **M16 已定案：我们不下发会话 ID**，以下整段是**协议能力存档**，我们只保留两个行为：**能大小写不敏感地识别它**、**收到就忽略**：
  - 服务端**可以**（MAY）在 `initialize` 响应上带该头下发会话 ID；
  - 客户端**必须**（MUST）在所有后续请求带上它；
  - 服务端若**要求**会话，缺该头（初始化除外）**应当**回 `400 Bad Request` —— 注意是 **SHOULD + 400**，不是 MUST；
  - 服务端**可以**随时终止会话，此后带该会话 ID 的请求**必须**回 `404`；客户端收到 `404` **必须**不带会话 ID 重新发 `initialize` 开新会话；
  - 客户端**应当**（SHOULD）发 `DELETE /mcp` 结束会话；服务端若不支持，**可以**回 `405`（**成功码原文未规定**，见 M18）；
  - 会话 ID 安全：SHOULD 全局唯一且密码学安全（安全 UUID / JWT / 哈希），**MUST 只含可见 ASCII（`0x21`–`0x7E`）**；`2025-11-25` 新增"客户端**必须**安全处理会话 ID"（防会话劫持）；
  - **⚠️ 头名写法差异**：`2025-03-26` / `2025-06-18` 页面写作 `Mcp-Session-Id`，`2025-11-25` 写作 `MCP-Session-Id`，changelog **未记录**该变更。但 **HTTP 头名本身大小写不敏感**，二者在协议层是同一个头 → **不构成兼容问题**，按大小写不敏感匹配即可（引用规范时注明该差异）。
- **`GET /mcp` 推送流**：
  - 客户端 MAY 打开（"**何时才应打开**"原文**未规定**，属规范空白）；可与多条 SSE 流**同时并存**；
  - 服务端**必须**把每条 JSON-RPC 消息只发在**其中一条**流上，**禁止**把同一条消息广播到多条流；
  - 服务端**可以**在这条流上发请求与通知，SHOULD 与并发中的客户端请求无关；
  - 服务端**不得**在这条流上发 JSON-RPC *response*，除非是"续传一条与之前客户端请求关联的流"。
- **SSE 续传（本形态独有，`2026-07-28` 已删除）**：
  - 服务端 **MAY** 给 SSE 事件加 `id`；若加了，**MUST 在会话内全局唯一**；
  - 客户端想续传时 **SHOULD** 发 `GET /mcp` 并带 `Last-Event-ID`；
  - 服务端 **MAY** 据此补发断流期间的消息，但 **MUST NOT 跨流补发**（只能补原流上的）；
  - `2025-11-25` 新增：事件 ID **SHOULD** 编码足以识别"原流身份"的信息；续传**一律走 `GET` + `Last-Event-ID`**（无论原流是 POST 还是 GET 起的）；"断连"包括服务端主动关闭；服务端关连接但不结束流时应发 `retry`（轮询行为）。
- **版本头**：`MCP-Protocol-Version` 自 `2025-06-18` 引入，行为见 4.2。
- 版本协商靠 `initialize` 握手（见 4.2）。
- **取消（⚠️ 与形态三正好相反）**：原文明确 **"Disconnection SHOULD NOT be interpreted as the client cancelling its request."**，取消**必须**由客户端**显式发送** `notifications/cancelled` → 详见 3.5。

### 3.3 形态三：Streamable HTTP（`2026-07-28`，现代无状态）

- **单端点** `POST /mcp`，只接受 `POST`。
- `GET` / `DELETE` 到该端点 → `405 Method Not Allowed`（本修订版既没有 GET 流端点，也没有协议级会话）。
- 请求头 `Mcp-Session-Id`：**忽略**，不生成、不回显。
- 请求头 `Last-Event-ID`：**忽略**，流不可续传；流断了就由客户端换新请求 id 重发。
- 请求体是**单个** JSON-RPC *request* 或 *notification*；**不接受** JSON-RPC *response*。
- `Accept` 必须同时列出 `application/json` 与 `text/event-stream`。
- 若是 *request*，响应二选一：`application/json`（单对象）或 `text/event-stream`（**请求域** SSE 流，先发本请求相关的通知，再发最终响应，最终响应**应当**终止流）。
- 请求域 SSE 流上**不得**发独立的 JSON-RPC *request*；要向客户端索取输入用 MRTR（`resultType: "input_required"`，见 6.2）。
- 若请求体是 *notification*：接受则 `202 Accepted` 且无响应体；不能接受则回 HTTP 错误码（可带无 `id` 的 JSON-RPC 错误）。
- 不请自来的变更通知**只能**走 `subscriptions/listen`；老式的 GET 推送流已被删除。（协议能力如此；**M19 定案：本轮不实现**，见 6.4）

### 3.4 era 识别与分流（三种形态共用入口）

依据规范"服务端按客户端如何开场选择行为"：

| 客户端开场 | 判定 | 服务形态 |
| --- | --- | --- |
| `GET /sse` | 老 SSE 客户端 | 形态一 |
| `POST /messages`（`sessionId` 无效或已过期） | 会话丢失 | `404` + JSON-RPC 错误（并提示重新 `GET /sse`） |
| `POST /mcp`，body 带现代 `_meta` 且带 `MCP-Protocol-Version` 头 | 现代客户端 | 形态三（无状态） |
| `POST /mcp`，body 是 `initialize` | 旧版 Streamable HTTP 客户端 | 形态二（**应答握手，但不建会话、不下发 ID**） |
| `POST /mcp`，body 是其他方法且无现代 `_meta`、**但有** `Mcp-Session-Id` | 旧客户端重放（**我们从未下发过**，故一律忽略该头） | 形态二（**忽略会话头**，按默认能力处理） |
| `POST /mcp`，body 是其他方法、无现代 `_meta`、**无** `Mcp-Session-Id` | 旧客户端（无会话） | 形态二（**M16 定案：绝不因缺会话头而拒绝**，按默认能力处理） |

**⚠️ 必须防的坑**：现代客户端在拿到 `400 Bad Request` 且响应体**不是**它认识的标准现代错误时，会**回退去 `GET /sse` 探测老服务端**。所以我们返回现代错误时必须严格用规范格式（`-32020` / `-32021` / `-32022`），否则会把现代客户端**误带去老形态**。

### 3.5 跨形态通用行为

- **安全基线（三形态都要）**：必须校验 `Origin` 头（不合法且存在 → `403`，防 DNS rebinding）；本地部署只绑 `127.0.0.1`；所有连接都要认证。
- **服务端永不主动发 JSON-RPC 请求**（不发 sampling / elicitation / roots）。因此三种形态都只需"响应 + 通知"两件事，实现量与风险都显著降低，也自动符合弃用政策；现代形态需要向客户端要信息时用 MRTR。
- SSE 响应**应当**带 `X-Accel-Buffering: no`（避免 nginx 等反代缓冲导致不实时）；长流**建议**周期性发 SSE 注释行（`:`）保活。
- **取消语义按形态不同**：

| 形态 | 取消方式 |
| --- | --- |
| 形态一 | **显式发 `notifications/cancelled`**（`2024-11-05` `utilities/cancellation`，该页传输无关）；**"关流即取消"在该版无原文依据**，属未定义行为 |
| 形态二 | **显式发 `notifications/cancelled`**；原文明确 **"Disconnection SHOULD NOT be interpreted as the client cancelling its request."** → **关流不算取消** |
| 形态三 | **只能靠关闭该请求的响应流**（现代规范明确"关流本身就是取消信号，不再期待 `notifications/cancelled`"）；服务端不得再发该请求的任何消息 |

**⚠️ 三形态的取消机制互不相同，这是最容易实现错的地方。**

共通的取消细则（`2024-11-05` 原文）：收到取消后**是 SHOULD 不是 MUST** —— 应停止处理、释放资源、**不回响应**；**MAY 忽略**（请求未知 / 已处理完 / 不可取消）；**`initialize` 禁止被取消**（唯一硬性约束）；取消可能晚于响应到达，双方**必须**优雅处理竞态；发出取消的一方 SHOULD 忽略之后才到的响应。

- **能力与方法集按协商版本不同**：老形态规范里有 `ping`、`logging/setLevel`、`resources/subscribe`（**其中 `resources/subscribe` 本项目不实现**，见 6.1 / M19），现代形态**已移除**这些；详见 10.3 矩阵。
- 形态一的 `sessionId` **出现在 URL 里**（老规范如此），日志与监控必须脱敏（M13）。

---

## 4. 版本协商与请求元数据（分 era 处理）

### 4.1 现代形态（`2026-07-28`）：逐请求元数据，**没有握手**

| 头 | 取值来源 | 适用 |
| --- | --- | --- |
| `MCP-Protocol-Version` | 必须与请求体 `_meta` 中的协议版本字段**一致** | 所有 POST |
| `Mcp-Method` | 请求体的 `method` | 所有请求 |
| `Mcp-Name` | `params.name`（`tools/call`）或 `params.uri`（`resources/read`） | `tools/call`、`resources/read`、`prompts/get` |

- 头名大小写不敏感；头**值**大小写敏感。
- 值若含非 ASCII / 控制字符 / 首尾空白，必须用 `=?base64?{Base64}?=` 形式编码（`Mcp-Name` 同样适用）。
- **必须做头-体一致性校验**：不一致或必需头缺失 → `400` + **`-32020`（HeaderMismatch）**。
- **不存在** `initialize` 握手，也没有 `notifications/initialized`；版本、客户端身份、客户端能力**逐请求**携带在 `_meta` 里。
- 不支持的版本 → `400` + **`-32022`（UnsupportedProtocolVersionError）**，错误体给出自身支持的版本列表，客户端据此重试。
- **必须实现 `server/discover`**，供客户端提前获知支持的版本与能力。
- 规范化的错误码区间：`HeaderMismatch` **`-32020`**、`MissingRequiredClientCapability` **`-32021`**、`UnsupportedProtocolVersion` **`-32022`**（`-32020`~`-32099` 归规范，`-32000`~`-32019` 为实现自定义）。

### 4.2 旧形态（`2024-11-05` ~ `2025-11-25`）：`initialize` 握手

**已按 `2024-11-05` 原文逐页核对**（Lifecycle / Utilities-Cancellation / Server-Resources / Index / Schema）：

- **请求**：客户端**必须**先发 `initialize`，含 `protocolVersion` + `capabilities` + `clientInfo`（`clientInfo` 里 `name` / `version` 必填）。
- **响应**：服务端**必须**回 `protocolVersion` + `capabilities` + `serverInfo`；`instructions` 可选（只在 schema 里有，Lifecycle 示例未出现）。
- **版本协商规则（原文逐字）**：
  - 服务端支持客户端请求的版本 → **必须**回同一个版本；
  - 不支持 → **必须**回另一个自己支持的版本；
  - 客户端若无法支持服务端回的版本 → Lifecycle 页写 **SHOULD** disconnect，Schema 写 **MUST** disconnect → **两处口径冲突**，我们按更严的 **MUST** 处理；
  - 老版不支持版本时用的示例错误码是 **`-32602`**（与现代版的 `-32022` 不同，别混用）。
- **`notifications/initialized`**：初始化成功后客户端**必须**发送；在收到它之前，服务端**不应**发送除 `ping` 与 `logging` 之外的**请求**（原文只约束"发送"，**未**约束是否处理入站请求）。
- **禁止取消**：`initialize` 请求**禁止**被客户端取消（原文 MUST NOT，是唯一一条硬性"不可取消"约束）。
- **超时与关闭**：原文只要求"实现应当为所有请求设置合理超时"，**没有具体秒数**；**没有定义 shutdown 消息**，HTTP 传输下的关闭 = 关闭关联的 HTTP 连接。
- **协商结果的存放**：形态一绑到 `GET /sse` 连接（**必然有状态**，这是 M12 唯一的真实需求方）；形态二**不绑会话**（M16 定案）→ 版本**逐请求**从 `MCP-Protocol-Version` 头判断（缺头按 M17 假定 `2025-03-26`）；形态三**逐请求**从 `_meta` 判断。
- **老形态没有** `Mcp-Method` / `Mcp-Name`；`MCP-Protocol-Version` 只在 `2025-06-18` 及以后存在。
  → **绝不能把这三种头当老客户端的必填项**，否则所有老客户端会被 `400` 掉。
- 老客户端**没有 fail-forward 机制**：若我们无法服务它请求的版本，必须在握手响应里给出清楚的版本信息。

**`MCP-Protocol-Version` 头（老形态 Streamable HTTP 专用，已原文核对）**：

- 自 `2025-06-18` 引入（changelog 明确写着"要求 HTTP 下用该头声明协商版本"）。
- 客户端**必须**（MUST）在所有后续请求带上 `MCP-Protocol-Version: <协商到的版本>`，值 SHOULD 就是初始化协商出的版本。
- **缺失时**：服务端 **SHOULD 假定版本为 `2025-03-26`** ← 这是硬行为，必须实现（见 M17）。
- **值非法时**：服务端 **MUST 回 `400 Bad Request`**。
- 形态一（`2024-11-05`）**完全没有**这个头，不能要求。

### 4.3 未知方法与请求体错误

- 方法不存在 → `404` + JSON-RPC `-32601`（Method not found）。
- 请求体不是合法 JSON-RPC / 不是单个 request 或 notification → `400` + JSON-RPC 错误。

---

### 4.4 配置项（`env`）：方言上限怎么定（M24 定案 + A24）

**为什么需要它**：形态二**不下发会话 ID、不保存握手结果**（M16），而请求缺 `MCP-Protocol-Version` 头时规范说应**假定 `2025-03-26`**（M17）→ 于是"客户端 `initialize` 时说 `2025-06-18`、后续请求却不带版本头"这种情况，**光靠协议本身无法分辨**。解决办法是**我们自己钉一个方言上限**，不去猜客户端。

**生效规则（定案）**：

```text
有效版本 = min(客户端协商版本, 配置方言上限)
```

- **Codex 说 `2025-03-26`** + 我们开了文件接口 → 仍按 `2025-03-26` ⇒ **绝不给它发 `resource_link`**（它代码里 vendored 的就是这版 schema）；
- **Dify 说 `2025-06-18`** + 我们**没开**文件接口 → 按 `2025-03-26` ⇒ 不发新内容块（`resource_link` / 内容块 `_meta` / `audio` 全部降级，见 8.6）。

**辅助机制**：Token 维度记一个"**最近协商版本**"轻缓存（**不下发会话 ID、不用于鉴权**，纯兼容缓存，TTL 24h）—— 只为让"后续请求没带版本头"时不至于误降级。

**配置来源（A24 定案）：本期走 `env` 文件，控制台不新增接口**；**假设将来会有「设置配置页」**再迁过去 → **读取侧现在就做配置源抽象**（`env` → 将来 DB / 页面），别把 `System.getenv` 散落在业务代码里。

```ini
# 是否接入「生成文件」的接口：决定 MCP 方言上限
# true  → 方言上限 2025-06-18（可用 resource_link + 短 TTL 签名 URL 给文件链接）
# false → 方言上限 2025-03-26（只出 text / image / 内嵌 resource）
MCP_FILE_INTERFACE_ENABLED=true

# 可选：显式钉死方言上限（留空则按上面的开关推导）；用于临时排障或客户特殊要求
MCP_DIALECT_MAX=            # 2025-03-26 | 2025-06-18

# Token 维度「最近协商版本」轻缓存的 TTL（纯兼容缓存，不参与鉴权）
MCP_NEGOTIATED_VERSION_TTL=24h
```

> ⚠️ **运维注意**：`env` 改动需**重启生效**（不做热加载，避免同一实例半新半旧）；**启动日志必须打印实际生效的方言上限** —— 否则排障时无法区分"客户端不发版本头"和"开关配错了"。

---

## 5. 鉴权与可见范围

- **三种形态共用同一套 Token 模型**：Bearer Token，**Token 绑定可见范围**（能看见哪些服务 / 工具 / 资源 / 提示词）。
- 传输方式建议 `Authorization: Bearer <token>`；具体签发与作用域模型挂在控制台「访问管理」页（该页无设计，待补，见 M3）。
- **协议明确允许**：`tools/list` / `resources/list` 的结果**可以随请求携带的授权变化**（只返回该调用方作用域内的条目），但**不得**按连接变化、也不得作为其他请求的副作用变化。→ 我们按 Token 过滤可见范围**合规**。
- 形态一/形态二的会话**不改变**鉴权模型：会话只承载协商结果，每个请求仍要带 Token（会话不能代替鉴权）。
- 鉴权与 `Origin` 校验是两件事，两者都要做。
- 未授权 / 令牌无效：`401`；令牌有效但无权访问该对象：`403`。
- 敏感约束：服务的**鉴权参数**（Cookie / Header / Query / Body 里的 token 等）**不得**出现在任何对外 MCP 定义里（不暴露给模型），只在服务端转发时注入。
- **规范立场（原文核对）**：`2024-11-05` 明确写"Authentication and authorization are not currently part of the core MCP specification" → 我们的 Bearer Token 属于**实现自定义**，不违背规范；但也意味着**规范不规定**老形态下 `401` / `403` 的语义，需在联调文档里自己写清。
- **会话不能代替鉴权（原文强制）**：`2025-11-25` 安全最佳实践页明确 **"MCP Servers MUST NOT use sessions for authentication"**，并要求会话 ID 用安全随机源、不可预测、建议形如 `<user_id>:<session_id>` → 与我们"Token 才是鉴权、会话只承载协商结果"的设计一致，会话劫持面也被规范点名。

---

## 6. 能力与方法清单

> 服务端能力（capabilities）在现代形态随 `server/discover` 声明，在旧形态随 `initialize` 响应声明；具体结构以对应修订版原文为准。
>
> **老形态（`2024-11-05`，已原文核对）服务端能力顶层键**：`prompts`、`resources`、`tools`、`logging`、`experimental`。其中 `tools` / `prompts` 下是 `listChanged`；`resources` 下是 `subscribe` + `listChanged`，**两者各自可选**（可都给、可都不给）。客户端能力为 `roots`（含 `listChanged`）、`sampling`、`experimental`。→ **老形态的能力声明结构与现代形态不同**，必须分版实现（M14）。

| 能力 | 是否提供 | 说明 |
| --- | --- | --- |
| `tools` | ✅ | 对应控制台「工具」；**不声明 `listChanged`**（M19 定案：不主动推变更通知） |
| `resources` | ✅ | 对应控制台「资源」；**不声明 `listChanged`、不声明 `subscribe`**（同上） |
| `prompts` | ⏳ 待定 | 首页计数有「提示词 1」，但无功能设计（M7） |

### 6.1 方法

> **方法可用性分 era**：下表以现代形态（`2026-07-28`）为准；`ping`、`logging/setLevel`、`resources/subscribe` 等在老形态**存在**、在现代形态**已移除**。完整差异见 **10.3 矩阵**。

| 方法 | 用途 | 要点 |
| --- | --- | --- |
| `server/discover` | 声明支持的协议版本与服务端能力 | 现代形态**必须实现**；老形态不存在此方法 |
| `tools/list` | 列出当前 Token 可见的 MCP 工具 | 分页 + 缓存；**应当**返回确定性顺序，利于客户端缓存与提示词缓存命中 |
| `tools/call` | 调用工具 | 按工具定义拼真实 HTTP 请求（含服务级鉴权参数注入）→ 外发 → 映射响应为内容块 |
| `resources/list` | 列出可见资源 | 分页 + 缓存 |
| `resources/templates/list` | 列出带 URI 模板的资源（如路径参数化） | 参数可由 `completion/complete` 补全 |
| `resources/read` | 读取资源内容 | 内容为 **text 或 blob(base64)**；**资源不存在：老形态回 `-32002`、现代形态回 `-32602`**（原文核对，勿混用），且**不得**返回空 contents 数组 |
| `prompts/list` / `prompts/get` | 提示词发现与取用 | 待 M7 定 |
| `subscriptions/listen` | 客户端按需订阅变更通知 | **M19 定案：不实现**（我们不提供变更通知，也就不必维护订阅状态）；客户端若仍调用 → 按未知方法 `-32601` 返回，并给出明确原因 |
| `completion/complete` | 参数 / 资源模板补全 | 可选，待定 |
| `initialize` / `notifications/initialized` | 旧形态握手 | 仅老形态；现代形态已删除 |
| `resources/subscribe` | 老形态的资源变更订阅 | **M19 定案：不实现**。原文细节存档：入参 `{uri}`，**文档页没有给响应体**（只在流程图写 "Subscription confirmed"）；触发 `notifications/resources/updated`（入参 `{uri}`，原文说它可能是"所订阅资源的子资源"） |
| `resources/unsubscribe` | 老形态取消订阅 | ⚠️ **`2024-11-05` 文档页里完全没有这个方法**，只有官方 **JSON Schema** 里有 `UnsubscribeRequest`（params 仅 `uri`）→ 若我们要落地它，依据是 schema 而非文档页（页面自称 schema 是 source of truth），需在实现说明里标注 |
| `ping` | 连通性 | 仅老形态；现代形态**已移除** |
| `logging/setLevel` | 日志级别 | 仅老形态；现代形态**已移除**，改为逐请求 `_meta` 带 `logLevel`，且服务端**不得**对未带该字段的请求发 `notifications/message` |

### 6.2 结果通用要求（现代形态强制，老形态不适用）

- 所有结果**必须**带 `resultType`：普通结果 `"complete"`，多轮往返的中间结果 `"input_required"`。
- `tools/list`、`prompts/list`、`resources/list`、`resources/read`、`resources/templates/list` 的结果**必须**带 **`ttlMs`**（新鲜度提示，毫秒）与 **`cacheScope`**（`public` / `private`）——少这两个字段即不合规。
- 服务端**应当**在每个结果的 `_meta` 里带上自身 `serverInfo`；客户端**应当**逐请求带 `clientInfo`。
- **老形态响应里不要加这些现代字段**（老客户端可能严格校验未知字段而报错）。

### 6.3 错误的两层模型（照规范，不要混用）

| 层 | 场景 | 表达 |
| --- | --- | --- |
| **协议错误** | 工具名不存在、请求体不合 schema、服务端内部错误 | JSON-RPC `error`（如 `-32602`、`-32603`） |
| **工具执行错误** | 上游 API 失败、入参校验失败（格式 / 范围）、业务逻辑错误 | 正常 result 里 `isError: true`，把可自我纠正的信息放在内容里 |

**原则**：能被模型看懂并自我纠正的（入参不合法、上游返回 4xx/5xx）一律走 `isError: true`；只有请求本身结构不对才走 JSON-RPC error。

### 6.4 变更通知怎么送到客户端（**M19 已定案：不主动推**）

> **定案（2026-10-14）**：**三种形态都不主动推送变更通知。** 客户端要新数据就自己来请求 —— 调 `tools/list` / `resources/list` / `prompts/list`，或重新触发 `/mcp`（重连、重新 `initialize`）。**中间因为"客户端缓存与最新配置不一致"造成的代价由用户自己承担**（例如菜单里短暂挂着已停用的工具）。
>
> 因此：capabilities **不声明** `listChanged`，也**不声明** `subscribe`；`subscriptions/listen` **不需要实现**（客户端若仍调用，按未知方法返回 `-32601` 并给出原因说明）。这是对客户端**诚实**的信号：没声明，它就不会指望我们推。

**留一个未来改进（不进本轮）**：若以后要加实时刷新，形态一/形态三各有现成通道（见下表），届时再声明 `listChanged` 即可 —— **改造成本主要在于"是否愿意维护订阅状态"**，而不是协议本身。

**下面是这个决策的场景推演（供以后回头参考）**：

1. 用户在控制台**停用 / 删除了一个工具**（或改了工具名）；
2. 某个 MCP 客户端**已经连着**我们的 MCP Server，它启动时调过一次 `tools/list`，把工具列进了自己的菜单；
3. 客户端**不会**再主动问（多数客户端拉一次列表就长期用缓存）→ 菜单里还挂着这个**已经不存在的工具**，用户点了就失败；
4. 若想让服务端**主动**发一条 `notifications/tools/list_changed` 去修好它，服务端手上必须**有一条现成的通道** —— 而客户端此刻**没有任何正在进行的请求**可以搭车。三形态的通道能力如下：

| 形态 | 有现成通道吗 | 原因 |
| --- | --- | --- |
| 形态一 | ✅ **天然有** | 客户端一连上就挂着一条 `GET /sse` 常驻流，服务端随时能往里塞 |
| 形态三 | ✅ **订阅后就有** | 客户端想收通知必须先发 `subscriptions/listen`（POST，响应是长流）—— **客户端显式发起** |
| 形态二 | ⚠️ **不一定有** | 常驻推送流要客户端**主动开** `GET /mcp`，而**规范没写"什么时候该开"**（没说"你得一直挂着"）→ 客户端不开，我们就**一条通道都没有** |

**⚠️ 硬约束**：**不得在形态二撒谎声明 `listChanged: true`** —— 声明了却推不出去，客户端会一直显示过期的工具列表，且**永远不会自愈**（因为它以为你会通知它），比不声明糟糕得多。

> 反直觉但成立：**形态一的通知能力最强**（常驻流天然存在），比更新的形态二还占便宜；但**我们这一轮三形态统一选择不推**，保持行为一致、实现最省。

---

## 7. 控制台配置 → MCP 定义的映射（本文核心）

| 控制台上的东西 | 变成 MCP 的什么 | 备注 |
| --- | --- | --- |
| 工具列表「MCP 工具」名 | `tool.name` | 规范建议 1–128 字符、仅 ASCII 字母数字与 `_ - .`、服务内唯一 → **保存工具时就要校验**（控制台侧） |
| 工具描述 | `tool.description` | 直接来自「编辑工具」页描述 |
| 参数表：MCP 参数名 / 类型 / 必填 / **MCP 对该参数的描述** | `inputSchema` 的属性、`required`、`description` | **「编辑工具」那 8 张参数面板本质就是在设计 `inputSchema`**，描述字段是现成的 |
| 路径参数（只读派生） | 同样进 `inputSchema` | 控制台侧自动生成，不由用户填 |
| 「非空」开关 | **协议 schema 表达不了** | 落在服务端调用前校验；不满足 → `isError: true`（不走协议错误） |
| 请求头 / Cookie / Query 参数三套（结构一致） | 均为 `inputSchema` 属性，区别只在服务端把它们放回 HTTP 的哪个位置 | 三者复用同一模型 |
| 请求体参数（None / Form Data / Json） | `inputSchema` 属性；示例 JSON 用于文档与调试 | 「xml 同 json」按 Json 处理；plain text、GraphQL 本期不管 |
| 服务级「鉴权方式」映射 | **不进任何对外定义** | 仅服务端转发时注入，防止把 token 暴露给模型 |
| 响应体「成功 / 成功\|失败」 | 决定返回一个内容块还是按结果给不同内容 | |
| 响应体内容类型（Json / XML / HTML / Plain Text / Blob） | 映射为 MCP 内容块类型，见第 8 章 | **存在无法 1:1 映射的情况** |
| 导入时「MCP 类型推断」= Tool / Resource | 决定这条 HTTP 端点上架成 `tools/*` 还是 `resources/*` | 设计稿里已有这一列，说明上架类型是一等概念 |
| 工具 / 资源「启用状态」 | 是否出现在 `list` 结果里 | **状态变化不推通知**（M19 定案）；客户端下次 `list` 时自然生效 |
| 路径改写规则（全保留 > 去域名 > …） | **只影响实际转发 URL**，不影响对外 MCP 名称 | 对外名称用「MCP 工具」名 |
| 上游接口**是否存在** | **不在上架 / 保存时确认**，改在**被调用时**确认（9.2） | 与管理面"导入步骤② 检查接口信息 100% 前端"一致；调用时不可达 → `isError: true` |
| 上游**协议类型**（预留位） | 决定调用链要不要"先建长连接"（9.3） | 本期只实现 `http`；枚举预留 `http-stream` / `websocket` 等 |

---

## 8. 二进制通道（**M5 已定案**）

### 8.1 问题（MCP 的承载能力）

设计稿响应体给了 6 组内容类型（音视频 / office / 压缩包 / PDF / 图片 / 网页与数据）+ URL/Base64 切换；但 MCP 的承载能力是：

| 通道 | 能装什么 |
| --- | --- |
| `tools/call` 的 `content` | 现代形态 **5 种**：`text`、`image`、`audio`、`resource_link`、内嵌 `resource`；**`2024-11-05` 只有 3 种**（无 `audio`、无 `resource_link`）→ **必须按客户端版本裁剪，见 8.6** |
| `resources/read` 的 `contents` | `text` 或 **`blob`（base64）**，可一次返回多条 |

**结论：不存在通用的「任意二进制」内容块。** 只有 `image` / `audio` 能直接内联；**PDF、office（doc/xls/ppt）、压缩包（zip/rar/7z/tar/gz）没有内联通道**，必须走 `blob` 或"托管 + 链接"。

### 8.2 定案 · 输入侧（模型 → 我们 → 上游）

**参数级配置二选一**（用户在工具参数上选）：

| 配置 | 模型传什么 | 我们做什么 | 给上游什么 |
| --- | --- | --- | --- |
| **`url → blob`** | 一个 **URL** | **服务端代理下载**（本机拿到字节流） | **blob**：按配置以 `multipart/form-data` 文件部分（字段名 / 文件名可配）或 `application/octet-stream` 提交 |
| **`base64 → blob`** | **base64 字符串** | 解码成字节流 | 同上 |

**⚠️ `url → blob` 必须做 SSRF 防护**（URL 来自模型，是**不可信输入**）：

- 协议白名单：只允许 `http` / `https`（禁 `file:` / `gopher:` / `ftp:` 等）；
- 地址黑名单：环回 / 私网 / 链路本地 / **云元数据地址**（如 `169.254.169.254`）；
- 限制重定向次数（建议 ≤3）且**每次跳转都重新校验**；
- **大小上限 + 超时**，超限直接 `isError: true`（不截断）；
- 可选域名白名单（更严）。

> 这条不做，就是"让模型用我们的服务器去探内网 / 打云元数据"的口子，必须在设计里写明。

### 8.3 定案 · 输出侧（上游 → 我们 → 模型）

**每个接口 / 响应在配置里选 `uri` 或 `base64`**：

| 配置 | 返回什么 | 前置依赖 |
| --- | --- | --- |
| **`base64`** | 内嵌 `resource.blob`（base64） | 无额外依赖；**超过阈值给尺寸警告**（大文件会撑爆模型上下文与带宽），阈值可配 |
| **`uri`** | **不内嵌字节**，返回指向托管地址的引用 | **二选一**：① **本机存储**（需要对外**流下载**接口，见 8.4）② **S3**——选 S3 时**必须提示「agent 所在网络需能访问该 S3」** |

两种形态都出现在 **`resources/read`** 与 **`tools/call`** 结果里，形如：

```json
{
  "content": [{
    "type": "resource",
    "resource": {
      "uri": "artifact://report-123",
      "mimeType": "application/pdf",
      "blob": "JVBERi0xLjQK..."
    }
  }]
}
```

> ✅ **已按官方原文 + JSON Schema 逐字核对**，`uri` 方案的可行形态如下（下面每条都有原文依据）：

**① 工具结果里给"链接"→ 用 `resource_link`（⚠️ `2025-06-18` 才引入）**

```json
{ "type": "resource_link", "uri": "https://cdn.example.com/artifacts/report-123?sign=...", "name": "report-123.pdf", "mimeType": "application/pdf", "size": 1048576 }
```

- **必填**：`uri`、`name`、`type`；**可选**：`title` / `description` / `mimeType` / **`size`** / `annotations` / `_meta` / `icons`；
- 原文："A tool **MAY** return links to Resources…the tool will return a URI that can be subscribed to or fetched by the client"；
- ⚠️ 原文还有一句别忽略："Resource links returned by tools are **not guaranteed** to appear in the results of a `resources/list` request." → **别假设它一定出现在资源列表里**。

**② ⚠️ `resources/read` 不能只回 uri（这条改变了方案预期）**

- `contents` 元素**只有两个分支**：`{uri, mimeType?, text}` 或 `{uri, mimeType?, blob}` —— **没有 `resource_link` 分支，也没有"只给 uri 不给内容"的形态**（原文未提供第三分支）；
- 所以**只要走 `resources/read`，就一定会内嵌内容**（text 或 base64 blob）→ **`uri` 方案不能靠 `resources/read` 省流量**；
- `uri` 方案的真正机制是**客户端直接抓取**。原文："Alternatively, if the scheme of `uri` is `https://`, clients may fetch the resource directly from the web."，并给了取舍原则：
  > "Servers **SHOULD** use this scheme only when the client is able to fetch and load the resource directly from the web on its own—that is, it doesn't need to read the resource via the MCP server. For other use cases, servers **SHOULD** prefer to use another URI scheme, or define a custom one…"

  → ✅ **M22 已定案：就按这条原文来** —— `uri` 方案 = **`https://` + 短 TTL 签名 URL，让客户端直接抓取**；`2025-06-18` 以下客户端**没有 `resource_link`**（见 8.6）→ 降级为「文本里给 URL」或走 `resources/read`。

**③ `size` 字段正好合我们的需求**（两版描述逐字相同）

> "The size of the raw resource content, in bytes (i.e., **before base64 encoding** or any tokenization), if known. This can be used by Hosts to display file sizes and estimate context window usage."

- 只存在于 `Resource` 与 `resource_link` 上；**`resources/read` 的 contents 元素里没有 `size`**。

**④ "不要内嵌大文件"的规范禁令或阈值：不存在。** 没有 `maxItems`、没有大小上限、没有 SHOULD NOT —— **尺寸阈值与警告是我们自己的产品决策**（见 A23），别在文档里写成"规范要求"。

**⑤ base64 与 text 的边界（原文明确）**：`blob` 是 base64（"A base64-encoded string representing the binary data of the item."，schema 带 `"format": "byte"`）；`text` 分支原文明说 "This must only be set if the item can actually be **represented as text (not binary data)**" → **二进制必须走 `blob`，不要塞 `text`**，这正好支持我们否决"统一塞 text"的方案。

**⑥ `resources/read` 里不能返回 `image` / `audio` 块** —— `contents` 联合只有 Text/Blob 两个分支。所以**图片、音视频若作为 Resource 上架，也只能以 `blob` + `mimeType` 返回**（原文示例：`{ "uri": "file:///example.png", "mimeType": "image/png", "blob": "..." }`）。`image` / `audio` 块**只**出现在 `tools/call` 结果（以及 `prompts/get` 的消息）里。

### 8.4 ⚠️ 本机存储方案**缺的接口（必须补进设计）**

你指出"接口少了"—— 本机存储要对外提供**流下载**能力，至少缺这些：

| 缺口 | 内容 | 归属 |
| --- | --- | --- |
| **下载端点** | `GET /artifacts/{id}` 流式返回，建议支持 `Range` 断点续传（大文件友好）；带 `Content-Type` / `Content-Disposition`；**鉴权（Token）+ TTL 过期**；防路径遍历 | **对外**（模型 / 客户端要能取到） |
| **URI 形态** | ✅ **已定案（M22）**：**`https://` + 短 TTL 签名 URL**，客户端**直接抓取**（有原文依据：scheme 为 `https://` 时客户端可自行 fetch，且 SHOULD 只在"客户端能自己取"时用）。**降级规则**：`2025-06-18` 以下无 `resource_link` → 文本里给 URL 或走 `resources/read`。若客户端**网络不可直达**（内网部署 / agent 访问不到 S3）→ 只能自定义 scheme + 走 `resources/read`，而它**必然内嵌 base64** → **必须给尺寸警告** | ✅ **M22 已定案** |
| **生命周期** | 生成时机（调用时落盘）、TTL、清理策略（定时清理 / 引用计数） | 对外 + 运维 |
| **管理面配置** | 存储后端（本机 / S3）、S3 连接信息、大小阈值、TTL、清理周期 | **管理面 v0.2**（见 A23） |

**下载端点与统一响应体的关系**：它是**二进制流**，不套 `{code, data}` 信封（见管理面 A1 的例外条款）。

### 8.5 已否决的备选

- 二进制统一转 base64 塞进 `text` → 炸上下文，且客户端认不出这是可下载文件，**否决**；
- 只支持图片 / 音视频内联 → PDF / office / 压缩包正是 OA 场景的常见产物（设计稿专门画了分组），砍掉很快返工，**否决**。

### 8.6 ⚠️ 内容块类型必须**按客户端版本裁剪**（原文核对新增的硬规则）

| 内容块 / 字段 | `2024-11-05` | `2025-03-26` | `2025-06-18` | `2025-11-25` | `2026-07-28` |
| --- | --- | --- | --- | --- | --- |
| `text` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `image` | ✅ | ✅ | ✅ | ✅ | ✅ |
| 内嵌 `resource` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `audio` | ❌ | ✅ **引入** | ✅ | ✅ | ✅ |
| `resource_link` | ❌ | ❌ | ✅ **引入** | ✅ | ✅ |
| 内容块上的 `_meta` | ❌ | ❌ | ✅ **引入** | ✅ | ✅ |

**必须实现的行为**：

- **绝不能**给 `2024-11-05` 客户端返回 `resource_link` 或 `audio` —— 它那版 `CallToolResult.content` 的联合类型里**只有 `text | image | 内嵌 resource`**，返回多余类型可能被拒绝或解析失败；
- 同理**不要**给 `2025-03-26` 客户端返回 `resource_link`；
- 因此 **`uri`（链接）输出方式在老客户端上必须降级**：`2025-06-18` 以下只能用「文本里放 URL」或「让客户端走 `resources/read`」；**在 `2024-11-05` 上无法给出真正的资源链接** —— **M22 定案后的落地规则**：`resource_link` + 签名 URL **只在 `2025-06-18` 及以上使用**，`2025-03-26` 与 `2024-11-05` 一律降级为文本 URL；
- 好消息是**我们总是知道对方版本**：形态一必然是 `2024-11-05`；形态二在 `initialize` 里协商得到；形态三在每个请求的 `_meta.protocolVersion` 里 → **按版本裁剪完全可行**，不存在"不知道对方是谁"的问题。

---

## 9. 调用链、惰性校验与长连接余量

### 9.1 调用链（`tools/call` 一次调用的完整路径）

```
1. 定位 era：现代 → 校验版本头 ↔ body _meta 一致、Mcp-Method / Mcp-Name 匹配
             旧版 → 校验会话有效、握手已完成（且不带现代头）
2. 鉴权：Token → 作用域；目标工具是否在可见范围内（否则 403）
3. 取定义：工具 + 所属服务的 URL 前缀 + 鉴权参数映射
4. 拼请求：路径参数替换 → Query/Cookie/Header 落位 → 请求体按类型序列化（json / form-data）
5. 注入服务级鉴权参数（模型不可见的那部分）
6. 外发上游 HTTP 请求（超时与重试策略见 M9）
7. 映射响应：按响应定义的内容类型转 MCP 内容块（第 8 章）
8. 回包：现代 → 在请求的响应里回，并按需补 resultType / ttlMs / cacheScope
        旧版 → 形态一走该会话 SSE 流、形态二走该请求的响应
9. 失败处理：上游失败 / 入参校验失败 → result + isError:true；请求结构错误 → JSON-RPC error
10. 审计日志：调用方 Token、**被调用的工具 / 资源及其所属服务**、耗时、上游状态码、成功与否
    （不记录模型传入的敏感值；形态一的 sessionId 要脱敏）
    ⚠️ 这条字段要求是为**未来改进**预留的：日志要能**按工具 / 资源维度**检索"是否曾被调用 + 调用次数 + 最后调用时间"，
    以支持控制台在**删除时提醒**（见 M21）。建表时就要带这些维度，否则以后查不出来。
```

### 9.2 惰性校验（**不在上架时探测上游，改在调用时确认**）

**决策**：工具 / 资源**保存、上架、导入时，一律不去请求上游确认"这个接口存在"**；只有**被调用时**才确认。

| 阶段 | 做什么 | 不做什么 |
| --- | --- | --- |
| 导入 / 保存工具 | **只做本地静态校验**：MCP 名称合法且唯一（M10）、必填项齐备、能否生成 `inputSchema` | ❌ 不发任何探测请求；❌ 不校验上游路径是否真存在 |
| 上架 / 启用 | 只改状态位 | ❌ 不探活 |
| **被调用时** | **确认上游是否存在 / 可达** → 不可达或 `404` 一律转成 **`isError: true`**（工具执行错误，**不是**协议错误），文案要让模型能读懂（如"上游接口不存在或不可达"） | — |
| 显式测试 | **只有**控制台的「测试」按钮才真的外发一次请求（对应管理面 v0.2 的 A19 待确认项） | — |

**为什么这么定**：

- 批量导入 N 个接口时不产生 N 次探测（否则导入 200 个接口＝200 次外发，还可能把上游打挂）；
- 上游短暂抖动**不影响**配置动作的成败，控制台的"保存"永远是本地事务；
- 与管理面已确认的结论一致：导入流程的"步骤② 检查接口信息"**100% 前端**（管理面 v0.2 第 6 章），后端在导入/提交阶段同样不探测。

**代价（要认下）**：错误**延后暴露** —— 用户可能到第一次调用才发现路径写错。

**缓解（建议列为后续增强，不进本轮）**：控制台给工具 / 资源加一个「**从未验证 / 最近验证成功 / 最近验证失败**」的展示位，由「测试」按钮或真实调用结果回写；这是纯管理面状态，不改变本条设计。

### 9.3 长连接上游的设计余量（**M20 已定案：本期只留位，不实现**）

> **定案（2026-10-14）**：本期**不实现**任何长连接上游；只做下面列出的"留位"动作，保证以后加得进来、不返工。

**背景**：被转发的上游不一定都是"一次请求一次响应"。还可能是：

| 上游形态 | 例子 | 与 MCP 的冲突 |
| --- | --- | --- |
| 流式 Streamable HTTP | 上游返回 SSE / 分块长响应 | MCP 一次调用只有一条响应，但上游要持续吐数据 |
| **WebSocket** | 双向长连接 | MCP 的工具调用是**一次性**的，**无法**在一次调用里保持连接 |
| 其他长连接 | gRPC streaming、消息队列订阅 | 同上 |

**核心约束（这是留余量的原因）**：MCP 侧没有"连接"这个一等公民，一次 `tools/call` 结束后就没有载体了。跨调用的状态**必须**变成**显式句柄**——`2026-07-28` changelog 原文正是这么要求的：

> "Servers that need cross-call state use explicit, server-minted handles passed as ordinary tool arguments."（SEP-2567）

**因此未来的承载方式（现在只需留位）**：

1. **单向流**（上游 SSE / 分块）：在该次调用的 **MCP 响应流**上推进度与分片 —— 现代形态天然支持请求域 SSE（3.3）；老形态推到会话流 / 该请求的流。
2. **双向长连接**（WS）：拆成 **`open` / `send` / `recv` / `close` 多个工具**，用**服务端铸造的连接句柄**当普通工具参数传递（如 `connectionId`）；或把"服务端推来的新数据"暴露成 **Resource** —— 但**走订阅就需要实现 `subscriptions/listen`，与 M19 定案冲突**，属未来变更，届时要一并评估。
3. 建连、心跳、断线重连、鉴权注入时机（建连时注入而非每次调用）、句柄 TTL 与回收，都要显式设计。

**本期我们要留的"位"（改造成本最低的几处）**：

- 工具定义里预留**上游协议类型**字段（现状只有 HTTP 方法 + 路径），枚举值预留 `http` / `http-stream` / `websocket` 等；
- 调用链（9.1）预留**"建立连接"阶段**（现在只有"外发请求"一步）；
- **会话 / 句柄存储抽象成同一套**——连接句柄和 MCP 会话一样都是服务端状态，直接复用 M12 定的存储方案，避免以后再来一套；
- 二进制 / 大内容的 Resource 通道（第 8 章）与流式上游天然契合：流式产物直接落成 Resource，不需要新机制。

---

## 10. 兼容性、era 分流与部署影响

### 10.1 修订版时间线（官方原文核对，共 5 个修订版）

| 修订版 | 关键变化 | era | 对应形态 |
| --- | --- | --- | --- |
| **`2024-11-05`** | **最初版**：`stdio` + **HTTP+SSE 双端点** —— `GET /sse` 建流，服务端**先发 `endpoint` 事件**告知该往哪 POST；客户端消息一律 `POST` 到该地址，服务端消息以 SSE `message` 事件下发 | Legacy | 形态一 |
| `2025-03-26` | 引入 Streamable HTTP 取代 HTTP+SSE，**旧 SSE 传输自此弃用** | Legacy | 形态二 |
| `2025-06-18` | 引入 `MCP-Protocol-Version` 请求头（此前没有这个头） | Legacy | 形态二 |
| `2025-11-25` | 仍为会话制；允许服务端在 SSE 响应流上发 JSON-RPC 请求 | Legacy | 形态二 |
| **`2026-07-28`** | **当前版**：删协议级会话与 `Mcp-Session-Id`、删 `initialize`/`notifications/initialized` 握手、删 `GET` 流端点、删 SSE 续传；新增 `server/discover`、`subscriptions/listen`、MRTR、`Mcp-Method`/`Mcp-Name` 必需头；移除 `ping`/`logging/setLevel` | **Modern** | 形态三 |

> ⚠️ **`2026-07-28` 的发布状态待确认**：该修订版在规范站上可访问、changelog 完整，但**未见到明确的"已正式发布"标注**（核对时也提示了这一点）。→ 实现前建议确认一次。即使它仍是草案，本设计按"现代形态"实现也是**向前兼容**的（现代字段是增量添加的）。
>
> ⚠️ **文档页与 JSON Schema 不一致时以 Schema 为准**（核对时实际撞到 3 处）：`2026-07-28` 的 `Resource` 清单漏列 `annotations`/`_meta`；`2024-11-05` 的 `Resource` 清单漏列 `size`；`ReadResourceResult` 标 `ttlMs` 必填但同页示例没有。

### 10.2 M1 定案（决策留痕）

| 方案 | 端点形态 | 兼容到的客户端 | 结论 |
| --- | --- | --- | --- |
| ① 只做现代版 | 单端点 `POST /mcp` | 仅 `2026-07-28`+ | 否 |
| ② dual-era | 单端点 + 握手分流 | `2025-11-25` 及以后 | 否 |
| **③ 兼容到 `2024-11-05`** | **三种形态并存**（含 `GET /sse` + `POST /messages`） | **`2024-11-05` ~ `2026-07-28` 全部** | **✅ 已选** |

**选它的理由**：客户端群横跨多个修订版时，只做现代版会"一个老客户端都连不上"，而老客户端没有 fail-forward 机制、无法自愈。

**它的代价（要在排期里认下）**：

1. 形态一/形态二有状态 → 多实例部署需要共享会话存储或粘性路由（10.4）；
2. 要为已弃用的传输写代码，且规范声明未来修订版可直接移除（长期要留迁移计划）；
3. 三种形态 × 5 个修订版 = **回归测试矩阵变大**（M15）。

### 10.3 era 差异矩阵（实现对照表）

| 特性 | 形态一 `2024-11-05` | 形态二 `2025-03-26`~`2025-11-25` | 形态三 `2026-07-28` |
| --- | --- | --- | --- |
| 端点 | `GET /sse` + `POST /messages` | `POST /mcp` + `GET /mcp`（可回 `405`）+ `DELETE`（仅会话章节，可回 `405`） | 仅 `POST /mcp` |
| 请求的响应位置 | **该会话的 SSE 流**（POST 只回 `202`） | 该请求的响应（JSON 或该请求的 SSE 流） | 同左 |
| 请求体能否是 *response* | 不能 | **能**（服务端可发请求，客户端需回） | **不能** |
| 请求体批处理 | 不适用 | `2025-03-26` 允许数组；**`2025-06-18` 起收窄为单条** | 单条 |
| 版本协商 | `initialize` 握手 | `initialize` 握手 | 逐请求 `_meta`，**无握手** |
| 会话 | SSE 连接即会话 | `Mcp-Session-Id`（可选；缺头 SHOULD `400`、过期 MUST `404`、客户端收到 `404` MUST 重新 `initialize`；ID MUST 仅可见 ASCII；**不得用于鉴权**）→ ⚠️ **本项目不下发（M16 定案）**，客户端若带来则忽略 | **无会话** |
| `MCP-Protocol-Version` 头 | 无（不能要求） | `2025-06-18` 起**必需**；缺失时服务端**应假定 `2025-03-26`**；非法值 → `400` | **必需** |
| `Mcp-Method` / `Mcp-Name` 头 | 无 | 无 | **必需** |
| 不请自来的通知通道 | 直接在该 SSE 流推（**天然有通道**） | 走 `GET /mcp` 流（**客户端不开就没通道**，见 M19） | **必须**走 `subscriptions/listen`（客户端订阅后才有） |
| 通知能力是否使用 | ❌ 不用（M19） | ❌ 不用（M19） | ❌ 不用（M19） |
| 资源变更订阅 | 无（直接推） | `resources/subscribe` / `unsubscribe`（⚠️ `unsubscribe` 仅见于 schema） | `subscriptions/listen` |
| 是否实现订阅方法 | ❌ 不实现（M19） | ❌ 不实现（M19） | ❌ 不实现（M19） |
| `resource_link` 内容块 | ❌ **无此类型**（content 只有 text / image / 内嵌 resource） | `2025-06-18` 起有 | ✅ 有 |
| `audio` 内容块 | ❌ 无 | `2025-03-26` 起有 | ✅ 有 |
| 内容块上的 `_meta` | ❌ 无 | `2025-06-18` 起有 | ✅ 有 |
| 资源 `size` 字段 | ✅ 有（在 schema 里；文档页漏列） | ✅ 有 | ✅ 有（`resource_link` 上也可用） |
| 资源不存在错误码 | `-32002` | `-32002`（现代版原文称"早期版本用的就是这个码"） | `-32602`（客户端应兼容接受 `-32002`） |
| 不支持版本时错误码 | `-32602` | `-32602`（沿用老版；**2025 三版的这一点未核到原文**，实现前需单独确认） | `-32022` `UnsupportedProtocolVersionError` |
| SSE 续传（`Last-Event-ID`） | 不适用 | **支持** | **不支持**（断了就换新 id 重发） |
| `resultType` | 无 | 无 | **必需** |
| `ttlMs` / `cacheScope` | 无 | 无 | 5 个方法**必需** |
| `ping` | 有 | 有 | **已移除** |
| `logging/setLevel` | 有 | 有 | **已移除**（改 `_meta.logLevel`） |
| 服务端 → 客户端请求 | 有（sampling / roots） | 有 | 改为 **MRTR** |
| 取消方式 | 显式发 `notifications/cancelled` | 显式发 `notifications/cancelled`（**关流不算取消**） | **只能**关流 |
| `server/discover` | 无 | 无 | **必须实现** |
| 传输状态 | **已弃用** | 已被现代版取代 | 当前版 |

### 10.4 会话状态与部署影响（选③的直接后果）

#### 先回答一个容易搞混的问题：会话是**谁和谁**之间的？

**是「我们（MCP Server）↔ 调用我们的 MCP 客户端」之间的**，跟被转发的上游服务**完全无关**，跟控制台也无关。

**它解决的具体问题**：HTTP 是**无状态**的，但 `initialize` 握手是**有状态**的 ——

1. 客户端发 `initialize`（说：我支持 `2025-06-18`、我叫 Claude Desktop、我有这些能力）；
2. 我们回一个响应（说：我也支持 `2025-06-18`、我有 tools / resources）；
3. **这次谈成的结果需要留到后面每个请求用**（版本会决定行为差异，比如 `2025-11-25` 的 GET 流轮询、Origin 403）；
4. 但下一个 `tools/list` 是一个**全新的 HTTP 请求**，我们怎么知道它和刚才那次握手是同一个客户端？→ 于是下发 `Mcp-Session-Id`，客户端后续每个请求都带上它，我们据此**查回**"这次握手谈成了什么"。

> 一句话：**会话 ID = "这次握手谈成了什么"的寄存柜钥匙。** 它**不是**鉴权手段（规范原文明确 `MCP Servers MUST NOT use sessions for authentication`），也**不是**跟上游的连接。

#### M16：那"强制 / 不强制"到底差在哪？

差在**客户端不带会话 ID 时我们能不能服务它**：

| | **不下发** ✅ 已定案 | 强制（未采纳） |
| --- | --- | --- |
| 我们下发会话 ID 吗 | **不下发**（客户端自然就不会带） | 下发，并要求后续都带 |
| 客户端不带 ID 请求 | **正常服务**（因为我们不依赖握手结果） | **回 `400`** |
| 多实例扩容 | **随便扩**，任一实例都能处理任一请求 | 需要**共享会话存储或粘性路由** |
| 依赖 Redis | 形态二**不需要** | 需要 |
| 代价 | 我们**必须**保证行为不依赖握手结果 | 扩容与运维成本 |

**关键判断：我们依赖握手结果吗？**

| 握手会给我们的东西 | 我们用不用 |
| --- | --- |
| 协商版本 | ❌ 不需要 —— 版本**每个请求的头里都带**（`MCP-Protocol-Version`），逐请求判断即可 |
| 客户端能力（sampling / roots / elicitation） | ❌ 不需要 —— **我们从不发服务端请求**（3.5） |
| 我们的能力声明 | ❌ 不需要 —— 每次 `list` 都按 Token 可见范围**实时算** |

→ **定案（M16）：形态二做成无状态。** 我们仍然**必须**应答 `initialize`（老规范要求），但**不保存**它的结果。

**定案（M16）：不强制会话、且干脆不下发**（客户端若带了不明会话 ID 就**忽略**，不要回 `404`，因为我们从未下发过）。这样形态二几乎和形态三一样好扩容。

#### 各形态的部署影响

- **形态一**：`GET /sse` 连接即会话 → 会话与**具体实例**强绑定；网关必须支持长连接且不能中途换实例。**`POST /messages` 必须回到持有那条 SSE 流的实例**（要么粘性路由，要么共享注册表 + 消息转发）→ **形态一才是 M12 的真正需求方**。
- **形态二**：**已定案不下发会话 ID** → **无状态**；不需要共享会话存储、不需要粘性路由。
- **形态三**：无状态 → 可自由横向扩容。
- 会话 TTL 与断连清理策略必须显式设计（否则内存泄漏 / 陈旧会话）。

---

### 10.5 目标客户端兼容性（文档核对；含"未找到"标注）

> 2026-10-14 核对。**四个目标客户端（Dify / Codex / OpenClaw / WorkBuddy）全部核完，无待补项**；结论全部带原文出处，**官方没写的一律标 ❌未找到，不猜**。

| 客户端 | MCP 角色 | 支持传输 | 自定义 header / 鉴权 | 是否声明协议修订版 |
| --- | --- | --- | --- | --- |
| **OpenClaw** | **客户端 + 服务端**（`openclaw mcp serve` 仅 stdio） | ✅ `stdio` / `SSE` / `Streamable HTTP` 全支持；⚠️ **省略 transport 时默认 `sse`** | ✅ `headers`（示例 `Authorization: Bearer <token>`）+ OAuth + mTLS | ❌ **未找到**（6 个官方页面无版本号、无 `protocolVersion`） |
| **腾讯 WorkBuddy** | **客户端**（"连接器"底层即 MCP）；**服务端角色 ❌未找到** | 企业连接器 Transport 字段示例 **`streamable_http`**；桌面端官方只说"与 MCP 配置类似"（未列枚举） | ✅ 三选一：MCP OAuth 2.1 / OAuth 2.0 / **API Key（Header Name 默认 `X-API-Key`，可设为 `Authorization`）** + 自定义请求头 | ❌ **未找到** |
| **Dify**（v1.6.0 起原生） | **客户端 + 服务端**（v1.6.0 起"双向内置"） | 客户端：**只支持 HTTP 传输** —— `SSE` ✅ / `Streamable HTTP` ✅ / **`stdio` ❌**；⚠️ **按 URL 末段选传输** | ✅ 自定义 header（示例 `Authorization: Bearer <token>`）；v1.16.0+ 支持动态透传 `{{request.headers.X-Custom-Auth}}`；OAuth 默认 DCR | ⚠️ **上限 `2025-06-18`**（源码常量：`LATEST_PROTOCOL_VERSION = "2025-06-18"`，支持集 `{2024-11-05, 2025-03-26, 2025-06-18}`，`DEFAULT_NEGOTIATED_VERSION = "2025-03-26"`；客户端侧 `2025-06-18` 支持 **2025-10-27 就已合入**，服务端侧到 **2026-07-17 / v1.16.0** 才发版）→ **不认识 `2025-11-25` / `2026-07-28`** |
| **OpenAI Codex** | 客户端（CLI / IDE 扩展 / ChatGPT 桌面端**共享同一份配置**） | ✅ `STDIO` + ✅ `Streamable HTTP`；**未列旧式 HTTP+SSE**（❌未找到明确否认原话）；**配置里没有 `transport` 键** | ✅ `http_headers` / `env_http_headers` / `http_headers_helper` / `bearer_token_env_var`；OAuth（CIMD / DCR） | ⚠️ **文档无版本号**，但**代码里直接 vendored `2025-03-26`**（硬证据，见 10.6）；**明确读 `initialize` 响应的 `instructions`** |

**原文摘引**：

- OpenClaw 默认传输：「Set to `"streamable-http"` to select this transport; **when omitted, OpenClaw uses `sse`**」——https://docs.openclaw.ai/cli/mcp/transports
- OpenClaw 三种传输：「Give it a unique name and pick a transport: **Streamable HTTP**, **SSE**, or **Stdio**.」——https://docs.openclaw.ai/tools/mcp
- OpenClaw 对状态会话的处理：「If a legacy SSE message endpoint returns HTTP 404, or a **stateful Streamable HTTP session expires**, OpenClaw retires that connection and reconnects on the next discovery. The failed call is reported **without replaying it**」——同上
- OpenClaw 出站 MCP 随 release **`2026.3.31`** 落地（维护者对 issue #29053 的结案评论）——https://github.com/openclaw/openclaw/issues/29053
- WorkBuddy 连接器即 MCP：「连接器是 Tencent WorkBuddy 与外部系统之间的桥梁。它基于标准化协议（如 **MCP**）将外部服务的能力引入到 AI 工作流中」——https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Connector
- WorkBuddy 企业连接器认证：「**MCP OAuth 2.1** …／**OAuth 2.0（Authorization Code）**…／**API Key** — **Header Name**：HTTP 请求头字段名（默认 `X-API-Key`），也可设为 `Authorization` 等其他值。」——https://intl.cloud.tencent.com/zh/document/product/1316/84536
- Dify 只支持 HTTP 传输：「Only MCP servers with **HTTP transport** are supported.」——https://docs.dify.ai/en/cloud/use-dify/workspace/tools
- Dify **按 URL 末段选传输**（源码 + issue）：「In `api/core/mcp/mcp_client.py`, `MCPClient._initialize` picks the transport by the **last segment of the URL path**」（代码为 `if method_name in {"mcp", "sse"}:`，否则先试 sse 再回退 streamable http）——https://github.com/langgenius/dify/issues/39301 ；另：「The MCP client chooses SSE or streamable HTTP based on the URL path, so the URL needs to be an MCP server endpoint, **not a normal API base path**.」——https://github.com/langgenius/dify/discussions/38053
- Dify 协议版本上限（源码常量）：「`LATEST_PROTOCOL_VERSION = "2025-06-18"` / `SERVER_SUPPORTED_PROTOCOL_VERSIONS: frozenset[str] = frozenset({"2024-11-05", "2025-03-26", "2025-06-18"})` / `DEFAULT_NEGOTIATED_VERSION = "2025-03-26"`」——https://raw.githubusercontent.com/langgenius/dify/main/api/core/mcp/types.py
- Dify v1.6.0 发布说明：「Note: HTTP-based MCP servers only. **Protocol version 2025-03-26**.」（2025-07-10）——https://dify.ai/blog/v1-6-0-built-in-two-way-mcp-support
- Codex 配置位置与传输字段：「Codex stores MCP configuration in `config.toml` … By default this is `~/.codex/config.toml`, but you can also scope MCP servers to a project with `.codex/config.toml` (trusted projects only).」；`url` = "Endpoint for an MCP **streamable HTTP** server."；`http_headers` = "Static HTTP headers included with each MCP HTTP request."；`startup_timeout_sec` 默认 **10s**、`tool_timeout_sec` 默认 **60s**——https://learn.chatgpt.com/docs/config-file/config-reference
- Codex 两类传输：「**STDIO servers**: Servers that run as a local process…／**Streamable HTTP servers**: Servers that you access at an address. + **Bearer token authentication** + OAuth…」——https://learn.chatgpt.com/docs/extend/mcp?surface=cli
- Codex 读 `instructions`：「Codex reads the MCP `instructions` field returned during **initialization** and uses it as server-wide guidance alongside the server's tools.」＋「Keep the **first 512 characters** self-contained」——同上
- Codex 支持 streamable HTTP 的版本：「- Add support for **streamable HTTP** MCP servers (#4317)」（`0.44.0`；`0.42.0` 时仍只支持 stdio，`0.45.0` 起免实验开关）——https://github.com/openai/codex/releases/tag/rust-v0.44.0

**对我们的形态要求（硬结论）**：

1. **形态一（HTTP+SSE 双端点）必须做** —— OpenClaw **省略 transport 时默认就是 SSE**，且明确支持 legacy SSE（`GET /sse` + `POST /messages`）→ **直接验证了 M1 选方案③的必要性**（若只做现代版，这类客户端开箱即连不上）。我们已覆盖（3.1）。
2. **Streamable HTTP（形态二 / 三）也必须做** —— OpenClaw 显式支持 `streamable-http`，其 CLI 示例 URL 形如 `https://mcp.example.com/mcp`；WorkBuddy 企业连接器 Transport = `streamable_http` → 与我们单端点路径建议 `POST /mcp`（M2）吻合。
3. **自定义 header / Bearer Token 鉴权必须支持** —— 两家都能带自定义头（OpenClaw `Authorization: Bearer …`；WorkBuddy API Key 可把 Header Name 设为 `Authorization`）→ 我们"静态 Bearer Token 先行"（M3）可行，**不必被迫先做 OAuth**。⚠️ 但 WorkBuddy 企业侧首选 **MCP OAuth 2.1**，长期要补。
4. **⚠️ 两家都没声明协议修订版** —— OpenClaw 六个官方页面里没有 `2024-11-05`/`2025-*`/`2026-07-28` 任何字样，也没有 `protocolVersion` / `MCP-Protocol-Version` / `Mcp-Session-Id`（只有"stateful session 会过期"这类行为描述）。→ **结论：不能指望客户端"报名版本"，只能按它怎么开场来分流** —— 这正是 3.4 分流表与 8.6 版本裁剪的**唯一可行依据**。
5. **⚠️ 大响应会被客户端截断（对 M5 / M22 的强证据）** —— 同厂商 CodeBuddy 侧给出明确数值：「When the content returned by an MCP tool exceeds `MAX_MCP_OUTPUT_TOKENS` (default: **20000 tokens**, approximately **80KB** of characters), CodeBuddy automatically processes it」，默认**写盘 + 截断**并追加 `[OUTPUT TRUNCATED - exceeded N token limit]`。→ **内嵌 base64 大文件必然被截断**（base64 还要再膨胀 ~33%）→ **强化 M22「签名 URL 让客户端直取」的方向**；**尺寸警告阈值可先按 ~80KB / 20000 tokens 量级设默认值**（可配，见管理面 A23）。（WorkBuddy 桌面端自身是否有该限制：❌ 未找到。）
6. **⚠️ 端点路径必须以 `/mcp` 或 `/sse` 结尾（Dify 的硬约束）** —— Dify 客户端**按 URL 最后一个路径段选传输**（`mcp` / `sse`），末段是别的值它会**先试 SSE 再回退**，而 1.15.0 该回退路径有个未捕获的 `httpx.ReadTimeout` 会**卡到 300s**。→ 我们的 `POST /mcp` + `GET /sse` **恰好合规**（M2 因此从"建议"升级为**硬约束**）；**绝不能**把 MCP 端点挂在带随机 id 或带 query 的路径上。形态一里我们下发给 Dify 的 `POST /messages?sessionId=…` 不受此限（那个 URL 由我们下发、不由用户填）。
7. **必须实现 `initialize` 握手** —— Codex 明确读 `initialize` 响应的 `instructions` 当服务级指引，且要求**前 512 字符自包含**；**只做形态三（无握手）会让 Codex 失效**。→ 我们的 `instructions` 该写什么，纳入字段级设计。
8. **客户端超时就是我们的硬上限** —— Dify 默认请求超时 **30s**、SSE 读超时 **300s**；Codex 默认启动 **10s**、**工具调用 60s**。→ 上游请求默认超时**必须短于 60s**（否则 Codex 先放弃），长任务要**尽早建 SSE 流 + 发 `notifications/progress`** 或心跳保活。（M9 据此给出推荐值：**上游默认 30s**。）
9. **⚠️ 协议版本上限：Dify 只认到 `2025-06-18`**（源码常量，支持集 `{2024-11-05, 2025-03-26, 2025-06-18}`），Codex 文档**无版本声明** → **没有任何已核客户端声明支持 `2025-11-25` / `2026-07-28`**。→ 结论：**形态二是当前主战场，形态一是兼容层，形态三（现代）目前只是"面向未来"**。M1 方案③不变，但**优先级 = 形态二 > 形态一 > 形态三**。
10. **Codex 未列旧式 HTTP+SSE**（也未明确否认）→ 形态一主要是为 **Dify / OpenClaw** 服务，别指望 Codex 走形态一。另：**Dify 自己的 MCP Server 是"单 `POST`、无 GET/SSE、无 session"的极简实现**（源码观察）→ 侧证**无状态单 POST 工程上完全可行**（我们 M16"不下发会话"因此不冒险），但**不能反推客户端能接受什么** —— 服务端的实现自由度与客户端的兼容要求是两件事，别混淆。

**未找到清单（明确缺失，不是没查）**：OpenClaw 的协议修订版声明、`Mcp-Session-Id` 头名、`MCP-Protocol-Version` 头；WorkBuddy 的协议修订版声明、`Mcp-Session-Id` 头名、**WorkBuddy 作为 MCP 服务端**的任何官方说明、桌面端 MCP 专属限制（并发 / 超时 / 数量上限）、连接器引入的确切起始版本（changelog 最早只到 `5.2.3` / 2026-07-15）。

**⚠️ 同名 / 归属风险（需你确认）**：`workbuddy.ai` 域下同时托管 **CodeBuddy IDE / CLI / Agent SDK** 的文档（与 WorkBuddy 桌面应用是**不同产品面**）—— 上文凡引用 CodeBuddy 的内容都已标注归属。另：同名游戏引擎（Captain Claw 重制）不在 `openclaw/openclaw`，而在 `pjasicek/OpenClaw`；`openclaw/openclaw` 现为 AI 助手仓库（约 391.9k stars）。

### 10.6 按「接入 MCP 时间」反推的版本窗口（补"官方不声明版本"的缺口）

**为什么这样做**：四家官方文档几乎都不声明协议修订版（见 10.5）。唯一可用的间接证据是**它们接入 MCP 的时间点** —— MCP 修订版是按日期发布的，**接入当时能见到的最新版就是它的下限**。

**推算基准（修订版发布时间，见 10.1）**：`2024-11-05` → `2025-03-26` → `2025-06-18` → `2025-11-25` → `2026-07-28`。

| 客户端 | 最早接入 MCP 的证据 | 接入时"当时最新"的修订版 | 推断可及窗口 | 证据性质 |
| --- | --- | --- | --- | --- |
| **Dify** | **2025-03-14**（`dify-plugins` PR **#138** `mcp_sse`、#139 mcp agent、#141；**#167「mcp server」2025-03-19**）→ 原生双向 **v1.6.0 / 2025-07-10**；客户端侧 `2025-06-18` 支持 **2025-10-27** 合入 | ⚠️ **`2024-11-05`** —— 2025-03-14 时 **`2025-03-26` 还没发布**！→ 7 月转为 `2025-06-18` | **`2024-11-05` ~ `2025-06-18`** | ✅ **实证**（源码支持集常量 + 插件起点日期） |
| **OpenAI Codex** | **2025-05-02**：commit `83961e0` / PR **#787**「feat: introduce mcp-types crate」**直接把 `2025-03-26/schema.json` vendor 进代码**；stdio 客户端 PR **#829（2025-05-06）** | **`2025-03-26`** | **`2025-03-26` ~ `2025-06-18`**（上限为推断：`0.44.0`/2025-10-03 加 streamable HTTP 时，已发布的最新版是 `2025-06-18`） | ✅ **下限硬证据**（vendored schema）／⚠️ 上限推断 |
| **OpenClaw** | **2025-11-27**：commit `85f53a4`（当时项目名还是 clawdbot，"add recommended MCPs section"）；原生出站注入 **2026-03-07**（PR #39337）；⚠️ **`2026.3.31` 只是"remote HTTP/SSE 传输 + `streamable-http` 选择"落地，不是起点** | `2025-11-25`（**发布仅 2 天**，实际很可能先按 `2025-06-18` 起步） | **≤ `2025-11-25`**；**省略 transport 时默认 `sse`** + 处理 legacy SSE 会话 → **至少覆盖 `2024-11-05`** | ⚠️ 推断（**起点日期已修正**） |
| **腾讯 WorkBuddy** | **2026-03-09**（上线当日官方口径"内置…**MCP 协议**"）／官方 changelog **4.6.0（2026-03-19）**"新增 MCP 服务集成面板"；**CodeBuddy 侧 2025-05-13**（"中国首个支持 MCP 的代码助手"） | `2025-11-25`（`2026-07-28` 尚未发布） | **≤ `2025-11-25`**；**接入时间在 `2025-11-25` 发布之后** → **很可能就说这一版**；企业侧 `Transport = streamable_http` → 形态二 | ⚠️ 推断 |

**⚠️ 用这张表的三条铁律（不一起看就会误用）**：

1. **接入时间只能给"下限"，不能证明"上限"** —— **Dify 是铁证**：它直到 **2026-07-17（v1.16.0）** 才补上 `2025-06-18` 的版本协商与结构化输出，而此时 `2025-11-25` 已发布约 8 个月 → **"见得到新版本" ≠ "会跟进新版本"**。
2. **`2026-07-28`（形态三）在四家接入时都还不存在** —— 最晚接入的 WorkBuddy 是 **2026-03-09**，比它早 **四个多月** → **形态三没有任何接入期证据**，属纯"面向未来"。
3. **`2024-11-05` 有多处实证，不是历史包袱** —— ① **Dify 插件起点 2025-03-14 时 `2025-03-26` 还没发布**，那时唯一存在的修订版就是 `2024-11-05`，且该插件自述就是 "HTTP with **SSE** transport"；② Dify 源码支持集**明确包含** `2024-11-05`；③ OpenClaw **默认就走 `sse`** → **形态一是 Dify 的"起点形态"**。
4. **⚠️ 对 M24 的精确化（谁真的会受损）** —— **Codex 直接把 `2025-03-26` schema vendor 进代码**，所以即使我们按 M17 把它当 `2025-03-26`（缺头默认值），**一点也不损失能力**；**真正会因 M24 受损的是 Dify** —— 它的客户端**能到 `2025-06-18`**（2025-10-27 合入），却**疑似不发版本头** → **M24 的取舍实质就是"要不要为 Dify 记住协商版本"**。

**交集结论（对排期的净影响）**：

| 版本窗口 | 覆盖谁 | 取舍 |
| --- | --- | --- |
| **`2025-03-26` ~ `2025-06-18`** | **四家共同可及**（Codex **vendored** `2025-03-26`、Dify 上限 `2025-06-18`） | **"四家共同语言" = 形态二的主战场** |
| `2024-11-05` | Dify（**插件起点即此版**）+ OpenClaw（默认 SSE） | 形态一：**必做**（M1 已定） |
| **`2025-11-25`** | OpenClaw（2025-11-27 接入，**发布后 2 天**）/ WorkBuddy（2026-03-09 接入，**发布后 3.5 个月**）→ **从"推断"升级为"很可能真会有客户端说"** | **做，并按"真会被说"对待**（无声明，仍不换功能依赖） |
| `2026-07-28` | **无人**（四家最晚接入 2026-03-09，早 4 个多月） | 形态三：**设计保留、本期不排期**（2026-10-14 确认，优先级最低） |

---

## 11. 待确认清单

| 编号 | 事项 | 推荐值 / 说明 |
| --- | --- | --- |
| ~~**M1**~~ | ~~目标 era~~ | ✅ **已定案：方案③，兼容到 `2024-11-05`**（三种形态全做）。⚠️ **补充（2026-10-14）**：**`2026-07-28` 现代形态"设计保留、本期不排期"** —— 四家目标客户端在接入时它都还不存在（最晚的 WorkBuddy 早 4 个多月），优先级最低，但**设计不做裁剪**，文档继续完整保留 |
| M2 | MCP 端点路径 | ⚠️ **已升级为硬约束（有客户端级证据）**：**必须**是 `POST /mcp`（形态二 / 三）与 `GET /sse`（形态一 + `POST /messages`）—— **Dify 按 URL 最后一个路径段选传输**（`mcp` / `sse`），末段为其他值会先试 SSE 再回退，且 Dify 1.15.0 该回退有未捕获 `httpx.ReadTimeout` 会卡到 300s。→ **绝不能**把 MCP 端点挂在带随机 id 或 query 的路径上；与管理面 `/api/v1` 分开 |
| M3 | Token 形态与签发 | 静态 Bearer Token 先行；OAuth 授权规范与「访问管理」页设计一起补 |
| ~~**M4**~~ | ~~是否提供变更通知~~ | ✅ **已随 M19 定案：都不提供** —— 不声明 `listChanged` / `subscribe`，不实现 `subscriptions/listen` |
| ~~**M5**~~ | ~~二进制通道~~ | ✅ **已定案（第 8 章）**：**输入侧**参数级选 `url→blob`（服务端代理下载，**必做 SSRF 防护**）或 `base64→blob`；**输出侧**选 `base64`（内嵌 blob，**≥阈值给尺寸警告**）或 `uri`（**本机存储 / S3 二选一**；S3 需提示"agent 网络要能访问"）。**新增 M22（URI 形态与下载端点）** |
| M6 | 资源 URI 方案 | 建议自定义 scheme（如 `mcp-rest-bridge://{service}/{tool}`），避免用 `https://` 让客户端绕过鉴权直取 |
| M7 | 是否提供 `prompts` 能力 | 首页有「提示词 1」计数但无设计，需产品补 |
| M8 | 是否提供 `outputSchema`（结构化输出） | 建议工具返回 JSON 时同时给 `structuredContent` + 文本 |
| M9 | 上游超时 / 重试 / 限流 | 规范要求服务端做限流与超时。**客户端给了硬上限**：Codex 工具超时默认 **60s**、Dify 请求超时默认 **30s**（SSE 读 300s）→ **建议上游请求默认超时 30s**；长任务走 SSE + `notifications/progress`；重试只对幂等请求、按 Token 维度限流 |
| M10 | 「MCP 参数名」冲突校验 | 同一工具内 MCP 参数名唯一性需在**保存工具时**校验 |
| M11 | SSE 与网关配置 | 三种形态都涉及 SSE：需关闭反代缓冲（`X-Accel-Buffering: no`）、放宽读超时、允许长连接 |
| ~~**M12**~~ | ~~会话存储方案~~ | ✅ **已定案：存储后端可配置，三选一** —— ① Redis ② DB ③ **本进程全局变量（明确不支持分布式存储：仅单实例或粘性路由下可用）**。TTL 与断连清理策略随所选后端一起定；**M16 已定案不下发会话 → 只有形态一需要它** |
| **M13** | 形态一 `sessionId` 日志脱敏 | 老规范的 `sessionId` 出现在 URL 里，访问日志 / 监控 / 报错信息都要脱敏 |
| **M14** | 老形态的能力声明结构 | `initialize` 响应里的 capabilities 结构与现代 `server/discover` **不同**，需按老版原文实现 |
| **M15** | 三形态回归测试矩阵 | 需准备覆盖 5 个修订版的可测客户端（或 SDK）各一个，否则"兼容"无法验收 |
| ~~**M16**~~ | ~~形态二是否**强制**要求会话~~ | ✅ **已定案：不下发会话 ID**（= 不强制）—— 形态二**无状态**：`initialize` 仍必须应答但不保存结果；客户端若带不明 `Mcp-Session-Id` 则**忽略**（不回 `404`，因为我们从未下发过）。**连带后果：只有形态一需要 M12 的存储**（3.4 分流表、10.4 已同步） |
| ~~**M17**~~ | ~~缺 `MCP-Protocol-Version` 头时怎么办~~ | ✅ **已定案：照规范假定 `2025-03-26`**（对老客户端更友好）；**非法值仍必须回 `400`** |
| **M18** | 形态二 `DELETE /mcp` 的成功码 | 规范**未规定**（只规定不支持时 MAY `405`）→ 我们自己定（建议 `204`）并写进联调文档 |
| ~~**M19**~~ | ~~形态二的变更通知怎么办（**具体场景见 6.4**）~~ | ✅ **已定案：三形态都不主动推变更通知** —— 客户端自己来请求（`*/list` 或重新触发 `/mcp`），**不一致的代价由用户承担**；capabilities 不声明 `listChanged` / `subscribe`，`subscriptions/listen` 不实现。场景推演保留在 6.4 供以后回头参考 |
| ~~**M20**~~ | ~~长连接上游余量做到什么程度~~ | ✅ **已定案：本期只留数据位与调用链位（9.3），不实现长连接上游** |
| **M21** | 审计日志的**检索维度**（为"删除时提醒"预留） | 未来改进：控制台删除工具 / 资源 / 服务时，若操作日志显示**曾被调用过**则提醒（次数 + 最后调用时间）。→ **本期只需日志表带"工具 / 资源 / 所属服务"维度**，否则以后查不出来（见 9.1 第 10 步、管理面 A22） |
| ~~**M22**~~ | ~~`uri` 方案的 URI 形态与下载端点~~ | ✅ **已定案：`https://` + 短 TTL 签名 URL，客户端直接抓取**（有原文依据，见 8.3 ②）。**降级规则**：`2025-06-18` 以下无 `resource_link` → 文本给 URL 或走 `resources/read`（8.6）。**仍需补的接口**：8.4 的下载端点（`GET /artifacts/{id}`：流式、`Range`、TTL、鉴权、防遍历）+ 签名 URL 的签发与时钟偏差处理 |
| **M23** | **真实客户端实测**（重点：形态二"不下发会话"） | ⏳ **待做**：四家**都未声明协议修订版**；OpenClaw 文档提到"stateful Streamable HTTP session 过期"，Dify 客户端源码实现了 session / 续传 / `DELETE` / GET 流（**对 GET 流失败容忍**，记 non-fatal 日志）→ 需**实测形态二"不下发 `Mcp-Session-Id`" + "GET 流回 `405`"是否被接受**；若实测有问题，改动很小（改为"下发但不强制"，一行配置）。同时把四家的传输 / 鉴权差异并进 **M15 回归矩阵** |
| ~~**M24**~~ | ~~形态二"无会话"下如何记住协商版本~~ | ✅ **已定案（(b) + 配置兜底）**：① 用 **Token 维度记"最近协商版本"轻缓存**（**不下发会话 ID、不用于鉴权**，纯兼容缓存 → 不违反 M16 精神）；② **更关键的是加一个用户配置项**「**是否接入生成文件的接口**」来定我们的**方言上限**：**是 → `2025-06-18`；否 → `2025-03-26`**。**生效规则：`有效版本 = min(客户端协商版本, 配置方言上限)`** —— 例：Codex 说 `2025-03-26` + 我们开了文件接口 → 仍按 `2025-03-26`（**绝不给它发 `resource_link`**）；Dify 说 `2025-06-18` + 没开文件接口 → 按 `2025-03-26` 输出（**不发新内容块**）。→ **配置来源已定案：本期走 `env` 文件**（管理面 A24 已定，控制台不新增接口），键名 / 生效规则 / 运维注意见 **4.4** |

---

## 12. 下一步

1. ~~逐页对齐老形态原文~~ ✅ **已完成** —— `2024-11-05` / `2025-03-26` / `2025-06-18` / `2025-11-25` / `2026-07-28` 的相关页面（含官方 JSON Schema）已逐页核对，结论回填进 3.1 / 3.2 / 4.2 / 5 / 6.1 / 10.3，并单独列出「规范自身的 5 处空白」；
2. ~~定 **M12（会话存储）**~~ ✅ **已定案**：后端可配置三选一（Redis / DB / 本进程全局变量，后者不支持分布式）；**M16 也定了"不下发会话"**，故只有形态一需要它；
3. ~~定 **M16 / M17**~~ ✅ **已定案**：M16 = **不下发会话 ID**（形态二无状态）、M17 = **缺版本头假定 `2025-03-26`**；连带 **M22** 也定了（`https://` + 签名 URL）；
4. ~~定 **M5（二进制通道）**~~ ✅ **已定案**（第 8 章）：输入侧 `url→blob`（+ SSRF 防护）/ `base64→blob`；输出侧 `base64`（尺寸警告）或 `uri`（本机 / S3）；
5. **只差你确认 A1 的 3 条假设**（管理面 v0.2 的 3.1：是否镜像 HTTP 状态码 / 字段集 / 分页位置）→ 确认后两边文档标准即可对齐；
6. 字段级设计（`inputSchema` / `outputSchema` 生成规则）单独出一版；
7. 准备 **M15 的三形态回归测试矩阵**（各修订版的可测客户端样本）；
8. 需要时把本文的 MCP 方法清单同步进 Apifox 或单独的联调清单；
9. **数据库双支持评估**（MySQL / PostgreSQL）另起一版：[`数据库兼容性评估.md`](./数据库兼容性评估.md) —— 与本篇的交叉点：**M12**（会话存储选 DB 会引入 `DELETE … LIMIT` 方言差异，选 Redis 可规避）、**M5/M22**（二进制不进库，规避 `LONGBLOB` vs `bytea`）、**M21/A22**（审计日志表的时间索引与归档必须两库同构）、**M24/A24**（配置走 env，少一张表）
