# Digital Person

一个以领域模型为核心的 Java 21 数字人物原型。当前重点是把人物的性格、事件时间线、短期状态和模型调用建立成可持久化、可测试、可替换基础设施的内核。

## 当前能力

- Spring Boot 可执行服务与 Actuator 健康检查
- HEXACO 稳定人格模型
- 人物与用户的独立事件时间线
- 按活动渠道处理并发活动
- 情绪、认知、身体和社交短期状态
- 指数状态变化模型
- 可复用、无状态的 `StateUpdater`
- 异步 `StateTransitionEvaluator` 边界
- `PersonId`、`PersonRepository` 和应用层状态更新服务
- 防御性复制，避免调用方绕过时间线和状态规则
- Spring Boot 默认日志体系下的结构化运行日志
- LangChain4j 1.18.0 与 OpenAI-compatible 模型的最小连接层

## 架构

```text
src/main/java/com/laishengkai/digitalperson/
├── DigitalPersonApplication.java
├── application/
│   ├── UpdatePersonStateService.java
│   ├── StateUpdateResult.java
│   └── PersonNotFoundException.java
├── dialogue/
│   ├── LanguageModelGateway.java
│   ├── LanguageModelRequest.java
│   ├── LanguageModelResponse.java
│   └── LanguageModelException.java
├── experience/
│   ├── EventTimeline.java
│   ├── PersonEvent.java
│   └── TimeRange.java
├── infrastructure/
│   └── langchain4j/
│       ├── LangChain4jLanguageModel.java
│       └── LangChain4jModelConfig.java
├── person/
│   ├── Person.java
│   ├── PersonId.java
│   └── PersonRepository.java
├── personality/
└── state/
    ├── StateUpdater.java
    ├── StateEvolutionContext.java
    ├── StateUpdatePreparation.java
    └── StateTransitionEvaluator.java
```

领域层不依赖 Spring、LangChain4j、Mem0、数据库或微信。Spring Boot 只负责应用启动和运行时基础设施；`dialogue.LanguageModelGateway` 是系统自己的模型边界，LangChain4j 只存在于 `infrastructure.langchain4j` 中。

## Spring Boot 最小运行方式

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

当前 Spring Boot 层只提供启动入口、嵌入式 Web 服务器和健康检查，不提前加入 Controller、数据库或复杂 Bean 配置。

## LangChain4j 最小连接

当前只实现一次普通文本调用：

```text
LanguageModelRequest
  ↓
LanguageModelGateway
  ↓
LangChain4jLanguageModel
  ↓
OpenAiChatModel
  ↓
OpenAI-compatible API
```

这一阶段还没有接入聊天历史、工具循环、Mem0、主动消息或 `ChatApplicationService`。

### 环境变量

```bash
export LLM_BASE_URL=https://openrouter.ai/api/v1
export LLM_API_KEY=your-api-key
export LLM_MODEL=provider/model-name
export LLM_TIMEOUT_SECONDS=60
export LLM_MAX_RETRIES=2
```

`LLM_BASE_URL` 可以替换为任何 OpenAI-compatible API 地址。API Key 不会被配置对象或日志输出。

### Java 调用示例

```java
LangChain4jModelConfig config = LangChain4jModelConfig.fromEnvironment();
LanguageModelGateway model = new LangChain4jLanguageModel(config);

LanguageModelResponse response = model.generate(
        new LanguageModelRequest(
                "你是数字人物系统的模型连接测试。",
                "回复一句连接成功。"
        )
);

System.out.println(response.text());
```

适配器不会记录 System Prompt、用户消息或模型回复，只记录模型名、服务端主机、文本长度、调用编号和耗时。

## 状态更新流程

```text
读取 Person
  ↓
复制当前 PersonState
  ↓
StateUpdater.prepare
  ├─ 结算旧活动效果
  └─ 找出需要重新评估的新活动
  ↓
异步 StateTransitionEvaluator
  ↓
StateUpdater.complete
  ↓
Person.commitStateUpdate
  ↓
PersonRepository.save
```

`StateUpdater` 不保存任何人物专属状态。`lastUpdatedAt` 和每个活动渠道的效果保存在不可变的 `StateEvolutionContext` 中，并与人物一起持久化。

如果 LLM 评估失败，工作副本不会提交到 `Person`，也不会调用 Repository 保存。

## 日志

项目代码通过 SLF4J API 写日志，由 Spring Boot 管理 Logback 运行时。默认输出到标准输出，方便 Docker、systemd 或云平台统一采集；项目本身不直接管理日志文件。

默认日志级别为 `INFO`，可以使用环境变量调整：

```bash
export LOG_LEVEL=INFO
export STATE_LOG_LEVEL=DEBUG
export EVENT_LOG_LEVEL=DEBUG
```

日志覆盖以下关键流程：

- 人物状态更新的开始、完成、失败和耗时
- 待评估活动渠道及状态结算过程
- 事件开始、替换、结束、记录和删除
- 模型客户端配置以及模型调用成功、失败和耗时

日志只记录 `PersonId`、`EventId`、模型名、服务端主机、活动类型、渠道、时间、文本长度和数量等结构化信息。禁止记录聊天正文、事件标题、地点、参与者、备注、提示词、API Key 和长期记忆内容。

测试使用独立的 `logback-test.xml`，默认只输出 `WARN` 和 `ERROR`。

## 注释规范

- 对聚合根、应用服务、不可变值对象和重要领域服务使用类级 Javadoc。
- 对存在事务边界、异步边界或不明显副作用的方法说明前置条件与失败语义。
- 不为显而易见的 getter 或简单赋值逐行添加注释。
- 注释解释“为什么”和业务约束，不重复代码已经清楚表达的“做什么”。

## 构建

```bash
mvn verify
```

GitHub Actions 会使用 Java 21 执行编译、Spring 上下文启动测试、单元测试和 JaCoCo 报告生成。单元测试不会向真实模型供应商发送网络请求。

## 下一步

1. 使用 `LanguageModelGateway` 实现 `StateTransitionEvaluator`
2. 增加聊天应用服务和消息历史接口
3. 实现数据库版 `PersonRepository`
4. 接入 Mem0 长期记忆
5. 增加乐观锁，防止同一人物的并发更新覆盖
