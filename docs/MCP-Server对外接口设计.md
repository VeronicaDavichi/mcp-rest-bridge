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
| v0.1 修订17 | 2026-10-15 | 鱼鱼 | **C 类契约矛盾 6 条定案 + 参数四态口径**：① **C1** 兼容性稿「配置不进库」限定为 **env-only**（页面可管的策略配置进 `mcp_config`）；② **C2** 错误码规则 = **存在性检查可判定 → `40002`；依赖 `version`/`status` 前置条件 → `40901`**，裸 `409` 全改 `40901`；③ **C3** 上游凭据**默认由调用方在工具入参里传**（设计图 #13 鉴权块本就带 `MCP 参数名` + `MCP 描述` 两列）→ **推翻本稿 §7 原「鉴权不进任何对外定义」**，改判为"**由 `固定值` / `允许调用方覆盖` 两开关决定是否进 `inputSchema`**"；④ **C4** 本期**限流与配额都不做**（删 M9 的「按 Token 维度限流」，`42901` 转预留）；⑤ **C5** 导入格式与版本**解耦**（JSON/YAML × 2.0/3.0/3.1，按内容 `swagger:` / `openapi:` 探测版本），导出只出 **3.1**；⑥ **C6** 形态三**本期做、分两期**（一期传输层 + `server/discover`，二期 MRTR）→ **推翻修订15 的「本期不排期」**；⑦ **新增 §7.1「参数四态的唯一真源」**（`required` / `nonEmpty` / `value` / `overridable`）；⑧ caps **补记不声明 `logging`**。⚠️ **诚实声明**：C7 原报的「13 章声明 `logging` ✅」经复核是**假账**（本稿只到 12 章、全文无此声明），**已撤销**，仅保留"显式写明不声明"这个残留项 |
| v0.1 修订18 | 2026-10-15 | 鱼鱼 | **`resources` 与 `prompts` 本期范围定案（设计保留、不实现）**：① **`prompts` 不声明能力** —— `prompts/list` / `prompts/get` 回 `-32601`（**M7 结案**；理由：设计稿只有首页「提示词 1」计数与导航入口、**无功能设计**，**声明空能力 = 撒谎**）；② **`resources` 彻底不声明能力** —— `resources/list` / `resources/templates/list` / `resources/read` **全回 `-32601`**（**M6 关闭为本期不适用**）；③ ⚠️ **已认下的代价，并写进第 8 章开头**：`resources/read` 本是 **M22（二进制通道）在内网场景的唯一兜底** → **该兜底整体推迟到二期**；本期文件交付只走 `resource_link` + `https://` 短 TTL 签名 URL（`2025-06-18` 及以上）或**文本 URL 降级**，**agent 网络到不了 S3 / 本机存储时无退路**；④ 同步改动：6 章能力表、6.1 方法表、8 章范围变更声明、8.3 ② 降级路径、8.6 矩阵降级规则、M6 / M7 行 |
| v0.1 修订19 | 2026-10-15 | 鱼鱼 | **D 批次开工：`backend.mcp` 定案 + SDK spike 实证回写（5 条新发现）**：① 🔴 **§5 新增「列表过滤 ≠ 鉴权」** —— 官方 SDK javadoc 明确"被过滤隐藏的原语**仍可按名直调**"，权限**必须**在处理器内再校验 → **`tools/call` 必须重查可见性**，并给出验收用例；② **§6 新增 `logging` 行**：SDK **关不掉**该能力（`McpSchema.java:987` Builder 初值即启用），主人拍定 **方案 ① 接受**，文档如实写明"**接受 `setLevel` 并返回空结果、不发送 `notifications/message`**"（规范 MUST 是**单向**的 → 不构成撒谎）；③ **§6 新增三条实现约束**：工具名须**管理台自校验**（`^[A-Za-z0-9_\-.]+$`、1~128 —— 运行时 `addTool` 实测**零校验**）、能力是**构建期快照**（`tools` 删空仍声明）、**必须显式传 `capabilities(...)`** 且 `tools(false)` 落实 M19；④ **§10.6 新增自研范围注**：`mcp-core` jar 内 `Discover` 命中 **0** → `server/discover` / `Mcp-Method` / `Mcp-Name` / `_meta.protocolVersion` / MRTR **全自研**；⑤ **§6.1 补 `logging/setLevel` 落地口径**。选型详情见仓库根 **`stack-decision.md`**（v0.6） |
| v0.1 修订20 | 2026-10-16 | 鱼鱼 | **P2 真传输探针回写（报文级实证）**：① **§6 `logging` 行补报文实证** —— 探针抓到的真实 `initialize` 响应确为 `"capabilities":{"logging":{},"tools":{"listChanged":false}}`，**`logging` 确实被发给了客户端**（我们只传了 `tools(false)`），`tools.listChanged=false` 正确（M19）；② **M9 行 + §10 客户端结论第 8 条同步收紧超时上限** —— 官方 **MCP Java SDK 客户端**的 `requestTimeout` 默认 **20s**（`McpClient.java:167/604`）⇒ **真正的硬上限是 20s 而非 30s**，**建议上游默认超时下调为 ≤15s** 并把 `数据库设计.md` 的 `mcp.upstream_timeout_ms` 默认值从 `30000` 同步下调（**待拍板**，见 [`设计决策记录.md`](./设计决策记录.md) §5 #14）；③ 同一探针另实证：**逐请求 Token 过滤在真传输上生效**（S2）、**被隐藏的工具按名直调仍会执行**（**D1 实锤**）、**`server/discover` 回 `-32601`**（**D5 实锤**）、**形态二/三同挂 `POST /mcp` 时后者静默失效**（**D6**）→ 详见 [`stack-decision.md`](../stack-decision.md) **§6.8** |
| v0.1 修订21 | 2026-10-16 | 鱼鱼 | **超时上限拍板 + era 分流 Router 方案跑通**：① ✅ **主人拍板：上游默认超时 = 15s** —— 由「官方 SDK 客户端默认 **20s**」这个更紧的硬上限推出（**原建议 30s 作废**），`数据库设计.md` 的 `mcp.upstream_timeout_ms` 默认值**已由 `30000` 改为 `15000`**；**M9 行**与**§10 客户端结论第 8 条**同步改判；⚠️ 并写明**上游超时 ≠ 端到端上限**（上游真需 >15s 的场景走**异步 + `notifications/progress`**，不是调大该值）；② ✅ **Router 方案已跑通**（探针 profile `router`）—— 单个 `EraRouterServlet` 独占 `POST /mcp`，按 **`Mcp-Method`** 头**直接调 `service()`** 派发（**不能用 `forward`**：provider 以 `getRequestURI().endsWith(endpoint)` 判路径）。实测：`initialize`（无头）→ **形态二**（返回 `Mcp-Session-Id`）、`Mcp-Method: tools/list` → **形态三**（返回 **`tool_gamma`**、**无会话头**）、`server/discover` → 形态三（`-32601`）⇒ **三形态（SSE + 形态二 + 形态三）同进程共存可落地**。详见 [`stack-decision.md`](../stack-decision.md) **§6.8.3** |
| v0.1 修订22 | 2026-10-16 | 鱼鱼 | **❗ 改判：方言上限开关归 `page-managed`（撤销 A24 的 env 口径）** —— 主人拍板「放管理台/数据库」：① 开关改为 **`mcp_config.mcp.file_interface_enabled`**（键名/默认值见 [`数据库设计.md`](./数据库设计.md) §12.3），**管理台可改、热生效、可按租户覆盖**（`租户级 > 全局 tenant_id=0 > 内置默认`）；② **`MCP_DIALECT_MAX` 作废** —— 它与开关**表达同一件事**，两个旋钮会让"谁说了算"无解 → 方言上限固定为 **`有效版本 = min(客户端协商版本, 由开关推导出的方言上限)`**；③ env 只保留 **`MCP_NEGOTIATED_VERSION_TTL`**（纯运维参数，不给管理员改）；④ 同步 **§4.4**（正文 + `ini` 块 + 运维注意）与 **M24 行**。⚠️ **改判原因留痕**：这是**同一事实在两处有两种写法**（根因 ②）—— 本稿 §4.4 写 env（10-14），数据库稿 §12.3 写 page-managed（后来），二者**生效范围与生效时机都不同** → 已登记为 [`设计决策记录.md`](./设计决策记录.md) **§6.5 C17** |
| v0.1 修订23 | 2026-10-21 | 鱼鱼 | **#13 关闭：能力按租户工具集构造，空则不声明 `tools`** —— ① 把 **§6 实现约束 2** 重写为**四条定案规则**：**工具集为空 ⇒ 完全没有 `tools` 键**（不是 `{}`，声明空能力 = 对客户端撒谎）、**非空 ⇒ `{"listChanged": false}`**、**按租户构造**（不能挂 `McpServer` 单例）、**握手后定格**（工具增删需客户端重新握手 → 写进用户手册「限制」）；② 明确 **「`tools/list` 实时过滤」与「能力定格」不冲突**（**列表实时、能力定格**）；③ 连带把 `数据库兼容性评估.md` 的 MySQL 版本口径统一为 **`8.0.30+`**（原文「8.0.13+」作废，与 §9 Q1 对齐） |

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

**配置来源（✅ 2026-10-16 改判 —— 撤销 A24 原「走 `env` 文件、控制台不新增接口」）：归 `page-managed`** —— 即 `mcp_config` 表的 **`mcp.file_interface_enabled`**（键名与默认值见 [`数据库设计.md`](./数据库设计.md) **§12.3**），**管理台「配置」页可改、热生效、可按租户覆盖**（生效链：租户级 > 全局 `tenant_id=0` > 代码内置默认）。**读取侧仍要做配置源抽象**（`mcp_config` → 测试替身），别把取值散落在业务代码里。
>
> ⚠️ **同时作废 `MCP_DIALECT_MAX`**：它与那个开关**表达的是同一件事**（都是"方言上限"），**两个旋钮会让"谁说了算"无解** → **只保留开关**。方言上限的推导固定为：**`有效版本 = min(客户端协商版本, 由开关推导出的方言上限)`**。

```ini
# ✅ 2026-10-16 改判：方言上限的开关已挪到管理台（mcp_config.mcp.file_interface_enabled）
#    本代码块只剩下面这一个「纯运维参数」—— 它不该给管理员改。

# Token 维度「最近协商版本」轻缓存的 TTL（纯兼容缓存，不参与鉴权）
MCP_NEGOTIATED_VERSION_TTL=24h
```

> ⚠️ **运维注意（✅ 2026-10-16 改判后）**：方言上限开关在**管理台**改 → **热生效**（`mcp_config` 缓存失效，多实例走 Redis 广播；见 [`数据库设计.md`](./数据库设计.md) §12.3 规则），**不再需要重启**；只有 `MCP_NEGOTIATED_VERSION_TTL` 仍在 env，改它**要重启**。**启动日志必须打印实际生效的方言上限** —— 否则排障时无法区分"客户端不发版本头"和"开关配错了"。

---

## 5. 鉴权与可见范围

- **三种形态共用同一套 Token 模型**：Bearer Token，**Token 绑定可见范围**（能看见哪些服务 / 工具 / 资源 / 提示词）。
- 传输方式建议 `Authorization: Bearer <token>`；具体签发与作用域模型挂在控制台「访问管理」页（该页无设计，待补，见 M3）。
- **协议明确允许**：`tools/list` / `resources/list` 的结果**可以随请求携带的授权变化**（只返回该调用方作用域内的条目），但**不得**按连接变化、也不得作为其他请求的副作用变化。→ 我们按 Token 过滤可见范围**合规**。
- 🔴 **列表过滤 ≠ 鉴权（`2026-10-15` SDK 实证 → 必须在处理器内二次校验）**：官方 SDK 的 `McpSyncListFilter` javadoc 原文明确 —— *"A primitive hidden by this filter is omitted from listings **ONLY**. It remains reachable through its own endpoint: **a hidden tool called by name still executes**. Permissions **MUST** be enforced in the primitive's handler."* → **`tools/list` 的过滤只影响"看得见"，`tools/call` 仍可按名直调**。故 **`tools/call` 必须在处理器内重新校验 `mcp_token_service_scope` / 该工具的可见性**，**不得**依赖列表过滤兜底。
  - **验收用例**：用只对服务 A 有权的 Token **直调服务 B 的工具名** → 必须拒绝，且**不得发起任何上游调用**（日志需要 `WARN`）。进 M15 回归矩阵。
- 形态一/形态二的会话**不改变**鉴权模型：会话只承载协商结果，每个请求仍要带 Token（会话不能代替鉴权）。
- 鉴权与 `Origin` 校验是两件事，两者都要做。
- 未授权 / 令牌无效：`401`；令牌有效但无权访问该对象：`403`。
  - ⚠️ **唯一例外（A1 定案）**：形态一 `sessionId` **主体不匹配** → 对外回 **`404`**（不区分"会话不存在"与"会话不属于你"）。理由见 5.1。
- 敏感约束：服务的**鉴权参数**（Cookie / Header / Query / Body 里的 token 等）**不得**出现在任何对外 MCP 定义里（不暴露给模型），只在服务端转发时注入。
- **规范立场（原文核对）**：`2024-11-05` 明确写"Authentication and authorization are not currently part of the core MCP specification" → 我们的 Bearer Token 属于**实现自定义**，不违背规范；但也意味着**规范不规定**老形态下 `401` / `403` 的语义，需在联调文档里自己写清。
- **会话不能代替鉴权（原文强制）**：`2025-11-25` 安全最佳实践页明确 **"MCP Servers MUST NOT use sessions for authentication"**，并要求会话 ID 用安全随机源、不可预测、建议形如 `<user_id>:<session_id>` → 与我们"Token 才是鉴权、会话只承载协商结果"的设计一致，会话劫持面也被规范点名。

### 5.1 形态一「会话 ↔ 主体」必须绑定（**A1 定案 2026-10-15，安全强制**）

**问题**：`POST /messages?sessionId=X` 只按 `sessionId` 路由到 SSE 流，**不校验请求者是不是这个会话的主人**。而 `sessionId` 出现在 **URL** 里 → 必然进 nginx/ALB 访问日志、APM、报错页、`Referer`（**M13 自己就要求对它脱敏**，等于承认它会漏）。攻击者只需要一个**自己合法的 Token**：

```text
① 拿到受害者的 sessionId（从访问日志 / 监控 / 报错页 / 工单截图里）
② 用自己的 Token  POST /messages?sessionId=<受害者的>
   ← 鉴权用的是攻击者的 Token（他只能调自己可见的工具，这点没错）
   ← 但路由用的是受害者的 sessionId → 响应被写进【受害者的 SSE 流】
③ 受害者的客户端收到一条自己从没发过的 message 事件
```

**危害不是"收条垃圾消息"**：JSON-RPC 的 `id` 由客户端自编、通常是**小整数**（1、2、3…）。受害者手上有在途请求 `id:2`，攻击者注入的响应只要也写 `id:2` → **客户端按 id 匹配，把伪造的工具输出当成自己那次调用的结果交给模型** = **对另一个租户 agent 的提示词注入 / 响应伪造**（还能连续注入挤掉真响应，形成队列投毒）。

> **边界（不夸大）**：按本表实现后，攻击者**读不到**受害者响应、也**调不到**自己无权调的工具 → 危害是**完整性 / 越权写入**，不是直接的数据读取。但若实现者图省事写成"**会话即身份**"（`GET /sse` 已鉴权，POST 就复用会话里的身份 —— 这是最自然也最危险的写法），立即升级为**完整的跨租户冒充 + 读取**。规范写 MUST NOT 就是为这个。

**定案（4 条，缺一不可）**：

| # | 规则 |
| --- | --- |
| 1 | `GET /sse` 建会话时记录 **`{tenantId, userId, tokenId}`**；此后每次 `POST /messages?sessionId=X` 校验**请求 Token 解析出的主体与 X 的记录完全一致** |
| 2 | 不一致 → **拒绝写入该 SSE 流**；HTTP 对外统一回 **`404`**：**不区分"会话不存在"与"会话不属于你"**（回 `403` 等于告诉攻击者"这会话存在"，泄漏存在性）。**真实原因只写服务端日志 + `WARN` 告警** |
| 3 | **默认不终止会话** —— 终止等于送攻击者一个 **DoS 开关**（拿到 `sessionId` 就能反复打死受害者会话）。仅当**同一 `sessionId` 连续 N 次主体不匹配**时才拉黑 / 终止 |
| 4 | **任何路径都不得回退到"会话即身份"**（规范 MUST NOT）。`sessionId` 按**准密钥**对待：≥128 位安全随机、短 TTL、日志脱敏（M13） |

**验收用例（进 M15 回归矩阵）**：两个不同 Token + 一条 SSE 流 → 第二个 Token 的 `POST` 必须得 **`404`**，且**该 SSE 流里一条消息都不能多出来**。

> **为什么不用 `401`**：`401` = "你没通过认证"。这里攻击者的 Token **是有效的、认证已成功**，只是这个会话不是他的 → 用 `401` 会让客户端误以为"该重新登录 / 刷新 Token"，而它重试同一个 `sessionId` **永远不会成功**（会话在 `GET /sse` 时就绑定了主体）。`403` 语义正确但会泄漏存在性 → 故取 **`404`**。

---

## 6. 能力与方法清单

> 服务端能力（capabilities）在现代形态随 `server/discover` 声明，在旧形态随 `initialize` 响应声明；具体结构以对应修订版原文为准。
>
> **老形态（`2024-11-05`，已原文核对）服务端能力顶层键**：`prompts`、`resources`、`tools`、`logging`、`experimental`。其中 `tools` / `prompts` 下是 `listChanged`；`resources` 下是 `subscribe` + `listChanged`，**两者各自可选**（可都给、可都不给）。客户端能力为 `roots`（含 `listChanged`）、`sampling`、`experimental`。→ **老形态的能力声明结构与现代形态不同**，必须分版实现（M14）。

| 能力 | 是否提供 | 说明 |
| --- | --- | --- |
| `tools` | ✅ | 对应控制台「工具」；**不声明 `listChanged`**（M19 定案：不主动推变更通知） |
| `resources` | ❌ **本期不实现**（设计保留） | ✅ **定案（2026-10-15）：彻底不声明 `resources` 能力** —— `resources/list` / `resources/templates/list` / `resources/read` **全部回 `-32601`**。⚠️ **已认下的代价（不留暗账）**：`resources/read` 本是 **M22（二进制通道）在内网场景的唯一兜底**（见 8.3 ②）→ **该兜底整体改为二期**；本期文件交付**只走 `resource_link` + `https://` 短 TTL 签名 URL（`2025-06-18` 及以上）或文本 URL 降级**，**agent 网络到不了 S3 / 本机存储时无退路**（这是产品上可接受的取舍：内网部署场景本期不承诺文件交付）。控制台「资源」菜单**本期置灰 / 占位**。数据库侧已一致：`mcp_resource` 在「预留不建表」里 |
| `prompts` | ❌ **本期不实现**（设计保留） | ✅ **M7 定案（2026-10-15）**：**不声明 `prompts` 能力**，`prompts/list` / `prompts/get` 按未知方法回 **`-32601`**。**理由**：设计稿首页有「提示词 1」计数、左侧导航有「提示词」入口，但**无任何功能设计** → 与其声明一个空能力（**违反"不得撒谎声明能力"**，见 M19 与 6.4），不如**不声明**。控制台「提示词」菜单**本期置灰 / 占位** |
| `logging` | ⚠️ **由 SDK 声明，无法关闭（定案 ① 接受）** | ✅ **定案（2026-10-15）**：源码实证 `McpSchema.java:987` 的 `ServerCapabilities.Builder` **字段初值即 `new LoggingCapabilities()`**（= 启用），且 Builder **没有"关闭"的写法** → **显式传 `.capabilities(...)` 也关不掉**。**如实行为**：**接受** `logging/setLevel` 并返回**空结果**（`McpAsyncServer:1011` 会保存 min level），**但本服务端不发送 `notifications/message`** —— `McpSyncServer` 上**只有** `notifyToolsListChanged` / `notifyResourcesListChanged` / `notifyPromptsListChanged`，**没有任何发送日志通知的 API**（`LoggingMessageNotification` 仅出现在**客户端**代码）。**规范依据**：`2025-11-25` logging 页只有**单向 MUST** —— *"Servers that **emit** log message notifications **MUST** declare the `logging` capability"*，**并未**要求"声明了就必须发" → **不构成撒谎声明能力**。⚠️ **安全 MUST 联动**：若将来接入日志推送，规范强制日志 **MUST NOT** 含凭据/密钥、个人信息、可用于攻击的内部系统细节（与 A4 敏感值口径一致）。**✅ 报文实证（2026-10-16）**：探针 P2 抓到的真实 `initialize` 响应确为 `"capabilities":{"logging":{},"tools":{"listChanged":false}}` —— **`logging` 确实被发给了客户端**（我们只传了 `tools(false)`）。详见 [`stack-decision.md`](../stack-decision.md) §6.8 |

**🔴 三条实现约束（`2026-10-15` SDK 真机实证，直接决定能不能落地）**：

1. **工具名必须由管理台侧自校验** —— SDK 规格为 **`^[A-Za-z0-9_\-.]+$`、长度 1~128**（`ToolNameValidator` 源码）。⚠️ **运行时 `addTool` 完全不校验**：实测 `'bad name'` / `'中文工具'` / `'tool/1'` / `'$x'` / **200 字符** **全部"成功"**，仅**空名**被拒（`name must not be empty`）→ **非法名会静默流到客户端**。
   - ⇒ **管理面保存工具时必须按此规则校验**；**`MCP 工具名` 列只允许 ASCII**（中文只用于「模型可见的描述」）。**验收**：输入 `中文工具` → 保存被拒并提示。
2. 🔴 **能力是构建期快照 —— 定案：能力必须按「当前租户实际可调用工具集」构造（2026-10-21，#13 关闭）** —— 实测 `removeTool` 到空后**仍返回** `ToolCapabilities[listChanged=false]`：能力**不随增删回落**。四条规则：
   - **① 工具集为空 ⇒ 完全不出现 `tools` 键**（不是 `tools: {}`、更不是 `tools: {"listChanged": false}`）—— 声明空能力就是**对客户端撒谎**（与 M19 / §6.4 同一条原则）；
   - **② 非空 ⇒ `tools: {"listChanged": false}`**（M19：不主动推变更）；
   - **③ 按租户构造**：`initialize` / `server/discover` 里的 `capabilities` **必须按该 Token 所属租户当时可见的工具集**动态构造，**不能挂在 `McpServer` 单例上**（不同租户的工具集不同）；
   - **④ 握手后定格**：形一/形二在 `initialize` 之后**不重算能力**（协议没有"能力变更"通知，且已声明 `listChanged=false`）⇒ **工具增删需客户端重新握手才生效**，**必须写进用户手册「限制」**。⚠️ 这与「`tools/list` 每次按 Token 实时过滤」（§5.2）**不冲突**：**列表实时、能力定格**。
3. **必须显式传 `capabilities(...)`** —— 不传时 SDK 用 `McpServerFeatures` 的**默认值**（同样带 `logging`）。**`tools` 传 `Boolean.FALSE`** 以落实 **M19「不主动推」**：实测得 `ToolCapabilities[listChanged=false]`，且 `addTool` / `removeTool` **不会**触发客户端推送（桩传输 `notifyClients` 全程未被调用）→ **M19 定案安全**。

### 6.1 方法

> **方法可用性分 era**：下表以现代形态（`2026-07-28`）为准；`ping`、`logging/setLevel`、`resources/subscribe` 等在老形态**存在**、在现代形态**已移除**。完整差异见 **10.3 矩阵**。
>
> **老形态下 `logging/setLevel` 的落地口径（2026-10-15 定案）**：**接受请求并返回空结果**、**不发送** `notifications/message`（依据见第 6 章 `logging` 行）。⚠️ 现代形态下该方法是**已移除**的 → 回 `-32601`。

| 方法 | 用途 | 要点 |
| --- | --- | --- |
| `server/discover` | 声明支持的协议版本与服务端能力 | 现代形态**必须实现**；老形态不存在此方法 |
| `tools/list` | 列出当前 Token 可见的 MCP 工具 | 分页 + 缓存；**应当**返回确定性顺序，利于客户端缓存与提示词缓存命中 |
| `tools/call` | 调用工具 | 按工具定义拼真实 HTTP 请求（含服务级鉴权参数注入）→ 外发 → 映射响应为内容块 |
| `resources/list` | 列出可见资源 | ❌ **本期不实现**（不声明 `resources` 能力）→ `-32601`。**原文依据保留**（二期用）：分页 + 缓存 |
| `resources/templates/list` | 列出带 URI 模板的资源（如路径参数化） | ❌ **本期不实现** → `-32601`。**原文依据保留**（二期用）：参数可由 `completion/complete` 补全 |
| `resources/read` | 读取资源内容 | ❌ **本期不实现** → `-32601`。⚠️ **这是已认下的代价** —— 它本是 M22 的内网兜底（见 8.3 ②）。**原文依据保留**（二期实现时照此）：内容为 **text 或 blob(base64)**；**资源不存在：老形态回 `-32002`、现代形态回 `-32602`**（原文核对，勿混用），且**不得**返回空 contents 数组 |
| `prompts/list` / `prompts/get` | 提示词发现与取用 | ❌ **本期不实现（M7 定案 2026-10-15）**：不声明 `prompts` 能力 → 客户端若仍调用，按未知方法回 **`-32601`**（同 `subscriptions/listen` 的处理） |
| `subscriptions/listen` | 客户端按需订阅变更通知 | **M19 定案：不实现**（我们不提供变更通知，也就不必维护订阅状态）；客户端若仍调用 → 按未知方法 `-32601` 返回，并给出明确原因 |
| `completion/complete` | 参数 / 资源模板补全 | 可选，待定 |
| `initialize` / `notifications/initialized` | 旧形态握手 | 仅老形态；现代形态已删除 |
| `resources/subscribe` | 老形态的资源变更订阅 | **M19 定案：不实现**。原文细节存档：入参 `{uri}`，**文档页没有给响应体**（只在流程图写 "Subscription confirmed"）；触发 `notifications/resources/updated`（入参 `{uri}`，原文说它可能是"所订阅资源的子资源"） |
| `resources/unsubscribe` | 老形态取消订阅 | ⚠️ **`2024-11-05` 文档页里完全没有这个方法**，只有官方 **JSON Schema** 里有 `UnsubscribeRequest`（params 仅 `uri`）→ 若我们要落地它，依据是 schema 而非文档页（页面自称 schema 是 source of truth），需在实现说明里标注 |
| `ping` | 连通性 | 仅老形态；现代形态**已移除** |
| `logging/setLevel` | 日志级别 | 仅老形态；现代形态**已移除**，改为逐请求 `_meta` 带 `logLevel`，且服务端**不得**对未带该字段的请求发 `notifications/message` |

### 6.2 结果通用要求（现代形态强制，老形态不适用）

- 所有结果**必须**带 `resultType`：普通结果 `"complete"`，多轮往返的中间结果 `"input_required"`。
- `tools/list`、`prompts/list`、`resources/list`、`resources/read`、`resources/templates/list` 的结果**必须**带 **`ttlMs`**（新鲜度提示，毫秒）与 **`cacheScope`**（`public` / `private`）——少这两个字段即不合规。⚠️ **本期适用性（2026-10-15）**：这条**只对已声明能力的方法**生效 —— 本期只声明 `tools` → **实际只有 `tools/list` 受此约束**；`prompts/list` 与 `resources/*` **本期不实现**（第 6 章定案），其条目**仅为二期依据、现在不得据此写代码**。
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
>
> ✅ **补记（C7 残留项，2026-10-15）**：老形态的能力顶层键里还有 **`logging`** —— 我们**同样不声明**它，且**不实现 `logging/setLevel`**（该能力在老规范已弃用、现代规范已移除；日志级别改由逐请求 `_meta.logLevel` 表达，见 10.3 矩阵）。**理由与上一条相同：不得撒谎声明能力。**

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
| 参数表：MCP 参数名 / 类型 / 必填 / **固定值** / **MCP 对该参数的描述** | `inputSchema` 的属性、`required`、`description` | **「编辑工具」那 8 张参数面板本质就是在设计 `inputSchema`**，描述字段是现成的。**参数语义（三态 + 派生）见 §7.1（唯一真源）** |
| 路径参数（只读派生） | 同样进 `inputSchema` | 控制台侧自动生成，不由用户填；**无「必填/非空」开关**（恒必填，且 `{id}` 不能为空） |
| 「非空」开关 | ✅ **能表达**：`minLength: 1`（`string`）/ `minItems: 1`（`string[]`） | ⚠️ **原文写"协议 schema 表达不了"是错的** —— schema 能表达，**但仍必须在服务端调用前校验**（不能假设客户端会替我们校验），且**仅对 `string` / `string[]` 有意义**（对 `boolean` / `object` 标它属语义空洞） |
| **「固定值」** | 有值时**服务端转发时补齐**，调用方可不传 → **`required` 失效** | 凭据类固定值**读写同 `sensitive`**（读回 `***`）；**调用方传入的值也绝不进日志** |
| ~~「允许调用方覆盖」~~ → **「动态 / 固定」二选一** | 🔄 **2026-10-21 改判（主人原话："不存在覆盖一说"）**：**不再是独立开关** —— 「动态」勾上 ⇔ `value` 为空 ⇒ 参数**进 `inputSchema`**；不勾（= 固定）⇔ `value` 非空 ⇒ **不进 `inputSchema`** | 🔴 **"给默认值 + 允许覆盖"的组合不存在** —— 该列 UI 上**"描述"与"固定值"占同一格、互斥切换**（动态填描述、固定填固定值）；且**服务级鉴权的"必填/非空"也取消**（天然恒 1，见 §7.1） |
| 请求头 / Cookie / Query 参数三套（结构一致） | 均为 `inputSchema` 属性，区别只在服务端把它们放回 HTTP 的哪个位置 | 三者复用同一模型 |
| 请求体参数（None / Form Data / Json） | `inputSchema` 属性；示例 JSON 用于文档与调试 | 「xml 同 json」按 Json 处理；plain text、GraphQL 本期不管 |
| 服务级「鉴权方式」映射 | ✅ **C3 定案（2026-10-15）：按「固定值 / 覆盖」两开关决定** | ⚠️ **原文写"不进任何对外定义"已被推翻** —— 设计图 #13 的鉴权块**带着 `MCP 参数名` 与 `MCP 对该参数的描述` 两列**，即**凭据默认由调用方在工具入参里传**（主人原话：「那个就是为了让用户在调用的时候传的」）。**有固定值且未开覆盖 → 不进 `inputSchema`**（这才是"不暴露给模型"的正确表达方式）；**无固定值 → 必须进**，否则模型无从提供。**凭据值（无论固定值还是调用方传入）永不进日志** |
| 响应体「成功 / 成功\|失败」 | 决定返回一个内容块还是按结果给不同内容 | |
| 响应体内容类型（Json / XML / HTML / Plain Text / Blob） | 映射为 MCP 内容块类型，见第 8 章 | **存在无法 1:1 映射的情况** |
| 导入时「MCP 类型推断」= Tool / Resource | 决定这条 HTTP 端点上架成 `tools/*` 还是 `resources/*` | 设计稿里已有这一列，说明上架类型是一等概念 |
| 工具 / 资源「启用状态」 | 是否出现在 `list` 结果里 | **状态变化不推通知**（M19 定案）；客户端下次 `list` 时自然生效 |
| 路径改写规则（全保留 > 去域名 > …） | **只影响实际转发 URL**，不影响对外 MCP 名称 | 对外名称用「MCP 工具」名 |
| 上游接口**是否存在** | **不在上架 / 保存时确认**，改在**被调用时**确认（9.2） | 与管理面"导入步骤② 检查接口信息 100% 前端"一致；调用时不可达 → `isError: true` |
| 上游**协议类型**（预留位） | 决定调用链要不要"先建长连接"（9.3） | 本期只实现 `http`；枚举预留 `http-stream` / `websocket` 等 |

### 7.1 参数语义的唯一真源（✅ **C16 定案** 2026-10-15；🔄 **2026-10-21 改判为"三态 + 1 派生"**）

> **为什么要单列**：这四个开关**同时出现在两处界面** —— **服务级鉴权**（设计图 #13 → 表 `mcp_service_auth_param`）与**工具级参数**（设计图 #03/#04/#06 → `mcp_tool.request_json` 的参数对象）。**规则只写在这里**，那两处只是承载（见 [`数据库设计.md`](./数据库设计.md) §8.6 / §8.9），**不得各写一套**。

| 固定值 `value` | 状态 | `required` | `nonEmpty` | 调用方视角 | 在 `inputSchema` 里 |
| --- | --- | --- | --- | --- | --- |
| **无** | **动态** | 服务级恒 `1` / 工具级可配 `0·1` | **服务级恒 `0`** / 工具级可配 | 必须提供 | 属性存在；`1` 时进 `required` 数组；**服务级不映射 `minLength`** |
| **无** | **动态** | `0`（仅工具级可设） | **服务级恒 `0`** / 工具级可配 | 可不提供 | 属性存在、不在 `required` |
| **有** | **固定** | **DB 层：服务级恒 `1`（= 最终值必须存在）/ 工具级恒 `0`** | — | **看不到、改不了** | **完全不出现在 `inputSchema`** |

> 🔄 **2026-10-21 改判**：原表是"四态"、把 `overridable` 当独立开关；现 **`overridable` 派生**（`= value IS NULL`）⇒ **只剩三行**，"有值 + 可覆盖"那一行**已删除**。**语义分层**：DB 的 `required` 表达"**最终值必须存在**"，`inputSchema.required` 表达"**调用方必须传**" —— 固定模式下参数根本不进 `inputSchema`，故两者不冲突。

> 🔄 **2026-10-21 晚再改判（主人："非必填可空更合适"）**：**服务级 `nonEmpty` 恒 `0`** —— 允许空串、**不映射 `minLength:1`**；**`required` 仍恒 `1`**。分工：**「必须配这个参数」= `required`**、**「值可以是空串」= `nonEmpty`**。
>
> 🔴 **配套硬规则**：`value` 的 **`NULL` 与 `''` 不等价** —— `NULL` = 动态模式，`''` = 固定模式下"固定值为空串"。**派生 `overridable` 必须用 `IS NULL` 判定**，且**禁止把 `''` 归一成 `NULL`**（否则凭据会意外变成模型可见）。

**三条硬规则**：

1. **`nonEmpty` 仅对 `string` / `string[]` 生效** → `minLength: 1` / `minItems: 1`；对 `boolean` / `object` / `number` 标它属**语义空洞**；
2. **`value` 非空 ⇒ 该参数根本不进 `inputSchema`**（所以无所谓 `inputSchema.required`）；**工具级** DB 的 `required` 此时必须写 `0`；**服务级** DB 的 `required` 恒 `1`（它的语义是"最终值必须存在"，不是"调用方必须传"）；
3. **`value` 非空 ⇒ 不暴露给模型** —— 这是"**凭据不暴露给模型**"的**唯一正确表达**（原文"鉴权一律不进对外定义"既做不到、也说不通：没有固定值时模型无从提供）。

**「和/或」与必填的关系（服务级鉴权专属，🔄 2026-10-21 改判）**：服务级 `required` **恒 `1`** ⇒ `AND` 组（全部都要有值）语义完全吻合；`OR` 组（至少一个有值）**天然允许组内某参数无值** ⇒ **`OR` 组内的 `required` 按 `0` 处理**（约束是"至少一个"）。若既要"至少一个"又要"每个都有值"，那就是 `AND`。

**✅ 设计图重画清单（🔄 2026-10-21 作废 —— 见决策 #25「校验 / 错误不进设计图」+ #26「服务级天然必填」）**：

| 设计图 | 结论 |
| --- | --- |
| **#13 服务级鉴权块** | ✅ **不用加任何列** —— `必填`/`非空` **天然恒 1**、`允许调用方覆盖` **已废除** ⇒ 现有「类型 / 原参数名 / MCP 参数名 / 描述或固定值（动态切换）」**即完整** |
| **#04 / #06 工具级参数面板** | ✅ **不用改** —— 新稿已有 `必填` / `非空` / `动态` / `描述兼固定值` |

---

## 8. 二进制通道（**M5 已定案**）

> ⚠️ **本期范围变更（2026-10-15：`resources` 不实现）** —— 本章关于 **`resources/read`** 的原文核对结论是**设计依据、完整保留**，但该能力**本期不实现**（第 6 章定案）→ 由此产生三条约束：
>
> 1. **本期文件交付只有两条路**：① `resource_link` + `https://` 短 TTL 签名 URL（**仅 `2025-06-18` 及以上**）；② 更低版本（`2025-03-26` / `2024-11-05`）降级为**文本里给 URL**；
> 2. **8.3 ② 推出的降级路径「自定义 scheme + 走 `resources/read`」整体推迟到二期** → **agent 网络不可直达 S3 / 本机存储时，本期没有兜底**（**产品取舍，已知并接受**；内网部署场景本期不承诺文件交付）；
> 3. 本章 `resources/read` 相关条目（`contents` 只有 text/blob 两个分支、`size` 不在 contents 里、不得返回 `image`/`audio`）**二期实现时照此执行** —— **现在不得据此写代码**。

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

**⚠️ `url → blob` 必须做 SSRF 防护**（URL 来自模型，是**不可信输入**）。

> **A3 定案（2026-10-15）：本期做，防护做「全套」** —— 原设计只有 5 条要点、**零验收用例**，等于把口子留在设计里。以下每条都是**可二值判定**的准出条件。

| # | 要求 | 为什么必须 |
| --- | --- | --- |
| 1 | 协议白名单：只允许 `http` / `https`（禁 `file:` / `gopher:` / `ftp:` / `dict:` …） | 非 HTTP 协议能读本地文件、打内网服务 |
| 2 | **默认不跟随重定向**（`followRedirects=false`）。某服务确需跳转时由**管理员显式开启**，开启后**每一跳都重新走第 3~5 条全部校验**，上限 ≤3 跳 | 302 跳内网是最经典的绕过；默认关 = 默认安全 |
| 3 | **DNS 重解析校验（防 TOCTOU / DNS rebinding）**：先解析域名 → **校验解析出的*全部* IP** 都过第 4 条 → **用该 IP 直连**（保留 `Host` 头与 SNI），不让 HTTP 客户端再解析一次 | 只校验域名不校验 IP，等于给攻击者"第一次解析给公网、第二次给内网"的机会 |
| 4 | 地址黑名单：环回 `127/8`、私网 `10/8` `172.16/12` `192.168/16`、链路本地 `169.254/16`、`::1`、`fc00::/7`、`fe80::/10`；**云元数据** `169.254.169.254`、`100.100.100.200`（阿里云）、`metadata.google.internal` | 内网探测 + 云凭据窃取 |
| 5 | 端口限制：**默认放行 80/443**；收紧时白名单制 —— 走 **env-only** 键 **`SSRF_ALLOWED_PORTS`**（如 `80,443,8443`），**不进页面白名单**（属引导/安全类，改完重启） | 内网服务常挂在高位端口 |
| 6 | **连接超时 3s / 总超时 10s** + **响应大小上限**（与 `base64` 阈值同值；超出直接 `isError: true`，**不截断**）+ **流式丢弃、不落盘** | 超时防挂死；大小上限防打爆内存/磁盘；不落盘防"拿我们当文件中转站" |
| 7 | **频次限制**（按 Token 维度）+ **审计日志**（谁 / 何时 / 拉了哪个 URL / 结果 / 上游状态码） | 防被当成内网扫描器；出事可追溯 |
| 8 | 可选**域名白名单**（更严；开了文件接口的租户建议强制开启） | 最小暴露面 |

**验收用例（8 条，全部必须被拒）**：

| # | 输入 URL | 期望 |
| --- | --- | --- |
| 1 | `http://localhost:8080/` | 拒绝（回环主机名） |
| 2 | `http://127.0.0.1/` | 拒绝（回环 IP 直写） |
| 3 | `http://169.254.169.254/latest/meta-data/` | 拒绝（云元数据） |
| 4 | `http://intranet.corp.local/`（解析到 `10.x`） | 拒绝（解析后 IP 落私网） |
| 5 | `http://attacker.example/302` → `302 Location: http://127.0.0.1/` | 拒绝（默认不跟随；开启跳转后**每跳**仍拒） |
| 6 | `http://rebind.example/`（首次解析公网、二次解析 `127.0.0.1`） | 拒绝（第 3 条：IP 直连 + 全 IP 校验） |
| 7 | `http://example.com/huge`（返回 5GB） | 拒绝（大小上限，`isError: true`，**不截断**） |
| 8 | `file:///etc/passwd` | 拒绝（协议白名单） |

> **备选方案（已评估、未采用）**：本期只保留 `base64 → blob`、`url → blob` 延后 → SSRF 面**直接归零**。**当前定案 = 做 + 全套防护**，故上表 8 条要求与 8 条用例为准出条件。
>
> 这条不做，就是"让模型用我们的服务器去探内网 / 打云元数据"的口子。

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

  → ✅ **M22 已定案：就按这条原文来** —— `uri` 方案 = **`https://` + 短 TTL 签名 URL，让客户端直接抓取**；`2025-06-18` 以下客户端**没有 `resource_link`**（见 8.6）→ 降级为「文本里给 URL」（原还有"或走 `resources/read`"一条，**本期因不实现 `resources` 而删除**，见第 8 章开头）。

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
| **URI 形态** | ✅ **已定案（M22）**：**`https://` + 短 TTL 签名 URL**，客户端**直接抓取**（有原文依据：scheme 为 `https://` 时客户端可自行 fetch，且 SHOULD 只在"客户端能自己取"时用）。**降级规则**：`2025-06-18` 以下无 `resource_link` → **文本里给 URL**（⚠️ 原写"或走 `resources/read`"，**本期因 `resources` 不实现而删除**，见第 8 章开头）。若客户端**网络不可直达**（内网部署 / agent 访问不到 S3）→ 原方案是「自定义 scheme + 走 `resources/read`」（它**必然内嵌 base64** → **必须给尺寸警告**），但 ⚠️ **`resources` 本期不实现**（2026-10-15 定案）→ **该兜底整体推迟到二期，本期无退路**（见第 8 章开头的范围变更说明） | ✅ **M22 已定案** |
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
- 因此 **`uri`（链接）输出方式在老客户端上必须降级**：`2025-06-18` 以下只能用「文本里放 URL」（原还有「让客户端走 `resources/read`」一条，**本期因不实现 `resources` 而删除**，见第 8 章开头）；**在 `2024-11-05` 上无法给出真正的资源链接** —— **M22 定案后的落地规则**：`resource_link` + 签名 URL **只在 `2025-06-18` 及以上使用**，`2025-03-26` 与 `2024-11-05` 一律降级为文本 URL；
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
8. **客户端超时就是我们的硬上限** —— Dify 默认请求超时 **30s**、SSE 读超时 **300s**；Codex 默认启动 **10s**、**工具调用 60s**。→ 上游请求默认超时**必须短于 60s**（否则 Codex 先放弃），长任务要**尽早建 SSE 流 + 发 `notifications/progress`** 或心跳保活。（M9 据此给出推荐值：**上游默认 30s**。）✅ **2026-10-16 补充更紧的观测值（探针 P2 实测）**：**官方 MCP Java SDK 客户端**的 `requestTimeout` 默认 **20s** ⇒ **硬上限应取 20s 而非 30s** ⇒ **主人拍板：上游默认超时 = 15s**（见 [`设计决策记录.md`](./设计决策记录.md) §7.2 D7）。
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
| `2026-07-28` | **无人**（四家最晚接入 2026-03-09，早 4 个多月） | 形态三：✅ **C6 改定案（2026-10-15）—— 本期做，分两期**：**一期 = 传输层 + `server/discover`**（无会话、无 `initialize`、`_meta.protocolVersion`、必需头 `Mcp-Method`/`Mcp-Name` 校验）；**二期 = MRTR**（多轮往返请求）。⚠️ **推翻 2026-10-14 的「本期不排期」**。**验收限制（必须如实声明）**：**没有任何真实客户端支持该版本** → 一期靠**自测 + 规范一致性**验证；**二期 MRTR 无客户端可验**，只能靠规范一致性 + 自建探针，**不得声称"兼容性已验证"** |

> 🔴 **形态三的自研范围（`2026-10-15` SDK 实证）**：官方 SDK `2.1.0` 的 `mcp-core` jar 内 **`Discover` 相关类命中数为 0**；且 SDK 的合规验证目标版本是 **`2025-11-25`**，合规报告明确把 SEP-2106 标注为 *"post-2025-11-25 spec additions"* 而**跳过**。⇒ **`server/discover`、必需头 `Mcp-Method` / `Mcp-Name` 校验、`_meta.protocolVersion`、MRTR 全部必须自研**；SDK 只提供三个传输 Servlet（`HttpServletSseServerTransportProvider` / `HttpServletStreamableServerTransportProvider` / `HttpServletStatelessServerTransport`，**均已实测存在于 jar 内**）。

---

## 11. 待确认清单

| 编号 | 事项 | 推荐值 / 说明 |
| --- | --- | --- |
| ~~**M1**~~ | ~~目标 era~~ | ✅ **已定案：方案③，兼容到 `2024-11-05`**（三种形态全做）。⚠️ **补充（2026-10-14）**：`2026-07-28` 现代形态"设计保留、本期不排期" —— 四家目标客户端在接入时它都还不存在（最晚的 WorkBuddy 早 4 个多月），优先级最低，但**设计不做裁剪**，文档继续完整保留。<br>✅ **再改定案（C6，2026-10-15）**：**形态三本期做，分两期**（一期传输层 + `server/discover`，二期 MRTR）→ **本行 2026-10-14 的"不排期"作废**；**优先级不再是"最低"**，但**验收仍无真实客户端**（见 10.6 交集结论表） |
| M2 | MCP 端点路径 | ⚠️ **已升级为硬约束（有客户端级证据）**：**必须**是 `POST /mcp`（形态二 / 三）与 `GET /sse`（形态一 + `POST /messages`）—— **Dify 按 URL 最后一个路径段选传输**（`mcp` / `sse`），末段为其他值会先试 SSE 再回退，且 Dify 1.15.0 该回退有未捕获 `httpx.ReadTimeout` 会卡到 300s。→ **绝不能**把 MCP 端点挂在带随机 id 或 query 的路径上；与管理面 `/api/v1` 分开 |
| M3 | Token 形态与签发 | 静态 Bearer Token 先行；OAuth 授权规范与「访问管理」页设计一起补 |
| ~~**M4**~~ | ~~是否提供变更通知~~ | ✅ **已随 M19 定案：都不提供** —— 不声明 `listChanged` / `subscribe`，不实现 `subscriptions/listen` |
| ~~**M5**~~ | ~~二进制通道~~ | ✅ **已定案（第 8 章）**：**输入侧**参数级选 `url→blob`（服务端代理下载，**必做 SSRF 防护**）或 `base64→blob`；**输出侧**选 `base64`（内嵌 blob，**≥阈值给尺寸警告**）或 `uri`（**本机存储 / S3 二选一**；S3 需提示"agent 网络要能访问"）。**新增 M22（URI 形态与下载端点）**。⚠️ **补充（A3 定案 2026-10-15）**：`url→blob` **本期照做**，SSRF 防护要求与 **8 条验收用例**见 **8.2**，为准出条件 |
| ~~**M6**~~ | ~~资源 URI 方案~~ | ⛔ **本期不适用（2026-10-15）** —— `resources` 能力**本期不实现**（见第 6 章），"资源上架"的 URI 方案**没有落点**。⚠️ **别与 M22 混淆**：M22 定的是**二进制产物的链接形态**（`https://` + 短 TTL 签名 URL），与"资源 URI"是两件事 —— 后者已随本能力一起**推迟到二期** |
| ~~**M7**~~ | ~~是否提供 `prompts` 能力~~ | ✅ **已定案（2026-10-15）：不提供** —— **不声明 `prompts` 能力**，`prompts/list` / `prompts/get` 回 **`-32601`**（**设计保留、不进本期**）。**理由**：设计稿只有首页「提示词 1」计数与导航入口、**无功能设计**；**声明一个空能力就是撒谎**（同 M19 / 6.4 的既定原则）。数据库侧已一致（`mcp_prompt` 在「预留不建表」里） |
| M8 | 是否提供 `outputSchema`（结构化输出） | 建议工具返回 JSON 时同时给 `structuredContent` + 文本 |
| M9 | 上游超时 / 重试 | **客户端给了硬上限**：Codex 工具超时默认 **60s**、Dify 请求超时默认 **30s**（SSE 读 300s）→ **建议上游请求默认超时 30s**。✅ **2026-10-16 探针 P2 收紧上限 + 主人拍板：定 15s**：官方 **MCP Java SDK 客户端**的 `requestTimeout` 默认 **20s**（`McpClient.java:167/604`）⇒ **真正的硬上限是 20s，不是 30s** ⇒ **上游默认超时 = 15s**（留 5s 给建连 + 鉴权注入 + 响应转换 + 序列化 + 客户端解析），`数据库设计.md` 的 `mcp.upstream_timeout_ms` 默认值**已从 `30000` 改为 `15000`**（见 [`设计决策记录.md`](./设计决策记录.md) §7.2 D7）。⚠️ **注意：上游超时 ≠ 端到端上限** —— 上游真需 >15s 的场景走**异步 + `notifications/progress`**，不是靠调大这个值。长任务走 SSE + `notifications/progress`；重试只对幂等请求。⚠️ **C4 定案（2026-10-15）：本期「限流」与「配额」都不做** —— 原文「按 Token 维度限流」**已删除**（限流无承载：17 张表里没有限流配置表、env 清单里也没有阈值键；配额见 [`数据库设计.md`](./数据库设计.md) 第 19 章 Q2）。`42901` 错误码**保留但本期不使用**（预留） |
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
| ~~**M22**~~ | ~~`uri` 方案的 URI 形态与下载端点~~ | ✅ **已定案：`https://` + 短 TTL 签名 URL，客户端直接抓取**（有原文依据，见 8.3 ②）。**降级规则**：`2025-06-18` 以下无 `resource_link` → **文本给 URL**（⚠️ 原写"或走 `resources/read`"，**本期因 `resources` 不实现而删除**，见第 8 章开头）。**仍需补的接口**：8.4 的下载端点（`GET /artifacts/{id}`：流式、`Range`、TTL、鉴权、防遍历）+ 签名 URL 的签发与时钟偏差处理 |
| **M23** | **真实客户端实测**（重点：形态二"不下发会话"） | ⏳ **待做**：四家**都未声明协议修订版**；OpenClaw 文档提到"stateful Streamable HTTP session 过期"，Dify 客户端源码实现了 session / 续传 / `DELETE` / GET 流（**对 GET 流失败容忍**，记 non-fatal 日志）→ 需**实测形态二"不下发 `Mcp-Session-Id`" + "GET 流回 `405`"是否被接受**；若实测有问题，改动很小（改为"下发但不强制"，一行配置）。同时把四家的传输 / 鉴权差异并进 **M15 回归矩阵** |
| ~~**M24**~~ | ~~形态二"无会话"下如何记住协商版本~~ | ✅ **已定案（(b) + 配置兜底）**：① 用 **Token 维度记"最近协商版本"轻缓存**（**不下发会话 ID、不用于鉴权**，纯兼容缓存 → 不违反 M16 精神）；② **更关键的是加一个用户配置项**「**是否接入生成文件的接口**」来定我们的**方言上限**：**是 → `2025-06-18`；否 → `2025-03-26`**。**生效规则：`有效版本 = min(客户端协商版本, 配置方言上限)`** —— 例：Codex 说 `2025-03-26` + 我们开了文件接口 → 仍按 `2025-03-26`（**绝不给它发 `resource_link`**）；Dify 说 `2025-06-18` + 没开文件接口 → 按 `2025-03-26` 输出（**不发新内容块**）。→ **❗ 配置来源已改判（2026-10-16）**：**归 `page-managed`** —— `mcp_config.mcp.file_interface_enabled`（管理台「配置」页可改、**热生效**、**可按租户覆盖**），**撤销 A24 原「走 `env`、控制台不新增接口」**；**`MCP_DIALECT_MAX` 作废**（与开关冗余 → 只留一个旋钮）；env 只剩 `MCP_NEGOTIATED_VERSION_TTL`。生效规则 / 运维注意见 **4.4** |

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
