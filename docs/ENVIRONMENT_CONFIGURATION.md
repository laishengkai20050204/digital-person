# 生产环境变量配置

本文记录 `person-ai` 在生产服务器上的环境变量存放位置、systemd 加载方式和安全检查命令。

## 唯一应用配置入口

生产 Java 应用的环境变量统一保存在：

```text
/etc/person-ai/person-ai.env
```

其中包括：

- `MYSQL_*`：MySQL 连接与连接池配置；
- `PERSON_*`：人物 API 开关和内部令牌；
- `LLM_*`：模型地址、API Key、模型 ID、超时和连接测试；
- `ACTIVITY_SCHEDULER_*`：自主活动调度器配置；
- `DIAGNOSTICS_*`：原始模型诊断开关；
- `MEM0_*`：Java 应用访问 Mem0 的开关、地址和 API Key；
- `SPRING_*`、`SERVER_*`：Spring MVC 和服务端口配置。

编辑时使用：

```bash
sudoedit /etc/person-ai/person-ai.env
```

不要把该文件完整打印、截图、提交到 Git 或粘贴到聊天中。

## systemd 加载方式

生产 unit 位于：

```text
/etc/systemd/system/person-ai.service
```

它通过以下声明读取应用环境文件：

```ini
EnvironmentFile=-/etc/person-ai/person-ai.env
```

开头的 `-` 表示文件不存在时 systemd 不会仅因为该文件缺失而拒绝启动。因此，配置文件缺失时 Java 仍可能按默认开关启动，但 MySQL、人物 API、LLM 和 Mem0 等功能会被关闭。

确认 systemd 读取的文件路径：

```bash
sudo systemctl show person-ai \
  -p FragmentPath \
  -p DropInPaths \
  -p EnvironmentFiles \
  --no-pager
```

## 为什么 `Environment` 可能为空

下面的命令只显示直接写在 unit 的 `Environment=` 项：

```bash
sudo systemctl show person-ai -p Environment --value
```

从 `EnvironmentFile=` 加载的变量不应依赖该字段判断。因此，即使这条命令没有输出，也不代表 `/etc/person-ai/person-ai.env` 不存在或没有配置。

检查环境文件是否存在：

```bash
sudo test -f /etc/person-ai/person-ai.env \
  && echo 'person-ai.env 存在' \
  || echo 'person-ai.env 不存在'
```

只显示变量名，不显示任何值：

```bash
sudo grep -E '^[A-Z0-9_]+=' /etc/person-ai/person-ai.env \
  | sed 's/=.*$/=<hidden>/'
```

检查运行中 Java 进程的非敏感变量：

```bash
PID="$(systemctl show person-ai -p MainPID --value)"

sudo sh -c "tr '\0' '\n' < /proc/$PID/environ" \
  | grep -E '^(MYSQL_PERSISTENCE_ENABLED|PERSON_API_ENABLED|LLM_ENABLED|LLM_MODEL|ACTIVITY_SCHEDULER_ENABLED|MEM0_ENABLED|SERVER_PORT)='
```

禁止无过滤地输出 `/proc/<pid>/environ`，其中可能包含 API Key、数据库密码和内部令牌。

## 文件权限

推荐权限：

```bash
sudo chown root:deploy /etc/person-ai/person-ai.env
sudo chmod 640 /etc/person-ai/person-ai.env
```

验证：

```bash
sudo stat -c '%U:%G %a %n' /etc/person-ai/person-ai.env
```

预期类似：

```text
root:deploy 640 /etc/person-ai/person-ai.env
```

## Mem0 专用部署配置

Mem0 的 Docker 部署和模型提供商配置单独保存在：

```text
/etc/person-ai/mem0.env
```

两份文件职责不同：

```text
/etc/person-ai/person-ai.env
Java 应用运行配置，包括 Java 调用 Mem0 所需的 MEM0_ENABLED、MEM0_BASE_URL 和 MEM0_API_KEY。

/etc/person-ai/mem0.env
ops/ensure-mem0.sh 使用的部署配置，包括 MEM0_AUTO_INSTALL_ENABLED、Mem0 版本、记忆提取 LLM 和 embedding 提供商配置。
```

推荐权限：

```bash
sudo chown root:deploy /etc/person-ai/mem0.env
sudo chmod 640 /etc/person-ai/mem0.env
```

## 修改后的生效顺序

先验证配置文件仍可读取，再重启应用：

```bash
sudo -u deploy test -r /etc/person-ai/person-ai.env
sudo systemctl restart person-ai
curl -fsS http://127.0.0.1:8080/actuator/health | jq
```

如果修改的是 Mem0 部署配置，先执行并验证 Mem0：

```bash
cd /opt/person-ai/builds/<commit>
bash ops/ensure-mem0.sh
curl -fsS http://127.0.0.1:8888/auth/setup-status | jq
```

随后再重启 Java 应用。
