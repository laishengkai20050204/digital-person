# 结构化记忆启用清单

1. 先部署包含 Flyway V6/V7 的版本，确认结构化记忆表、证据表和提取游标表创建成功。
2. 设置 `STRUCTURED_MEMORY_ENABLED=true` 和 `MEMORY_TEST_API_ENABLED=true` 后重启；测试接口依赖结构化 Repository，因此关闭结构化记忆时不会注册。
3. 通过受保护接口写入实体、别名和事实样本，验证错字解析、有效期过滤、幂等事实更新和 Mem0 降级行为。
4. 删除测试数据，关闭 `MEMORY_TEST_API_ENABLED`，保留 `STRUCTURED_MEMORY_ENABLED=true`。
5. 先保持 `STRUCTURED_MEMORY_EXTRACTION_ENABLED=false`，确认普通对话、摘要和 Mem0 正常。
6. 开启自动提取后观察 `Structured-memory extraction completed` 日志、游标推进和候选事实质量。
7. 自动提取按稳定批次运行，默认保留最近 2 条原始消息、每次处理 8 条；回复生成不等待提取完成。
8. 需要立即止损时只关闭 `STRUCTURED_MEMORY_EXTRACTION_ENABLED` 并重启；已有结构化事实和提取游标不会被删除。
