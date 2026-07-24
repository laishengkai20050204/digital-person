# 服务器环境变量配置说明

## person-ai 服务配置位置

生产环境中的 Spring Boot 服务通过 systemd 的 `EnvironmentFile` 加载配置：

```text
/etc/person-ai/person-ai.env
```

systemd 配置示例：

```text
EnvironmentFile=-/etc/person-ai/person-ai.env
```

该文件由服务器维护，不提交到 Git 仓库。

## 当前主要配置项

### OpenRouter / LLM

```bash
LLM_ENABLED=true
LLM_BASE_URL=https://openrouter.ai/api/v1
LLM_API_KEY=<openrouter-api-key>
LLM_MODEL=<provider/model-name>
```

### MySQL

```bash
MYSQL_PERSISTENCE_ENABLED=true
MYSQL_JDBC_URL=<jdbc-url>
MYSQL_USERNAME=<username>
MYSQL_PASSWORD=<password>
```

### 人物 API

```bash
PERSON_API_ENABLED=true
PERSON_API_TOKEN=<internal-token>
```

## Mem0 配置

Mem0 自动部署脚本默认读取：

```text
/etc/person-ai/person-ai.env
/etc/person-ai/mem0.env
```

其中应用已有的 LLM 配置可以复用到 Mem0：

```bash
MEM0_LLM_API_KEY=${LLM_API_KEY}
MEM0_LLM_BASE_URL=${LLM_BASE_URL}
MEM0_LLM_MODEL=${LLM_MODEL}
```

Embedding 配置：

```bash
MEM0_EMBEDDER_API_KEY=<embedding-api-key>
MEM0_EMBEDDER_BASE_URL=<embedding-base-url>
MEM0_EMBEDDER_MODEL=<embedding-model>
```

## 修改配置后的操作

修改环境变量文件后，需要重新加载并重启服务：

```bash
sudo systemctl daemon-reload
sudo systemctl restart person-ai
```

## 安全要求

不要将以下内容提交到 Git：

- API Key
- 数据库密码
- JWT Secret
- 内部访问 Token

查看配置变量名时，可以使用：

```bash
grep -E '^[A-Z0-9_]+=' /etc/person-ai/person-ai.env \
| sed 's/=.*$/=<hidden>/'
```
