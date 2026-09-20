# logviewer

> 一个部署在服务器上的 **Web 端日志查看 / 文件管理 / 应用发布** 工具。
> 运维或管理员预先配好机器与账号，开发人员只需打开浏览器，就能查看、搜索和下载集群里各台服务器的日志文件，无需拿到 Linux 登录凭据。

- 技术栈：Vaadin 8 + Spring Boot（内嵌 Tomcat，打 **jar** 包）
- 访问方式：`http://<ip>:9095/log/`
- 默认账号：`admin / admin`（首次登录后请及时修改）

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
- 顺带把 **jar 包管理 / Tomcat 管理 / 通用应用管理** 做成了页面操作，上传、启停、看状态、看日志都在一个界面里完成。

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
运行目录（`user.dir`）就是数据目录，`demo.db`、`fileStorage/`、`users.properties` 都会生成在这里。

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
- 默认账号 **admin / admin**，登录成功后进入主界面
- 注意 URL 里的 **`/log`** 上下文路径不能省

### 4. 兼容性

- 不支持 CentOS 6.x，开发与验证环境为 CentOS 7.9。

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
| `users.properties` | 登录用户名与密码摘要 | **是**，见下 |
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

### 用户配置 `users.properties`

- 首次启动时，如果运行目录下没有该文件，**登录会失败**。把 jar 包内 `BOOT-INF/classes/users.properties` 解出来放到 **jar 同级目录**即可。

  ```properties
  admin=DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6
  ```

- 格式为 `用户名=密码摘要`，一行一个用户，默认用户为 `admin`（密码 `admin`）。
- 页面「用户管理」里新增 / 修改用户会自动写回运行目录下的 `users.properties`，重启后生效。
- 密码摘要使用 UTF-8 计算，**Windows 与 Linux 下结果一致**，不要用记事本改成其他编码保存。

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
| `users.properties` | 用户与密码摘要（见上） |
| `bin/server.sh` | 给被管理的 jar 项目使用的默认启停脚本 |
| `app.log` | 使用 `server.sh` 或重定向启动时的应用日志 |

> **升级时请保留** `demo.db`、`fileStorage/`、`users.properties` 和 `<用户名>.properties`，否则会丢失已配置的机器、项目与用户。

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
Docker管理                 （预留）
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
│   └── util/                  # 上传器、弹窗、TabSheet 工具等
├── config/                    # BeansConfig：数据源与 SqlSessionFactory
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

## 常见问题

**1. 登录提示用户名或密码错误，但密码没问题**
密码摘要按 UTF-8 计算。如果手工改过 `users.properties` 的编码（例如用记事本另存为 ANSI），摘要就会对不上。请保证该文件是 UTF-8 无 BOM。

**2. 启动后页面打不开 / 404**
访问地址必须带上下文路径：`http://<ip>:9095/log/`。端口和上下文路径在 `application.properties` 里改。

**3. 启动 jar 项目后看不到日志**
检查启动命令：JVM 参数要写在 `-jar` 前面；重定向要写成 `> app.log 2>&1`。另外确认「jar 所在路径」填的是**目录**，不含 jar 包名。

**4. `users.properties` 放在哪里？**
jar 包**同级目录**（也就是启动 jar 时的当前目录），不是 jar 内部。首次启动若找不到该文件会打印明确提示。

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
- Docker 管理、定时任务页面（菜单已预留）。

---

- 我的博客地址：https://blog.csdn.net/sl4379
