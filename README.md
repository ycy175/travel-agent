# 企业智能差旅 Agent 平台

基于 **AgentScope 2.x（Harness + ReAct）** 与 **Spring Boot 4** 的差旅智能助手：
Agent 负责理解需求、查差标、起草审批单；**人负责审批**；审批通过后 Agent 才能下单。

> 核心设计原则：LLM 只负责"表达与规划"，所有身份、权限、金额、状态、幂等都由服务端校验。
> 模型可以被提示词影响，但绕不过业务规则。

## 界面预览

登录页：

![登录页](docs/screenshots/login.png)

对话与审批：

![对话与审批](docs/screenshots/chat.png)

---

## 1. 架构

```
Browser (index.html)
   │  SSE / REST + HMAC Token
   ▼
AuthInterceptor ── RateLimitInterceptor ── ChatController
   │                                        │
   │                                        ├─ AgentScope HarnessAgent（工具调用 / 计划模式 / 上下文压缩）
   │                                        └─ 会话与消息落库
   ▼
ApprovalService ── PolicyService ── OrderService ── SupplierGateway（Mock 机票/酒店）
   │                 │                │
   └── 审批状态机     └── 差标规则库    └── 订单 + 唯一索引幂等
   ▼
AuditService（审计）  UsageService（Token 预算）
   ▼
H2（可替换 MySQL/PostgreSQL）
```

分层：

| 层 | 职责 |
|---|---|
| `sse/` | 对话入口、SSE 事件分发、消息持久化、Token 计费 |
| `auth/` | 登录、PBKDF2 密码、HMAC Token、鉴权拦截器 |
| `chat/` | 会话与消息仓储、会话 REST 接口 |
| `approval/` | 审批单、状态机、RBAC、乐观锁 |
| `policy/` | 差标规则匹配与合规校验 |
| `order/` | 订单用例、供应商防腐层、Mock 供应商 |
| `tools/` | Agent 工具（身份由 RuntimeContext 注入，全部写审计） |
| `audit/` | 审计日志写入与查询 |
| `usage/` | 月度 Token 预算 |
| `ratelimit/` | 进程内令牌桶 |

---

## 2. 核心链路

1. 用户："帮我订下周三北京到上海的机票，3500 以内"
2. Agent 调 `query_travel_policy` → 按登录员工职级 + 城市查差标
3. Agent 调 `submit_travel_approval` → 生成 `PENDING` 审批单（幂等，重复调用返回同一单）
4. 前端展示审批卡片；本人可确认 ≤5000 元的单，超过需**同部门审批人/管理员**
5. 审批通过后前端自动续跑，Agent 调 `book_travel`
6. `OrderService` 校验：审批状态必须 `APPROVED/BOOKED`、差标必须合规、同审批单同类型只能一单
7. 调用 Mock 供应商 → 订单 `BOOKED`，审批单联动 `BOOKED`
8. 全过程写审计；对话 Token 计入月度预算

---

## 3. 数据表

| 表 | 说明 |
|---|---|
| `app_user` | 员工账号、职级、角色、部门 |
| `chat_session` / `chat_message` | 会话与消息（含 token、成本） |
| `travel_approval` | 审批单，`version` 乐观锁 |
| `travel_policy` | 差标规则（职级 × 城市，`*` 为默认） |
| `travel_order` | 订单，`(approval_id, type)` 唯一索引 |
| `audit_log` | 审计日志 |
| `idempotency_record` | 通用幂等记录 |

---

## 4. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录（按 IP 限流） |
| GET | `/api/auth/me` | 当前用户 |
| GET/POST | `/api/sessions` | 会话列表 / 新建 |
| PATCH/DELETE | `/api/sessions/{id}` | 重命名、置顶 / 删除 |
| GET | `/api/sessions/{id}/messages` | 历史消息 |
| GET | `/api/chat/stream` | SSE 对话（`sessionId`、`token`、`message`） |
| GET | `/api/approvals?sessionId=` | 会话内审批单 |
| POST | `/api/approvals/{id}/approve` `/reject` | 审批 |
| GET | `/api/approvals/inbox` | 审批人待办（同部门） |
| GET | `/api/orders?approvalId=` | 审批单对应订单 |
| GET | `/api/orders/mine` | 我的订单 |
| GET | `/api/usage` | 本月 token 用量 |
| GET | `/api/audit` | 全量审计（仅 ADMIN） |
| GET | `/api/audit/mine` | 我的审计 |

---

## 5. 启动与演示

```bash
export DEEPSEEK_API_KEY=sk-xxx       # Windows: set DEEPSEEK_API_KEY=sk-xxx
./mvnw spring-boot:run
# 打开 http://localhost:8080
```

演示账号：

| 账号 | 密码 | 角色 | 部门 |
|---|---|---|---|
| zhangsan | 123456 | EMPLOYEE（P6） | 研发部 |
| lisi | 123456 | EMPLOYEE（M3） | 市场部 |
| wangwu | 123456 | APPROVER（M3） | 研发部 |
| admin | admin123 | ADMIN | 信息中心 |

推荐演示：

1. `zhangsan` 登录 → "查一下去上海的差标" → 返回 600 元/晚（城市专项）
2. 提交 3500 元审批 → 审批卡片点"通过"（≤5000 本人可确认）→ 自动续跑
3. 让 Agent 订机票 1280 元 → 订单 `BOOKED`，审批单变 `BOOKED`
4. 再订 1500 元/晚的酒店 → 被差标拦截
5. `admin` 登录 → 审计日志看到 LOGIN / TOOL_CALL / APPROVAL_APPROVE / ORDER_BOOK / CHAT

---

## 6. 安全与治理

- **身份**：HMAC Token（12h），`/api/**` 统一拦截；SSE 用 `?token=`（EventSource 限制）
- **密码**：PBKDF2WithHmacSHA256，12 万次迭代，随机盐
- **越权**：会话按 `user_id` 过滤；审批按"本人 / 同部门审批人 / 管理员"判定
- **金额**：≤5000 本人确认，超过必须他人审批，禁止自己批自己
- **幂等**：提交审批用 `userId|sessionId|行程|金额` 的 MD5；下单用唯一索引
- **差标**：舱位按等级比较，酒店按每晚均价比较
- **限流**：对话 10 次/分钟/用户；登录 5 次/分钟/IP
- **预算**：每用户每月 200 万 token
- **审计**：工具调用、审批、下单、登录、对话用量全留痕

---

## 7. Docker 部署

```bash
# 宿主机设置密钥（Windows PowerShell：$env:DEEPSEEK_API_KEY="sk-xxx"）
export DEEPSEEK_API_KEY=sk-xxx

docker compose up --build -d
# 打开 http://localhost:8080
```

镜像采用 `classes + libs/*` 展开式布局，而不是 Spring Boot fat jar：应用启动 MCP 子进程时依赖 `java.class.path`，fat jar 下该值只有 `app.jar`，子进程会加载不到 `FlightMcpServer`，`search_flights` 工具会失效。

- 数据库文件持久化在 `travel-data` 卷（容器内 `/app/data`）
- Agent 工作区持久化在 `agent-workspace` 卷（`/app/.agentscope`）
- `TRAVEL_TOKEN_SECRET` 未设置时使用开发默认值，生产必须覆盖
- 停止：`docker compose down`；连数据卷一起清理：`docker compose down -v`

---

## 8. 持续集成

`.github/workflows/ci.yml` 在 push / pull request 时执行：

```bash
./mvnw -B -ntp test
```

使用 JDK 17 + Maven 缓存，覆盖 23 个测试（集成测试使用 H2 内存库，不依赖外部服务）。

---

## 9. 已知限制与演进方向

1. 限流是单机令牌桶，多实例需 Redis/网关
2. 下单把外部调用放在事务内，生产建议本地消息表（Outbox）/ Saga
3. 每次对话新建 `HarnessAgent` 并 `synchronized`，高并发应改为按会话缓存
4. 未接入 AgentScope 原生 `RequireUserConfirmEvent` 的确认回传，当前用业务审批单承接
5. 供应商为 Mock；接真实供应商只需实现 `SupplierGateway`
6. 前端 Token 存 localStorage，生产建议 HttpOnly Cookie + CSRF
7. Dockerfile 尚未纳入 CI 构建验证，首次构建需联网拉取基础镜像与 Maven 依赖
