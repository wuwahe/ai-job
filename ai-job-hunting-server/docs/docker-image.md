# 生产镜像 ai-job:1.0

此配置面向 Linux 生产服务器，默认由宿主机上的 HTTPS 反向代理访问 API，PostgreSQL 已独立部署。前端仍是 Tampermonkey 用户脚本，不包含在后端镜像里。

## 打包与构建

在仓库根目录执行，使用 Java 17。生产 profile 生成独立的 `target/ai-job-production.jar`，排除 `application.properties` 和 `application-dev.properties`，不覆盖本机运行的 `target/ai-job.jar`：

```sh
mvn -f ai-job-hunting-server/pom.xml -Pproduction-image \
  -Dtest=SelfUseModeTest,BackgroundJobsTest,CompatibleChatClientTest,UserAIConfigServiceTest,OpenAIServiceProtocolTest,ResumeTextExtractorTest,ResumeImportTest,ResumeModelFlowTest \
  package

docker build -t ai-job:1.0 \
  -f ai-job-hunting-server/src/main/resources/docker/Dockerfile \
  ai-job-hunting-server/target
```

构建上下文必须是 `ai-job-hunting-server/target`，Dockerfile 专用忽略文件只允许生产 JAR。构建需要实际可用的 Docker 引擎；仅安装 Docker CLI 无法构建镜像。

基础镜像使用官方 Eclipse Temurin Java 17，固定多架构摘要，支持 AMD64、ARM64。在目标服务器构建时默认匹配其架构；跨机器构建应根据生产服务器 `uname -m` 的结果传入 `--platform linux/amd64` 或 `--platform linux/arm64`，不要直接把 Mac ARM 镜像部署到 x86 服务器。

IDEA 中设置镜像标签 `ai-job:1.0`、上述 Dockerfile 和上下文，并选择实际的构建服务器连接。先执行 `production-image` Maven 打包任务。

IDEA 默认可能隐藏上下文字段：打开「运行 → 编辑配置 → ai-job → 构建 → 修改选项」，勾选「上下文文件夹」，填写 `ai-job-hunting-server/target`（本机项目路径，不是远程服务器路径）。IDEA 会把该目录发送给选定的远程 Docker 引擎。若日志显示 `transferring context: 2B` 并报 JAR 不存在，优先检查此设置；不要将上下文设为 Dockerfile 所在的 `src/main/resources/docker`。

仅构建镜像时保持「运行构建的镜像」未勾选。启动生产容器前，另行按下文配置挂载和环境变量，推荐使用 Compose。

## 外部配置

将 `src/main/resources/docker/application-production.properties.example` 复制到生产服务器，例如 `/etc/ai-job/application.properties`。实际文件名必须以 `.properties` 结尾；只读挂载后供启动使用。容器使用 UID/GID `10001:10001`，文件必须允许该身份读取，例如文件所有者 `root:10001`、权限 `0640`。

在服务器自行创建 `/etc/ai-job/production.env`，权限设为 `0600`，通过受控部署流程填写真实值，不提交到仓库：

```dotenv
AI_JOB_IMAGE=ai-job:1.0
AI_JOB_CONFIG_FILE=/etc/ai-job/application.properties
DB_URL=jdbc:postgresql://<数据库私网地址>:5432/ai_job
DB_USERNAME=ai_job
DB_PASSWORD=<数据库密码>
SMART_ADMIN_USERNAME=admin
SMART_ADMIN_PASSWORD=<独立的强密码>
```

数据库地址必须从容器可达。默认桥接网络下，`127.0.0.1` 指向容器自身；如果 PostgreSQL 在其他容器中，需要将两者加入同一受控 Docker 网络，再使用数据库服务名。若 ai-job 明确使用 `network_mode: host`，`127.0.0.1` 才指向宿主机网络，同时端口映射不再生效，须限制管理端口 6768 的访问。新版 Compose 默认不再依赖旧的 `data_smart_course` 外部网络；升级现有部署时应显式配置对应网络。

不会自动建库或迁移数据。新库与已有 PostgreSQL 的升级步骤见 [PostgreSQL 说明](postgresql.md) 和 [AI 配置迁移](ai-api-formats.md)。生产配置默认使用用户自有模型，关闭 Kimi 清理、支付对账及邮件告警；若运营收费服务，需另外配置并验证这些能力。

## 自用模式

更新后端镜像后，在服务器挂载的 `application.properties` 增加：

```properties
app.self-use.enabled=true
```

重新创建后端容器，并安装本次构建的前端脚本。此开关默认关闭，不需要数据库迁移。
可生成直接分发文件的前端版本（将域名换成自己的后端地址）：

```sh
cd ai-job-hunting-ui
VITE_API_BASE_URL=https://你的后端域名/ npm run build -- --mode manual-install
```

产物为 `dist/ai-job-hunting.user.js`。此模式关闭脚本自动更新，后续手动重新导入新版文件；
已有用户保存的服务器地址不会被默认值覆盖。
开启后，所有已登录用户均可使用套餐功能，不消耗试用次数；支付订单生成和支付后台任务停止。
用户仍需导入简历、登录并配置可用的大模型。新用户的 AI 坐席默认关闭，需手动开启。
前端收到用户信息后显示“自用模式”，隐藏产品购买和邀请兑换；旧脚本的订单组请求返回空列表。
该模式不是账号白名单，适用于你信任的使用者；模型提供商仍按其规则计费。
已有订单与套餐记录保留，历史支付回调仍处理；改回 `false` 并重启可恢复原有收费检查。

验证：导入简历并刷新后，无购买入口；保存 AI 配置、手动切换坐席不再提示购买；
调用 `/api/pay/generate/order/group` 返回空列表且不向支付宝下单。
本机测试通过不代表线上已更新；需要同时发布后端镜像和前端脚本。

## 启动与验证

在服务器仓库根目录执行：

```sh
docker compose --env-file /etc/ai-job/production.env \
  -f ai-job-hunting-server/src/main/resources/docker/docker-compose.yml config --quiet

docker compose --env-file /etc/ai-job/production.env \
  -f ai-job-hunting-server/src/main/resources/docker/docker-compose.yml up -d

curl --fail http://127.0.0.1:9100/actuator/health
docker compose --env-file /etc/ai-job/production.env \
  -f ai-job-hunting-server/src/main/resources/docker/docker-compose.yml ps
```

校验使用 `config --quiet`，避免输出已展开的密钥。健康状态应为 `UP`，容器应为 `healthy`。镜像可通过 `docker run --rm --entrypoint java ai-job:1.0 -version` 单独验证 Java。

API 仅发布到宿主机 `127.0.0.1:9100`，由现有 Nginx/网关配置 HTTPS、20MB 上传限制及 SSE 的长连接、禁用缓冲。反向代理若也运行在容器中，应改为共同的 Docker 网络并访问 `ai-job:9100`，不能使用代理容器自身的 `127.0.0.1`。插件的服务器地址应填写生产 HTTPS 地址。

SmartConfig 的 6768 端口不发布，配置通过受控文件和环境变量管理。容器使用只读根文件系统，临时文件仅写入有容量限制的 `/tmp`；日志输出到标准输出，并启用轮转。默认内存上限为 2GB，可通过 `AI_JOB_MEMORY_LIMIT` 调整。

升级前保留上一版镜像标签。需要回退时将 `AI_JOB_IMAGE` 指向上一版并重新执行 `up -d`；镜像回退不会回退数据库，涉及数据库变更时需单独评估兼容性。

参考：[Docker Compose 服务配置](https://docs.docker.com/reference/compose-file/services/)、[环境变量优先级](https://docs.docker.com/compose/how-tos/environment-variables/envvars-precedence/)、[固定基础镜像源码](https://github.com/adoptium/containers/blob/511f9356dc4d50932a0a5f8cfb0f87ed1aef4f07/17/jre/ubuntu/jammy/Dockerfile)。
