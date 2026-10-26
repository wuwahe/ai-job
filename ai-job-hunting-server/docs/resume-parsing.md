# 简历解析与模型调用

流程：附件简历 → 服务端文本提取 → 保存 `user_resume.resume_content` → 现有提示词组装 → 用户配置的 OpenAI / Anthropic 兼容模型。

## 使用方式

1. 在插件中导入附件简历。首次导入会继续创建用户并登录，此步骤不需要 AI 配置，也不调用模型。
2. 在「AI 配置」中填写服务地址、模型名、API Key 和协议，测试通过后启用保存。
3. AI 坐席新会话、提示词调试和招呼语使用提取的正文。模型仍通过已选协议处理文字，无需支持文件上传接口。

导入保留原始正文，不让模型重写履历、补全缺失信息或生成摘要。原始文件仅在服务端解析，不上传给 Kimi、OpenAI 或 Anthropic；后续 AI 功能会将正文发送给所配置的模型服务。既有会话沿用已有历史上下文。

## 支持范围与限制

- 支持含文字的 PDF、DOCX、DOC，按内容识别格式，不依赖浏览器文件名或 MIME 类型。
- 提取 Word 段落与表格文字；图片、照片和扫描 PDF 不执行 OCR。无可提取文字时提示先 OCR 或重新导出。
- 文件不超过 20MB，PDF 不超过 100 页，正文不超过 100000 字符；超限会拒绝，不静默截断。
- DOCX 最多 1000 个 ZIP 条目，累计解压内容不超过 40MB，并保留 POI 的压缩炸弹检查。
- 加密、损坏或格式不符的文件会被拒绝；解析失败不会覆盖旧简历。用户信息与简历写入在同一事务中完成。
- 模型自身仍有上下文限制；若模型提示输入过长，请精简简历或选择支持更长上下文的模型。

无需数据库迁移，也不需填写 `spring.ai.kimi.api-key` 来导入简历。历史 Kimi 对话配置与旧文件工具保留供其他既有用途，导入入口不再调用它们。

## 实现与验证

`ResumeTextExtractor` 负责格式校验、解析和限制；`UserService` 负责联系方式识别及事务持久化；`AbstractAIService` / `CustomOpenAIService` 沿用现有正文组装与协议调用。

固定依赖：[PDFBox 3.0.8](https://pdfbox.apache.org/download)、[Apache POI 5.5.1](https://poi.apache.org/changes.html)。解析器在应用进程内运行，文件大小限制不等于进程沙箱；不要向公网开放无访问控制的文件解析服务。

```sh
cd ai-job-hunting-server
mvn -Dtest=ResumeTextExtractorTest,ResumeImportTest,ResumeModelFlowTest test
```

测试用合成简历和本地 HTTP 模型模拟服务，覆盖格式解析、失败保护，以及两种协议携带正文。不读取真实简历或调用真实模型。
