# Mem0 语义去重与历史清理

## 目标

对话记忆写入前先查询同一人物已有的 Mem0 记忆，减少同一长期事实因近义复述而反复新增，同时保留事实变化、纠正和新增细节。

## 写入前去重

去重只作用于：

- `infer=true`；
- `metadata.source=dialogue`；
- 用户消息不包含明确纠错标记。

默认流程：

1. 使用当前用户消息查询同一 `agent_id` 的最多 5 条候选记忆；
2. 候选语义分数至少达到 `0.62`；
3. 用户消息与候选记忆的字符二元组 Dice 相似度至少达到 `0.30`；
4. 候选来源、归属人和记忆分区与当前对话兼容；
5. 同时满足以上条件时跳过 `/memories` 写入，并记录不含正文的 INFO 日志。

双门槛用于区分“同一事实换一种说法”和“同一主题下发生了另一件事”。单纯主题相关但文字内容不同的记忆仍会写入。

出现以下纠错或变化表达时，直接绕过去重并交给 Mem0 处理：

```text
更正、纠正、之前说错、其实不是、不再、已经不、改成、改为、换成、取消、停止、放弃、相反
```

## 故障语义

查重搜索失败时采用 fail-open：

- 正常对话回复不受影响；
- 继续执行原始 Mem0 写入；
- 日志记录 `Mem0 duplicate check failed; proceeding with recording`；
- 不打印用户消息或候选记忆正文。

该策略优先避免因查重服务临时故障而丢失长期记忆。

## 配置

```bash
MEM0_DEDUPLICATION_ENABLED=true
MEM0_DUPLICATE_SEMANTIC_THRESHOLD=0.62
MEM0_DUPLICATE_TEXT_THRESHOLD=0.30
MEM0_DUPLICATE_MAX_CANDIDATES=5
```

建议先保留默认值。调高阈值会减少误判但放过更多重复；调低阈值会更积极地去重，但可能抑制相近的新事实。

临时关闭：

```bash
MEM0_DEDUPLICATION_ENABLED=false
```

## 日志验收

重复事实被抑制时：

```text
Mem0 dialogue recording suppressed as a semantic duplicate
```

新事实正常写入时：

```text
Dialogue memory recording completed asynchronously: ... mutationCount=1
```

查重命中后，外层异步完成日志仍可能显示 `mutationCount=0`，应结合上面的 duplicate suppressed 日志判断。

## 历史记忆清理

只按经过人工确认的 memory ID 删除，不根据宽泛关键词自动批量删除。

预览：

```bash
cd /opt/person-ai/builds/<commit>
sudo bash ops/mem0-delete-memories.sh --dry-run \
  <memory-id-1> \
  <memory-id-2>
```

执行：

```bash
sudo bash ops/mem0-delete-memories.sh \
  <memory-id-1> \
  <memory-id-2>
```

脚本只读取 `/etc/person-ai/person-ai.env` 中的 `MEM0_BASE_URL` 和 `MEM0_API_KEY`，不会输出密钥，并拒绝包含不安全字符的 memory ID。

删除后重新调用 `/search`，确认目标 ID 不再出现，同时保留的规范记忆仍可检索。
