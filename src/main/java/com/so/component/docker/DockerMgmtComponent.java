package com.so.component.docker;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.component.CommonComponent;
import com.so.docker.DockerExecutor;
import com.so.docker.DockerService;
import com.so.docker.model.DockerDaemonStatus;
import com.so.entity.ConnectionInfo;
import com.so.mapper.ConnectionInfoMapper;
import com.so.ui.ComponentFactory;
import com.so.util.Util;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Docker 管理主页面。
 * <p>
 * 页面结构：顶部选目标服务器 → 建立一条 SSH 通道（所有 docker 命令都走它）→
 * <b>先探测 docker 服务在不在</b> → 在才建下面那排子 TabSheet
 * （容器 / 镜像 / 数据卷 / 网络 / 系统信息）。
 * <p>
 * <b>为什么要先探测。</b>以前连上就直接建五个页签，每个页签各自去拉数据；
 * 目标机 docker 没启动时，五个页签全部失败、失败回调又触发重新加载，
 * 结果是一条自我循环的命令风暴（日志里 60ms 一条
 * {@code Cannot connect to the Docker daemon}）。现在的规则是：
 * <ul>
 *   <li>daemon 在跑 → 照常建页签；</li>
 *   <li>装了但没启动 → 不建页签，改显示一块占位提示 + 弹窗问「要不要现在启动」；</li>
 *   <li>压根没装 docker → 弹窗明确告知「当前系统没有 docker，请先安装后再进行管理」，
 *       并按发行版给出安装命令。</li>
 * </ul>
 * 目标服务器直接复用「免登录服务器列表」里那套来源：数据库里的 {@code connection_info}
 * 表 + classpath 下 {@code remoteServerList.conf}，不额外造一份配置。
 */
@Service
@Scope("prototype")
public class DockerMgmtComponent extends CommonComponent {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(DockerMgmtComponent.class);

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    private Panel mainPanel;
    private VerticalLayout contentLayout;
    private ComboBox<ConnectionInfo> hostCombo;
    private TextField prefixField;
    private Button connectBtn;
    private Button checkBtn;
    private Label busyLabel;
    private Label envLabel;
    private TabSheet subTabs;

    /** daemon 不可用时的占位面板（不建页签，避免五个页签一起空转） */
    private VerticalLayout daemonNotice;
    private Label noticeTitle;
    private TextArea noticeText;
    private Button noticeStartBtn;

    private DockerExecutor executor;
    private DockerService service;

    /** 已经建出来的子页面，用于服务启动后统一刷新 */
    private final List<AbstractDockerPage> pages = new ArrayList<AbstractDockerPage>();
    /** 同一时刻只允许有一个「Docker 不可用」弹窗 */
    private DockerDaemonWindow daemonWindow;

    @Override
    public void initLayout() {
        mainPanel = new Panel();
        mainPanel.setHeight("760px");
        mainPanel.setWidth("100%");
        setCompositionRoot(mainPanel);
        setWidth("100%");

        contentLayout = new VerticalLayout();
        contentLayout.setWidth("100%");
        contentLayout.setHeight("730px");
        contentLayout.setSpacing(true);
        mainPanel.setContent(contentLayout);

        contentLayout.addComponent(buildHostRow());
        envLabel = ComponentFactory.getStandardLabel("");
        envLabel.addStyleName("docker-env-label");
        envLabel.setWidth("100%");
        contentLayout.addComponent(envLabel);

        daemonNotice = buildDaemonNotice();
        contentLayout.addComponent(daemonNotice);
        daemonNotice.setVisible(false);

        subTabs = new TabSheet();
        subTabs.setWidth("100%");
        subTabs.setHeight("600px");
        subTabs.setVisible(false);
        contentLayout.addComponent(subTabs);
        contentLayout.setExpandRatio(subTabs, 1f);
    }

    private HorizontalLayout buildHostRow() {
        HorizontalLayout row = ComponentFactory.getHorizontalLayout();
        row.setHeight("44px");
        row.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        row.addStyleName("docker-toolbar");

        row.addComponent(ComponentFactory.getStandardLabel("目标服务器："));
        hostCombo = new ComboBox<ConnectionInfo>();
        hostCombo.setWidth("240px");
        hostCombo.setItemCaptionGenerator(item -> item.getIdHost() + "  (" + item.getIdUser() + ")");
        hostCombo.setPlaceholder("选择一台已配置的服务器");
        hostCombo.addStyleName("field_box_standard_height");
        row.addComponent(hostCombo);

        row.addComponent(ComponentFactory.getStandardLabel("docker 命令："));
        prefixField = ComponentFactory.getStandardTtextField();
        prefixField.setWidth("180px");
        prefixField.setPlaceholder("留空自动探测，如 sudo -n docker");
        row.addComponent(prefixField);

        connectBtn = ComponentFactory.getPrimaryButtonWithType("连接", com.so.component.util.ButtonType.PRIMARY);
        connectBtn.setWidth("90px");
        connectBtn.setHeight("30px");
        row.addComponent(connectBtn);

        checkBtn = ComponentFactory.getStandardButton("检测服务状态");
        checkBtn.setWidth("130px");
        checkBtn.setHeight("30px");
        checkBtn.setEnabled(false);
        row.addComponent(checkBtn);

        busyLabel = ComponentFactory.getStandardLabel("");
        busyLabel.addStyleName("docker-busy");
        row.addComponent(busyLabel);
        row.setExpandRatio(busyLabel, 1f);
        return row;
    }

    /** daemon 不可用时的占位：把"为什么不能用 + 下一步做什么"放在页面上，而不是只弹一次窗 */
    private VerticalLayout buildDaemonNotice() {
        VerticalLayout box = new VerticalLayout();
        box.setWidth("100%");
        box.setHeight("300px");
        box.setSpacing(true);
        box.setMargin(true);
        box.addStyleName("docker-daemon-notice");

        noticeTitle = new Label("");
        noticeTitle.addStyleName("docker-daemon-title");
        noticeTitle.setWidth("100%");
        box.addComponent(noticeTitle);

        noticeText = ComponentFactory.getStandardTtextArea();
        noticeText.setReadOnly(true);
        noticeText.setWidth("100%");
        noticeText.setHeight("140px");
        noticeText.addStyleName("docker-daemon-text");
        box.addComponent(noticeText);

        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(true);
        noticeStartBtn = ComponentFactory.getPrimaryButtonWithType("启动 Docker 服务",
                com.so.component.util.ButtonType.SUCCESS);
        noticeStartBtn.setWidth("170px");
        noticeStartBtn.setHeight("30px");
        row.addComponent(noticeStartBtn);
        box.addComponent(row);
        return box;
    }

    @Override
    public void initContent() {
        hostCombo.setItems(loadCandidateHosts());
    }

    /**
     * 目标服务器候选：数据库里的连接信息 + {@code remoteServerList.conf} 里的免登录配置。
     * 按 host:port 去重，避免两台来源重复时下拉框里出现两条一样的。
     */
    private List<ConnectionInfo> loadCandidateHosts() {
        List<ConnectionInfo> list = new ArrayList<ConnectionInfo>();
        Set<String> seen = new LinkedHashSet<String>();
        try {
            List<ConnectionInfo> fromDb = connectionInfoMapper.selectList(new QueryWrapper<ConnectionInfo>());
            if (null != fromDb) {
                for (ConnectionInfo info : fromDb) {
                    addHost(list, seen, info);
                }
            }
        } catch (Exception e) {
            log.warn("读取数据库中的服务器列表失败：{}", e.getMessage());
        }
        try {
            for (String line : Util.getRemoteServerList()) {
                String[] split = line.split("=");
                if (split.length < 4) {
                    continue;
                }
                String keyPath = split.length > 4 ? split[4] : null;
                addHost(list, seen, new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath));
            }
        } catch (Exception e) {
            log.warn("读取 remoteServerList.conf 失败：{}", e.getMessage());
        }
        if (list.isEmpty()) {
            envLabel.setValue("没有可用的服务器，请先到「远程应用管理 → 免登录服务器列表」添加机器。");
        }
        return list;
    }

    private boolean addHost(List<ConnectionInfo> list, Set<String> seen, ConnectionInfo info) {
        if (null == info || StrUtil.isBlank(info.getIdHost())) {
            return false;
        }
        String key = info.getIdHost() + ":" + portOf(info);
        if (!seen.add(key)) {
            return false;
        }
        list.add(info);
        return true;
    }

    private static String portOf(ConnectionInfo info) {
        return StrUtil.isBlank(info.getCdPort()) ? "22" : info.getCdPort().trim();
    }

    @Override
    public void registerHandler() {
        connectBtn.addClickListener(e -> connect());
        // 手动检测：用户可能自己在服务器上把 docker 起起来了，或者刚装完
        checkBtn.addClickListener(e -> checkDaemon(true));
        noticeStartBtn.addClickListener(e -> openDaemonWindow(null));
    }

    /**
     * 建立到目标服务器的 SSH 通道，并<b>在同一个后台线程里</b>把 docker 服务状态探出来。
     * <p>
     * 建链 + 探测都是秒级的远程动作，放在请求线程里界面会僵住。
     */
    private void connect() {
        final ConnectionInfo info = hostCombo.getValue();
        if (null == info) {
            Notification.show("请先选择目标服务器", Notification.Type.WARNING_MESSAGE);
            return;
        }
        closeExecutor();
        final String prefix = prefixField.getValue();
        setBusy(true, "正在连接 " + info.getIdHost() + " …");

        final UI ui = UI.getCurrent();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DockerExecutor newExecutor = new DockerExecutor(info, prefix);
                    // 连上就先问一次 daemon 在不在，别急着建页签发命令
                    final DockerDaemonStatus status = newExecutor.probeDaemon(true);
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            executor = newExecutor;
                            service = new DockerService(newExecutor);
                            setBusy(false, "");
                            checkBtn.setEnabled(true);
                            applyDaemonStatus(status);
                        }
                    });
                } catch (final Exception e) {
                    log.error("连接 {} 失败：{}", info.getIdHost(), e.getMessage(), e);
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, "");
                            Notification.show("连接 " + info.getIdHost() + " 失败："
                                    + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()),
                                    Notification.Type.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }, "docker-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /** 手动重新探测目标机的 docker 状态 */
    private void checkDaemon(boolean notifyWhenOk) {
        if (null == executor || executor.isClosed()) {
            Notification.show("请先选择目标服务器并点「连接」", Notification.Type.WARNING_MESSAGE);
            return;
        }
        final DockerExecutor current = executor;
        setBusy(true, "正在检测 docker 服务状态 …");
        final UI ui = UI.getCurrent();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DockerDaemonStatus status = current.probeDaemon(true);
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, "");
                            applyDaemonStatus(status);
                            if (status.isDaemonRunning()) {
                                if (notifyWhenOk) {
                                    Notification.show("Docker 服务正常",
                                            "服务端版本 " + StrUtil.emptyToDefault(status.getServerVersion(), "?"),
                                            Notification.Type.HUMANIZED_MESSAGE);
                                }
                            } else {
                                openDaemonWindow(status);
                            }
                        }
                    });
                } catch (final Exception e) {
                    log.warn("检测 docker 状态失败：{}", e.getMessage());
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, "");
                            Notification.show("检测失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"),
                                    Notification.Type.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }, "docker-check");
        thread.setDaemon(true);
        thread.start();
    }

    /* ------------------------------------------------------------------ */
    /* daemon 状态 -> 界面                                                 */
    /* ------------------------------------------------------------------ */

    private void applyDaemonStatus(DockerDaemonStatus status) {
        if (null == executor) {
            return;
        }
        boolean running = null != status && status.isDaemonRunning();
        String prefixText = executor.getCommandPrefix();
        envLabel.removeStyleName("docker-env-warn");
        if (running) {
            envLabel.setValue("已连接 " + executor.hostLabel() + "　docker 命令：`" + prefixText + "`　"
                    + status.summary());
            updateDaemonNotice(null);
            showTabs();
        } else {
            envLabel.addStyleName("docker-env-warn");
            envLabel.setValue("已连接 " + executor.hostLabel() + "　docker 命令：`" + prefixText + "`　"
                    + (null == status ? "docker 状态未知" : status.summary()));
            hideTabs();
            updateDaemonNotice(status);
            // 弹一次窗把话说清楚，用户关掉后页面上还留着占位提示
            openDaemonWindow(status);
        }
    }

    private void hideTabs() {
        subTabs.setVisible(false);
    }

    private void showTabs() {
        subTabs.setVisible(true);
        buildSubTabs();
    }

    private void updateDaemonNotice(DockerDaemonStatus status) {
        if (null == status) {
            daemonNotice.setVisible(false);
            return;
        }
        daemonNotice.setVisible(true);
        if (!status.isDockerInstalled()) {
            noticeTitle.setValue("目标机未安装 Docker");
            noticeText.setValue("当前系统没有检测到 docker，请先安装后再进行管理。\n\n" + status.diagnosis());
            if (CollectionUtil.isNotEmpty(DockerDaemonWindow.installHintsOf(status))) {
                noticeText.setValue(noticeText.getValue() + "\n\n安装命令：\n"
                        + StrUtil.join("\n", DockerDaemonWindow.installHintsOf(status)));
            }
            noticeStartBtn.setVisible(false);
        } else {
            noticeTitle.setValue("Docker 服务未运行，已暂停所有 docker 命令");
            noticeText.setValue(status.diagnosis()
                    + "\n\n启动命令：\n" + status.getStartCommandPreview());
            noticeStartBtn.setVisible(true);
            noticeStartBtn.setEnabled(!status.buildStartCommands().isEmpty());
        }
    }

    /** 弹出「Docker 不可用」窗口；已经弹着就不再弹第二个 */
    public void promptDockerUnavailable(DockerDaemonStatus status) {
        if (null == status) {
            return;
        }
        updateDaemonNotice(status);
        openDaemonWindow(status);
    }

    private void openDaemonWindow(DockerDaemonStatus status) {
        if (null == executor || executor.isClosed()) {
            Notification.show("请先选择目标服务器并点「连接」", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (null != daemonWindow) {
            return;
        }
        DockerDaemonStatus current = (null != status) ? status : executor.currentDaemonStatus();
        if (null == current) {
            // 还没有探测结果就先探一次，避免拿 null 去建窗口
            checkDaemon(false);
            return;
        }
        if (current.isDaemonRunning()) {
            return;
        }
        final DockerDaemonWindow window = new DockerDaemonWindow(executor, current,
                new DockerDaemonWindow.Started() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onDockerReady(DockerDaemonStatus now) {
                        daemonWindow = null;
                        // applyDaemonStatus 里已经负责建页签/刷新数据，这里不用再来一遍
                        applyDaemonStatus(now);
                    }
                });
        window.addCloseListener(new com.vaadin.ui.Window.CloseListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void windowClose(com.vaadin.ui.Window.CloseEvent e) {
                daemonWindow = null;
            }
        });
        daemonWindow = window;
        UI ui = UI.getCurrent();
        if (null == ui) {
            daemonWindow = null;
            return;
        }
        ui.addWindow(window);
    }

    private void buildSubTabs() {
        if (subTabs.getComponentCount() > 0) {
            reloadAllPages();
            return;
        }
        pages.clear();
        pages.add(initPage(new DockerContainerPage(this, executor), "容器管理"));
        pages.add(initPage(new DockerImagePage(this, executor), "镜像管理"));
        pages.add(initPage(new DockerVolumePage(this, executor), "数据卷"));
        pages.add(initPage(new DockerNetworkPage(this, executor), "网络"));
        pages.add(initPage(new DockerSystemPage(this, executor), "系统信息"));
        subTabs.setSelectedTab(0);
    }

    private AbstractDockerPage initPage(AbstractDockerPage page, String caption) {
        page.initLayout();
        page.initContent();
        page.registerHandler();
        subTabs.addTab(page, caption);
        return page;
    }

    /** docker 服务刚起来（或刚装完）时，把所有页签的数据重新拉一遍 */
    public void reloadAllPages() {
        for (AbstractDockerPage page : pages) {
            try {
                page.reload();
            } catch (Exception e) {
                log.warn("刷新 {} 失败：{}", page.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private void closeExecutor() {
        if (null != executor) {
            executor.close();
            executor = null;
            service = null;
        }
        pages.clear();
        // detach() 有可能在 initLayout 之前就被调到（构造过程里抛异常时），所以都要判空
        if (null != subTabs) {
            subTabs.removeAllComponents();
        }
        if (null != checkBtn) {
            checkBtn.setEnabled(false);
        }
    }

    @Override
    public void detach() {
        // 页面关掉就断开这条 SSH 通道，否则每开一次标签页都会在目标机上留一个 ssh 会话
        closeExecutor();
        // 弹窗是挂在 UI 上的，组件自己被卸载时要一起收掉，否则它会拿着已经关掉的 executor
        if (null != daemonWindow) {
            try {
                daemonWindow.close();
            } catch (Exception ignore) {
                // 关不掉也不影响主流程
            }
            daemonWindow = null;
        }
        super.detach();
    }

    /* ------------------------------------------------------------------ */
    /* 给子页面用的回调                                                    */
    /* ------------------------------------------------------------------ */

    /** 后台任务开始/结束时更新状态提示，并禁掉连接按钮避免并发建链 */
    public void setBusy(boolean busy, String text) {
        if (null != busyLabel) {
            busyLabel.setValue(StrUtil.emptyToDefault(text, ""));
        }
        if (null != connectBtn) {
            connectBtn.setEnabled(!busy);
        }
    }

    public DockerExecutor getExecutor() {
        return executor;
    }

    public DockerService getService() {
        return service;
    }

    /** 子页面之间切换时的跳转（例如容器页要求先切到镜像页） */
    public void selectTab(String caption) {
        if (null == subTabs) {
            return;
        }
        for (int i = 0; i < subTabs.getComponentCount(); i++) {
            if (caption.equals(subTabs.getTab(i).getCaption())) {
                subTabs.setSelectedTab(i);
                return;
            }
        }
    }
}
