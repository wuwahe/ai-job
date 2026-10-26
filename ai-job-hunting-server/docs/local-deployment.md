# 本机部署

本次部署使用 macOS 上已有的 PostgreSQL 16 和 Java 17，独立数据库及角色均为 `ai_job_local`。启动脚本只管理本项目的 Java 和安装页进程，不管理 PostgreSQL。

## 使用入口

- 安装页：<http://127.0.0.1:9101/>。
- 后端健康检查：<http://127.0.0.1:9100/actuator/health>，正常返回 `{"status":"UP"}`。
- 安装并启用 Tampermonkey，再从安装页安装用户脚本，打开 BOSS 直聘职位页使用。前端是用户脚本，不是独立网站。
- 插件服务器地址应为 `http://127.0.0.1:9100/`。旧脚本保存过其他地址时，手动修改或点击重置。

## 进程管理

在仓库根目录运行：

```sh
python3 scripts/local-service.py status
python3 scripts/local-service.py stop
python3 scripts/local-service.py start
```

这些进程在后台运行；电脑重启后需手动启动。修改配置后先 `stop` 再 `start`。应用日志位于 `runtime.local/logs/`，此目录已被 Git 忽略。

## 配置模型

运行配置为仓库根目录的 `runtime.local/application.properties`，不会加载项目原有的应用配置。简历在服务端提取文字，首次导入无需 Kimi 或其他 AI Key。不要提交包含密钥的文件。

导入简历并登录后，在插件的 AI 配置中选择 OpenAI 或 Anthropic 格式，填写服务地址、模型名及自己的 API Key，测试并启用保存。后续模型请求携带提取出的简历正文，文件本身不会上传给模型服务。支持 PDF、DOCX、DOC，扫描件需先 OCR，详细限制见 [简历解析](resume-parsing.md)。尚未配置支付、邮件服务；本地关闭了支付对账和远程文件清理任务。

SmartConfig 管理端使用 6768 端口，其依赖默认监听所有网卡。启动脚本默认生成不显示的随机管理密码；如需管理端，在启动前通过安全方式设置环境变量 `LOCAL_ADMIN_PASSWORD`，用户名为 `local-admin`。不要向公网映射该端口。API 与安装页仅绑定 `127.0.0.1`。

## 更新构建

后端使用 Java 17；现有全量测试包含外部服务调用，本地使用以下范围：

```sh
cd ai-job-hunting-server
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest=BackgroundJobsTest,CompatibleChatClientTest,UserAIConfigServiceTest,OpenAIServiceProtocolTest,ResumeTextExtractorTest,ResumeImportTest,ResumeModelFlowTest package
cd ../ai-job-hunting-ui
VITE_API_BASE_URL=http://127.0.0.1:9100/ npm run build -- --mode local-deploy
npm run test:ai-config
cp dist/ai-job-hunting.user.js ../runtime.local/www/ai-job-hunting.user.js
```

构建完成后重启本地服务，并在 Tampermonkey 中更新脚本。新建空数据库只需执行当前 `schema.sql`；不要对已初始化的本地数据库重复初始化。
