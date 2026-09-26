# logviewer

## 当前项目已经停止维护，请移步新项目：https://github.com/sl43793390/lanyue

### 新项目使用全新的框架：jdk21 + vaadin24 + spring-boot3.5 打造，功能更加强大，欢饮试用

> 一个部署在服务器上的 **Web 端日志查看 / 文件管理 / 应用发布** 工具。
> 运维或管理员预先配好机器与账号，开发人员只需打开浏览器，就能查看、搜索和下载集群里各台服务器的日志文件，无需拿到 Linux 登录凭据。

- 技术栈：Vaadin 8 + Spring Boot（内嵌 Tomcat，打 **jar** 包）
- 访问方式：`http://<ip>:9095/log/`
- 默认账号：`admin / admin`（首次登录后请及时修改）
- 内置 **Docker 管理**：复用服务器凭据走 SSH + `docker` 命令行，直接管理容器 / 镜像 / 数据卷 / 网络，**不需要在目标机上把 docker daemon 的 2375 端口暴露出来**。

## 目录

- [解决什么问题](#解决什么问题)
- [功能清单](#功能清单)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [运行时生成的文件](#运行时生成的文件)
- [使用说明](#使用说明)
- [二次开发](#二次开发)
- [常见问题](#常见问题)
- [截图](#截图)

## 解决什么问题

- 服务器数量不多的场景下，无需额外搭建日志采集、推送或日志平台，把这一个 jar 丢到任意一台机器上就能通过浏览器查看所有授权服务器的日志和其他文件。
- 需要查看日志的 Linux 账号密码不必交给开发人员，管理员在页面里配好机器即可，降低账号外泄风险。
- 顺带把 **jar 包管理 / Tomcat 管理 / 通用应用管理**webssh docker/docker compose 管理等 做成了页面操作，上传、启停、看状态、看日志都在一个界面里完成。

## 功能清单

| 分类 | 功能 | 说明 |
| --- | --- | --- |
| 本地应用管理 | 本地日志搜索 | 搜索并下载**本机**指定目录下的日志，可预览分页内容、按关键字过滤 |
| | 本地文件管理 | 浏览本机目录、上传 / 下载 / 删除 / 重命名文件，新建目录和文件 |
| | jar 项目管理 | 上传 jar 与脚本、启动 / 停止 / 查看状态、配置 JVM 参数与启动命令 |
| | Tomcat 管理 | 上传 war 到 `webapps`、启动 / 停止、查看运行状态 |
| | 通用项目管理 | 面向 nginx 等自定义应用：自定义启动 / 停止 / 重启 / 刷新 / 状态检查命令 |
| 远程应用管理 | 远程日志搜索 | 输入密码或使用密钥登录任意服务器，搜索并下载日志 |
| | 免登录服务器列表 | 管理远程连接，一键打开「应用管理」「SSH 终端」「文件管理」 |
| | 远程应用管理 | 在远程机器上管理 jar / Tomcat / 通用项目（与本地同类功能一致） |
| | Web SSH 终端 | 基于 xterm.js 的完整终端，支持 vim / top / tmux、256 色、搜索与会话导出 |
| Docker 管理 | 容器管理 | 列表（运行中 / 已停止 / 全部）、启停 / 重启 / 暂停、批量删除、创建容器（端口 / 环境变量 / 卷 / 网络 / 重启策略）；详情（inspect 可视化）、实时日志（可下载 / 搜索）、Web 终端（`docker exec`）、CPU / 内存 / 网络曲线、容器内文件浏览与上传下载 |
| | 镜像管理 | 本地列表、拉取（可指定 registry）、构建（上传 Dockerfile 或从 Git 构建）、导出下载、导入、删除、清理悬空镜像 |
| | 数据卷管理 | 列表（含挂载它的容器）、创建、删除、清理未使用卷 |
| | 网络管理 | 列表（含子网 / 网关 / 接入容器）、创建、删除、连接与断开容器 |
| | 系统信息 | Docker / API / 客户端版本、引擎信息、磁盘占用（`system df`）、一键清理，以及 Rocky / Ubuntu 环境自检 |
| | Docker-Compose 管理 | 菜单已预留，功能开发中 |
| 其他工具 | 加密工具 | 密码摘要等小工具 |
| 用户管理 | 用户管理 | 新增 / 修改 / 删除用户，按 `ADD / UPDATE / DELETE / UPLOAD` 分配权限 |

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Vaadin | 8.16.0 | 前端 UI（含 vaadin-push、vaadin-charts 4.3.0） |
| Spring Boot | 2.7.18 | 容器与自动装配，内嵌 Tomcat |
| MyBatis-Plus | 3.4.3 | 数据访问 |
| HikariCP | 随 Boot 版本 | 连接池 |
| SQLite（sqlite-jdbc） | 随 Boot 版本 | 内置数据库，文件为运行目录下的 `demo.db` |
| JSch（mwiede 0.2.17） | — | SFTP 文件传输、远程命令执行 |
| sshj | 0.31.0 | 远程文件管理 |
| Hutool | 5.8.7 | 工具类 |
| xterm.js | 6.0.0 | Web 终端的浏览器端实现，发行文件本地托管在 `src/main/webapp/VAADIN/xterm/`，不依赖 CDN |
| fastjson2 / EasyExcel / BouncyCastle | — | JSON、Excel 导出、国密摘要 |
| log4j2 | — | 日志（配置见 `log4j2.xml`） |

> 编译目标为 **Java 1.8**，运行环境建议 JDK 8 / 11 / 17。

## 快速开始

### 1. 构建

```bash
mvn clean package -DskipTests
```

产物为 `target/logviewer.jar`（单文件，内嵌 Tomcat）。

> 首次构建需要联网下载依赖；Vaadin widgetset 已内置在 `src/main/webapp/VAADIN`，无需安装 Node。

### 2. 部署

把 `logviewer.jar` 放到服务器上任意一个**空目录**（建议新建），然后在该目录下启动。
运行目录（`user.dir`）就是数据目录，`demo.db`、`fileStorage/` 都会生成在这里。

```bash
# 方式一：直接启动
nohup java -jar logviewer.jar > app.log 2>&1 &

# 方式二：使用自带的 server.sh（推荐，支持 start/stop/restart/status）
sh server.sh start logviewer.jar
sh server.sh status logviewer.jar
sh server.sh stop   logviewer.jar
```

启动成功后控制台会打印可访问的本机地址：

```
====================address============================
192.168.1.100:9095/log
```

### 3. 访问

```
http://<ip>:9095/log/
```

- 未登录时自动跳到登录页（`#!loginView`）
- 默认账号 **admin / admin**（内置管理员，由程序在启动时自动写入数据库），登录成功后进入主界面
- 注意 URL 里的 **`/log`** 上下文路径不能省

### 4. 兼容性

- 不支持 CentOS 6.x，开发与验证环境为 CentOS 7.9。
- Docker 管理按 **Rocky Linux 8/9/10、Ubuntu 22.04** 优先适配，同时兼容 **CentOS 7**（含系统仓库自带的 docker 1.13、只有 init 脚本的机器，以及没有 systemd 的容器环境）。

## 配置说明

配置文件都在 `src/main/resources/` 下，打包后位于 jar 内的 `BOOT-INF/classes/`。

| 文件 | 作用 | 是否必须外置 |
| --- | --- | --- |
| `application.properties` | 端口、上下文路径、数据源、日志配置 | 否 |
| `application-dev.properties` / `application-prd.properties` | 不同环境的差异配置，由 `spring.profiles.active` 选择 | 否 |
| `dataAccessConfiguration.xml` | MyBatis 全局配置 | 否 |
| `log4j2.xml` | 日志输出配置 | 否 |
| `demo.sql` | 首次启动时的建表与初始数据脚本 | 否 |
| `fileSuffix.conf` | 允许查看 / 搜索的文件后缀列表 | 否（可放 jar 同级覆盖） |
| `remoteServerList.conf` | 免登录服务器列表 | 否（可放 jar 同级覆盖） |
| `server.sh` | jar 项目的默认启停脚本模板 | 自动释放到运行目录 |
| `tomcat.sh` | Tomcat 启停脚本模板 | 否 |
| `userGuide.txt` | 页面「使用说明」展示的内容 | 否 |

### 端口与上下文路径

`application.properties`：

```properties
server.port=9095
server.servlet.context-path=/log
spring.profiles.active=dev
spring.datasource.url=jdbc:sqlite:demo.db
spring.datasource.driver-class-name=org.sqlite.JDBC
```

修改端口或上下文路径后，访问地址相应变为 `http://<ip>:<port>/<context-path>/`。

### 用户与登录账号

- 账号的唯一来源是数据库 `users` 表，**没有任何外部用户文件**。历史版本要求把 `users.properties` 放到 jar 同级目录，现已彻底移除；那个文件丢了、没拷、编码错了都不会再把系统锁在门外。
- 启动时会做两件幂等的事（见 `src/main/java/com/so/util/DbInitializer.java`）：
  1. 库里一个用户都没有时执行 `demo.sql`，建表并写入示例账号；
  2. 检查内置管理员 `admin`——不存在就按默认密码写入，密码列为空 / 被禁用 / 已过期 / 权限被摘掉都会自动修回可用状态。
- 因此即便换机器、删掉 `demo.db` 重来，也一定能用 **admin / admin** 登进来。
- 登录后请到「用户管理」里改掉 `admin` 的密码，并按需创建其他账号。
- 内置管理员 `admin` 受保护：**不能删除、不能禁用、不能改权限、不能设有效期**，否则系统会失去唯一的引导账号；它的姓名、邮箱、手机、密码仍可以正常修改。
- 任何账号都不能删除自己。

### 文件后缀 `fileSuffix.conf`

一行一个后缀，UTF-8 编码；`*` 表示匹配全部文件。搜索时可勾选「包含模式」，用于匹配 `catalina.log.20200717` 这类滚动日志。

### 免登录服务器列表 `remoteServerList.conf`

```properties
# 格式：ip=用户=密码=端口=私钥文件名（私钥文件放在 jar 同级目录）
# 无密钥：密码填真实密码；有密钥但无密码：密码必须写 null，否则报错
192.168.22.50=sl=sl=22
192.168.22.51=user=null=22=test/key.pem

# 每个服务器还可以配置默认日志路径，必须以 =path 结尾，否则不会被加载
192.168.22.50=/home/sl=/home/tomcat=path
```

配置后这些机器会出现在「免登录服务器列表」页面，点一下即可打开日志搜索、应用管理、SSH 终端或文件管理。

## 运行时生成的文件

全部位于**启动 jar 时所在的目录**：

| 文件 / 目录 | 说明 |
| --- | --- |
| `demo.db` | SQLite 数据库，首次启动由 `demo.sql` 建表 |
| `fileStorage/` | 文件上传接口的存储根目录 |
| `<用户名>.properties` | 每个用户自己的搜索历史、连接记录 |
| `bin/server.sh` | 给被管理的 jar 项目使用的默认启停脚本 |
| `app.log` | 使用 `server.sh` 或重定向启动时的应用日志 |

> **升级时请保留** `demo.db`、`fileStorage/` 和 `<用户名>.properties`，否则会丢失已配置的机器、项目与用户（用户账号也都在 `demo.db` 里）。

## 使用说明

### 菜单结构

```
本地应用管理
├── 本地日志搜索
├── 本地文件管理
├── jar项目管理
├── Tomcat管理
└── 通用项目管理
远程应用管理
├── 远程日志搜索
└── 免登录服务器列表       ← 从这里进入远程机器：应用管理 / SSH 终端 / 文件管理
Docker管理
├── Docker管理             ← 选目标服务器后管理容器 / 镜像 / 数据卷 / 网络 / 系统信息
└── Docker-Compose管理      （预留，功能开发中）
安全管理                   （预留）
其他工具
└── 加密工具
用户管理
└── 用户管理
```

### 典型使用路径

1. **管理员**先在「免登录服务器列表」里把要运维的机器加进去（或直接编辑 `remoteServerList.conf`）。
2. **开发人员**登录后打开「远程日志搜索」，选择机器 → 输入日志目录 → 搜索 → 预览或下载。
3. 需要发布时用「本地应用管理 / 远程应用管理」上传 jar 或 war，点「启动服务」，再回到日志搜索查看输出。

### Docker 管理

Docker 管理走的是 **SSH + `docker` 命令行**，不是 Docker Remote API。原因是内网的 docker daemon 通常只监听 `unix:///var/run/docker.sock`，要让它接受远程调用就得开 TCP 端口，等于把一台能起特权容器的机器暴露在网络上；而本项目已经有成套的 SSH 连接体系，直接复用更省事也更安全。

#### 使用方式

1. 进入「Docker管理 → Docker管理」，在顶部下拉框选一台**已经在「免登录服务器列表」里配置好的机器**。
2. `docker 命令` 一栏留空即自动探测（依次尝试 `docker`、`/usr/bin/docker`、`/usr/local/bin/docker`、`/snap/bin/docker`）；非 root 用户会自动降级成 `sudo -n docker`。要手工指定就按 `sudo -n docker` 这种形式填。
3. 点「连接」。连接成功后下方会出现容器 / 镜像 / 数据卷 / 网络 / 系统信息五个子页。

#### 权限要求

登录用户在目标机上必须能执行 docker，二选一：

- 把用户加进 `docker` 组：`usermod -aG docker <用户>`（**重新登录后生效**，加完立刻试会仍然是 permission denied）；
- 或者给该用户配免密 sudo：在 `/etc/sudoers.d/` 下写一条 `<用户> ALL=(ALL) NOPASSWD: /usr/bin/docker`。

> 用 `sudo -n`（non-interactive）是刻意的：SSH 通道上没有交互终端，一旦 sudo 要密码就会直接失败。所以要么配 NOPASSWD，要么进 docker 组。

#### 目标机器环境要求（Rocky Linux / Ubuntu）

### jar 项目的启动方式（优先级从高到低）

1. 填了 **JVM 参数** 或 **jar 包参数** → 自动拼装命令：
   `nohup java <JVM参数> -jar <jar包名> <jar参数> > app.log 2>&1 &`
2. 填了 **启动命令** → 直接执行该命令（会校验必须以 `nohup` 开头、以 `&` 结尾）。
3. 都没填 → 在项目目录生成并使用 `server.sh`：
   `sh server.sh start <jar包名>`。

> 两个容易写错的地方：**JVM 参数必须在 `-jar` 之前**；**重定向要写成 `> app.log 2>&1`**。写成 `java -jar -Xmx512m app.jar` 会报 `Unable to access jarfile`，写成 `2>&1 > app.log` 则异常堆栈不会进日志文件。

## 二次开发

### 目录结构

```
src/main/java/com/so
├── Application1.java          # 启动类（Spring Boot）
├── component/                 # 页面组件
│   ├── CommonComponent.java   # 所有组件基类
│   ├── LogSearchComponent.java        # 本地日志搜索
│   ├── LogDetailComponent.java        # 日志分页预览
│   ├── UserManagementComponent.java   # 用户管理
│   ├── management/            # 本地：文件管理、jar、Tomcat、通用项目、使用说明
│   ├── remote/                # 远程：登录、服务器列表、应用管理、SSH 终端、文件管理
│   ├── docker/                # Docker：主页面 + 容器 / 镜像 / 数据卷 / 网络 / 系统信息子页 + 「Docker 不可用」提示窗
│   └── util/                  # 上传器、弹窗、TabSheet 工具等
├── config/                    # BeansConfig：数据源与 SqlSessionFactory
├── docker/                    # Docker 命令执行器（含 daemon/服务管理方式探测与启动）、服务层与数据模型（不依赖 Spring 容器）
├── controller/                # SshHandler（WebSocket）、FileUploadController 等
├── entity/ mapper/ service/   # 实体、Mapper、服务
├── ui/                        # MyUI、LoginView、LogCheckView、ComponentFactory
└── util/                      # SSH/SFTP、加解密、校验、文件工具
```

### 新增一个页面组件

1. 在 `com.so.component` 或其子包下新建类，继承 `CommonComponent`，实现三个方法：

   ```java
   @Service
   @Scope("prototype")   // 每个用户一个实例，务必是 prototype
   public class DemoComponent extends CommonComponent {
       @Override public void initLayout() { /* 构建组件树 */ }
       @Override public void initContent() { /* 加载数据 */ }
       @Override public void registerHandler() { /* 注册事件 */ }
   }
   ```

2. 在 `LogCheckView#addMenuItem()` 里挂到菜单上：

   ```java
   addSubMenu(localMgmt, "DemoComponent", "示例页面");
   ```

3. 需要权限控制时用 `LoginView.checkPermission(Constants.ADD/UPDATE/DELETE/UPLOAD)`。

> 约定：`@Scope("prototype")` 不能省。组件是有状态的，写成单例会让多个用户互相看到对方的数据。

### Web 终端

- 页面：`src/main/webapp/VAADIN/themes/mytheme/terminal.html`，由 `RemoteSSHXterm` 通过 `BrowserFrame`（iframe）嵌入。
- 终端组件是 **xterm.js 6.0.0** 加插件，发行文件托管在 `src/main/webapp/VAADIN/xterm/`。本项目没有 npm / webpack，页面用 `<script>` 直接引 UMD 包；升级走 `scripts/fetch-xterm-assets.mjs`（加 `--latest` 自动解析最新版），版本号锁在脚本里的 `PINNED` 常量。**不要改成 CDN**：内网机房通常没有外网。
- 已挂载插件：`fit`（自适应容器）、`search`（Ctrl+F 搜索）、`webgl`（GPU 渲染，失败自动退回 DOM 渲染）、`web-links`（日志里的 IP / URL 可点）、`serialize`（导出整个会话）、`unicode11`（宽字符与 emoji 宽度）、`clipboard`（OSC 52，仅 https 或 localhost 下生效，其余环境插件不加载）。
- WebSocket 端点：`/ws/ssh`（`com.so.controller.SshHandler`），完整地址为 `ws://<ip>:9095/log/ws/ssh?token=xxx&cols=120&rows=30`
- 连接信息不放静态字段，而是每次打开标签页生成一个带有效期的随机 token 登记到服务端，多人同时使用不会串台；token 在标签页存活期内可重复使用，「重新连接」按钮依赖这一点。
- **帧格式**（改协议时两边必须一起改）：
  - 服务端到浏览器：**二进制帧**是远端 pty 的原始字节，页面直接 `term.write(new Uint8Array(...))`；**文本帧**是控制消息 `{"t":"notice","lv":"info|err","v":"..."}`。
  - 浏览器到服务端：统一是文本帧 JSON，`{"data":"按键"}` 与 `{"resize":{"cols":120,"rows":30}}`。
  - 终端数据不套 JSON 的原因：pty 输出本来就是字节流，套成字符串要在服务端先解码、到浏览器再编码回去，白跑两趟；直接转发原始字节则交给 xterm.js 的流式解码器，跨读取块被切断的多字节汉字不会变成乱码。
- 快捷键：`Ctrl+F` 搜索缓冲区，`Ctrl+Shift+C` 复制选中内容，`Ctrl+Shift+V` 粘贴，回车 / Shift+回车 在搜索结果间跳转。
- 本地验证渲染与协议：`node scripts/terminal-selftest.mjs` 会在 18080 端口起静态目录和一个最小 WebSocket 端点，用无头 Chrome 打开启动日志里的地址，即可看到颜色、中文对齐、跨块解码的自测输出。

### 登录页

登录页是「左侧品牌区 + 右侧登录卡片」的整屏分栏（`LoginView`），窄屏（≤1000px）自动收起左侧品牌区。

- 样式在 `src/main/webapp/VAADIN/themes/mytheme/styles.css` 末尾的「登录页样式」段落，类名统一 `login-` 前缀；左侧品牌区、右侧卡片分别由 `LoginView#buildBrandPanel` / `buildLoginPanel` 构建。
- `LoginView` 里左右分栏用的是 `CssLayout` 而不是 `HorizontalLayout`：`CssLayout` 不给子组件套 `v-slot`，可以直接用 flex 控制比例。
- **改样式不要重跑 `vaadin:compile-theme`**：主题是按 jar 预编译的，`styles.css` 里已有一批手工覆盖规则，重新编译会全部冲掉。
- 两处 Valo 的坑（改动时容易踩）：
  - `.v-widget` 被设成 `display:inline-block`，所以卡片必须显式 `display:block` 才能用 `margin:0 auto` 居中；
  - `.v-slot` 和 `.v-label-undef-w` 带 `white-space:nowrap`，说明文案不覆盖成 `normal` 就会冲出容器不换行。
- 预览与校验（不需要起服务）：

  ```bash
  python scripts/build-login-preview.py     # 把主题样式内联进 docs/login-preview.html
  python scripts/login-layout-check.py      # 无头 Chrome 量盒模型并断言（1440x900）
  python scripts/login-layout-check.py 900 780
  ```

  预览稿 `docs/login-preview.html` 的 DOM 照抄 Vaadin 真实渲染结果，浏览器直接打开即可看效果；校验脚本会用无头 Chrome 真的排一次版，检查卡片居中、输入框宽度、文案换行、窄屏收起等。

### 用户体系与登录入口

- **账号只存在数据库里**。`users` 表是唯一来源，没有任何外部用户文件；历史版本要求的 `users.properties` 已彻底移除。
- 启动时由 `DbInitializer.ensureAdminUser()` 保证引导入口可用，它是幂等的：
  - `users` 表不存在就建；缺字段（从老版本升级上来的库）就自动 `ALTER TABLE` 补齐；
  - 内置管理员 `admin` 不存在就按默认密码写入；
  - `admin` 存在但密码列为空 / 被禁用 / 已过期 / 权限被摘，自动修回可用状态，并在日志里 WARN 说明改了什么。
- 因为上面这层保证，删掉 `demo.db` 重来、换机器、误改库都不会出现「谁都登不进去」。
- `admin` 在用户管理页受硬保护：不能删除、不能禁用、不能改权限、不能设有效期（编辑窗口里这些控件是灰的，服务端在 `checkProtected` 里还会再拦一次）；任何账号都不能删除自己。
- 日期字段的读写要一起看：`users` 表的 `create_time` / `expire_time` 是文本列，实体上必须**同时**有 `@TableField(typeHandler = TextDateTypeHandler.class)` 和 `@TableName(autoResultMap = true)`。只有前者时，insert / update 会按约定格式写入，但 select 会退回默认的 `DateTypeHandler`（它调 `rs.getTimestamp()`），遇到库里 `date('now')` 这类纯日期就抛 `Error parsing time stamp`，整个用户管理页打不开。
- 回归验证（JDK 8，工作目录为项目根目录，只用临时库，不动 `demo.db`）：

  ```bash
  mvn -o -B dependency:build-classpath "-Dmdep.outputFile=target_cp.txt"
  CP="target/classes;$(cat target_cp.txt)"
  "C:/Program Files/Java/jdk1.8.0_202/bin/javac.exe" -encoding UTF-8 -nowarn -cp "$CP" \
      -d target/verify-classes scripts/verify/VerifyAdminSeed.java
  "C:/Program Files/Java/jdk1.8.0_202/bin/java.exe" -Dfile.encoding=UTF-8 \
      -cp "target/classes;target/verify-classes;$CP" com.so.component.VerifyAdminSeed
  ```

  46 项断言覆盖：空库自动建表、旧库补字段、`admin` 被删 / 被禁用 / 过期 / 降权 / 清空密码后的自愈、重复调用幂等、登录判定链路，以及用真实的 `demo.db` 验证历史纯日期数据能正常读出。

## 常见问题

**1. 登录提示用户名或密码错误，但密码没问题**
先看「用户管理」里该账号的状态是不是「已禁用」或「已过期」——这两种状态即使密码正确也会被拒绝登录，并且页面上会写明原因。密码摘要按 UTF-8 计算后存入 SQLite，不存在外部文件的编码问题。

**2. 启动后页面打不开 / 404**
访问地址必须带上下文路径：`http://<ip>:9095/log/`。端口和上下文路径在 `application.properties` 里改。

**3. 启动 jar 项目后看不到日志**
检查启动命令：JVM 参数要写在 `-jar` 前面；重定向要写成 `> app.log 2>&1`。另外确认「jar 所在路径」填的是**目录**，不含 jar 包名。

**4. 忘记 admin 密码，或者所有账号都登不进去了**
内置管理员 `admin` 由程序保证存在：重启后如果它被禁用、已过期、权限被摘掉或密码列为空，都会自动修回可用状态，所以正常情况下不会彻底进不去。只是忘了密码的话，用 `admin` 登录后在「用户管理」里重置即可；连 `admin` 的密码也忘了，就停掉程序、把运行目录下的 `demo.db` 删掉再启动，程序会重新建库并写入 `admin / admin`（这会清空已保存的机器、项目与账号，操作前先备份）。

**5. 上传的密钥文件放在哪？**
同上，放 jar 同级目录；`remoteServerList.conf` 里写相对路径，例如私钥在 `test/key1.rsa` 就写 `test/key1.rsa`。

**6. 搜索不到日志 / 提示目录不存在**
确认该目录对配置的登录用户有读权限；文件名后缀必须在 `fileSuffix.conf` 里，或勾选「包含模式」。

**7. Web 终端里 vim、top 这类全屏程序显示不正常**

先确认浏览器版本：xterm.js 只支持较新的 Chrome / Edge / Firefox / Safari。终端本身是完整的 VT 实现，vim、top、tmux、less 都能跑，配色方案里的 256 色与真彩色也支持。

若终端完全不显示内容，按顺序查：浏览器的开发者工具 Console 有没有脚本报错、Network 里 `VAADIN/xterm/xterm.js` 是不是 404（部署时漏拷了 `VAADIN/xterm/` 目录）、WebSocket 有没有连上（工具栏状态栏会写明失败原因）。卡在全屏程序里出不来时，按 `Esc` 后输入 `:q!`（vim）或 `q`（top、less）即可。

**8. 监控页、文件管理页提示连接失败**
页面会直接显示失败原因。若配了私钥，会先试私钥、失败再回落密码，两条路都不通时会把两个原因都列出来。常见两类：
- `私钥文件不存在` / `私钥认证失败` —— 检查密钥路径，或该密钥是 `ssh-keygen` 默认产出的 OpenSSH 新格式（`-----BEGIN OPENSSH PRIVATE KEY-----`），sshj 解析不了，需要在服务器上执行 `ssh-keygen -p -m PEM -f <私钥文件>` 转成 PEM 再上传。
- `Auth fail` —— 用户名或密码不对；注意密码字段在配了带口令的私钥时会被当作私钥口令使用。

**8. 提示"目录不为空，无法删除"**
删除目录要求目录为空，先删干净里面的文件再删目录。

**9. 上传大文件失败**
上传会先落到 Web 服务器本地磁盘再转发到目标机器（远程场景），确保 Web 服务器和目标磁盘都有足够空间。

**10. 连上服务器后提示「Docker 服务未运行」或「目标机未安装 Docker」**
这是探测结果，不是报错：目标机上没有可用的 docker daemon，页面已经**停止发送**所有 docker 命令。按窗口里的提示操作即可 —— 没装就照对应发行版的命令装（CentOS 7 / Rocky / Ubuntu 各有一份），装了但没启动就点「启动 Docker 服务」（需要 `root` 或免密 `sudo`）。启动失败时窗口下方的报告里有 `systemctl is-active` 与 `journalctl -u docker` 的日志，常见原因是 `/etc/docker/daemon.json` 配置有误、存储驱动不匹配、或 SELinux 拦了。自己在服务器上把 docker 起起来之后，回到页面点「检测服务状态」刷新即可。

## 截图

<img width="900px" alt="登录页" src="images/login.png"/>
<img width="800px" height="300px" alt="本地日志搜索" src="images/localsearch.png"/>
<img width="600px" height="400px" alt="本地文件管理" src="images/local.png"/>
<img width="800px" height="400px" alt="远程日志登录" src="images/loginserver.png"/>
<img width="600px" height="400px" alt="免登录服务器列表" src="images/serverlist.png"/>
<img width="700px" height="500px" alt="应用管理" src="images/applicationMgmt.png"/>
<img width="700px" height="500px" alt="文件管理" src="images/fileMgmt.png"/>
<img width="1000px" alt="Web SSH 终端" src="images/terminal.png"/>

## 后续计划

- 安全管理页面：列出当前监听端口、协议、策略、允许 IP 与备注，支持配置 IP 白名单。
- 日志预览支持大文件（当前按行分页读取，超大文件仍建议直接下载）。
- 日志编码自动识别、按日志级别过滤。
- Docker-Compose 管理（菜单已预留）：compose 项目列表与状态、项目启停 / 重建、项目日志聚合、在线编辑 `docker-compose.yml`。
- 定时任务页面（菜单已预留）。

---

- 我的博客地址：https://blog.csdn.net/sl4379
