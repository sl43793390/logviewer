package com.so.component.docker;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.so.component.util.ColorEnum;
import com.so.docker.DockerExecutor;
import com.so.docker.model.DockerDaemonStatus;
import com.so.docker.model.DockerSystemInfo;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.CheckBox;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 基础系统信息：版本、引擎信息、磁盘占用、一键清理。
 * <p>
 * 这个页面同时还承担「环境自检」的职责：目标机器是 Rocky 8 还是 Ubuntu 22、
 * 当前登录用户要不要 sudo、SELinux 是不是 enforcing、firewalld 有没有挡端口，
 * 都会在这里一次性摊开——后续创建容器/卷时遇到的那些"看起来莫名"的报错，
 * 根因基本都落在这一屏里。
 */
public class DockerSystemPage extends AbstractDockerPage {

    private static final long serialVersionUID = 1L;

    private Label versionLabel;
    private Label containerLabel;
    private TextArea engineArea;
    private TextArea envArea;
    private Grid<DockerSystemInfo.DiskUsage> diskGrid;

    private DockerSystemInfo lastInfo;

    public DockerSystemPage(DockerMgmtComponent owner, DockerExecutor executor) {
        super(owner, executor);
    }

    @Override
    protected void buildToolbar(HorizontalLayout toolbar) {
        Button refresh = ComponentFactory.getStandardButton("刷新");
        refresh.setWidth("80px");
        Button prune = ComponentFactory.getButtonWithColor("一键清理", ColorEnum.YELLOW);
        prune.setWidth("100px");
        Button daemonBtn = ComponentFactory.getStandardButton("检测/启动 Docker 服务");
        daemonBtn.setWidth("180px");
        for (Button button : new Button[]{refresh, prune, daemonBtn}) {
            button.setHeight("30px");
            toolbar.addComponent(button);
        }
        Label spacer = new Label("");
        toolbar.addComponent(spacer);
        toolbar.setExpandRatio(spacer, 1f);

        buildBody();

        refresh.addClickListener(e -> userReload());
        prune.addClickListener(e -> showWindow(new PruneWindow()));
        daemonBtn.addClickListener(e -> checkDaemon());
    }

    /**
     * 手动检测 docker 服务状态。
     * <p>
     * 页面上留这个入口，是因为用户很可能自己去服务器上把 docker 起起来、或者刚装完 ——
     * 那之后需要一次"强制探测"把状态刷过来，而不是等缓存过期。
     * 仍然不可用时，走统一弹窗（含"未安装 docker"的提示与安装命令）。
     */
    private void checkDaemon() {
        runAsync("检测 Docker 服务状态", new DockerTask<DockerDaemonStatus>() {
            private static final long serialVersionUID = 1L;

            @Override
            public DockerDaemonStatus run() throws Exception {
                return executor.probeDaemon(true);
            }
        }, new DockerTaskDone<DockerDaemonStatus>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(DockerDaemonStatus status) {
                if (status.isDaemonRunning()) {
                    Notification.show("Docker 服务正常",
                            "服务端版本 " + StrUtil.emptyToDefault(status.getServerVersion(), "?"),
                            Notification.Type.HUMANIZED_MESSAGE);
                    reload();
                } else {
                    owner.promptDockerUnavailable(status);
                }
            }
        });
    }

    private void buildBody() {
        HorizontalLayout summaryRow = new HorizontalLayout();
        summaryRow.setWidth("100%");
        summaryRow.setSpacing(true);
        versionLabel = new Label("正在读取 Docker 版本…");
        versionLabel.addStyleName("docker-summary");
        containerLabel = new Label("");
        containerLabel.addStyleName("docker-summary");
        summaryRow.addComponents(versionLabel, containerLabel);
        summaryRow.setExpandRatio(containerLabel, 1f);
        contentLayout.addComponent(summaryRow);

        HorizontalLayout infoRow = new HorizontalLayout();
        infoRow.setWidth("100%");
        infoRow.setSpacing(true);
        infoRow.setHeight("190px");

        engineArea = ComponentFactory.getStandardTtextArea();
        engineArea.setReadOnly(true);
        engineArea.setWidth("50%");
        engineArea.setHeight("180px");
        envArea = ComponentFactory.getStandardTtextArea();
        envArea.setReadOnly(true);
        envArea.setWidth("50%");
        envArea.setHeight("180px");
        infoRow.addComponents(engineArea, envArea);
        infoRow.setExpandRatio(engineArea, 1f);
        infoRow.setExpandRatio(envArea, 1f);
        contentLayout.addComponent(infoRow);

        Label diskTitle = ComponentFactory.getStandardLabel("磁盘占用（docker system df）");
        diskTitle.addStyleName("docker-section-title");
        contentLayout.addComponent(diskTitle);

        diskGrid = new Grid<DockerSystemInfo.DiskUsage>();
        diskGrid.setWidth("100%");
        diskGrid.setHeight("190px");
        diskGrid.addStyleName("grid_standard");
        diskGrid.addColumn(DockerSystemInfo.DiskUsage::getType).setCaption("类型");
        diskGrid.addColumn(DockerSystemInfo.DiskUsage::getTotal).setCaption("总数");
        diskGrid.addColumn(DockerSystemInfo.DiskUsage::getActive).setCaption("活跃");
        diskGrid.addColumn(DockerSystemInfo.DiskUsage::getSize).setCaption("占用空间");
        diskGrid.addColumn(DockerSystemInfo.DiskUsage::getReclaimable).setCaption("可回收");
        contentLayout.addComponent(diskGrid);
        contentLayout.setExpandRatio(diskGrid, 1f);
        diskGrid.setItems(new ArrayList<DockerSystemInfo.DiskUsage>());
    }

    @Override
    protected void reload() {
        runAsync("读取 Docker 系统信息", new DockerTask<DockerSystemInfo>() {
            private static final long serialVersionUID = 1L;

            @Override
            public DockerSystemInfo run() throws Exception {
                return service.systemInfo();
            }
        }, new DockerTaskDone<DockerSystemInfo>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(DockerSystemInfo info) {
                lastInfo = info;
                render(info);
            }
        });
    }

    private void render(DockerSystemInfo info) {
        if (StrUtil.isNotBlank(info.getServerError())) {
            versionLabel.setValue("Docker 服务端信息不可用：" + info.getServerError());
        } else {
            versionLabel.setValue("Docker 服务端 " + StrUtil.emptyToDefault(info.getServerVersion(), "?")
                    + "　API " + StrUtil.emptyToDefault(info.getApiVersion(), "?")
                    + "　客户端 " + StrUtil.emptyToDefault(info.getClientVersion(), "?"));
        }

        if (StrUtil.isNotBlank(info.getInfoError())) {
            containerLabel.setValue("（引擎信息不可用：" + info.getInfoError() + "）");
        } else {
            containerLabel.setValue(info.getContainerSummary() + "　镜像 " + info.getImages() + " 个");
        }

        StringBuilder engine = new StringBuilder();
        engine.append("操作系统   : ").append(StrUtil.emptyToDefault(info.getOsType(), "-"))
                .append(" / ").append(StrUtil.emptyToDefault(info.getArch(), "-")).append('\n');
        engine.append("内核版本   : ").append(StrUtil.emptyToDefault(info.getKernelVersion(), "-")).append('\n');
        engine.append("存储驱动   : ").append(StrUtil.emptyToDefault(info.getStorageDriver(), "-")).append('\n');
        engine.append("数据根目录 : ").append(StrUtil.emptyToDefault(info.getRootDir(), "-")).append('\n');
        engine.append("容器       : ").append(info.getContainerSummary()).append('\n');
        engine.append("镜像数量   : ").append(info.getImages()).append('\n');
        engine.append('\n');
        engine.append("容器 / 镜像的实际磁盘占用见下方表格，");
        engine.append("\n「可回收」表示这部分空间在清理后能被释放。");
        engineArea.setValue(engine.toString());

        StringBuilder env = new StringBuilder();
        env.append("目标主机   : ").append(StrUtil.emptyToDefault(info.getOsRelease(), "-")).append('\n');
        env.append("登录用户   : ").append(StrUtil.emptyToDefault(info.getLoginUser(), "-")).append('\n');
        env.append("执行命令   : ").append(StrUtil.emptyToDefault(info.getDockerCommand(), "-")).append('\n');
        env.append("SELinux    : ").append(StrUtil.emptyToDefault(info.getSelinuxMode(), "-")).append('\n');
        env.append("防火墙     : ").append(StrUtil.emptyToDefault(info.getFirewallState(), "-")).append('\n');
        env.append("Compose    : ").append(StrUtil.emptyToDefault(info.getComposeVersion(), "未安装"))
                .append('\n');
        env.append('\n');
        if (CollectionUtil.isEmpty(info.getEnvironmentHints())) {
            env.append("环境自检未发现需要注意的地方。");
        } else {
            env.append("环境提示：\n");
            for (String hint : info.getEnvironmentHints()) {
                env.append("  · ").append(hint).append('\n');
            }
        }
        envArea.setValue(env.toString());

        List<DockerSystemInfo.DiskUsage> usages = info.getDiskUsages();
        diskGrid.setItems(usages == null ? new ArrayList<DockerSystemInfo.DiskUsage>() : usages);

        /*
         * 有些精简版镜像/老版本 docker 的 system df 输出解析不出版式，
         * 这时候表格是空的，要明确告诉用户是「没数据」而不是「没占用」。
         */
        if (CollectionUtil.isEmpty(usages)) {
            showStatus("系统信息读取完成。磁盘占用为空，可能是该 docker 版本的 df 输出格式不被识别"
                    + "（可用「一键清理」按钮手动执行清理）。");
        } else {
            showStatus("系统信息读取完成。");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 一键清理                                                            */
    /* ------------------------------------------------------------------ */

    private class PruneWindow extends Window {

        private static final long serialVersionUID = 1L;

        private final CheckBox containerBox = new CheckBox("已停止的容器（docker container prune）");
        private final CheckBox imageBox = new CheckBox("悬空镜像（docker image prune）");
        private final CheckBox volumeBox = new CheckBox("未使用的数据卷（docker volume prune）");
        private final CheckBox networkBox = new CheckBox("未使用的网络（docker network prune）");
        private Button execBtn;

        PruneWindow() {
            super("一键清理");
            setWidth("660px");
            setHeight("420px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            root.addComponent(ComponentFactory.getStandardLabel(
                    "勾选要清理的对象，执行后不可恢复。默认不会碰正在运行的容器和还有用的镜像。"));

            FormLayout form = new FormLayout();
            form.setWidth("100%");
            form.addComponent(containerBox);
            form.addComponent(imageBox);
            form.addComponent(volumeBox);
            form.addComponent(networkBox);
            root.addComponent(form);

            Label warn = new Label("注意：清理未使用数据卷会连同卷内数据一起删除，"
                    + "如果卷里是数据库/日志这类需要保留的内容，请先确认没有容器（含已停止的容器）在引用它。");
            warn.addStyleName("docker-hint");
            warn.setWidth("580px");
            root.addComponent(warn);
            root.setExpandRatio(warn, 1f);

            HorizontalLayout buttons = new HorizontalLayout();
            buttons.setWidth("100%");
            buttons.setSpacing(true);
            execBtn = ComponentFactory.getPrimaryButtonWithType("开始清理",
                    com.so.component.util.ButtonType.PRIMARY);
            execBtn.setWidth("110px");
            Button cancel = ComponentFactory.getStandardButton("取消");
            cancel.setWidth("80px");
            buttons.addComponents(execBtn, cancel);
            buttons.setExpandRatio(cancel, 1f);
            buttons.setComponentAlignment(cancel, Alignment.MIDDLE_RIGHT);
            root.addComponent(buttons);

            cancel.addClickListener(e -> close());
            execBtn.addClickListener(e -> doPrune());
        }

        private void doPrune() {
            if (!LoginView.checkPermission(Constants.DELETE)) {
                Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
                return;
            }
            final List<String> targets = new ArrayList<String>();
            if (Boolean.TRUE.equals(containerBox.getValue())) {
                targets.add("containers");
            }
            if (Boolean.TRUE.equals(imageBox.getValue())) {
                targets.add("images");
            }
            if (Boolean.TRUE.equals(volumeBox.getValue())) {
                targets.add("volumes");
            }
            if (Boolean.TRUE.equals(networkBox.getValue())) {
                targets.add("networks");
            }
            if (targets.isEmpty()) {
                Notification.show("请至少勾选一项要清理的对象", Notification.Type.WARNING_MESSAGE);
                return;
            }

            execBtn.setEnabled(false);
            execBtn.setCaption("清理中…");
            DockerUi.async("一键清理", new DockerUi.Task<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public String run() {
                    StringBuilder report = new StringBuilder();
                    for (String target : targets) {
                        try {
                            report.append(service.prune(target));
                        } catch (Exception e) {
                            report.append("【").append(target).append("】清理失败：")
                                    .append(reason(e)).append('\n');
                        }
                    }
                    return report.toString();
                }
            }, new DockerUi.Done<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(String report) {
                    close();
                    // 清理输出可能有好几行，用面板展示比 Notification 清楚
                    showWindow(new ResultWindow("清理结果", report));
                    reload();
                }
            }, new DockerUi.Failure() {
                private static final long serialVersionUID = 1L;

                @Override
                public void onFailure(Exception e) {
                    execBtn.setEnabled(true);
                    execBtn.setCaption("开始清理");
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* 结果展示                                                            */
    /* ------------------------------------------------------------------ */

    private class ResultWindow extends Window {

        private static final long serialVersionUID = 1L;

        ResultWindow(String title, String content) {
            super(title);
            setWidth("720px");
            setHeight("480px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            Panel panel = new Panel();
            TextArea area = ComponentFactory.getStandardTtextArea();
            area.setSizeFull();
            area.setReadOnly(true);
            area.setValue(StrUtil.emptyToDefault(content, "（无输出）"));
            panel.setContent(area);
            panel.setSizeFull();
            root.addComponent(panel);
            root.setExpandRatio(panel, 1f);

            HorizontalLayout buttons = new HorizontalLayout();
            buttons.setWidth("100%");
            Button close = ComponentFactory.getStandardButton("关闭");
            close.setWidth("80px");
            buttons.addComponent(close);
            buttons.setComponentAlignment(close, Alignment.MIDDLE_RIGHT);
            root.addComponent(buttons);

            close.addClickListener(e -> close());
        }
    }
}
