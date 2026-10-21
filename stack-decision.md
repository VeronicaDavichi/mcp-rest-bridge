---
decidedAt: 2026-10-15
decidedBy: 主人（逐维度确认中）
status: in-progress
dimensions:
  backend.framework:
    choice: spring-boot
    version: "4.1.1"
    confirmed: true
    alternatives: ["4.0.8", "4.2.0-M2"]
    rationale: 主人指定 JDK 25 + Spring Boot 4（要用新特性）；4.1.1 为 2026-08-20 最新稳定版，MyBatis-Plus 3.5.17 与 Spring AI 2.0.1 均已适配
  backend.orm:
    choice: mybatis-plus
    version: "3.5.17"
    artifact: "com.baomidou:mybatis-plus-spring-boot4-starter"
    confirmed: true
    alternatives: ["spring-data-jpa", "jooq", "mybatis-native"]
    rationale: 受 B3 定案硬约束 —— TenantLineInnerInterceptor 承担多租户行隔离 + 强制追加 deleted_seq=0；改 JPA 会作废 B3 并重写数据库稿多处
  backend.db:
    choice: "mysql+postgresql"
    confirmed: true
    note: 被现有设计钉住（兼容性稿 Q1：MySQL 8.0.30+ / PostgreSQL 13,14+）
  backend.migration:
    choice: flyway
    confirmed: true
    note: 被现有设计钉住（数据库稿 V1~V4）
  backend.auth:
    choice: jwt
    confirmed: true
    note: 被现有设计钉住（接口稿 §5 + A6 定案的 mcp_client_token）
  backend.mcp:
    choice: official-sdk-hybrid
    version: "io.modelcontextprotocol.sdk:mcp:2.1.0"
    confirmed: true
    alternatives: ["hand-rolled", "sdk-only"]
    rationale: 主人 2026-10-15 拍板。SDK 承载形态一/二 + 运行时动态工具（复杂 schema 原样保留、同名 upsert、300 工具 29ms）+ 按 Token per-request 过滤 + 两级错误模型；仅 2026-07-28（server/discover、Mcp-Method/Mcp-Name、MRTR）自研。代价：引入 Project Reactor
    blocking_followups: ["logging 口径待定（6.6）", "工具名须管理台侧自校验（6.7）"]
  backend.ai:
    choice: none
    confirmed: true
    rationale: 本项目不是 AI 应用，是协议网关；真正的同类维度是 backend.mcp
  frontend.framework:
    choice: null
    confirmed: false
    owner: 前端负责人
    note: 主人明确「前端不归我管」——本会话不代填、不推荐
  frontend.ui:
    choice: null
    confirmed: false
    owner: 前端负责人
  frontend.state:
    choice: null
    confirmed: false
    owner: 前端负责人
  frontend.test:
    choice: null
    confirmed: false
    owner: 前端负责人
---

# 技术栈决策（stack-decision）

> **本文件的地位**：D 类承载缺口里**「无 ADR / 技术选型文档」**那条的落点。
> 规则（来自 skill `stack-decision`）：**每个维度必须由人明确表态后才写入**，本文件**不做推荐套餐**。
>
> **触发背景**：本项目此前**只有设计文档、没有代码**（无 `pom.xml` / `package.json` / `src/`）。D 批次开工前需先定选型，否则 CI、测试策略、部署方案都无从落笔。

## 0. 前置事实（已实测，非推理）

| 发现 | 证据来源 | 影响 |
| --- | --- | --- |
| ✅ `com.baomidou:mybatis-plus-spring-boot4-starter` **存在**（`3.5.13`→**`3.5.17`**，2026-07-08） | Maven Central 目录（HTTP 200） | **B3 定案（多租户 `TenantLineInnerInterceptor`）在 Boot 4 上有官方承载** |
| ✅ Boostrap：Spring Boot `4.0.0` GA = **2025-11-20**；最新稳定 **`4.1.1`**（2026-08-20）；`4.2.0-M2` 在途 | 同上 | 可安全用 Boot 4 |
| ✅ Jackson 3 已 GA：`tools.jackson.core:jackson-databind` **`3.0.0`**（2025-10-03）→ 最新 `3.2.3` | 同上 | ⚠️ **A4 定案**（`@Sensitive` + Jackson 序列化器出参加码）**必须写 Jackson 3 版**（包名 `tools.jackson`） |
| ✅ 官方 **MCP Java SDK**：`io.modelcontextprotocol.sdk:mcp`，`1.0.0` GA（2026-02-23），最新 **`2.1.0`**（2026-10-15） | 同上 | 见维度 `backend.mcp` |
| ✅ SDK `mcp` 包 = `mcp-core` + **`mcp-json-jackson3`**（**默认 Jackson 3**），MIT、**Java 17+** | `mcp-2.1.0.pom` | 与 Boot 4 **天然对齐** |
| 🔴 **SDK 的目标 spec 版本 = `2025-11-25`**，SEP-2106 标注为 *"post-2025-11-25 spec additions"* 且 **SKIPPED**；实测 2026-08-17 | `conformance-tests/VALIDATION_RESULTS.md` | ⚠️ **我们的 `2026-07-28`（`server/discover` / `Mcp-Method`+`Mcp-Name` 头 / 无状态 / MRTR）SDK 不覆盖 → 那部分必须自研** |
| ✅ SDK Server 合规 **73/73 checks、31 场景 100%**，含 **SSE Transport**、Resources、Prompts、subscribe、DNS rebinding 防护 | 同上 | **形态一 + 形态二有官方承载** |
| ⚠️ SDK 已知缺陷：Client `sse-retry` 不解析 `retry:` 字段、不发 `Last-Event-ID`（在 baseline 里） | 同上 | 属 **SDK 作为 client** 的问题；**我们做 server，不受直接影响** |
| ✅ Spring AI BOM：`2.0.0` GA = **2026-06-12**，最新稳定 **`2.0.1`**（2026-08-20） | Maven Central 目录 | 其 `mcp-spring-webmvc` / MCP Annotations / MCP Security 可用 |
| ✅ `dynamic-datasource-spring-boot4-starter` 存在（2025-12-18） | 同上 | 对应数据库稿 `route_mode = DATASOURCE` 逃生舱 |
| ⚠️ 本机 MySQL 实测 **8.0.28**，而 Q1 已定案 **最低 8.0.30+** | 本会话早前实测 | **本地开发环境低于自定最低版本** → D 批次要处理（见「待办」） |

⚠️ **skill 资源缺失（如实声明）**：`stack-decision` skill 目录里**只有 `SKILL.md`**，其 Step 4 要求对照的 `capabilities/*/manifest.json` 与 `probe.sh` **均不存在** → **兼容性矩阵由本项目自行维护**（即第 2 节），不依赖 skill 未安装的文件。

## 1. 讨论背景（Step 1，✅ 已锚定）

| 问题 | 答案 |
| --- | --- |
| **项目性质** | **企业内部系统**（多租户管理台 + MCP 网关）。主人原话：「先做内部系统吧，我实在是没有公网服务器给人去做什么 SaaS」→ **不做 SaaS**：无计量/计费/公网多租户运营要求 |
| **团队主力语言** | **Java（后端主力）** + **TypeScript / JS（前端主力）** |
| **已知硬约束** | **JDK 25 + Spring Boot 4**。主人原话：「JDK25 SpringBoot4 我要用新特性，我要开发一个爽的！！！！」→ **不用 JresCloud / JDK 8 路线**；全程 `jakarta.*`，**绿地无 `javax`→`jakarta` 返工** |

> **由背景推出的直接结论**：设计文档里那些偏「SaaS 运营」的能力（限流、配额）本期不做是对的（C4 已定案）；而「多租户隔离」仍必须做（设计稿与 B3 都是多租户），因为内部系统也按租户隔团队/业务线。

## 2. 兼容性约束（本项目自维护，替代 skill 缺失的矩阵）

| 约束 | 说明 |
| --- | --- |
| `backend.orm = mybatis-plus` ⇒ `backend.framework` 必须 Spring 系 | MyBatis-Plus 是 Spring 集成 |
| `mybatis-plus` 选型 ⇒ **B3 定案（`TenantLineInnerInterceptor` 强制追加 `deleted_seq=0` + 租户行隔离）可原样落地** | 换 JPA 则 B3、B6 细节、`*_norm` 列约定**全要重议** |
| Boot 4 ⇒ **Jackson 3**（`tools.jackson`）⇒ **A4 掩码序列化器按 Jackson 3 写** | 不是选型问题，是**已定案的实现口径变化** |
| Boot 4 ⇒ **Jakarta EE 11 / Servlet 6.1**、Spring Framework 7、Spring Security 7 | 全程 `jakarta.*` |
| JDK 25 ⇒ 可用虚拟线程、结构化并发（`StructuredTaskScope`）、模式匹配等；**B5 定案（异步显式带 `tenantId`）与 B9（workerId 0~31）不受影响** | —— |
| 若用 MCP Java SDK ⇒ 引入 **Project Reactor**（Reactive Streams 公开 API + 同步门面） | 全项目异步模型要统一，不能混用两套 |
| 若用 MCP Java SDK ⇒ 其 **Server 传输在 core 里是 Jakarta Servlet**；WebMVC/WebFlux 传输在 **Spring AI 2.0+** | 与 Boot 4 的 Servlet 6.1 一致 |

## 3. 维度清单与状态

| # | 维度 | 状态 | 取值 |
| --- | --- | --- | --- |
| 1 | `backend.framework` | ✅ **已定案** | **Spring Boot `4.1.1`** |
| 2 | `backend.orm` | ✅ **已定案** | **MyBatis-Plus `3.5.17`**（`mybatis-plus-spring-boot4-starter`） |
| 3 | `backend.db` | 🔒 **被现有设计钉住** | MySQL 8.0.30+ / PostgreSQL 13,14+（双库，兼容性稿 Q1） |
| 4 | `backend.migration` | 🔒 **被现有设计钉住** | Flyway（数据库稿 `V1`~`V4`） |
| 5 | `backend.auth` | 🔒 **被现有设计钉住** | JWT（接口稿 §5）+ `mcp_client_token`（A6 定案） |
| 6 | `backend.ai` | ➖ **本维度不适用** | 我们**不是** AI 应用，是**协议网关**；真正的同类维度见 #12 |
| 7 | `backend.test` | ⏳ 待讨论 | —— |
| 8 | `frontend.framework` | 🚫 **不由本会话决定** | 主人明确「前端不归我管」→ **待前端负责人拍板** |
| 9 | `frontend.ui` | 🚫 **不由本会话决定** | 同上 |
| 10 | `frontend.state` | 🚫 **不由本会话决定** | 同上 |
| 11 | `frontend.test` | 🚫 **不由本会话决定** | 同上 |
| 12 | **`backend.mcp`**（⚠️ **skill 模板里没有的新维度**） | ⏳ **spike 已跑（S1/S2 通过）** | 待表态；见第 6 节 |
| 13 | `backendDir` / `frontendDir` / `harnessDir` | ⏳ 待讨论 | 默认 `back_end` / `front_end` / `harness` |

## 6. `backend.mcp` 能力核对（官方 Java SDK）

> **起因**：主人质疑「用官方 sdk 你能写这些请求的**动态工具**么？？？」—— 这一问直指要害：本产品**没有静态 `@Tool` 方法**，工具全部由管理台配置在**运行时生成**。
>
> **结论：能。** 证据 = `mcp-core` 源码 `McpServer.java`（`main` 分支）+ 官方文档 `java.sdk.modelcontextprotocol.io/latest/server/`。逐条核对如下。

| 我们的需求（出处） | SDK 支持 | 证据 |
| --- | --- | --- |
| **运行时构造 `inputSchema` 的动态工具**（管理台配置驱动） | ✅ **原生支持** | `Tool.builder(name, schema)` —— schema 是**我们传进去的对象**；注解式只是可选糖 |
| 注册处理器 + 取调用方入参 | ✅ | `.toolCall(Tool, (exchange, request) -> CallToolResult)`，`request.arguments()` |
| **按 Token 过滤 `tools/list` 可见范围**（MCP 稿第 6 章） | ✅ **一等 API** | `addToolFilter(McpSyncListFilter<McpSchema.Tool>)` / `McpAsyncListFilter` |
| **形态一**：老形态 HTTP + SSE | ✅ | 文档有 **"SSE HTTP (Legacy)"** 传输 |
| **形态二**：Streamable HTTP（有状态） | ✅ | 文档 "Streamable HTTP" 传输 |
| **形态三**：无状态 | 🟡 **有骨架，但版本对不上** | 源码有 `McpStatelessServerTransport` + `StatelessSyncSpecification` / `StatelessAsyncSpecification`；**但合规目标 = `2025-11-25`，无 `server/discover` / `Mcp-Method` / `Mcp-Name` / MRTR** → 我们的 `2026-07-28` **仍需自研或改造** |
| 两级错误模型（协议错误 vs 工具错误） | ✅ **官方就叫 "Two Tiers of Errors"** | 与设计稿 §6.3 完全一致 |
| 工具入参 schema 校验 | ✅ | 内置 `JsonSchemaValidator`（**但我们仍必须服务端再校验** —— 见 C 类 §6.2 三条硬规则） |
| resources / prompts **不实现** | ✅ **不注册即可** | 二者是独立注册项；不注册就不出现在 capabilities 里 |
| `ttlMs` / `cacheScope`（现代规范） | ❌ 超出 `2025-11-25` | 自研 |
| 认证 | ➖ **只给钩子、不带实现** | 正好配我们的 A1 / A2（JWT + api-key） |

### 6.1 spike 结果（2026-10-15 实测）

| # | 结论 | 证据（可复现） |
| --- | --- | --- |
| **S1** | ✅ **通过（超出预期）** | `_work/spike/mcp-core-2.1.0.jar`（661,893 B / 404 条目）含 `spec/McpStatelessServerTransport`、`server/McpStatelessSyncServer`、`server/McpStatelessAsyncServer`、`server/transport/HttpServletStatelessServerTransport`、`McpServer$StatelessSyncSpecification` |
| **S2** | ✅ **通过（升级为报文实证）** | `McpSyncListFilter` javadoc 原文 *"**Decide per request** whether a primitive is advertised in the corresponding listing, such as `tools/list`"*；签名 `isVisible(McpTransportContext transportContext, T primitive)`，该 context *"containing, for example, HTTP headers or a resolved principal"*；配套 `McpTransportContextExtractor` / `DefaultMcpTransportContext` 齐备。**且 `McpAsyncServer:548` 实为 `isVisible(exchange.transportContext(), tool)`**；2026-10-16 真传输**报文实证**见 §6.8 |
| **S3** | 🔴 **已跑，暴露一处冲突** | 真机探针（JDK 25.0.4.1 + Maven 3.9.16 + SDK 2.1.0）→ **`logging` 能力无法关闭**，显式传 `capabilities` 也不行。详见 §6.5 / §6.6 |
| **S4** | ✅ **已解决（方案已跑通验证）** | jar 内三种传输**各自独立** ✅；Spring MVC 层归我们所有 ✅。2026-10-16 运行验证**先发现硬冲突**（形态二与形态三**都挂 `POST /mcp` 时，后注册者拿到空映射、静默失效**；应用不报错、能启动，该形态永久收不到请求），**随后把 Router 方案跑通** ⇒ **三形态同进程共存可落地**。详见 §6.8.1 / §6.8.3 |

**额外收获（S1 复核）**：jar 内 **`Discover` 相关类 = 0 命中** → 🔴 **`2.1.0` 完全没有 `server/discover`** → 设计稿 `2026-07-28` 一期（含 `server/discover`）**必须自研**。此项由"推测"升级为**实测结论**。

**额外收获（安全，白送）**：jar 自带 `ServerTransportSecurityValidator` / `DefaultServerTransportSecurityValidator` / `ServerHttpHeaderValidator` / `HeaderAccessor` / `HttpServletHeaderAccessor` → 对应 **A3 的 DNS-rebinding / Origin 校验**，**不必自研**。

**额外收获（动态工具）**：`McpSyncServer` 提供 `addTool` / `removeTool` / `listTools` / `notifyToolsListChanged` → **运行时增删工具是一等 API**，管理台改配置可**不重启生效**（`notifyToolsListChanged` 我们**不调用**，符合 M19 定案）。

### 6.2 🆕 新发现风险（**D 类，必须回写设计稿**）

| 风险 | 出处（官方 javadoc 原文） | 对设计稿的影响 |
| --- | --- | --- |
| **列表过滤 ≠ 权限** | *"A primitive hidden by this filter is omitted from listings ONLY. **It remains reachable through its own endpoint: a hidden tool called by name still executes. Permissions MUST be enforced in the primitive's handler.**"* | ⚠️ `MCP-Server对外接口设计.md` 第 6 章「`tools/list` 结果随授权变化 → 合规」是**半截话**：**必须在 `tools/call` 处理器内对 `mcp_token_service_scope` / 可见范围再校验一次**，否则知道工具名的客户端可绕过。**待补写** |

### 6.3 本机环境现状（**含一处我自己的误判，已更正**）

> 🔴 **更正（2026-10-15，本人误判，记录在案）**：我先前据 `PATH` / `JAVA_HOME` / 几个常见安装目录判定「**本机无 JDK**」，**这是错的**。深入搜索后**实际存在 3 个 JDK 25**：
>
> | 路径 | 版本 |
> | --- | --- |
> | `C:\Users\EDY\.jdks\ms-25.0.4.1\` | **最新**（Microsoft OpenJDK 25.0.4.1） |
> | `C:\Users\EDY\.jdks\ms-25.0.3\` | Microsoft OpenJDK 25.0.3 |
> | `D:\.jdks\ms-25.0.2\` | Microsoft OpenJDK 25.0.2 |
>
> **根因**：JDK 由 IDEA 下载到 `.jdks`，**未进 PATH、未设 `JAVA_HOME`** → 只探 PATH 必然漏。
> **教训（写入流程）**：环境探测必须覆盖**文件系统 + 注册表 + WSL**，**不得仅凭 `PATH` 下结论**。

实测清单（2026-10-15）：

| 项 | 状态 | 备注 |
| --- | --- | --- |
| **JDK 25** | ✅ **有 3 个** | 但**均在 `PATH` 之外** → 需显式指定路径或设 `JAVA_HOME` |
| **Maven** | ❌ 无 | 处置见 §6.4 |
| **Docker** | ❌ 无 | ⚠️ **B6 / B7 已定案的 Testcontainers 卡口本地跑不了**，需 CI 或后续装 Docker |
| **Node** | ✅ `v24.15.0` + npm / pnpm | 前端可用 |
| **WSL2** | Ubuntu 22.04，**仅 JDK 8 / 11**（`java` = 1.8.0_504） | ⚠️ **本项目在 WSL 里编不了**（需 JDK 25）→ 与「命令优先 WSL 版」的偏好**冲突**，构建须显式走 Windows JDK 25 |
| **MySQL** | 本机 8.0.28；主人已定**去 WSL 起高版本实例** | 本机不作为验收环境 |
| **winget** | ✅ 可用（v1.2.10691）但**主人网络下不动** | 工具链一律走**直连下载**，不依赖 winget |

### 6.4 Maven 版本（主人索取的精确版本号）

Maven Central 实测（2026-10-15）：

- 🚫 **Maven 4 尚未 GA** —— 最新仅 `4.0.0-rc-7`（2026-09-21）→ **不用于真项目**
- 3.x 两条线：**`3.9.16`**（2026-05-13，3.9 线最后补丁）、**`3.10.0`**（2026-09-27，新线首发）
- ✅ **推荐 `3.9.16`**：3.9 线久经使用，作项目地基零意外；`3.10.0` 发布仅 18 天，不拿构建工具冒险

直链（**已验证本机 `pwsh` 可直连 Central**——先前下载 `mcp-core-2.1.0.jar` 成功）：

```
https://repo1.maven.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
```

### 6.5 S3 实测输出（2026-10-15，真机跑通）

探针工程：`_work/spike/mcp-probe`（**故意放在 `_work/` 下，不破坏仓库「设计阶段只有文档」的边界**）
环境：JDK `25.0.4.1`（Microsoft）+ Maven `3.9.16` + `io.modelcontextprotocol.sdk:mcp:2.1.0`

| 实测项 | 结果 |
| --- | --- |
| **运行时构造 `inputSchema`** | ✅ 我们传入的 `Map` 被原样持有：`{type=object, properties={city={type=string, minLength=1, ...}}, required=[city], additionalProperties=false}` |
| **运行时 `addTool` / `removeTool`** | ✅ 可用（`listTools()` 正确 `[get_weather]` → `[]`）；⚠️ **但构建期必须先 `.capabilities(...)` 开启 tools**，否则 `IllegalStateException: Server must be configured with tool capabilities` |
| **`tools(false)`** | ✅ 精确表达 **M19「不主动推」** → `ToolCapabilities[listChanged=false]` |
| **增删工具会否自动推送客户端** | ✅ **不会** —— 桩传输的 `notifyClients` 全程**未被触发** → **M19 定案安全** |
| **能力是否随增删回落** | ⚠️ **不回落**：`tools` 一旦声明即常驻（`removeTool` 到空仍是 `ToolCapabilities[...]`）→ **能力是构建期快照** |
| 🔴 **`logging` 会否被默认声明** | ❌ **会，且显式传 `capabilities` 也关不掉** |

### 6.6 🔴 新增设计冲突：`logging` 关不掉（**需主人拍板**）

实测对比（A 组 vs B 组）：

```
A) 不传 capabilities()          → logging = LoggingCapabilities[]   ⚠️
B) 显式传 capabilities(tools)   → logging = LoggingCapabilities[]   ⚠️ 仍然在！
```

**根因（源码级，已定位）**：

```java
McpSchema.java:987                   this.logging = new LoggingCapabilities();  // Builder 字段初值就「启用」
McpServerFeatures.java:78/202        new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
McpStatelessServerFeatures.java:182  ...同上（无状态规格的默认能力）
```

→ **Builder 一构造就带 logging**，所以"不调用 `logging()`"无效；不传 `capabilities()` 时 `McpServerFeatures` 的默认值同样带 logging。我们传的 `tools` 生效、`logging` 不受控。

**这与 C 类 §6.4 的落笔「caps 不声明 `logging`、不实现 `logging/setLevel`」直接冲突。**

**补充证据（决定"声明它算不算撒谎"）**：

- ✅ `logging/setLevel` **服务端确实实现**：`McpAsyncServer.java:1011` 接收 `SetLevelRequest` 并保存 min level → 客户端调用**不会报错**，合规测试 `logging-setLevel` 也**通过**。
- ❌ **服务端没有任何"发日志通知"的 API**：`McpSyncServer` 上只有 `notifyToolsListChanged` / `notifyResourcesListChanged` / `notifyResourcesUpdated` / `notifyPromptsListChanged`，**没有** `notifyLogging` / `sendLoggingMessage`。`LoggingMessageNotification` 只出现在**客户端**代码里（`McpAsyncClient` / `McpClient` / `McpClientFeatures`）。
- ⇒ 声明 `logging` = "我能接受 `logging/setLevel`"，**这句是真的**；但**"定了级别也没有日志可推"**，能力**功能性半空**。

| 方案 | 做法 | 代价 | 我的建议 |
| --- | --- | --- | --- |
| **① 接受 SDK 的 logging** | 承认 `logging` 由 SDK 承载，把 C 类落笔改为「`logging/setLevel` 接受并存储，但**不主动推 `notifications/message`**（SDK 无服务端发送 API）」 | 文档必须**如实写明"不推送"**，不得声称"支持服务端日志推送"；此外无实际代价 | ✅ **推荐** —— 它不阻断任何功能，且硬关会逆着 SDK 设计走 |
| **② 强行不声明** | 绕过 Builder，用 `ServerCapabilities` **规范构造器**把 `logging` 传 `null`（构造器是 public，可行） | 需实测 `initialize` / `server/discover` 响应确认未被补回；**逆 SDK 设计**，升级易碎 | 仅在"绝不声明半空能力"是硬红线时才选 |

#### 6.6.1 副作用详解（主人问「会产生什么副作用」，逐条答）

**规范侧依据**（`modelcontextprotocol` 仓库 `docs/specification/2025-11-25/server/utilities/logging.mdx`，原文）：

- 关于能力声明，规范只有一条**单向 MUST**：*"Servers that **emit** log message notifications **MUST** declare the `logging` capability"* —— **只约束"会发通知的服务器必须声明"，并未要求"声明了就必须发"**。
- `logging/setLevel`：*"clients **MAY** send"*，返回 **Empty Result**；非法级别 → `-32602`；配置错误 → `-32603`。
- `notifications/message`：规范为**描述性**表述（"Servers send log messages using…"），**无 MUST / SHOULD** → 不发不构成违规。
- ⚠️ **安全段有硬 MUST**：日志 **MUST NOT** 含 *Credentials or secrets / personal identifying information / internal system details that could aid attacks*。

**SDK 侧依据**：`McpSchema.java:807` 的 `ServerCapabilities` 带 **`@JsonInclude(JsonInclude.Include.NON_ABSENT)`** → **`logging` 传 `null` 会被干净地剔出报文**（不会输出 `"logging": null`）→ **方案 ② 在报文层面是干净的**。

| 维度 | 方案 ① 接受 | 方案 ② 强行 `null` |
| --- | --- | --- |
| 客户端调 `logging/setLevel` | ✅ 成功返回空结果，**与声明一致** | ⚠️ 仍会成功（SDK 照常处理）→ **声明与行为不一致** |
| 客户端等 `notifications/message` | 收不到（规范允许；客户端空等） | 同样收不到 |
| 报文合法性 | ✅ | ✅（`NON_ABSENT` 剔除 null） |
| 升级风险 | 无 | ⚠️ 逆 SDK 默认；未来版本若 `initialize` 路径解引用 `logging()` → **NPE** |
| 维护风险 | 无 | ⚠️ 后人可能"顺手改回去" |
| 额外工作 | 文档写明「接受 `setLevel`、**不推** `notifications/message`」+ 1 条测试用例 | 同上，另加 hack 注释 |
| 安全 MUST 联动 | 记一条：**将来若接推送，MUST NOT 含凭据/密钥/PII/内部系统细节** | 同 |

> **判断**：**规范上不构成撒谎** —— 我们确实支持 `logging/setLevel` 这个请求；缺的"发通知"规范并未强制。**① 副作用近于零，② 属逆 SDK 设计。**

> ⚠️ **同类隐患（一并记）**：`tools` 能力一旦声明即常驻，**工具被删空也仍声明** → 若某租户一个工具都没配，就会声明空的 `tools`。**需要「按实际注册项构造能力 + 必要时重建 server」的实现规则**，否则又是第 ① 类病（声明了能力没有承载）。

### 6.7 ✅ 结论：SDK **能用**于"全动态组装"（逐条真机验证）

> 主人的原话：「你确认一下能不能用 sdk，毕竟我们的工具什么的都是动态组装的」。
> **结论：能用，而且 SDK 的 API 形态就是为这种用法设计的。** 逐条证据如下（探针 `probe.DynamicAssembly`，`RUN_EXIT=0`）。

| 动态组装的需求 | 结论 | 真机证据 |
| --- | --- | --- |
| 运行时构造 `inputSchema` | ✅ | `Tool.builder(name, Map<String,Object>)`，schema 由我们给 |
| **复杂 schema 不被改写** | ✅ | `原样保留 = true`：`$schema`、`$defs`、`anyOf`、`minItems`、`enum`、嵌套 object **全部原样保留** |
| 运行时**增 / 删 / 改**工具 | ✅ | `addTool` 同名**直接覆盖**（= upsert，无需先 remove）；`removeTool` 正常；`listTools()` 实时反映 |
| 规模 | ✅ | 300 个工具：`addTool` 300/300 **29 ms**、`listTools()` 300 条 **0 ms** |
| 按 Token 过滤可见范围 | ✅ | `addToolFilter`（javadoc 明确 **per request**，且 `McpTransportContext` 含 HTTP headers / principal） |
| 工具处理器 = 我们自己的代码 | ✅ | `(exchange, request) -> CallToolResult`，入参从 `request.arguments()` 取（承载 C3 的调用方传参） |
| ⚠️ **工具名合法性** | ❌ **运行时完全不校验** | `'bad name'` / `'中文工具'` / `'tool/1'` / `'$x'` / **200 字符** 全部 **"成功"**；仅**空名**被拒（`name must not be empty`） |
| ⚠️ 能力为**构建期快照** | ⚠️ | `tools` 声明后不随增删回落（删空仍声明） |
| ⚠️ `logging` 关不掉 | ⚠️ | 见 §6.6（不阻断功能） |

**⇒ 三个"但是"都不阻断**，各自对应一条实现规则：

1. **工具名必须由管理台侧校验**：规格为 `^[A-Za-z0-9_\-.]+$`、长度 1~128（`ToolNameValidator` 源码）。⚠️ **因为运行时 `addTool` 不校验，非法名会静默流到客户端** —— 这是"SDK 不拦，我们必须拦"的典型。
2. **能力需按实际注册项构造**，并处理"工具集从空↔非空"的切换（必要时重建 server）。
3. **`logging` 按 §6.6 方案 ① 如实写文档**。

> 🔴 **本节含一次我自己的更正**：我先据 `ToolNameValidator` 源码推断"中文工具名会被 SDK 拒绝"，**真机证明是错的** —— **运行时 `addTool` 根本不调用该校验**。真相比推断更危险（**静默产出非法名字**）。**教训：源码推断必须真机复核。**

### 6.8 P2 实测：真传输 + 真报文（2026-10-16）

探针工程 `_work/spike/mcp-boot-probe`（`ProbeApp.java` 单文件），把三种形态全挂进一个 **Spring Boot 4.1.1 / Tomcat 11.0.24 / JDK 25** 应用，用 `curl` 抓真实报文（**`COMPILE_EXIT=0`**）。

| # | 实验 | 结果 |
| --- | --- | --- |
| 1 | **形态二 + 形态三都挂 `POST /mcp`** | 🔴 **静默失效**：应用**正常启动、日志零报错**，但 `/probe/mappings` 显示 `{"statelessTransport":[], "streamableProvider":["/mcp"], "sseProvider":["/sse","/messages"]}` —— 形态三的映射是 **`[]`** ⇒ **它永远收不到任何请求**。**最坏的失败方式：不报错** |
| 2 | `/mcp` 实际由谁服务 | **形态二**（返回 `Mcp-Session-Id`）⇒ 印证"先注册者赢" |
| 3 | 🔴 **真实 `initialize` 报文的 capabilities** | **`{"capabilities":{"logging":{},"tools":{"listChanged":false}}}`** —— **`logging` 真的被发给客户端了**（我们只传了 `tools(false)`）⇒ **D3 由源码推断升级为报文实证**；`tools.listChanged=false` 正确落地（**M19 ✅**） |
| 4 | **逐请求 Token 过滤（真传输）** | ✅ `tools/list` + `X-Probe-Tenant: A` → 只回 `tool_alpha`；`B` → 只回 `tool_beta`（**S2 报文实证**） |
| 5 | 🔴 **被隐藏的工具按名直调** | 🔴 **A 的列表里看不到 `tool_beta`，却调用成功**：`{"content":[{"type":"text","text":"beta executed (Streamable)"}],"isError":false}` ⇒ **「列表过滤 ≠ 鉴权」报文级实锤（D1）** |
| 6 | **`server/discover`** | 🔴 即使带 `Mcp-Method: server/discover` 头，仍回 `{"code":-32601,"message":"Method not found: server/discover"}` ⇒ **必须自研（D5 报文实证）** |
| 7 | **`requestTimeout` 的方向** | ✅ **我原先的担心方向错了**：服务端 `requestTimeout` 默认 **10s** 管的是"**服务端→客户端**"的请求（sampling / elicitation / roots），**不约束我们的工具执行**。⚠️ 但**官方 SDK 客户端**的 `requestTimeout` 默认 **20s**（`McpClient.java:167/604`），它约束"客户端等我们 `tools/call` 结果"的时长 |

#### 6.8.1 ✅ 由实验 1 得出的硬约束 + Router 方案（**已跑通验证**）：era 分流怎么实现

三个传输都靠 **`request.getRequestURI().endsWith(endpoint)`** 判断路径（如 `HttpServletStreamableServerTransportProvider:378`）⇒

- ❌ **`RequestDispatcher.forward` 不可行** —— forward **保留原 `getRequestURI()`**（仍是 `/mcp`），若把 provider 配成 `/__legacy`，请求会被它**自己的路径检查拒掉**；
- ✅ **直接调用 `service(req, resp)` 可行** —— 两个 provider 都配成 `/mcp`（各自另注册在私有路径以便容器 `init()`），**Router Servlet 挂 `/mcp`**，按 era 调用对应实例：`getRequestURI()` 仍是 `/mcp`，**两边的 `endsWith` 都通过**。
- **era 判据（建议）**：请求头含 **`Mcp-Method`**（`2026-07-28` 必需头）→ 形态三；否则 → 形态二。
- 🎁 **附带收获**：因为是 `endsWith` 而非 `equals`，**设了 `server.servlet.context-path` 也能正常挂载**（`/bridge/mcp` 仍以 `/mcp` 结尾）⇒ 对 **M2** 的端点硬约束是好消息。

#### 6.8.2 ✅ M9（上游超时）上限**已定案为 15s** —— 并更正我自己的说法

**先更正**：MCP 稿 **L860** 与 **M9 行**原本**已经写了**"客户端超时就是我们的硬上限"（Dify 30s / Codex 工具 60s → 才推出 30s）。我先前把它当成"新发现"是**不准确的**。

**真正新增的是一个更紧的观测值**：**官方 MCP Java SDK 客户端的 `requestTimeout` 默认 20s**。
⇒ **真正的硬上限是 20s，不是 30s**。✅ **2026-10-16 主人拍板：上游默认超时 = 15s**（留 5s 给建连 + 鉴权注入 + 响应转换 + 序列化 + 客户端解析），`数据库设计.md` 的 `mcp.upstream_timeout_ms` 默认值**已由 `30000` 改为 `15000`**。⚠️ **并写明：上游超时 ≠ 端到端上限** —— 上游真需 >15s 的场景走**异步 + `notifications/progress`**，不是靠调大这个值。

#### 6.8.3 ✅ Router 方案实测通过（2026-10-16）：三形态同进程共存可落地

`ProbeApp` 增加 profile **`router`**：`legacyProvider`（形态二）与 `modernTransport`（形态三）**都配成 `/mcp`**（因为判路径是 `endsWith("/mcp")`），但**都不注册到 `/mcp`**；改为在 `@Bean` 里**手动 `init(new MinimalServletConfig(...))`**，再由**唯一的 `EraRouterServlet` 独占 `POST /mcp`**，按 `Mcp-Method` 头**直接调 `service()`**。

实测报文（`curl`，跑在 `--spring.profiles.active=router`，`COMPILE_EXIT=0`）：

| 请求 | Router 日志 | 响应 | 结论 |
| --- | --- | --- | --- |
| `POST /mcp` `initialize`（**不带** `Mcp-Method`） | `-> LEGACY / 形态二 (streamable)` | `Mcp-Session-Id: 517f7ca9-…` + `"capabilities":{"logging":{},"tools":{"listChanged":false}}` | ✅ 落到**形态二** |
| `POST /mcp` `tools/list` + **`Mcp-Method: tools/list`** | `-> MODERN / 形态三 (stateless)` | **`{"tools":[{"name":"tool_gamma",…}]}`**、**无 `Mcp-Session-Id`** | ✅ 落到**形态三**（`tool_gamma` 是形态三独占工具，**来源可判定**） |
| 同上，**只带** `Mcp-Method`（不带 `MCP-Protocol-Version`） | `-> MODERN / 形态三 (stateless)` | 同上 | ✅ 版本头**非必需** |
| `POST /mcp` `server/discover` + `Mcp-Method` | `-> MODERN / 形态三 (stateless)` | `{"error":{"code":-32601,"message":"Method not found: server/discover"}}` | ✅ 路由正确；**方法仍需自研**（D5） |

⇒ **结论：`SSE（形态一） + Streamable（形态二） + Stateless（形态三）` 可以在同一个 Spring Boot 应用里共存**，代价是**必须自己实现 era 分流**（SDK 不提供），且**必须用 `service()` 直接调用而非 `forward`**。

⚠️ **两点如实说明**：① 探针里 `/probe/mappings` **没有列出 `eraRouter`**，原因是**我自己的过滤词大小写 bug**（查小写 `"router"`，实际名是 `eraRouter`）—— **`[ROUTER]` 日志行本身就是 Router Servlet 在处理 `/mcp` 的直接证据**，且它同时证明 naive 模式下那两个注册**已被 profile 正确排除**；② 本探针只验证了**路由与三种传输可用**，**没有**验证现代协议头（`Mcp-Name` / `_meta.protocolVersion`）的**校验规则**、也**没有**做 MRTR。

## 7. 待办（本文件自身）

- [x] ✅ **`logging` 关不掉** → **已拍板方案 ①**（§6.6）；**报文实证**见 §6.8
- [x] ✅ **`requestTimeout` 核实完成** → 服务端 10s **只管"服务端→客户端"请求、不掐我们的工具执行**；但**官方 SDK 客户端默认 20s** 才是真上限 ⇒ **M9 的 30s 建议需下调**（§6.8.2）
- [x] ✅ **S4 运行验证完成** → **先发现硬冲突**（形态二/三不能同时挂 `/mcp`，后注册者静默失效），**再把 Router 方案跑通** ⇒ **三形态共存可落地**（§6.8.1 / §6.8.3）
- [x] ✅ **M9 上游超时已拍板 = 15s**（§6.8.2），`mcp.upstream_timeout_ms` 默认值已改 `15000`
- [x] ✅ `strictToolNameValidation` 规则已核实（`^[A-Za-z0-9_\-.]+$`、1~128）→ ⚠️ **但运行时 `addTool` 不调用它**（§6.7）
- [x] ✅ **能力为构建期快照** → **已定规则（2026-10-21，#13）**：「工具集为空 ⇒ **完全不声明 `tools`**」（**不是 `{}`**）+ **按租户构造** + **握手后定格** ⇒ 落 `docs/MCP-Server对外接口设计.md` **§6 实现约束 2**
- [ ] **现代协议头校验 + MRTR**（`Mcp-Name` / `_meta.protocolVersion`；本探针未覆盖，见 §6.8.3 尾注）
- [ ] 余下维度：`backend.test` + 三个目录名（`backendDir` / `frontendDir` / `harnessDir`）
- [ ] 冲突自检（本项目自维护矩阵，见第 2 节）
- [ ] 产出最终 frontmatter 供主人 review

## 8. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v0.1 | 2026-10-15 | 新建。锚定讨论背景（内部系统 / Java+TS / JDK 25 + Boot 4）；登记 12 个维度；落盘 Maven Central 与 MCP SDK 合规实测证据 |
| v0.2 | 2026-10-15 | 定案 `backend.framework` = **Spring Boot 4.1.1**、`backend.orm` = **MyBatis-Plus 3.5.17**、`backend.ai` = none；新增**第 6 节：MCP Java SDK 能力逐条核对**（动态工具 ✅、按 Token 过滤 ✅、SSE/Streamable ✅、无状态 🟡 版本对不上）+ **6.1 四项 spike** |
| v0.3 | 2026-10-15 | **spike 实测**：S1 ✅（`mcp-core:2.1.0` 含无状态服务端 + Servlet 无状态传输）、S2 ✅（`McpSyncListFilter` 官方 javadoc 明确 **per request** + `McpTransportContext` 含 HTTP headers/principal）、S4 ✅ 前提（三种传输各自独立可挂载）；**S3 未定 + S4 未运行验证，均被"本机无 JDK"阻塞**（新增 6.3 环境缺口）。**新发现风险 6.2：SDK 明示列表过滤 ≠ 权限，`tools/call` 必须再校验**。**`Discover` 零命中 → `2026-07-28` 自研由推测升级为实测结论**。前端 4 维度按主人要求标为「待前端负责人」**不代填** |
| v0.4 | 2026-10-15 | 🔴 **更正 6.3 我自己的误判**：本机**有 3 个 JDK 25**（IDEA 下到 `.jdks`，未进 PATH）→ 我先前"无 JDK"的结论**作废**。据此**装好 Maven 3.9.16**（`D:\Programs`，与 JDK 25 配对 `mvn -v` 退出码 0）。**S3 真机跑通**（新增 6.5 实测表）：运行时动态工具 ✅、`addTool`/`removeTool` ✅、`tools(false)` 落实 M19 ✅、**不会自动推送** ✅；**暴露冲突 6.6：`logging` 能力关不掉**（显式 `capabilities` 亦然），与 C 类 §6.2/§6.4 落笔冲突，待拍板；另记"能力是构建期快照"隐患 |
| v0.5 | 2026-10-15 | ✅ **新增 6.7：SDK 可用于"全动态组装"的逐条真机结论**（复杂 schema 原样保留 = true；`addTool` 同名即 upsert；300 工具 29 ms；`addToolFilter` per-request）。⚠️ **发现工具名运行时零校验**（`bad name`/`中文工具`/200 字符均"成功"，仅空名被拒）→ **必须管理台侧自校验** `[A-Za-z0-9_\-.]{1,128}`。**🔴 更正我自己的推断**：先前说"中文名会被 SDK 拒绝"是**错的**，真机证明更危险（静默产出非法名）。**6.6 补齐源码级根因**（`McpSchema.java:987` Builder 初值即启用 logging）与"服务端无发日志通知 API"证据 → 建议方案 ①
| v0.7 | 2026-10-16 | 鱼鱼 | ✅ **P2 真传输探针跑通**（新增 **§6.8**：Spring Boot 4.1.1 / Tomcat 11.0.24 / JDK 25 + `curl` 抓真报文）。**7 项实验结果**：① 🔴 **形态二+形态三都挂 `/mcp` → 后注册者拿到空映射、静默失效**（不报错、能启动、该形态永久收不到请求）→ 新增 **§6.8.1 era 分流硬约束**：`forward` **不可行**（provider 用 `getRequestURI().endsWith(endpoint)` 判路径），✅ **直接调 `service()` 可行**，判据取 `Mcp-Method` 头；附带确认 **`context-path` 兼容**；② 🔴 **真实 `initialize` 报文里 `"logging":{}` 确实被发给客户端** → D3 升级为报文实证，`tools.listChanged=false` 正确（M19）；③ 🔴 **被隐藏的工具按名直调成功执行** → D1 报文级实锤；④ **`server/discover` 回 `-32601`** → D5 报文实证；⑤ **逐请求 Token 过滤在真传输上生效**（S2 报文实证）；⑥ 新增 **§6.8.2**：**更正我自己的说法** —— M9 与 L860 早已写"客户端超时是硬上限"，不是新发现；真正新增的是**官方 SDK 客户端默认 20s** ⇒ **上限从 30s 收紧到 20s**，`mcp.upstream_timeout_ms` 默认值需下调。S2/S4 行同步更新 |
| v0.8 | 2026-10-16 | 鱼鱼 | ✅ **两条收口 + Router 跑通**：① **S4 → 已解决** —— 新增 **§6.8.3 Router 方案实测通过**（profile `router`：两个 provider 都配 `/mcp` 但**不注册** `/mcp`，手动 `init()` 后由唯一的 `EraRouterServlet` 独占 `POST /mcp`，按 `Mcp-Method` **直接调 `service()`**）：实测 `initialize`（无头）→ **形态二**（有 `Mcp-Session-Id`）、`Mcp-Method: tools/list` → **形态三**（回 **`tool_gamma`**、**无会话头**）、`server/discover` → 形态三（`-32601`）⇒ **三形态同进程共存可落地**；⚠️ 如实说明探针 `/probe/mappings` 漏列 `eraRouter` 是**我自己的过滤词大小写 bug**，证据以 `[ROUTER]` 日志行为准；② **§6.8.2 M9 定案 = 15s**（`mcp.upstream_timeout_ms` 已改 `15000`），并写明**上游超时 ≠ 端到端上限**；③ S4 行、§6.8.1 标题、§7 待办同步 |
| v0.6 | 2026-10-15 | ✅ **定案 `backend.mcp` = (a) SDK 混合**（`io.modelcontextprotocol.sdk:mcp:2.1.0`），写入 frontmatter（含 4 条遗留跟进项）。新增 **6.6.1 副作用详解**：引用规范原文证明「声明 logging 但不发通知**不违反 MUST**」（MUST 是单向的），并据 `McpSchema.java:807` 的 `@JsonInclude(NON_ABSENT)` 确认**方案 ② 报文层面干净**；两方案逐维度对比后**推荐 ①** |
| v0.9 | 2026-10-21 | 鱼鱼 | ✅ **#13 闭环**：**「能力为构建期快照」已定规则** —— 工具集为空 ⇒ **完全不声明 `tools`**（**不是 `{}`**）+ **按租户构造**（不挂 `McpServer` 单例）+ **握手后定格**（工具增删需客户端重新握手）；落 `docs/MCP-Server对外接口设计.md` **§6 实现约束 2**。§7 待办已勾掉，frontmatter `blocking_followups` 移除已闭环的「能力为构建期快照」与「S4 era 分流」（后者 v0.8 已跑通）。另连带把**兼容性稿 + 数据库稿的 MySQL 版本口径统一为 `8.0.30+`**。⚠️ **本表 v0.6 行仍排在 v0.8 之后（历史遗留）**，本轮未整理 |
