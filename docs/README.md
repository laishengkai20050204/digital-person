# Digital Person 文档

本目录保存运行、架构和运维文档。根目录 `README.md` 介绍项目整体能力；这里记录需要长期维护的详细流程。

## 核心文档

- [活动决策与状态演化链路](./ACTIVITY_STATE_FLOW.md)
  - 一轮自主活动决策的完整执行顺序
  - 活动模型与状态效果模型的职责边界
  - `observation`、状态结算和持久化调度的关系
  - 一致性、失败回滚和并发保护
- [持久化活动调度器](./ACTIVITY_SCHEDULER.md)
  - `nextReviewAt` 的数据库持久化和自动消费
  - 单人物租约、主动续租、重启恢复和多实例抢占
  - 整轮决策 deadline、乐观锁冲突重试和模型失败退避
  - 全局 LLM 并发闸门和费用保护
  - 云服务器启用、观察、暂停和验证命令
- [Spring 装配、人物调度创建与诊断边界](./RUNTIME_WIRING_AND_DIAGNOSTICS.md)
  - 核心应用服务与 Web 适配器的 Bean 所有权
  - 人物聚合与首轮调度记录的同事务创建
  - 高频到期轮询与低频全表补漏的职责拆分
  - 连接测试和原始模型诊断的独立生产开关
- [领域模型精简与保留决策](./DOMAIN_MODEL_CLEANUP.md)
  - 已删除的无调用状态更新入口
  - `Person.create/reconstitute` 生命周期边界
  - HEXACO record 与兼容访问器
  - 为微信对话、主动消息保留的领域类型
- [活动现实性后续待办](./ACTIVITY_REALISM_TODO.md)
  - 自然饥饿、困倦、疲劳和能量演化
  - Java 侧活动持续时长等级
  - 设计边界、验收标准和实施顺序
- [生产环境变量配置](./ENVIRONMENT_CONFIGURATION.md)
  - 唯一应用配置入口 `/etc/person-ai/person-ai.env`
  - systemd `EnvironmentFile` 的加载与检查方式
  - 安全查看变量名、文件权限和 Mem0 专用配置
- [云服务器运行与排障手册](./SERVER_OPERATIONS.md)
  - systemd、健康检查、版本确认和日志命令
  - 环境变量与本地测试助手
  - 人物、事件和活动决策 API 的黑盒测试
  - 常见 HTTP 状态码、部署失败和手动回滚

## 当前自动化边界

启用持久化活动调度器后，系统是“数据库到期触发，轮内全自动”：

1. MySQL 保存每个人物的 `nextReviewAt`。
2. 新人物创建时在同一个 MySQL 事务中创建首轮调度记录。
3. Spring 高频轮询器只抢占调度表中的到期记录；完整人物表补漏独立低频执行。
4. 任务运行期间定期续租，并受整体决策 deadline 约束。
5. Java 自动结算旧状态效果。
6. Java 自动调用活动决策模型。
7. 如有新活动，Java 自动调用状态效果评估模型。
8. 所有模型入口共享全局并发上限。
9. 所有步骤按时成功后，以乐观锁统一保存人物聚合。
10. 调度表保存新的 `nextReviewAt`，等待下一轮。

调度器默认关闭，只有设置 `ACTIVITY_SCHEDULER_ENABLED=true` 后才会产生自动模型调用。原始提示词和模型输出诊断另由 `DIAGNOSTICS_ENABLED` 控制，默认关闭。

## 文档维护规则

- 示例中不得出现真实 API Key、数据库密码或内部令牌。
- 生产命令默认使用服务名 `person-ai`、目录 `/opt/person-ai`、应用环境文件 `/etc/person-ai/person-ai.env`、Mem0 部署环境文件 `/etc/person-ai/mem0.env`。
- 修改 API、环境变量、部署路径或自动化边界时，必须同步更新本目录。
- 运行结果与代码不一致时，以当前 `main` 分支代码和实际部署提交 SHA 为准。
