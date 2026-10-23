# 管理面 MCP 设计（面向 AI 编码 Agent）

> **版本 v0.1**（2026-10-23）｜ **阶段 3 / 不急** —— 本期（阶段 0）**不实现**，先定设计。
> **定位**：这是**管理面**端点，不是数据面。
> - **数据面** = `POST /mcp` —— 对外把本系统的工具**提供给** MCP 客户端调用（见 [`MCP-Server对外接口设计.md`](./MCP-Server对外接口设计.md)）
> - **管理面** = `POST /admin/mcp`（本稿）—— 让 AI agent 把手上的接口**注册进**本系统

---

## 0. 一句话

给 Cursor / Claude Code / Codex 这类**正在写代码的 agent** 一个 MCP 端点，让它把手**上正在开发的接口**直接注册成本系统的服务与工具 —— 用户不用在控制台一个个手工敲。

**典型场景**：用户对 coding agent 说「把这个 controller 里的 5 个接口同步到 MCP 平台」→ agent 读代码 → 调 `tool.batchUpsert` → 本系统里出现 5 个**草稿**工具 → 用户在控制台确认后上架。

---

## 1. 边界

### 1.1 做

- agent **查 / 建 / 改 / 删草稿**
- 从 OpenAPI operation 数组**批量导入**
- **试调用**（联调用，复用 `test-connection` 语义）

### 1.2 不做

| 不做 | 理由 |
| --- | --- |
| ❌ **双向同步**（控制台改动回推用户代码） | 双向同步是灾难源。**本系统为主**：agent 写进来就完事，控制台改了什么**不回推** |
| ❌ **上架 / 改鉴权凭据** | 见 §5 硬规则 ①② |
| ❌ **删除已上架（`enabled=1`）的对象** | 必须人工在控制台先下架 |
| ❌ Git webhook / CI 集成 | 另一种集成形态，另行设计 |
| ❌ `stdio` 传输 | 远端 MCP 只走 HTTP |
| ❌ 配额 / 计费 | 本期整体不做（见决策 #C4） |

---

## 2. 端点与传输

| 项 | 定案 | 依据 |
| --- | --- | --- |
| 路径 | **`POST /admin/mcp`** | 🔴 MCP 客户端**硬约束：端点必须以 `/mcp` 或 `/sse` 结尾**（[`MCP-Server对外接口设计.md`](./MCP-Server对外接口设计.md) §10.5 ⑥）⇒ **不能叫 `/mcp-admin`** |
| 传输 | **只实现形态三：stateless Streamable HTTP** | 管理操作**无会话语义**；多实例部署不必维护 `Mcp-Session-Id` 生命周期；agent 重连即恢复 |
| Servlet | **独立**，**不复用**数据面的 `EraRouterServlet` | 鉴权、限流、审计、能力集、超时全都不同，混在一起必然互相污染 |
| 版本协商 | 同数据面（`2024-11-05` / `2025-03-26` / `2025-06-18` / `2025-11-25` / `2026-07-28`） | 复用同一套协商逻辑，不另写 |
| 能力声明 | `tools: {}`（无 `listChanged`）、**无** `resources` / `prompts` / `logging` | 管理面不需要推送，也不需要对外宣告资源能力 |
| 超时 | 单次 tool 调用 **30s**（`tool.test` 例外见 §4） | 管理操作比数据面转发更重（要写库），但也不该无限等 |

**与数据面的隔离清单**（防止串权，逐项都要在代码里体现）：

| 维度 | 数据面 `/mcp` | 管理面 `/admin/mcp` |
| --- | --- | --- |
| 鉴权 | api-key `mcp_client_token` | **`agent_token`**（§3） |
| 认证后身份 | 租户 + 客户端 | **租户 + 用户 + token** |
| 作用域 | `tools/call` | **管理面工具集**（§4） |
| 限流 | 按客户端 | **按 token**（§9） |
| 审计 | 调用日志 | **写操作全审计**（§8） |

---

## 3. 鉴权：第三套凭据 `agent_token`

### 3.1 为什么不能复用现有两套

| 现有凭据 | 为什么 agent 用不了 |
| --- | --- |
| **console JWT** | 存在 `HttpOnly` cookie 里，**agent 拿不到也带不上**（除非让它做浏览器自动化，那更糟） |
| **数据面 api-key** | 语义是「**调工具**」，如果拿它来管工具 ⇒ 一个只能调用的客户端突然能改定义，**权限串档** |

⇒ **新增第三套：`agent_token`**，与控制台/数据面互不通用。

### 3.2 形态与约束

| 项 | 定案 |
| --- | --- |
| 格式 | `agt_<tenantid base36>_<base62(SecureRandom 32B)>` —— 前缀 `agt_` 便于泄露检测 |
| 存储 | **只存 SHA-256**，明文仅创建时返回一次 |
| 生成入口 | **控制台**「访问管理 → Agent 令牌」（阶段 3 一并做） |
| 作用域 | **读全量 + 写/删草稿**；**不可上架、不可改凭据、不可删已上架** |
| TTL | 默认 **90 天**，可配；到期即 `40100` |
| 吊销 | 控制台一键吊销 ⇒ **立即失效**（查库，不缓存，或缓存 ≤30s 并支持主动失效） |
| 审计 | 每次调用记 `token_id`（不记明文） |

> ⚠️ **假设 A1**：复用 `mcp_client_token` 表，加 `token_type`（`CLIENT` / `AGENT`）+ `user_id` 列，而不是新开表。**影响**：若后续 agent token 要扩出更多字段（如允许的 IP 段），则应独立成表。

---

## 4. 工具集（10 个）

| # | tool | 读/写 | 作用 | 关键约束 |
| --- | --- | --- | --- | --- |
| 1 | `service.list` | R | 列服务（分页 + 名称模糊） | agent 先查再建，**避免撞名** |
| 2 | `service.get` | R | 单服务详情 + 其下工具清单 | 不含鉴权参数值 |
| 3 | `service.upsert` | W | 按 `name` 幂等建/改服务 | **落草稿态**；改名不支持（改名=新建+删旧） |
| 4 | `service.delete` | W | **软删服务** | **仅当该服务下无 `enabled=1` 的工具**，否则 `40003` |
| 5 | `tool.list` | R | 列工具（按服务过滤） | |
| 6 | `tool.upsert` | W | 按 `name` 幂等建/改工具 | 落 `enabled=0`（§5 ①） |
| 7 | `tool.batchUpsert` | W | 吃 **OpenAPI operation 数组**批量 upsert | 单次上限 **50 条**，超出 `40001` |
| 8 | `tool.delete` | W | **软删工具** | **仅 `enabled=0`**，否则 `40003` |
| 9 | `tool.test` | R | 试调用上游，返回状态码/耗时/响应片段 | **超时 5s**（与 `test-connection` 一致）；⚠️ 是否真实外发见假设 A3 |
| 10 | `schema.describe` | R | 返回「本系统要求的最小字段集」 | 让 agent 不必猜字段名 |

### 4.1 删除语义（🔄 2026-10-23 主人拍板：「能删吧，给删」）

**结论：给删，但只在草稿层。** 三条规则：

| 规则 | 说明 |
| --- | --- |
| **D1 草稿可删** | `tool.delete` 只接受 `enabled=0`；`enabled=1` → **`40003`「请先在控制台下架该工具」** |
| **D2 服务级联要干净** | `service.delete` 要求该服务下**所有**工具都是 `enabled=0`；满足则**软删服务并级联软删其下全部工具**（写 `deleted_seq`） |
| **D3 幂等** | 删不存在 / 已删的对象 → **返回成功**（`data: {deleted: false}`），**不报 404** —— agent 重试友好 |

> ⚠️ **假设 A2**：**不区分创建者**（谁建的草稿都能删）。理由：草稿本身不生效、且是软删可恢复，加"只能删自己建的"会让 agent 在重建流程里寸步难行。**影响**：若你希望更严，改成「只能删本 token 创建的」——那就需要在对象上加 `created_by_token_id`。

> **为什么已上架的不给删**：删除 `enabled=1` 的对象会让**正在调用它的外部客户端立刻拿到 `tools/list` 变更**（M19：状态变化不推通知，客户端下次 list 生效），而 agent 无法判断这个工具是否有人在用。**误删的代价不对称**，所以这条底线不给。

### 4.2 `tool.upsert` 的幂等键与撞名处理

| 情况 | 行为 |
| --- | --- |
| `(tenant, tool.name_norm)` 不存在 | 创建（`enabled=0`） |
| 已存在 **且** 属于 `service_id` 相同的服务 | 更新（保留原 `enabled`） |
| 已存在 **但** 属于**另一个服务** | 🔴 **`40002`**「工具名 `X` 已被服务 `Y` 占用」 —— **绝不静默改挂** |

> 依据：`mcp_tool` 的唯一索引是 **`uk_tool_name (tenant_id, name_norm, deleted_seq)`= 租户内唯一**，不是服务内唯一（[`数据库设计.md`](./数据库设计.md) §8.9）。所以撞名必须**显式报错**，否则会悄悄把别的服务的工具挖走。

---

## 5. 四条硬规则

| # | 规则 | 实现位置 |
| --- | --- | --- |
| **①** | **只能写草稿** —— agent 建/改的对象一律 `enabled=0`；本端点**没有**任何能力把它改成 `1` | `tool.upsert` / `service.upsert` 强制覆盖；上架只能走控制台 |
| **②** | 🔴 **凭据是禁区** —— `mcp_service_auth_param.value` **读写全部拒绝**（`40003`） | 防止 agent 把 token 读进自己上下文再泄露给模型供应商 |
| **③** | **删除限草稿层** —— 见 §4.1（`enabled=1` 不给删） | `tool.delete` / `service.delete` |
| **④** | **SSRF 同 A3** —— 上游主机必须能被解析且端口受 `SSRF_ALLOWED_PORTS` 约束；非 `http`/`https` 直接 `40001` | `tool.upsert` 落库前校验 + `tool.test` 外发前再校验 |

---

## 6. 错误码（复用全局 5 位码）

| 码 | 场景 |
| --- | --- |
| `40001` | 必填缺失 / 字段格式错 / `batchUpsert` 超 50 条 / URL 非 http(s) |
| `40002` | 名称冲突（工具名被别的服务占用、服务名重复且非同一对象） |
| `40003` | **状态不允许**：删 `enabled=1` 对象、读写鉴权凭据、尝试上架 |
| `40901` | 并发冲突（`version` 不匹配，见 §7） |
| `40100` | token 无效 / 过期 / 已吊销 |
| `42901` | 限流（§9） |

---

## 7. 并发与版本

- 所有写操作**可选**带 `expected_version`：带了且不匹配 → `40901`，agent 重新 `get` 再写。
- 不带则**后写覆盖**（last-write-wins）—— agent 场景下通常只有一个写者，加乐观锁会平白增加往返。
- ⚠️ **假设 A4**：本期**不建** agent 专用的并发控制，复用对象上的 `version` 列（B2 已定：除 `mcp_config` / `mcp_user` 外都暴露 `version`）。

---

## 8. 审计

**必须落库**（agent 写入是"机器在改"的，出了问题要能追）。

```sql
-- 双库通用；MySQL 用 DATETIME(3)，PG 用 TIMESTAMPTZ
CREATE TABLE mcp_agent_audit (
  id            BIGINT       NOT NULL,           -- 雪花
  tenant_id     BIGINT       NOT NULL,
  token_id      BIGINT       NOT NULL,           -- 指向 mcp_client_token.id（不存明文）
  tool_name     VARCHAR(64)  NOT NULL,           -- 如 tool.batchUpsert
  target_type   VARCHAR(20)  NULL,               -- SERVICE / TOOL / AUTH_PARAM
  target_id     BIGINT       NULL,
  action        VARCHAR(20)  NOT NULL,           -- CREATE / UPDATE / DELETE / READ / TEST
  result        VARCHAR(20)  NOT NULL,           -- OK / REJECTED / ERROR
  error_code    INT          NULL,               -- 失败时的 5 位码
  request_digest CHAR(64)    NULL,               -- 入参 SHA-256，不存原文（防凭据落地）
  client_info   VARCHAR(200) NULL,               -- agent 上报的 clientInfo.name/version
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_agent_audit_tenant_time (tenant_id, created_at),
  KEY idx_agent_audit_token (tenant_id, token_id, created_at)
);
```

**硬约束**：
- `request_digest` **只存哈希**，不存入参原文 —— 否则凭据（虽然被禁）仍可能因 bug 泄漏进审计表
- 审计**只增不改不删**；保留期 ≥ 180 天（运维参数，不进配置页）

---

## 9. 限流（按 token 维度）

| 类别 | 限额 |
| --- | --- |
| 写操作（`*.upsert` / `*.delete` / `*.batchUpsert`） | **60 次/分钟** |
| 读操作（`*.list` / `*.get` / `schema.describe`） | **300 次/分钟** |
| `tool.test` | **20 次/分钟**（会对上游发真实请求） |

超限 → `42901`，响应带 `retryAfterMs`（不是 MCP 标准字段，走工具结果的文本提示）。

---

## 10. 验收标准（可二值判定）

| # | 场景 | 通过条件 |
| --- | --- | --- |
| V1 | agent 建工具 | 调 `tool.upsert` 后 `GET /api/v1/tools/{id}` 能查到，且 **`enabled=false`** |
| V2 | agent 无法上架 | 入参里带 `enabled: true` → **被忽略或 `40003`**，库中仍为 `0` |
| V3 | 凭据禁区 | 带 `authParams` 调 `service.upsert` → **`40003`**，库中鉴权表**无变化** |
| V4 | 删草稿成功 | `tool.delete` 一个 `enabled=0` 工具 → `data.deleted=true`，`deleted_seq != 0` |
| V5 | 删已上架被拒 | `tool.delete` 一个 `enabled=1` 工具 → **`40003`**，`deleted_seq` **不变** |
| V6 | 服务级联干净 | 服务下有 1 个 `enabled=1` 工具时 `service.delete` → `40003`；全部 `enabled=0` 时 → 服务与工具**同批**软删 |
| V7 | 删除幂等 | 对同一工具连调两次 `tool.delete` → 两次都成功，第二次 `data.deleted=false` |
| V8 | 撞名不静默 | 用其他服务已占用的工具名 upsert → **`40002`**，目标工具**未被改挂** |
| V9 | 端点独立性 | 用**数据面 api-key** 调 `/admin/mcp` → **`40100`**；用 **agent_token** 调 `/mcp` → **`40100`** |
| V10 | 审计可追 | 任一写操作后 `mcp_agent_audit` 有对应行，`request_digest` 为 64 位哈希且**不含明文** |

---

## 11. 待确认清单（我按假设写，错了请圈）

| # | 假设 | 若不符的影响 |
| --- | --- | --- |
| A1 | 复用 `mcp_client_token` 加 `token_type` | 否则要新开表 + 改鉴权过滤器 |
| A2 | 删除**不区分创建者**（草稿谁建的都能删） | 若要更严，对象上要加 `created_by_token_id` |
| A3 | `tool.test` **真实外发**（同 `test-connection`） | 若不外发则改为"只校验连通性" |
| A4 | 并发不加锁（last-write-wins） | 若要乐观锁，每个写工具都要带 `expected_version` |
| A5 | 本期只做设计、不写代码 | —— |

---

## 12. 阶段归属

| 阶段 | 内容 |
| --- | --- |
| 阶段 0～2 | **不做**（本期范围不变） |
| **阶段 3** | 本端点 + 控制台「Agent 令牌」页 + 审计表 |

> 关联决策：**#28**（见 [`设计决策记录.md`](./设计决策记录.md) §5）、#C4（不做配额）、#A3（SSRF）、#B2（`version` 暴露）、#M19（状态变化不推通知）、#M7（启用状态）。
