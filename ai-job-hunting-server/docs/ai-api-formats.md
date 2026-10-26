# 自有 AI 接口配置

在插件的「AI 配置 → 自有 API」中选择接口格式，再填写模型名称、API Key、基础地址和超时时间。模型名称允许自由输入，不受快捷预设列表限制；是否可用取决于服务商实际支持的模型和账号权限。

## 两种接口格式

| 配置 | OpenAI 兼容 | Claude / Anthropic 兼容 |
| --- | --- | --- |
| 接口格式值 | `OPENAI` | `ANTHROPIC` |
| 官方基础地址示例 | `https://api.openai.com/v1` | `https://api.anthropic.com/v1` |
| 默认接口路径 | `/chat/completions` | `/messages` |
| 模型名称 | 填写服务商提供的模型 ID | 填写服务商提供的模型 ID |
| 鉴权 | `Authorization: Bearer …` | `x-api-key` 与 `anthropic-version: 2023-06-01` |
| 输出长度 | 使用服务端默认值 | `max_tokens`，默认 1024，可在页面修改 |

第三方网关或本地服务使用其实际基础地址及接口路径。最终地址由基础地址去掉末尾 `/` 后与接口路径拼接：例如 `https://gateway.example/api/v1` 加 `/messages`。不要把完整的聊天请求地址同时填入基础地址和接口路径，也不要在 URL 中填写密钥。

接口格式描述的是请求协议，不是模型品牌。例如，服务商以 OpenAI 格式提供 Claude 模型时，选择 `OPENAI` 并填写其 Claude 模型 ID。快捷预设只帮助填写地址，仍可编辑地址和模型。

## 保存与生效

1. 填写配置后点击「测试」；该操作会向所选模型发送一次真实测试请求。
2. 测试成功后选择是否启用，再点击「保存」。
3. 修改接口格式、地址、路径、密钥、模型、超时或输出长度后，需重新测试。测试过程中修改配置，旧请求的成功结果不会验证新配置。
4. 「暂存」保存未测试草稿并关闭该自有 API；单独保存用户提示词会保留已有连接配置和测试状态。

两种格式均支持 AI 坐席、提示词调试、职位过滤及招呼语的文字调用，并保留系统提示词和多轮会话。简历文件先在服务端提取文字并保存，再由现有提示词流程将正文传给所选模型，不依赖 Kimi 文件接口，详见 [简历解析](resume-parsing.md)。此功能不提供工具调用、图片输入或流式响应。请求失败不会自动换模型或重试，错误提示不会回显上游原始响应。

## PostgreSQL 升级

新库直接使用更新后的 `src/main/resources/schema.sql`。已经完成上一版 PostgreSQL 初始化的数据库，需要先备份，在部署新版后端前由表所属用户执行一次：

```bash
psql -h <数据库地址> -U <数据库用户> -d ai_job \
  -v ON_ERROR_STOP=1 --single-transaction \
  -f ai-job-hunting-server/src/main/resources/migrations/20261024_add_ai_api_format.sql
```

脚本只为 `user_ai_config` 增加 `api_format` 和 `max_tokens`，不清空配置。旧记录默认使用 `OPENAI`；已用新版 `schema.sql` 初始化的新库不再执行此增量脚本。应用启动不会自动迁移数据库。

## 本地验证

后端使用本地 HTTP 模拟服务验证两种协议，不需要真实 API Key：

```bash
cd ai-job-hunting-server
mvn -Dtest=CompatibleChatClientTest,UserAIConfigServiceTest,OpenAIServiceProtocolTest test
```

数据库回归测试还覆盖新字段读写和旧配置升级，运行方式见 [PostgreSQL 说明](postgresql.md)。前端沿用现有 TypeScript、Vue 编译器和 Node 测试工具，无新增依赖：

```bash
cd ai-job-hunting-ui
npm run test:ai-config
npm run build
```

协议参考：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Anthropic Messages](https://platform.claude.com/docs/en/build-with-claude/working-with-messages)、[Anthropic 鉴权](https://platform.claude.com/docs/en/api/overview)。
