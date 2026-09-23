package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.docker.ComposeDiff;
import com.so.docker.ComposeService;
import com.so.docker.DockerExecutor;
import com.so.docker.DockerService;
import com.so.docker.DockerTerminalRegistry;
import com.so.docker.model.ComposeContainer;
import com.so.docker.model.ComposeModel;
import com.so.docker.model.ComposePortMapping;
import com.so.docker.model.ComposeProject;
import com.so.docker.model.ComposeResources;
import com.so.docker.model.ComposeServiceInfo;
import com.so.docker.model.ComposeTemplate;
import com.so.docker.model.DockerNetwork;
import com.so.docker.model.DockerVolume;
import com.so.ui.ComponentFactory;
import com.vaadin.server.ExternalResource;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.BrowserFrame;
import com.vaadin.ui.Button;
import com.vaadin.ui.CheckBox;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个 Compose 项目的详情窗口。
 * <p>
 * 七个页签围绕同一个项目，切来切去不用回列表：
 * <ol>
 *   <li><b>概览</b>：生命周期按钮 + 状态汇总 + 配置警告；</li>
 *   <li><b>服务</b>：声明维度（镜像 / 副本 / 端口 / 依赖）+ 单服务启停 / 重启 / 扩缩容 / 配置详情；</li>
 *   <li><b>容器</b>：实例维度，按服务分组，健康 / 重启次数 / 退出码，并复用单容器管理的日志 / 终端 / 监控；</li>
 *   <li><b>日志</b>：整个项目或某个服务的聚合日志（{@code compose logs -f}，走 pty 所以按服务着色）；</li>
 *   <li><b>网络与卷</b>：项目占用的网络 / 卷 / 端口映射（含冲突检查）/ 磁盘占用；</li>
 *   <li><b>依赖图</b>：depends_on 分层布局 + 每个服务所在网络；</li>
 *   <li><b>文件</b>：在线编辑 compose 文件与 .env，带校验、差异对比、模板库。</li>
 * </ol>
 * <p>
 * <b>所有远程动作都在后台线程。</b>compose 的一次 up 可能要几分钟（要拉镜像、要建容器），
 * 放在请求线程里界面会直接假死。
 */
public class ComposeProjectWindow extends Window {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ComposeProjectWindow.class);

    private static final String TERMINAL_PAGE = "VAADIN/themes/mytheme/terminal.html";
    private static final String TAIL_ALL = "全部";
    private static final String LOG_ALL_SERVICES = "（全部服务）";

    private final DockerComposeComponent owner;
    private final ComposeService compose;
    private final DockerService dockerService;
    private final DockerExecutor executor;
    private final ComposeProject project;
    private final UI ui;

    private TabSheet tabs;

    /* 概览 */
    private Label overviewLabel;
    private Label overviewWarnLabel;

    /* 服务 */
    private Grid<ComposeServiceInfo> serviceGrid;
    private Label serviceStatusLabel;
    private final List<ComposeServiceInfo> services = new ArrayList<ComposeServiceInfo>();

    /* 容器 */
    private Grid<ComposeContainer> containerGrid;
    private Label containerStatusLabel;
    private final List<ComposeContainer> containers = new ArrayList<ComposeContainer>();

    /* 日志 */
    private ComboBox<String> logServiceCombo;
    private ComboBox<String> logTailCombo;
    private CheckBox logTimestampBox;
    private BrowserFrame logFrame;
    private String logToken;

    /* 资源 */
    private Label resourceSummary;
    private Label resourceWarn;
    private Grid<ComposePortMapping> portGrid;
    private Grid<DockerNetwork> networkGrid;
    private Grid<DockerVolume> volumeGrid;

    /* 依赖图 */
    private Panel graphPanel;
    private Label graphLabel;

    /* 文件 */
    private ComboBox<String> fileCombo;
    private ComposeYamlEditor editor;
    private Label fileStatus;
    private TextArea envArea;
    private Label envVarLabel;

    /** 最近一次从服务器读到的内容，用于「查看差异」和「校验当前编辑内容」 */
    private final Map<String, String> savedFiles = new LinkedHashMap<String, String>();
    private String savedEnv = "";
    private String currentFile;

    private ComposeModel model;
    private ComposeResources resources;
    private String modelError = "";
    private boolean loading;

    public ComposeProjectWindow(DockerComposeComponent owner, ComposeService compose, ComposeProject project) {
        super("Compose 项目 - " + project.getName() + "（" + project.getDirectory() + "）");
        this.owner = owner;
        this.compose = compose;
        this.executor = compose.getExecutor();
        this.dockerService = new DockerService(executor);
        this.project = project;
        this.ui = UI.getCurrent();

        setWidth("1300px");
        setHeight("820px");
        setModal(true);
        setResizable(true);
        center();

        tabs = new TabSheet();
        tabs.setSizeFull();
        setContent(tabs);

        tabs.addTab(buildOverviewTab(), "概览");
        tabs.addTab(buildServiceTab(), "服务");
        tabs.addTab(buildContainerTab(), "容器");
        tabs.addTab(buildLogsTab(), "日志");
        tabs.addTab(buildResourceTab(), "网络与卷");
        tabs.addTab(buildGraphTab(), "依赖图");
        tabs.addTab(buildFileTab(), "文件");

        reloadAll();
    }

    public void show(UI target) {
        UI current = (null == target) ? UI.getCurrent() : target;
        if (null == current) {
            return;
        }
        current.addWindow(this);
    }

    public String getProjectName() {
        return project.getName();
    }

    public ComposeProject getProject() {
        return project;
    }

    @Override
    public void detach() {
        DockerTerminalRegistry.release(logToken);
        logToken = null;
        super.detach();
    }

    /* ================================================================== */
    /* 页签 1：概览                                                        */
    /* ================================================================== */

    private VerticalLayout buildOverviewTab() {
        VerticalLayout root = fullLayout();

        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setWidth("100%");
        actions.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        actions.addComponent(actionButton("启动（up -d）", () -> doProjectAction("启动项目",
                () -> compose.up(project, null, false, false, true))));
        actions.addComponent(actionButton("暂停（stop，保留容器）", () -> doProjectAction("暂停项目",
                () -> compose.stop(project, null))));
        actions.addComponent(actionButton("恢复（start）", () -> doProjectAction("恢复项目",
                () -> compose.start(project, null))));
        actions.addComponent(actionButton("重启（restart）", () -> doProjectAction("重启项目",
                () -> compose.restart(project, null))));
        actions.addComponent(actionButton("重新部署（应用文件改动）", () -> confirmRedeploy(false)));
        actions.addComponent(actionButton("拉取镜像（pull）", () -> doProjectAction("拉取镜像",
                () -> compose.pull(project, null))));
        actions.addComponent(actionButton("构建镜像（build）", () -> doProjectAction("构建镜像",
                () -> compose.build(project, null))));
        Button refresh = ComponentFactory.getStandardButton("刷新");
        refresh.setWidth("80px");
        refresh.addClickListener(e -> {
            executor.invalidateDaemon();
            compose.invalidateCli();
            reloadAll();
        });
        actions.addComponent(refresh);
        root.addComponent(actions);

        Label hint = ComponentFactory.getStandardLabel(
                "「重启（restart）」只重启进程，不会应用 compose 文件的改动；"
                        + "改完文件请用「重新部署」，它等价于 docker compose up -d --force-recreate --remove-orphans。"
                        + "「暂停」用 stop，容器还在，恢复时不用重建。");
        hint.addStyleName("docker-hint");
        hint.setWidth("100%");
        root.addComponent(hint);

        overviewLabel = new Label("");
        overviewLabel.setContentMode(com.vaadin.shared.ui.ContentMode.HTML);
        overviewLabel.addStyleName("compose-kv");
        overviewLabel.setWidth("100%");
        root.addComponent(overviewLabel);

        overviewWarnLabel = new Label("");
        overviewWarnLabel.setContentMode(com.vaadin.shared.ui.ContentMode.HTML);
        overviewWarnLabel.addStyleName("compose-warn-label");
        overviewWarnLabel.setWidth("100%");
        overviewWarnLabel.setVisible(false);
        root.addComponent(overviewWarnLabel);

        root.setExpandRatio(overviewLabel, 1f);
        return root;
    }

    private Button actionButton(String caption, Runnable action) {
        Button button = ComponentFactory.getStandardButton(caption);
        button.setHeight("30px");
        button.addClickListener(e -> action.run());
        return button;
    }

    /* ================================================================== */
    /* 页签 2：服务                                                        */
    /* ================================================================== */

    private VerticalLayout buildServiceTab() {
        VerticalLayout root = fullLayout();

        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setWidth("100%");
        actions.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        actions.addComponent(actionButton("启动全部服务", () -> doProjectAction("启动项目",
                () -> compose.up(project, null, false, false, true))));
        actions.addComponent(actionButton("暂停全部服务", () -> doProjectAction("暂停项目",
                () -> compose.stop(project, null))));
        actions.addComponent(actionButton("恢复全部服务", () -> doProjectAction("恢复项目",
                () -> compose.start(project, null))));
        actions.addComponent(actionButton("重启全部服务", () -> doProjectAction("重启项目",
                () -> compose.restart(project, null))));
        root.addComponent(actions);

        serviceStatusLabel = ComponentFactory.getStandardLabel("");
        serviceStatusLabel.addStyleName("docker-status");
        serviceStatusLabel.setWidth("100%");
        root.addComponent(serviceStatusLabel);

        serviceGrid = new Grid<ComposeServiceInfo>();
        serviceGrid.setSizeFull();
        serviceGrid.addStyleName("grid_standard");
        serviceGrid.addColumn(ComposeServiceInfo::getName).setCaption("服务").setWidth(150);
        serviceGrid.addComponentColumn(service -> {
            Label state = ComponentFactory.getStandardLabel(service.getStatusLabel());
            state.addStyleName(service.getStatusStyle());
            return state;
        }).setCaption("状态").setWidth(90);
        serviceGrid.addColumn(ComposeServiceInfo::getImage).setCaption("镜像").setWidth(220);
        serviceGrid.addComponentColumn(service -> {
            Label replicas = ComponentFactory.getStandardLabel(service.getReplicaText());
            if (service.isScalable()) {
                replicas.addStyleName("docker-state-running");
            } else {
                replicas.addStyleName("docker-hint");
            }
            return replicas;
        }).setCaption("副本（运行/期望）").setWidth(130);
        serviceGrid.addColumn(ComposeServiceInfo::getPortsText).setCaption("端口").setWidth(180);
        serviceGrid.addColumn(service -> StrUtil.join(", ", service.getDependsOn())).setCaption("依赖").setWidth(150);
        serviceGrid.addColumn(service -> healthText(service)).setCaption("健康 / 重启").setWidth(130);
        serviceGrid.addComponentColumn(this::buildServiceActions).setCaption("操作").setWidth(300);

        root.addComponent(serviceGrid);
        root.setExpandRatio(serviceGrid, 1f);
        return root;
    }

    private String healthText(ComposeServiceInfo service) {
        List<ComposeContainer> list = containersOf(service.getName());
        int restarts = 0;
        int unhealthy = 0;
        for (ComposeContainer container : list) {
            restarts += container.getRestartCount();
            if (ComposeContainer.UNHEALTHY.equals(container.getHealth())) {
                unhealthy++;
            }
        }
        if (list.isEmpty()) {
            return "—";
        }
        return (unhealthy > 0 ? "不健康 " + unhealthy : "正常") + " / " + restarts + " 次";
    }

    private HorizontalLayout buildServiceActions(ComposeServiceInfo service) {
        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(true);
        row.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        final String name = service.getName();

        row.addComponent(link("启动", () -> doProjectAction("启动服务 " + name,
                () -> compose.up(project, name, false, false, true))));
        row.addComponent(link("暂停", () -> doProjectAction("暂停服务 " + name,
                () -> compose.stop(project, name))));
        row.addComponent(link("重启", () -> doProjectAction("重启服务 " + name,
                () -> compose.restart(project, name))));
        if (service.isScalable()) {
            row.addComponent(link("扩缩容", () -> openScaleDialog(service)));
        }
        row.addComponent(link("配置", () -> showServiceDetail(service)));
        return row;
    }

    private void openScaleDialog(final ComposeServiceInfo service) {
        String html = "<h3>调整服务 " + ComposeDiff.escape(service.getName()) + " 的副本数</h3>"
                + "当前运行 " + service.getRunningContainers() + " 个，期望 " + service.getReplicas() + " 个。<br/>"
                + "执行的是 <b>docker compose up -d --scale " + ComposeDiff.escape(service.getName())
                + "=N</b>：配置没变时不会重建已有容器，扩容会新建、缩容会删掉多余的那几个。";
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("扩缩容", html, "执行", "取消", true);
        final TextField replicas = ComponentFactory.getStandardTtextField("副本数：");
        replicas.setWidth("180px");
        replicas.setValue(String.valueOf(Math.max(0, service.getReplicas())));
        win.getLayout().addComponent(replicas, 1);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                int value;
                try {
                    value = Integer.parseInt(StrUtil.trimToEmpty(replicas.getValue()));
                } catch (NumberFormatException e) {
                    Notification.show("副本数必须是整数", Notification.Type.WARNING_MESSAGE);
                    return;
                }
                win.close();
                doProjectAction("扩缩容 " + service.getName(), () -> compose.scale(project, service.getName(), value));
            }
        });
        win.showConfirmation();
    }

    private void showServiceDetail(ComposeServiceInfo service) {
        Window win = new Window("服务配置 - " + service.getName());
        win.setWidth("820px");
        win.setHeight("640px");
        win.setModal(true);
        win.setResizable(true);
        win.center();

        TextArea area = ComponentFactory.getTextArea();
        area.setSizeFull();
        area.setReadOnly(true);
        area.addStyleName("docker-inspect-area");
        area.setValue(describeService(service));
        win.setContent(area);
        if (null != ui) {
            ui.addWindow(win);
        }
    }

    /**
     * 服务配置详情。
     * <p>
     * 数据来自 {@code docker compose config} 的解析结果而不是用户写的 yml ——
     * 这里展示的端口、环境变量、依赖都是「最终生效」的值，变量已经替换过了。
     */
    private String describeService(ComposeServiceInfo service) {
        StringBuilder sb = new StringBuilder();
        sb.append("【基本】\n");
        sb.append("  服务名      : ").append(service.getName()).append('\n');
        sb.append("  镜像        : ").append(StrUtil.emptyToDefault(service.getImage(), "（无，可能只用 build）")).append('\n');
        if (StrUtil.isNotBlank(service.getBuildContext())) {
            sb.append("  构建上下文  : ").append(service.getBuildContext()).append('\n');
        }
        if (StrUtil.isNotBlank(service.getContainerName())) {
            sb.append("  容器名      : ").append(service.getContainerName())
                    .append("（写死了容器名，无法扩缩容）\n");
        }
        sb.append("  期望副本数  : ").append(service.getReplicas());
        if (service.isReplicasDeclared()) {
            sb.append("（来自 deploy.replicas，此时 --scale 不生效）");
        }
        sb.append('\n');
        sb.append("  当前运行    : ").append(service.getRunningContainers())
                .append(" / ").append(service.getTotalContainers()).append(" 个容器\n");
        sb.append("  重启策略    : ").append(StrUtil.emptyToDefault(service.getRestartPolicy(), "未设置（默认 no）")).append('\n');
        sb.append("  健康检查    : ").append(StrUtil.emptyToDefault(service.getHealthcheck(), "未配置")).append('\n');
        if (StrUtil.isNotBlank(service.getCommand())) {
            sb.append("  启动命令    : ").append(service.getCommand()).append('\n');
        }
        if (!service.getProfiles().isEmpty()) {
            sb.append("  profiles    : ").append(StrUtil.join(", ", service.getProfiles()))
                    .append("（不指定 profile 时不会启动）\n");
        }
        sb.append('\n').append("【端口】\n");
        if (service.getPorts().isEmpty()) {
            sb.append("  （没有对外发布的端口）\n");
        } else {
            for (String port : service.getPorts()) {
                sb.append("  ").append(port).append('\n');
            }
        }
        sb.append('\n').append("【网络】\n");
        sb.append("  ").append(service.getNetworks().isEmpty() ? "（默认网络）"
                : StrUtil.join(", ", service.getNetworks())).append('\n');

        sb.append('\n').append("【数据卷】\n");
        if (service.getVolumes().isEmpty()) {
            sb.append("  （没有挂载）\n");
        } else {
            for (String volume : service.getVolumes()) {
                sb.append("  ").append(volume).append('\n');
            }
        }

        sb.append('\n').append("【依赖（depends_on）】\n");
        sb.append("  ").append(service.getDependsOn().isEmpty() ? "（无）"
                : StrUtil.join(", ", service.getDependsOn())).append('\n');

        sb.append('\n').append("【环境变量（已替换变量）】\n");
        if (service.getEnvironment().isEmpty()) {
            sb.append("  （无）\n");
        } else {
            List<String> keys = new ArrayList<String>(service.getEnvironment().keySet());
            Collections.sort(keys);
            for (String key : keys) {
                String value = service.getEnvironment().get(key);
                // 密码类不原样展示，避免截图外传
                if (key.toUpperCase().contains("PASSWORD") || key.toUpperCase().contains("SECRET")
                        || key.toUpperCase().contains("TOKEN")) {
                    value = StrUtil.isBlank(value) ? "" : "******";
                }
                sb.append("  ").append(key).append('=').append(value).append('\n');
            }
        }

        sb.append('\n').append("【该服务的容器】\n");
        List<ComposeContainer> list = containersOf(service.getName());
        if (list.isEmpty()) {
            sb.append("  （还没有创建容器）\n");
        } else {
            for (ComposeContainer container : list) {
                sb.append("  ").append(container.getName())
                        .append("　").append(container.getStateLabel())
                        .append("　健康=").append(container.getHealthLabel())
                        .append("　重启=").append(container.getRestartCount())
                        .append("　退出码=").append(container.getExitCode())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /* ================================================================== */
    /* 页签 3：容器                                                        */
    /* ================================================================== */

    private VerticalLayout buildContainerTab() {
        VerticalLayout root = fullLayout();

        containerStatusLabel = ComponentFactory.getStandardLabel("");
        containerStatusLabel.addStyleName("docker-status");
        containerStatusLabel.setWidth("100%");
        root.addComponent(containerStatusLabel);

        Label hint = ComponentFactory.getStandardLabel(
                "容器按「服务 + 副本号」排序。日志 / 终端 / 监控直接复用单容器管理那套（docker logs / exec / stats）；"
                        + "启停与删除走的是 docker 原生命令，与 compose 的状态可能短暂不一致，"
                        + "想让 compose 重新接管，回来点一次「重新部署」。");
        hint.addStyleName("docker-hint");
        hint.setWidth("100%");
        root.addComponent(hint);

        containerGrid = new Grid<ComposeContainer>();
        containerGrid.setSizeFull();
        containerGrid.addStyleName("grid_standard");
        containerGrid.setSelectionMode(Grid.SelectionMode.SINGLE);

        containerGrid.addColumn(ComposeContainer::getServiceContainerLabel).setCaption("服务").setWidth(140);
        containerGrid.addColumn(ComposeContainer::getName).setCaption("容器名").setWidth(230);
        containerGrid.addComponentColumn(container -> {
            Label state = ComponentFactory.getStandardLabel(container.getStateLabel());
            state.addStyleName(container.getStateStyle());
            return state;
        }).setCaption("状态").setWidth(110);
        containerGrid.addComponentColumn(container -> {
            Label health = ComponentFactory.getStandardLabel(container.getHealthLabel());
            health.addStyleName(container.getHealthStyle());
            return health;
        }).setCaption("健康检查").setWidth(90);
        containerGrid.addColumn(ComposeContainer::getRestartCount).setCaption("重启次数").setWidth(90);
        containerGrid.addColumn(container -> container.getExitCode() == 0 && !container.isFailed()
                ? "—" : String.valueOf(container.getExitCode())).setCaption("退出码").setWidth(80);
        containerGrid.addColumn(ComposeContainer::getImage).setCaption("镜像").setWidth(200);
        containerGrid.addColumn(container -> StrUtil.emptyToDefault(container.getPorts(), "—"))
                .setCaption("端口").setWidth(190);
        containerGrid.addComponentColumn(this::buildContainerActions).setCaption("操作").setWidth(330);

        root.addComponent(containerGrid);
        root.setExpandRatio(containerGrid, 1f);
        return root;
    }

    private HorizontalLayout buildContainerActions(ComposeContainer container) {
        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(true);
        row.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        row.addComponent(link("详情", () -> openContainerWindow(container, "详情")));
        row.addComponent(link("日志", () -> openContainerWindow(container, "日志")));
        row.addComponent(link("终端", () -> openContainerWindow(container, "终端")));
        row.addComponent(link("监控", () -> openContainerWindow(container, "资源监控")));
        if (container.isUp()) {
            // stop/start/restart 这些在 DockerService 里是 void，而 Action 约定返回命令输出，
            // 所以显式补一个 null —— 单表达式 lambda 在这里会因为"void 转 String"编译不过
            row.addComponent(link("停止", () -> doContainerAction("停止容器 " + container.getName(),
                    () -> {
                        dockerService.stopContainer(container.getId());
                        return null;
                    })));
        } else {
            row.addComponent(link("启动", () -> doContainerAction("启动容器 " + container.getName(),
                    () -> {
                        dockerService.startContainer(container.getId());
                        return null;
                    })));
        }
        row.addComponent(link("重启", () -> doContainerAction("重启容器 " + container.getName(),
                () -> {
                    dockerService.restartContainer(container.getId());
                    return null;
                })));
        row.addComponent(link("删除", () -> confirmRemoveContainer(container)));
        return row;
    }

    private void openContainerWindow(ComposeContainer container, String tab) {
        try {
            new DockerContainerDetailWindow(container.toDockerContainer(), dockerService, tab).show();
        } catch (Exception e) {
            Notification.show("打开容器详情失败：" + DockerUi.reason(e), Notification.Type.ERROR_MESSAGE);
        }
    }

    private void confirmRemoveContainer(final ComposeContainer container) {
        String html = "<h3>删除容器 " + ComposeDiff.escape(container.getName()) + "</h3>"
                + "这是 <b>docker rm</b>，不是 compose 的删除：下次 <b>up -d</b> 会按 compose 文件把它重新建出来"
                + "（数据卷还在，容器内的临时改动会丢）。<br/>运行中的容器需要强杀才能删。";
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("删除容器", html, "确认删除", "取消", true);
        final CheckBox forceBox = new CheckBox("强制删除（-f，运行中也会被强杀）");
        forceBox.addStyleName("docker-dialog-checkbox");
        win.getLayout().addComponent(forceBox, 1);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                final boolean force = forceBox.getValue();
                win.close();
                doContainerAction("删除容器 " + container.getName(),
                        () -> dockerService.removeContainer(container.getId(), force, false));
            }
        });
        win.showConfirmation();
    }

    /* ================================================================== */
    /* 页签 4：日志                                                        */
    /* ================================================================== */

    private VerticalLayout buildLogsTab() {
        VerticalLayout root = fullLayout();

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setHeight("40px");
        bar.setWidth("100%");
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        logServiceCombo = new ComboBox<String>();
        logServiceCombo.setWidth("220px");
        logServiceCombo.setTextInputAllowed(false);
        logServiceCombo.setEmptySelectionAllowed(false);
        logServiceCombo.setItems(LOG_ALL_SERVICES);
        logServiceCombo.setValue(LOG_ALL_SERVICES);

        logTailCombo = new ComboBox<String>();
        logTailCombo.setItems("100", "500", "2000", "10000", TAIL_ALL);
        logTailCombo.setValue("500");
        logTailCombo.setWidth("110px");
        logTailCombo.setTextInputAllowed(false);
        logTailCombo.setEmptySelectionAllowed(false);

        logTimestampBox = new CheckBox("显示时间戳");
        Button reopen = ComponentFactory.getStandardButton("重新加载");
        reopen.setWidth("100px");

        bar.addComponents(ComponentFactory.getStandardLabel("服务："), logServiceCombo,
                ComponentFactory.getStandardLabel("显示末"), logTailCombo, logTimestampBox, reopen);
        root.addComponent(bar);

        Label hint = ComponentFactory.getStandardLabel(
                "这是 docker compose logs -f：整个项目（或选中的服务）的多个容器日志合并到一条流里，"
                        + "每行前面的服务名前缀由 compose 着色 —— 走的是带 tty 的通道，"
                        + "因为 compose 只有在输出目标是终端时才加前缀和颜色。只读视图，搜索用 Ctrl+F。");
        hint.addStyleName("docker-hint");
        hint.setWidth("100%");
        root.addComponent(hint);

        logFrame = new BrowserFrame();
        logFrame.setSizeFull();
        logFrame.addStyleName("docker-term-frame");
        root.addComponent(logFrame);
        root.setExpandRatio(logFrame, 1f);

        reopen.addClickListener(e -> reloadLogFrame());
        logTailCombo.addValueChangeListener(e -> reloadLogFrame());
        logServiceCombo.addValueChangeListener(e -> reloadLogFrame());
        logTimestampBox.addValueChangeListener(e -> reloadLogFrame());
        return root;
    }

    private void reloadLogFrame() {
        if (null == logFrame) {
            return;
        }
        DockerTerminalRegistry.release(logToken);
        logToken = null;
        final String service = LOG_ALL_SERVICES.equals(logServiceCombo.getValue()) ? null : logServiceCombo.getValue();
        final int tail = parseTail();
        final boolean timestamps = Boolean.TRUE.equals(logTimestampBox.getValue());
        DockerUi.async("准备聚合日志", new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                return DockerTerminalRegistry.register(
                        compose.buildLogsSpec(project, service, tail, timestamps));
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String token) {
                logToken = token;
                // ro=1 只读；不带 eol：pty 通道出来的本来就是 CRLF
                logFrame.setSource(new ExternalResource(TERMINAL_PAGE + "?ws=/ws/docker&ro=1&token=" + token));
            }
        });
    }

    private int parseTail() {
        String value = logTailCombo.getValue();
        if (TAIL_ALL.equals(value) || StrUtil.isBlank(value)) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 500;
        }
    }

    /* ================================================================== */
    /* 页签 5：网络与卷                                                    */
    /* ================================================================== */

    private VerticalLayout buildResourceTab() {
        VerticalLayout root = fullLayout();

        resourceSummary = ComponentFactory.getStandardLabel("");
        resourceSummary.addStyleName("compose-kv");
        resourceSummary.setWidth("100%");
        root.addComponent(resourceSummary);

        resourceWarn = ComponentFactory.getStandardLabel("");
        resourceWarn.addStyleName("compose-warn-label");
        resourceWarn.setWidth("100%");
        resourceWarn.setVisible(false);
        root.addComponent(resourceWarn);

        TabSheet inner = new TabSheet();
        inner.setSizeFull();

        portGrid = new Grid<ComposePortMapping>();
        portGrid.setSizeFull();
        portGrid.addStyleName("grid_standard");
        portGrid.addColumn(ComposePortMapping::getService).setCaption("服务").setWidth(150);
        portGrid.addColumn(ComposePortMapping::getHostPort).setCaption("宿主端口").setWidth(110);
        portGrid.addColumn(ComposePortMapping::getContainerPort).setCaption("容器端口").setWidth(110);
        portGrid.addColumn(ComposePortMapping::getProtocol).setCaption("协议").setWidth(80);
        portGrid.addColumn(ComposePortMapping::getDisplay).setCaption("映射").setWidth(220);
        portGrid.addComponentColumn(mapping -> {
            Label label = ComponentFactory.getStandardLabel(mapping.getConflictText());
            if (mapping.isConflict()) {
                label.addStyleName("docker-state-error");
            } else {
                label.addStyleName("docker-hint");
            }
            return label;
        }).setCaption("冲突检查").setWidth(320);
        inner.addTab(portGrid, "端口映射");

        networkGrid = new Grid<DockerNetwork>();
        networkGrid.setSizeFull();
        networkGrid.addStyleName("grid_standard");
        networkGrid.addColumn(DockerNetwork::getName).setCaption("网络名").setWidth(240);
        networkGrid.addColumn(DockerNetwork::getDriver).setCaption("驱动").setWidth(90);
        networkGrid.addColumn(DockerNetwork::getSubnet).setCaption("网段").setWidth(160);
        networkGrid.addColumn(DockerNetwork::getGateway).setCaption("网关").setWidth(150);
        networkGrid.addColumn(DockerNetwork::getContainersText).setCaption("已连接容器");
        inner.addTab(networkGrid, "网络");

        volumeGrid = new Grid<DockerVolume>();
        volumeGrid.setSizeFull();
        volumeGrid.addStyleName("grid_standard");
        volumeGrid.addColumn(DockerVolume::getName).setCaption("卷名").setWidth(240);
        volumeGrid.addColumn(DockerVolume::getDriver).setCaption("驱动").setWidth(90);
        volumeGrid.addColumn(DockerVolume::getMountpoint).setCaption("宿主机路径").setWidth(320);
        volumeGrid.addColumn(DockerVolume::getUsedByText).setCaption("使用中");
        inner.addTab(volumeGrid, "数据卷");

        root.addComponent(inner);
        root.setExpandRatio(inner, 1f);
        return root;
    }

    /* ================================================================== */
    /* 页签 6：依赖图                                                      */
    /* ================================================================== */

    private VerticalLayout buildGraphTab() {
        VerticalLayout root = fullLayout();

        Label hint = ComponentFactory.getStandardLabel(
                "实线箭头 = depends_on（从被依赖的服务指向依赖它的服务），层级按依赖深度自动分层；"
                        + "节点下方标出该服务加入的网络，虚线框标出同一个网络里的服务。"
                        + "如果出现环（A 依赖 B、B 依赖 A），compose 会拒绝启动，这里也会在概览里报出来。");
        hint.addStyleName("docker-hint");
        hint.setWidth("100%");
        root.addComponent(hint);

        graphLabel = new Label("");
        graphLabel.setContentMode(com.vaadin.shared.ui.ContentMode.HTML);
        graphLabel.addStyleName("compose-graph-box");
        graphLabel.setWidth("100%");

        graphPanel = new Panel();
        graphPanel.setSizeFull();
        graphPanel.setContent(graphLabel);
        root.addComponent(graphPanel);
        root.setExpandRatio(graphPanel, 1f);
        return root;
    }

    /**
     * 生成依赖关系图的 SVG。
     * <p>
     * 布局是「按依赖深度分层」：没有依赖的服务在第一层，依赖第一层的在第二层，以此类推。
     * 不用力导向布局：这种图通常只有几个节点，分层布局一眼就能看出启动顺序，
     * 而力导向每次刷新位置都不一样，反而更难读。
     */
    private String buildGraphHtml() {
        if (null == model || model.getServices().isEmpty()) {
            return "<div style=\"padding:12px;color:#8a95a5;font-size:12px;\">没有可绘制的服务"
                    + (StrUtil.isBlank(modelError) ? "" : "（配置解析失败：" + ComposeDiff.escape(modelError) + "）")
                    + "</div>";
        }
        Map<String, Integer> level = new LinkedHashMap<String, Integer>();
        for (ComposeServiceInfo service : model.getServices()) {
            resolveLevel(service.getName(), level, new LinkedHashSet<String>(), 0);
        }
        Map<Integer, List<ComposeServiceInfo>> rows = new LinkedHashMap<Integer, List<ComposeServiceInfo>>();
        int maxLevel = 0;
        for (ComposeServiceInfo service : model.getServices()) {
            Integer depth = level.get(service.getName());
            int value = null == depth ? 0 : depth;
            maxLevel = Math.max(maxLevel, value);
            List<ComposeServiceInfo> row = rows.get(value);
            if (null == row) {
                row = new ArrayList<ComposeServiceInfo>();
                rows.put(value, row);
            }
            row.add(service);
        }

        int nodeW = 176;
        int nodeH = 54;
        int gapX = 28;
        int rowGap = 84;
        int padX = 24;
        int padY = 20;
        int maxRow = 1;
        for (List<ComposeServiceInfo> row : rows.values()) {
            maxRow = Math.max(maxRow, row.size());
        }
        int width = padX * 2 + maxRow * nodeW + (maxRow - 1) * gapX;
        int height = padY * 2 + (maxLevel + 1) * nodeH + maxLevel * (rowGap - nodeH);

        Map<String, int[]> positions = new LinkedHashMap<String, int[]>();
        for (Map.Entry<Integer, List<ComposeServiceInfo>> entry : rows.entrySet()) {
            List<ComposeServiceInfo> row = entry.getValue();
            int rowWidth = row.size() * nodeW + (row.size() - 1) * gapX;
            int startX = (width - rowWidth) / 2;
            int y = padY + entry.getKey() * rowGap;
            for (int i = 0; i < row.size(); i++) {
                positions.put(row.get(i).getName(), new int[]{startX + i * (nodeW + gapX), y});
            }
        }

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height).append("\">");
        svg.append("<defs><marker id=\"compose-arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" ")
                .append("markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">")
                .append("<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#8fa3b8\"/></marker></defs>");

        // 先画连线，节点盖在上面
        for (ComposeServiceInfo service : model.getServices()) {
            int[] target = positions.get(service.getName());
            if (null == target) {
                continue;
            }
            for (String dependency : service.getDependsOn()) {
                int[] source = positions.get(dependency);
                if (null == source) {
                    continue;
                }
                int x1 = source[0] + nodeW / 2;
                int y1 = source[1] + nodeH;
                int x2 = target[0] + nodeW / 2;
                int y2 = target[1];
                int midY = (y1 + y2) / 2;
                svg.append("<path d=\"M ").append(x1).append(' ').append(y1)
                        .append(" C ").append(x1).append(' ').append(midY).append(' ')
                        .append(x2).append(' ').append(midY).append(' ')
                        .append(x2).append(' ').append(y2)
                        .append("\" fill=\"none\" stroke=\"#8fa3b8\" stroke-width=\"1.5\" ")
                        .append("marker-end=\"url(#compose-arrow)\"/>");
            }
        }

        // 同网络的虚线框：把每个网络的成员用一条虚线圈起来太乱，改成在节点上标网络名 + 图例
        for (ComposeServiceInfo service : model.getServices()) {
            int[] pos = positions.get(service.getName());
            if (null == pos) {
                continue;
            }
            String fill = "#f2f4f7";
            String stroke = "#b9c3cf";
            if (ComposeServiceInfo.STATUS_RUNNING.equals(service.getStatus())) {
                fill = "#e8f6ee";
                stroke = "#4c9e78";
            } else if (ComposeServiceInfo.STATUS_PARTIAL.equals(service.getStatus())) {
                fill = "#fdf6e8";
                stroke = "#d9a33e";
            } else if (service.getUnhealthyContainers() > 0) {
                fill = "#fdeced";
                stroke = "#c0392b";
            }
            svg.append("<rect x=\"").append(pos[0]).append("\" y=\"").append(pos[1])
                    .append("\" width=\"").append(nodeW).append("\" height=\"").append(nodeH)
                    .append("\" rx=\"6\" ry=\"6\" fill=\"").append(fill)
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1.4\"/>");
            svg.append("<text x=\"").append(pos[0] + 10).append("\" y=\"").append(pos[1] + 21)
                    .append("\" font-size=\"13\" font-weight=\"bold\" fill=\"#1b3a57\" ")
                    .append("font-family=\"Helvetica, Arial, sans-serif\">")
                    .append(ComposeDiff.escape(service.getName())).append("</text>");
            svg.append("<text x=\"").append(pos[0] + 10).append("\" y=\"").append(pos[1] + 37)
                    .append("\" font-size=\"11\" fill=\"#5b6b7d\" ")
                    .append("font-family=\"Helvetica, Arial, sans-serif\">")
                    .append(ComposeDiff.escape(shortText(StrUtil.emptyToDefault(service.getImage(), "（无镜像）"), 26)))
                    .append("</text>");
            String nets = service.getNetworks().isEmpty() ? "默认网络" : StrUtil.join(",", service.getNetworks());
            svg.append("<text x=\"").append(pos[0] + 10).append("\" y=\"").append(pos[1] + 50)
                    .append("\" font-size=\"10\" fill=\"#8a95a5\" ")
                    .append("font-family=\"Helvetica, Arial, sans-serif\">net: ")
                    .append(ComposeDiff.escape(shortText(nets, 30))).append("</text>");
        }
        svg.append("</svg>");

        StringBuilder html = new StringBuilder(svg);
        html.append("<div style=\"font-size:11px;color:#5b6b7d;padding:6px 4px;line-height:18px;\">")
                .append("图例：<span style=\"color:#4c9e78;\">■</span> 运行中　")
                .append("<span style=\"color:#d9a33e;\">■</span> 部分运行　")
                .append("<span style=\"color:#c0392b;\">■</span> 有不健康容器　")
                .append("<span style=\"color:#b9c3cf;\">■</span> 未启动</div>");
        if (!model.getDeclaredNetworks().isEmpty()) {
            html.append("<div style=\"font-size:11px;color:#5b6b7d;padding:0 4px;line-height:18px;\">")
                    .append("声明网络：").append(ComposeDiff.escape(StrUtil.join("、", model.getDeclaredNetworks())))
                    .append("　卷：").append(ComposeDiff.escape(model.getDeclaredVolumes().isEmpty()
                            ? "无" : StrUtil.join("、", model.getDeclaredVolumes())))
                    .append("</div>");
        }
        List<String> cycles = model.detectDependencyCycle();
        if (!cycles.isEmpty()) {
            html.append("<div class=\"compose-warn-label\">检测到依赖成环：")
                    .append(ComposeDiff.escape(StrUtil.join("；", cycles)))
                    .append("　compose 会拒绝启动，请打散这个环。</div>");
        }
        return html.toString();
    }

    private int resolveLevel(String name, Map<String, Integer> level, Set<String> visiting, int depth) {
        Integer cached = level.get(name);
        if (null != cached) {
            return cached;
        }
        if (visiting.contains(name) || depth > 20) {
            // 成环或嵌套过深，就地截断，避免死循环
            return depth;
        }
        visiting.add(name);
        ComposeServiceInfo service = null == model ? null : model.service(name);
        int value = 0;
        if (null != service) {
            for (String dependency : service.getDependsOn()) {
                value = Math.max(value, resolveLevel(dependency, level, visiting, depth + 1) + 1);
            }
        }
        visiting.remove(name);
        level.put(name, value);
        return value;
    }

    private static String shortText(String text, int max) {
        String value = StrUtil.emptyToDefault(text, "");
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /* ================================================================== */
    /* 页签 7：文件与变量                                                  */
    /* ================================================================== */

    private VerticalLayout buildFileTab() {
        VerticalLayout root = fullLayout();

        TabSheet inner = new TabSheet();
        inner.setSizeFull();
        inner.addTab(buildComposeFilePanel(), "compose 文件");
        inner.addTab(buildEnvPanel(), ".env 变量");
        root.addComponent(inner);
        root.setExpandRatio(inner, 1f);
        return root;
    }

    private VerticalLayout buildComposeFilePanel() {
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(false);
        root.setSpacing(true);

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setWidth("100%");
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        fileCombo = new ComboBox<String>();
        fileCombo.setWidth("260px");
        fileCombo.setTextInputAllowed(false);
        fileCombo.setEmptySelectionAllowed(false);

        Button saveBtn = ComponentFactory.getPrimaryButtonWithType("保存", com.so.component.util.ButtonType.SUCCESS);
        saveBtn.setWidth("80px");
        Button checkBtn = ComponentFactory.getStandardButton("校验并预览");
        checkBtn.setWidth("120px");
        Button diffBtn = ComponentFactory.getStandardButton("查看差异");
        diffBtn.setWidth("100px");
        Button templateBtn = ComponentFactory.getStandardButton("套用模板");
        templateBtn.setWidth("100px");
        Button reloadBtn = ComponentFactory.getStandardButton("重新加载");
        reloadBtn.setWidth("100px");
        Button downloadBtn = ComponentFactory.getStandardButton("下载当前文件");
        downloadBtn.setWidth("130px");

        bar.addComponents(ComponentFactory.getStandardLabel("文件："), fileCombo,
                saveBtn, checkBtn, diffBtn, templateBtn, reloadBtn, downloadBtn);
        root.addComponent(bar);

        fileStatus = ComponentFactory.getStandardLabel("");
        fileStatus.addStyleName("docker-hint");
        fileStatus.setWidth("100%");
        root.addComponent(fileStatus);

        editor = new ComposeYamlEditor("", "520px");
        root.addComponent(editor);
        root.setExpandRatio(editor, 1f);

        StreamResource resource = new StreamResource(new CurrentFileSource(), "compose-file.yml");
        resource.setCacheTime(0);
        FileDownloader downloader = new FileDownloader(resource);
        downloader.extend(downloadBtn);

        fileCombo.addValueChangeListener(e -> switchFile(e.getValue()));
        saveBtn.addClickListener(e -> saveCurrentFile());
        checkBtn.addClickListener(e -> validateCurrentDraft());
        diffBtn.addClickListener(e -> showDiff());
        templateBtn.addClickListener(e -> openTemplatePicker());
        reloadBtn.addClickListener(e -> reloadFiles());
        return root;
    }

    private VerticalLayout buildEnvPanel() {
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(false);
        root.setSpacing(true);

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setWidth("100%");
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        Button saveBtn = ComponentFactory.getPrimaryButtonWithType("保存 .env", com.so.component.util.ButtonType.SUCCESS);
        saveBtn.setWidth("110px");
        Button reloadBtn = ComponentFactory.getStandardButton("重新加载");
        reloadBtn.setWidth("100px");
        Button checkBtn = ComponentFactory.getStandardButton("检查变量");
        checkBtn.setWidth("100px");
        bar.addComponents(saveBtn, reloadBtn, checkBtn);
        root.addComponent(bar);

        Label hint = ComponentFactory.getStandardLabel(
                "KEY=VALUE 一行一个。compose 的取值顺序：shell 环境变量 → .env → yml 里的默认值。"
                        + "改完 .env 要「保存」后点「重新部署」才会应用到容器上（.env 变了 compose 认为配置变了，会重建容器）。");
        hint.addStyleName("docker-hint");
        hint.setWidth("100%");
        root.addComponent(hint);

        envArea = ComponentFactory.getTextArea();
        envArea.setSizeFull();
        envArea.addStyleName("docker-inspect-area");
        root.addComponent(envArea);
        root.setExpandRatio(envArea, 1f);

        envVarLabel = ComponentFactory.getStandardLabel("");
        envVarLabel.addStyleName("compose-warn-label");
        envVarLabel.setWidth("100%");
        root.addComponent(envVarLabel);

        saveBtn.addClickListener(e -> saveEnv());
        reloadBtn.addClickListener(e2 -> reloadFiles());
        checkBtn.addClickListener(e2 -> refreshEnvCheck());
        return root;
    }

    private void switchFile(String next) {
        stashCurrent();
        currentFile = next;
        if (null != editor) {
            editor.setValue(StrUtil.emptyToDefault(savedFiles.get(next), ""));
        }
        updateFileStatus();
    }

    private void stashCurrent() {
        if (null != currentFile && null != editor) {
            savedFiles.put(currentFile, editor.getValue());
        }
    }

    private void updateFileStatus() {
        if (null == fileStatus) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("项目目录：").append(project.getDirectory());
        if (StrUtil.isNotBlank(currentFile)) {
            String saved = StrUtil.emptyToDefault(savedFiles.get(currentFile), "");
            int now = StrUtil.emptyToDefault(editor.getValue(), "").split("\n", -1).length;
            sb.append("　当前文件：").append(currentFile)
                    .append("（").append(now).append(" 行")
                    .append(saved.equals(editor.getValue()) ? "，未修改" : "，已修改，记得保存")
                    .append("）");
        }
        sb.append("　叠加顺序：").append(project.getFilesText());
        if (!project.isManaged()) {
            sb.append("　⚠ 这是从 compose ls 发现的外来项目，保存文件会写进它自己的目录，请确认无误再改");
        }
        fileStatus.setValue(sb.toString());
    }

    private void reloadFiles() {
        async("读取项目文件", new DockerUi.Task<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Void run() throws Exception {
                Map<String, String> files = compose.readProjectFiles(project);
                String env = compose.readFile(project.getDirectory(), ComposeService.ENV_FILE);
                applyFiles(files, env);
                return null;
            }
        }, new DockerUi.Done<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(Void value) {
                Notification.show("已重新载入项目文件", Notification.Type.HUMANIZED_MESSAGE);
            }
        });
    }

    private void applyFiles(Map<String, String> files, String env) {
        Set<String> names = new LinkedHashSet<String>(project.getFiles());
        names.addAll(files.keySet());
        savedFiles.clear();
        for (String name : names) {
            savedFiles.put(name, StrUtil.emptyToDefault(files.get(name), ""));
        }
        if (StrUtil.isNotBlank(env)) {
            savedEnv = env;
        }
        List<String> options = new ArrayList<String>(savedFiles.keySet());
        fileCombo.setItems(options);
        String select = (null != currentFile && savedFiles.containsKey(currentFile))
                ? currentFile : (options.isEmpty() ? "" : options.get(0));
        fileCombo.setValue(select);
        currentFile = null;
        switchFile(select);
        if (null != envArea) {
            envArea.setValue(savedEnv);
        }
        refreshEnvCheck();
    }

    private void saveCurrentFile() {
        if (StrUtil.isBlank(currentFile)) {
            Notification.show("没有选中的文件", Notification.Type.WARNING_MESSAGE);
            return;
        }
        stashCurrent();
        final String name = currentFile;
        final String content = editor.getValue();
        async("保存 " + name, new DockerUi.Task<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Void run() throws Exception {
                compose.writeFile(project.getDirectory(), name, content);
                return null;
            }
        }, new DockerUi.Done<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(Void value) {
                savedFiles.put(name, content);
                updateFileStatus();
                Notification.show(name + " 已保存",
                        "改完配置要让它生效，请回到「概览」点一次「重新部署」（restart 不会应用文件改动）。",
                        Notification.Type.HUMANIZED_MESSAGE);
                log.info("用户 {} 保存了 compose 项目 {} 的 {}",
                        com.so.component.ComponentUtil.getCurrentUserName(), project.getName(), name);
            }
        });
    }

    private void saveEnv() {
        final String content = envArea.getValue();
        async("保存 .env", new DockerUi.Task<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Void run() throws Exception {
                compose.writeFile(project.getDirectory(), ComposeService.ENV_FILE, content);
                return null;
            }
        }, new DockerUi.Done<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(Void value) {
                savedEnv = content;
                refreshEnvCheck();
                Notification.show(".env 已保存", "需要「重新部署」才会应用到容器。",
                        Notification.Type.HUMANIZED_MESSAGE);
            }
        });
    }

    /** 校验「当前编辑框里的内容」而不是磁盘上的旧版本 —— 用户想知道的是「我这么改对不对」 */
    private void validateCurrentDraft() {
        final Map<String, String> files = currentDrafts();
        if (files.isEmpty()) {
            Notification.show("没有可校验的内容", Notification.Type.WARNING_MESSAGE);
            return;
        }
        final String env = null == envArea ? savedEnv : envArea.getValue();
        async("校验 compose 配置", new DockerUi.Task<ComposeService.ExecOutcome>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ComposeService.ExecOutcome run() throws Exception {
                return compose.validatePreview(files, env);
            }
        }, new DockerUi.Done<ComposeService.ExecOutcome>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(ComposeService.ExecOutcome outcome) {
                showTextWindow("compose 配置校验结果", outcome.isOk()
                        ? "✓ 校验通过（下面是变量替换后的最终配置）\n\n" + outcome.getStdout()
                                + (StrUtil.isBlank(outcome.getStderr()) ? ""
                                : "\n\n--- compose 警告 ---\n" + outcome.getStderr())
                        : "✗ 校验失败（退出码 " + outcome.getExitCode() + "）\n\n" + outcome.getErrorText(),
                        outcome.isOk());
            }
        });
    }

    private void refreshEnvCheck() {
        if (null == envVarLabel) {
            return;
        }
        Map<String, String> env = ComposeService.parseEnv(null == envArea ? savedEnv : envArea.getValue());
        List<String> used = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();
        for (Map.Entry<String, String> entry : savedFiles.entrySet()) {
            if (ComposeService.ENV_FILE.equals(entry.getKey())) {
                continue;
            }
            for (String name : ComposeService.extractVariables(entry.getValue())) {
                if (!used.contains(name)) {
                    used.add(name);
                }
                if (!env.containsKey(name) && !entry.getValue().contains("${" + name + ":-")
                        && !missing.contains(name)) {
                    missing.add(name);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("compose 文件里引用变量 ").append(used.size()).append(" 个");
        if (!used.isEmpty()) {
            sb.append("：").append(StrUtil.join(", ", used));
        }
        if (missing.isEmpty()) {
            sb.append("　全部有值（.env 或 ${VAR:-默认值}）");
        } else {
            sb.append("　⚠ 缺值：").append(StrUtil.join(", ", missing))
                    .append("（既没默认值也没在 .env 里给，compose 会告警并按空字符串处理）");
        }
        if (model != null && !model.getWarnings().isEmpty()) {
            sb.append("　compose 警告 ").append(model.getWarnings().size()).append(" 条");
        }
        envVarLabel.setValue(sb.toString());
    }

    private Map<String, String> currentDrafts() {
        stashCurrent();
        Map<String, String> files = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : savedFiles.entrySet()) {
            if (ComposeService.ENV_FILE.equals(entry.getKey())) {
                continue;
            }
            if (StrUtil.isNotBlank(entry.getValue())) {
                files.put(entry.getKey(), entry.getValue());
            }
        }
        return files;
    }

    private void showDiff() {
        if (StrUtil.isBlank(currentFile)) {
            return;
        }
        stashCurrent();
        final String name = currentFile;
        String original = null;
        async("读取原始文件", new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                String remote = compose.readFile(project.getDirectory(), name);
                return null == remote ? "" : remote;
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String remote) {
                String current = StrUtil.emptyToDefault(savedFiles.get(name), "");
                List<ComposeDiff.Line> lines = ComposeDiff.diff(remote, current);
                String title = "版本对比 - " + name;
                if (!ComposeDiff.hasChange(lines)) {
                    showTextWindow(title, "当前编辑内容与服务器上的文件完全一致（没有改动）。", true);
                    return;
                }
                String summary = "新增 " + ComposeDiff.count(lines, ComposeDiff.Type.ADD)
                        + " 行，删除 " + ComposeDiff.count(lines, ComposeDiff.Type.DEL)
                        + " 行（只显示改动处前后各 2 行上下文）";
                showHtmlWindow(title, summary, ComposeDiff.toHtml(lines));
            }
        });
    }

    private void openTemplatePicker() {
        if (StrUtil.isBlank(currentFile)) {
            Notification.show("请先选择一个文件", Notification.Type.WARNING_MESSAGE);
            return;
        }
        final String name = currentFile;
        String html = "<h3>套用模板会覆盖 " + ComposeDiff.escape(name) + " 的当前内容</h3>"
                + "选一个模板，内容会直接替换到编辑器里（不会自动保存，确认后再点「保存」）。"
                + "模板里的端口、密码、版本都是 ${VAR:-默认值} 形式，改 .env 即可。";
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("套用模板", html, "套用", "取消", true);
        final ComboBox<String> templates = new ComboBox<String>("模板：");
        templates.setWidth("260px");
        templates.setTextInputAllowed(false);
        templates.setEmptySelectionAllowed(false);
        List<String> names = new ArrayList<String>();
        for (ComposeTemplate template : ComposeTemplates.all()) {
            names.add(template.getName());
        }
        templates.setItems(names);
        templates.setValue(names.get(0));
        win.getLayout().addComponent(templates, 1);
        final Label desc = ComponentFactory.getStandardLabel("");
        desc.addStyleName("docker-hint");
        desc.setWidth("100%");
        win.getLayout().addComponent(desc, 2);
        templates.addValueChangeListener(e -> {
            ComposeTemplate template = ComposeTemplates.byName(e.getValue());
            desc.setValue(null == template ? "" : template.getDescription());
        });
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                ComposeTemplate template = ComposeTemplates.byName(templates.getValue());
                win.close();
                if (null == template) {
                    return;
                }
                editor.setValue(template.getYaml());
                if (StrUtil.isNotBlank(template.getEnv()) && null != envArea
                        && StrUtil.isBlank(envArea.getValue())) {
                    envArea.setValue(template.getEnv());
                }
                updateFileStatus();
                refreshEnvCheck();
                Notification.show("已套用模板 " + template.getName() + "，确认内容后点「保存」",
                        Notification.Type.HUMANIZED_MESSAGE);
            }
        });
        win.showConfirmation();
    }

    private void showTextWindow(String title, String text, boolean ok) {
        Window win = new Window(title);
        win.setWidth("900px");
        win.setHeight("620px");
        win.setModal(true);
        win.setResizable(true);
        win.center();
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);
        if (!ok) {
            Label warn = ComponentFactory.getStandardLabel("校验没通过：下面第一段就是 compose 报的原因。");
            warn.addStyleName("compose-warn-label");
            warn.setWidth("100%");
            root.addComponent(warn);
        }
        TextArea area = ComponentFactory.getTextArea();
        area.setSizeFull();
        area.setReadOnly(true);
        area.addStyleName("docker-inspect-area");
        area.setValue(text);
        root.addComponent(area);
        root.setExpandRatio(area, 1f);
        win.setContent(root);
        if (null != ui) {
            ui.addWindow(win);
        }
    }

    private void showHtmlWindow(String title, String summary, String html) {
        Window win = new Window(title);
        win.setWidth("980px");
        win.setHeight("640px");
        win.setModal(true);
        win.setResizable(true);
        win.center();
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);
        Label summaryLabel = ComponentFactory.getStandardLabel(summary);
        summaryLabel.addStyleName("docker-hint");
        summaryLabel.setWidth("100%");
        root.addComponent(summaryLabel);
        Label pre = new Label("<pre class=\"compose-diff-pre\">" + html + "</pre>");
        pre.setContentMode(com.vaadin.shared.ui.ContentMode.HTML);
        pre.setWidth("100%");
        Panel panel = new Panel();
        panel.setSizeFull();
        panel.setContent(pre);
        root.addComponent(panel);
        root.setExpandRatio(panel, 1f);
        win.setContent(root);
        if (null != ui) {
            ui.addWindow(win);
        }
    }

    /** 下载当前编辑框里的内容（不是服务器上的旧版本），方便用户在外面 diff / 备份 */
    private class CurrentFileSource implements StreamResource.StreamSource {

        private static final long serialVersionUID = 1L;

        @Override
        public InputStream getStream() {
            try {
                stashCurrent();
                String text = StrUtil.emptyToDefault(savedFiles.get(currentFile), "");
                return new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("导出 compose 文件失败：{}", e.getMessage());
                return null;
            }
        }
    }

    /* ================================================================== */
    /* 数据加载                                                            */
    /* ================================================================== */

    /** 重新拉取所有页签的数据 */
    public void reloadAll() {
        if (null == compose || executor.isClosed()) {
            return;
        }
        loading = true;
        async("读取项目 " + project.getName(), new DockerUi.Task<ComposeModel>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ComposeModel run() throws Exception {
                List<ComposeContainer> list = compose.listProjectContainers(project.getName());
                containers.clear();
                containers.addAll(list);
                ComposeModel loaded = null;
                String error = "";
                try {
                    loaded = compose.loadModel(project);
                } catch (Exception e) {
                    // 配置坏了也要能打开窗口：容器、日志、文件页签都还能用
                    error = DockerUi.reason(e);
                }
                final ComposeModel result = loaded;
                final String failure = error;
                ui.access(new Runnable() {
                    @Override
                    public void run() {
                        modelError = failure;
                        model = result;
                    }
                });
                return loaded;
            }
        }, new DockerUi.Done<ComposeModel>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(ComposeModel loaded) {
                loading = false;
                model = loaded;
                buildServices();
                refreshOverview();
                refreshServiceGrid();
                refreshContainerGrid();
                refreshResources();
                refreshGraph();
                reloadFilesAsync();
                reloadLogFrame();
            }
        });
    }

    private void reloadFilesAsync() {
        async("读取项目文件", new DockerUi.Task<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Void run() throws Exception {
                Map<String, String> files = compose.readProjectFiles(project);
                String env = compose.readFile(project.getDirectory(), ComposeService.ENV_FILE);
                applyFiles(files, env);
                return null;
            }
        }, null);
    }

    /** 服务列表 = 配置里声明的服务 + 只存在于容器上的服务（配置改过还没 redeploy 的那种） */
    private void buildServices() {
        services.clear();
        if (null != model) {
            services.addAll(model.getServices());
        }
        for (ComposeContainer container : containers) {
            String name = container.getService();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            boolean exists = false;
            for (ComposeServiceInfo service : services) {
                if (service.getName().equals(name)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                ComposeServiceInfo ghost = new ComposeServiceInfo();
                ghost.setName(name);
                ghost.setImage(container.getImage());
                services.add(ghost);
            }
        }
        for (ComposeServiceInfo service : services) {
            List<ComposeContainer> list = containersOf(service.getName());
            int running = 0;
            int unhealthy = 0;
            for (ComposeContainer container : list) {
                if (container.isUp()) {
                    running++;
                }
                if (ComposeContainer.UNHEALTHY.equals(container.getHealth())) {
                    unhealthy++;
                }
            }
            service.setTotalContainers(list.size());
            service.setRunningContainers(running);
            service.setUnhealthyContainers(unhealthy);
            service.resolveStatus();
        }
        // 日志页签的服务下拉也要跟着更新
        if (null != logServiceCombo) {
            List<String> options = new ArrayList<String>();
            options.add(LOG_ALL_SERVICES);
            for (ComposeServiceInfo service : services) {
                options.add(service.getName());
            }
            String previous = logServiceCombo.getValue();
            logServiceCombo.setItems(options);
            logServiceCombo.setValue(previous == null || !options.contains(previous) ? LOG_ALL_SERVICES : previous);
        }
    }

    private List<ComposeContainer> containersOf(String service) {
        List<ComposeContainer> list = new ArrayList<ComposeContainer>();
        for (ComposeContainer container : containers) {
            if (service.equals(container.getService())) {
                list.add(container);
            }
        }
        return list;
    }

    private void refreshOverview() {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>项目名</b>：").append(ComposeDiff.escape(project.getName()))
                .append(project.isManaged() ? "（本系统创建）" : "（从 compose ls 发现的外来项目）").append("<br/>");
        sb.append("<b>项目目录</b>：").append(ComposeDiff.escape(project.getDirectory())).append("<br/>");
        sb.append("<b>Compose 文件</b>：").append(ComposeDiff.escape(project.getFilesText())).append("<br/>");
        sb.append("<b>状态</b>：").append(project.getStatusLabel());
        if (StrUtil.isNotBlank(project.getStatusText())) {
            sb.append("（compose ls 报告：").append(ComposeDiff.escape(project.getStatusText())).append("）");
        }
        sb.append("<br/>");
        sb.append("<b>服务</b>：").append(project.getServiceCountText())
                .append("　<b>容器</b>：").append(project.getContainerCountText());
        if (project.getUnhealthyContainers() > 0) {
            sb.append("　<b>不健康容器</b>：").append(project.getUnhealthyContainers());
        }
        sb.append("<br/>");
        sb.append("<b>创建时间</b>：").append(StrUtil.emptyToDefault(project.getCreatedAt(), "—"))
                .append("　<b>.env</b>：").append(project.isEnvFilePresent() ? "已配置" : "没有");
        if (null != model && !model.getResolvedYaml().isEmpty()) {
            sb.append("<br/><b>最终生效的配置</b>：").append(model.getServices().size()).append(" 个服务，")
                    .append(model.getDeclaredNetworks().size()).append(" 个网络，")
                    .append(model.getDeclaredVolumes().size()).append(" 个卷");
        }
        overviewLabel.setValue(sb.toString());

        StringBuilder warn = new StringBuilder();
        if (StrUtil.isNotBlank(modelError)) {
            warn.append("⚠ compose 配置校验失败，服务 / 依赖图 / 端口页签的数据不可用：<br/>")
                    .append(ComposeDiff.escape(modelError).replace("\n", "<br/>")).append("<br/>");
        }
        if (null != model) {
            for (String line : model.getWarnings()) {
                warn.append("⚠ ").append(ComposeDiff.escape(line)).append("<br/>");
            }
            List<String> cycles = model.detectDependencyCycle();
            if (!cycles.isEmpty()) {
                warn.append("⚠ 依赖成环（compose 会拒绝启动）：")
                        .append(ComposeDiff.escape(StrUtil.join("；", cycles))).append("<br/>");
            }
        }
        if (null != resources && resources.getConflictCount() > 0) {
            warn.append("⚠ ").append(ComposeDiff.escape(resources.getConflictSummary())).append("<br/>");
        }
        overviewWarnLabel.setValue(warn.toString());
        overviewWarnLabel.setVisible(warn.length() > 0);
    }

    private void refreshServiceGrid() {
        if (null == serviceGrid) {
            return;
        }
        serviceGrid.setItems(new ArrayList<ComposeServiceInfo>(services));
        int running = 0;
        for (ComposeServiceInfo service : services) {
            if (ComposeServiceInfo.STATUS_RUNNING.equals(service.getStatus())) {
                running++;
            }
        }
        serviceStatusLabel.setValue("共 " + services.size() + " 个服务，运行中 " + running
                + "　（副本数「运行 / 期望」；写了 container_name 或 deploy.replicas 的服务不能扩缩容）");
    }

    private void refreshContainerGrid() {
        if (null == containerGrid) {
            return;
        }
        List<ComposeContainer> sorted = new ArrayList<ComposeContainer>(containers);
        Collections.sort(sorted, new Comparator<ComposeContainer>() {
            @Override
            public int compare(ComposeContainer left, ComposeContainer right) {
                int byService = StrUtil.emptyToDefault(left.getService(), "")
                        .compareTo(StrUtil.emptyToDefault(right.getService(), ""));
                if (byService != 0) {
                    return byService;
                }
                return left.getNumber() - right.getNumber();
            }
        });
        containerGrid.setItems(sorted);
        int running = 0;
        int unhealthy = 0;
        int restarts = 0;
        for (ComposeContainer container : containers) {
            if (container.isUp()) {
                running++;
            }
            if (ComposeContainer.UNHEALTHY.equals(container.getHealth())) {
                unhealthy++;
            }
            restarts += container.getRestartCount();
        }
        containerStatusLabel.setValue("共 " + containers.size() + " 个容器，运行中 " + running
                + "，不健康 " + unhealthy + "，累计重启 " + restarts + " 次"
                + "　（重启次数偏高通常意味着容器在反复崩溃，去看日志页签）");
    }

    private void refreshResources() {
        if (null == model) {
            resourceSummary.setValue("配置校验没通过，端口 / 网络 / 卷的盘点暂不可用。");
            resourceWarn.setVisible(false);
            return;
        }
        if (null == resources) {
            resources = compose.collectResources(project, model, containers);
        }
        resourceSummary.setValue(resources.getDiskSummary());
        StringBuilder warn = new StringBuilder();
        if (resources.getConflictCount() > 0) {
            warn.append("⚠ ").append(resources.getConflictSummary());
        }
        for (String note : resources.getNotes()) {
            if (warn.length() > 0) {
                warn.append("　");
            }
            warn.append("· ").append(note);
        }
        resourceWarn.setValue(warn.toString());
        resourceWarn.setVisible(warn.length() > 0);
        portGrid.setItems(resources.getPorts());
        networkGrid.setItems(resources.getNetworks());
        volumeGrid.setItems(resources.getVolumes());
    }

    private void refreshGraph() {
        if (null == graphLabel) {
            return;
        }
        graphLabel.setValue(buildGraphHtml());
    }

    /* ================================================================== */
    /* 动作 + 后台执行                                                     */
    /* ================================================================== */

    private interface Action {
        String run() throws Exception;
    }

    private void doProjectAction(String action, final Action task) {
        async(action, new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                return task.run();
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String output) {
                notifyResult(action, output);
                reloadAll();
            }
        });
    }

    private void doContainerAction(String action, final Action task) {
        async(action, new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                return task.run();
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String output) {
                notifyResult(action, output);
                reloadAll();
            }
        });
    }

    private void notifyResult(String action, String output) {
        String tail = StrUtil.trimToEmpty(output);
        if (tail.length() > 500) {
            tail = "…" + tail.substring(tail.length() - 500);
        }
        Notification.show(action + " 完成" + (StrUtil.isBlank(tail) ? "" : "：" + tail),
                Notification.Type.HUMANIZED_MESSAGE);
    }

    private void confirmRedeploy(final boolean build) {
        String html = "<h3>重新部署 " + ComposeDiff.escape(project.getName()) + "</h3>"
                + "执行 <b>docker compose up -d --force-recreate --remove-orphans</b>："
                + "按当前 compose 文件重建容器，配置有改动才会真正重建，"
                + "文件里已删除的服务对应的容器会被清掉（--remove-orphans）。<br/>"
                + "正在运行的容器会被重启，服务有短暂中断。";
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("重新部署", html, "执行", "取消", true);
        final CheckBox buildBox = new CheckBox("同时重新构建镜像（--build，仅对写了 build 的服务有效）");
        buildBox.setValue(build);
        buildBox.addStyleName("docker-dialog-checkbox");
        win.getLayout().addComponent(buildBox, 1);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                final boolean withBuild = buildBox.getValue();
                win.close();
                doProjectAction("重新部署", () -> compose.redeploy(project, withBuild));
            }
        });
        win.showConfirmation();
    }

    private Button link(String caption, Runnable action) {
        Button button = ComponentFactory.getLinkButton(caption);
        button.addClickListener(e -> action.run());
        return button;
    }

    private <T> void async(String action, DockerUi.Task<T> task, DockerUi.Done<T> done) {
        async(action, task, done, null);
    }

    private <T> void async(String action, DockerUi.Task<T> task, DockerUi.Done<T> done,
                           DockerUi.Failure extraFailure) {
        if (null == compose || executor.isClosed()) {
            Notification.show("连接已断开，请重新打开这个项目窗口。", Notification.Type.WARNING_MESSAGE);
            return;
        }
        final DockerUi.Failure userFailure = extraFailure;
        DockerUi.async(action, task, done, new DockerUi.Busy() {
            private static final long serialVersionUID = 1L;

            @Override
            public void setBusy(boolean busy, String text) {
                owner.setBusy(busy, text);
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                loading = false;
                if (DockerUi.isDaemonDown(e)) {
                    Notification.show(DockerUi.daemonDownHint, Notification.Type.WARNING_MESSAGE);
                }
                if (null != userFailure) {
                    userFailure.onFailure(e);
                }
            }
        });
    }

    private VerticalLayout fullLayout() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setMargin(true);
        layout.setSpacing(true);
        return layout;
    }

    /** 供外部工具（脚本/调试）读取当前加载到的容器列表 */
    public List<ComposeContainer> getContainers() {
        return containers;
    }

    /** SFTP 通道，供扩展使用 */
    public ComposeService getComposeService() {
        return compose;
    }

    /** 保留入口：把本地文件内容读成字符串（模板/上传场景之外的地方可能要用） */
    public static String readLocalText(File file) throws IOException {
        if (null == file || !file.isFile()) {
            throw new IOException("文件不存在：" + file);
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 保留入口：把远端文件下到本地（下载按钮走浏览器流，这里是给脚本用的） */
    public File downloadProjectFile(String fileName) throws IOException {
        return compose.downloadFile(project.getDirectory(), fileName);
    }

    /** 打开一个文件流（供上层做流式处理） */
    public InputStream openLocalStream(File file) throws IOException {
        return new FilterInputStream(new FileInputStream(file)) {
            private static final long serialVersionUID = 1L;

            @Override
            public void close() throws IOException {
                super.close();
            }
        };
    }
}
