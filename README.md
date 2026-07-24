# Digital Person

一个以领域模型为核心的 Java 21 数字人物原型。当前重点是把人物的性格、事件时间线、短期状态、模型调用和持久化建立成可测试、可替换基础设施的内核。

## 当前能力

- Spring Boot 可执行服务与 Actuator 健康检查
- HEXACO 稳定人格模型
- 人物与用户的独立事件时间线
- 按活动渠道处理并发活动
- 情绪、认知、身体和社交短期状态
- 指数状态变化模型
- 可复用、无状态的 `StateUpdater`
- 异步 `StateTransitionEvaluator` 边界
- 统一的实时事件开始、替换、结束和历史补录应用服务
- 基于版本号 CAS 的并发覆盖保护
- MySQL 8.4、InnoDB、JSON 聚合文档和 Flyway 持久化适配器
- 受令牌保护的人物创建、人物查询和状态查询 HTTP API
- 防御性复制，避免调用方绕过时间线和状态规则
- Spring Boot 默认日志体系下的结构化运行日志
- LangChain4j 1.18.0 与 OpenAI-compatible 模型连接层
- 异步、多消息、工具感知的系统模型边界
- Spring 管理的 OpenRouter 配置与受令牌保护的连接测试接口

## 架构

```text
src/main/java/com/laishengkai/digitalperson/
├── DigitalPersonApplication.java
├── application/
│   ├── PersonDirectoryService.java
│   ├── UpdatePersonStateService.java
│   ├── PersonEventCommandService.java
│   ├── StateEvaluationContextAssembler.java
│   └── PersonVersionConflictException.java
├── dialogue/
│   ├── LanguageModelGateway.java
│   ├── LanguageModelRequest.java
│   ├── LanguageModelResponse.java
│   ├── ModelMessage.java
│   ├── SystemModelMessage.java
│   ├── UserModelMessage.java
│   ├── AssistantModelMessage.java
│   ├── ToolResultModelMessage.java
│   ├── ModelToolCall.java
│   ├── ModelToolSpecification.java
│   └── LanguageModelException.java
├── experience/
├── infrastructure/
│   ├── langchain4j/
│   ├── persistence/mysql/
│   │   ├── JdbcPersonRepository.java
│   │   ├── PersonAggregateJsonMapper.java
│   │   ├── MySqlPersonPersistenceConfiguration.java
│   │   └── MySqlPersonPersistenceProperties.java
│   └── spring/
│       └── PersonApplicationConfiguration.java
├── web/
│   ├── PersonController.java
│   ├── PersonApiProperties.java
│   └── PersonApiExceptionHandler.java
├── person/
├── personality/
└── state/
    ├── StateUpdater.java
    ├── StateEvolutionContext.java
    ├── StateUpdatePreparation.java
    └── StateTransitionEvaluator.java
```

领域层和应用层不依赖 Spring、LangChain4j、Jackson、JDBC、Flyway、MySQL、Mem0 或微信。Spring Boot 负责运行时装配；`dialogue.LanguageModelGateway` 是系统自己的模型边界；数据库实现只存在于 `infrastructure.persistence.mysql`。

## Spring Boot 运行方式

本项目使用 Java 21 和 Spring Boot 4.1，默认监听 `8080` 端口。

```bash
mvn spring-boot:run
```

打包并运行可执行 JAR：

```bash
mvn clean verify
java -jar target/digital-person-0.3.0-SNAPSHOT.jar
```

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

可以通过环境变量修改端口：

```bash
SERVER_PORT=8081 java -jar target/digital-person-0.3.0-SNAPSHOT.jar
```

## MySQL 人物持久化

持久化默认关闭，因此现有部署在没有数据库配置时仍能正常启动。启用时使用 MySQL 8.4、InnoDB 和原生 `JSON` 列：

```bash
export MYSQL_PERSISTENCE_ENABLED=true
export MYSQL_JDBC_URL='jdbc:mysql://127.0.0.1:3306/digital_person?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
export MYSQL_USERNAME='digital_person'
export MYSQL_PASSWORD='replace-with-a-strong-password'
export MYSQL_MAXIMUM_POOL_SIZE=10
export MYSQL_CONNECTION_TIMEOUT=30s
```

启动时 Flyway 自动执行 `db/migration/mysql` 下的迁移。首个版本创建：

```text
digital_person
├── person_id        CHAR(36) PRIMARY KEY
├── version          BIGINT
├── aggregate_json   JSON
├── created_at       TIMESTAMP(6)
└── updated_at       TIMESTAMP(6)
```

一个 JSON 文档保存完整聚合：人格、当前状态、人物事件、用户事件、状态更新时间以及每个活动渠道的当前状态效果。文档包含独立的 `schemaVersion`，以后修改结构时必须增加显式迁移或兼容读取逻辑。

保存使用原子乐观锁：

```sql
UPDATE digital_person
SET aggregate_json = ?,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE person_id = ?
  AND version = ?;
```

受影响行数为 `1` 表示提交成功；为 `0` 表示人物不存在或已有更新先提交。应用层会把后者转换为版本冲突，不会让较慢的旧 LLM 结果覆盖新状态。

## 人物 HTTP API

人物 API 默认关闭。启用时必须提供独立令牌，当前用于服务器内部调用；以后接入统一身份认证后再替换：

```bash
export PERSON_API_ENABLED=true
export PERSON_API_TOKEN='use-a-long-random-token'
```

所有请求都必须携带：

```text
X-Internal-Token: use-a-long-random-token
```

创建人物：

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Token: use-a-long-random-token' \
  -d '{
    "personality": {
      "honestyHumility": 0.6,
      "emotionality": 0.7,
      "extraversion": 0.4,
      "agreeableness": 0.8,
      "conscientiousness": 0.6,
      "openness": 0.9
    }
  }' \
  http://127.0.0.1:8080/api/persons
```

查询人物及状态：

```bash
curl \
  -H 'X-Internal-Token: use-a-long-random-token' \
  http://127.0.0.1:8080/api/persons/{personId}

curl \
  -H 'X-Internal-Token: use-a-long-random-token' \
  http://127.0.0.1:8080/api/persons/{personId}/state
```

响应使用字符串 UUID，包含持久化版本、HEXACO 人格、当前短期状态、事件数量、状态更新时间和当前有效效果渠道。非法请求返回稳定错误码；令牌、数据库密码和人物内容不会写入日志。

## 模型边界

`LanguageModelGateway` 表示一次模型调用，不保存历史，也不执行工具循环：

```text
业务用例
  ↓ 组装完整消息列表、调用选项和可见工具
LanguageModelGateway.invoke(...)
  ↓ CompletionStage
LangChain4jLanguageModel
  ↓ 在虚拟线程执行阻塞式 LC4j ChatModel
OpenRouter / OpenAI-compatible API
```

请求由以下部分组成：

- `List<ModelMessage>`：System、User、Assistant 和 ToolResult 消息
- `ModelInvocationOptions`：temperature、最大输出 token、停止序列、工具选择和响应格式
- `List<ModelToolSpecification>`：本次调用允许模型请求的工具

工具请求属于 `AssistantModelMessage.toolCalls`，因为它由 assistant 角色产生。应用执行工具后，再加入对应的 `ToolResultModelMessage` 并进行下一次模型调用。循环次数、工具执行、超时和权限控制由独立的 Agent 执行层负责，不属于单次模型网关。

响应保留 assistant 文本、工具请求、结束原因和 token 使用量。状态评估可以只使用单次结构化调用；聊天历史由应用服务加载后作为消息列表传入。

## OpenRouter 连接

运行时链路：

```text
Spring 配置
  ↓
LanguageModelGateway Bean
  ↓
LangChain4jLanguageModel
  ↓
OpenAiChatModel
  ↓
OpenRouter /api/v1/chat/completions
```

### 服务器环境变量

模型连接默认关闭，因此没有密钥时服务仍能正常启动和部署。启用 OpenRouter 时设置：

```bash
export LLM_ENABLED=true
export LLM_BASE_URL=https://openrouter.ai/api/v1
export LLM_API_KEY=your-openrouter-api-key
export LLM_MODEL=provider/model-name
export LLM_TIMEOUT=60s
export LLM_MAX_RETRIES=2
```

`LLM_MODEL` 必须使用 OpenRouter 模型标识。API Key 不会被配置对象或日志输出。

### 连接测试接口

连接测试接口默认关闭。需要人工验证真实请求时，再设置：

```bash
export LLM_CONNECTION_TEST_ENABLED=true
export LLM_CONNECTION_TEST_TOKEN='use-a-long-random-token'
```

调用：

```bash
curl -X POST \
  -H 'X-Internal-Token: use-a-long-random-token' \
  http://127.0.0.1:8080/internal/llm/connection-test
```

成功响应：

```json
{
  "status": "UP",
  "modelResponse": "CONNECTION_OK"
}
```

接口使用固定提示词，不接受调用方提供的 Prompt；令牌错误返回 `401`，供应商调用失败或返回异常内容时返回 `502`。不要将连接测试令牌提交到仓库。

适配器不会记录 System Prompt、用户消息、工具参数、工具结果或模型回复，只记录模型名、服务端主机、消息数量、文本长度、工具数量、调用编号和耗时。

## 状态更新流程

```text
PersonRepository.findById
  ↓ Person + version
复制完整 Person 和当前 PersonState
  ↓
StateUpdater.prepare
  ├─ 按事件真实结束时间分段结算旧活动效果
  └─ 找出需要重新评估的新活动
  ↓
异步 StateTransitionEvaluator
  ↓
StateUpdater.complete
  ↓
Person.commitStateUpdate
  ↓
PersonRepository.save(person, expectedVersion)
  ├─ CAS 成功：version + 1
  └─ CAS 失败：拒绝过期结果
```

`StateUpdater` 不保存任何人物专属状态。`lastUpdatedAt` 和每个活动渠道的效果保存在不可变的 `StateEvolutionContext` 中，并与人物一起持久化。

如果 LLM 评估失败，工作副本不会提交；如果评估期间人物被其他请求更新，旧结果也不会覆盖数据库中的新版本。

实时人物事件必须通过 `PersonEventCommandService`：

- `start`：先结算旧效果，再开始或替换事件并评估新效果
- `finish`：把效果精确结算到结束时间，再移除对应渠道效果
- `recordHistorical`：只记录过去事件，不回放、不修改当前短期状态

## 日志

项目代码通过 SLF4J API 写日志，由 Spring Boot 管理 Logback 运行时。默认输出到标准输出，方便 Docker、systemd 或云平台统一采集；项目本身不直接管理日志文件。

默认日志级别为 `INFO`，可以使用环境变量调整：

```bash
export LOG_LEVEL=INFO
export STATE_LOG_LEVEL=DEBUG
export EVENT_LOG_LEVEL=DEBUG
```

日志只记录标识符、模型名、服务端主机、活动类型、渠道、时间、文本长度和数量等结构化信息。禁止记录聊天正文、事件标题、地点、参与者、备注、提示词、API Key、工具参数、工具结果、数据库密码、内部测试令牌和长期记忆内容。

测试使用独立的 `logback-test.xml`，默认只输出 `WARN` 和 `ERROR`。

## 构建

```bash
mvn verify
```

GitHub Actions 使用 Java 21 执行编译、Spring 上下文启动测试、单元测试、MySQL 8.4 Testcontainers 集成测试和 JaCoCo 报告生成。测试不会向真实模型供应商发送网络请求。

## 下一步

当前事件 API、活动调度、租约心跳、Agent 执行边界和 Mem0 读写适配已经落地。下一阶段重点是：

1. 完成正式对话编排，并在对话成功后调用已提供的 `DialogueMemoryRecorder` 异步提取长期记忆
2. 持久化最近原始对话，为 `RecentConversationGateway` 提供真实数据源
3. 增加记忆写入失败补偿、质量评估和可观测性
4. 增加调度管理 API、Prometheus 指标和按人物成本预算
5. 验证底层模型供应商对 `CompletableFuture.cancel(true)` 的主动取消支持
