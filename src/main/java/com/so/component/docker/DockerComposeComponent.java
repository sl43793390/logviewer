package com.so.component.docker;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.component.CommonComponent;
import com.so.docker.ComposePreferenceStore;
import com.so.docker.ComposeService;
import com.so.docker.DockerExecutor;
import com.so.docker.DockerService;
import com.so.docker.model.ComposeBaseDirEntry;
import com.so.docker.model.ComposeCliInfo;
import com.so.docker.model.ComposeProject;
import com.so.docker.model.ComposeUiPreference;
import com.so.docker.model.DockerDaemonStatus;
import com.so.entity.ConnectionInfo;
import com.so.mapper.ConnectionInfoMapper;
import com.so.ui.ComponentFactory;
import com.so.util.Util;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Docker-Compose 项目管理。
 * <p>
 * 页面结构：选目标服务器 → 建 SSH 通道 → 探测 docker daemon 与 compose CLI →
 * 项目列表（工具栏对选中项目做 up / stop / start / restart / redeploy / 删除），
 * 具体某个项目的服务、容器、日志、网络卷、文件编辑都在详情窗口
 * （{@link ComposeProjectWindow}）里，避免主页面堆成一张巨表。
 * <p>
 * <b>两个前置探测都不能省。</b>
 * docker daemon 不在时任何 compose 子命令都是白跑一趟 SSH（而且失败回调会引发连锁刷新，
 * 见 {@link AbstractDockerPage} 顶部的说明）；compose 本身没装时，
 * 更该直接把安装命令摆出来，而不是让 {@code docker compose up} 报一句
 * "unknown command" 让用户去猜。
 */
@Service
@Scope("prototype")
public class DockerComposeComponent extends CommonComponent {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(DockerComposeComponent.class);

    private static final String ACTION_START = "启动";
    private static final String ACTION_STOP = "暂停";
    private static final String ACTION_RESUME = "恢复";
    private static final String ACTION_RESTART = "重启";
    private static final String ACTION_REDEPLOY = "重新部署";
    private static final String ACTION_DELETE = "删除";

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    @Autowired
    private ComposePreferenceStore prefStore;

    private Panel mainPanel;
    private VerticalLayout contentLayout;
    private ComboBox<ConnectionInfo> hostCombo;
    private TextField prefixField;
    private TextField baseDirField;
    /** 输入框后面的「历史目录」下拉：用户用过多个根目录时靠它切换 */
    private ComboBox<ComposeBaseDirEntry> baseDirCombo;
    private Button connectBtn;
    private Button reloadCliBtn;
    private Label busyLabel;

    /** 下拉框当前承载的候选主机，记住的主机要在里面找对应的那一项 */
    private List<ConnectionInfo> candidateHosts = new ArrayList<ConnectionInfo>();
    /**
     * 从「免登录服务器列表」跳进来时带过来的目标主机。
     * <p>
     * 与 {@link DockerMgmtComponent} 同样的做法：点某台机器的按钮，落到的页面就该已经连着那一台，
     * 而不是让人再到下拉框里重新挑一遍。真正的连接要等 tab 挂上 UI 之后（{@link #attach()}）再发起。
     */
    private ConnectionInfo presetHost;
    /** 预置主机的自动连接只做一次 */
    private boolean autoConnectPending;

    /** 当前登录用户的 compose 偏好（上次用的主机 + 历史根目录），页面初始化时读一次，改动后立刻写回 */
    private ComposeUiPreference preference;
    /** 程序性改动 baseDirCombo 时置位，避免它自己的 ValueChangeListener 反过来又去刷项目列表 */
    private boolean updatingBaseDirCombo;

    private Label envLabel;
    private VerticalLayout daemonNotice;
    private Label daemonTitle;
    private com.vaadin.ui.TextArea daemonText;
    private Button daemonStartBtn;

    private HorizontalLayout toolbar;
    private Button createBtn;
    private Button refreshBtn;
    private final List<Button> projectActionButtons = new ArrayList<Button>();

    private Grid<ComposeProject> grid;
    private final List<ComposeProject> projects = new ArrayList<ComposeProject>();

    private DockerExecutor executor;
    private DockerService service;
    private ComposeService compose;
    private ComposeCliInfo cliInfo;

    /** 同一时刻只允许有一个「Docker 不可用」弹窗 */
    private DockerDaemonWindow daemonWindow;

    /** 页面已经打开的详情窗口，刷新项目列表时一并刷新（弹窗不归组件生命周期管，自己兜住） */
    private final List<ComposeProjectWindow> detailWindows = new ArrayList<ComposeProjectWindow>();

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
        contentLayout.addComponent(buildBaseDirRow());

        envLabel = ComponentFactory.getStandardLabel("");
        envLabel.addStyleName("docker-env-label");
        envLabel.setWidth("100%");
        contentLayout.addComponent(envLabel);

        daemonNotice = buildDaemonNotice();
        daemonNotice.setVisible(false);
        contentLayout.addComponent(daemonNotice);

        toolbar = buildToolbar();
        contentLayout.addComponent(toolbar);

        buildGrid();
        contentLayout.addComponent(grid);
        contentLayout.setExpandRatio(grid, 1f);
    }

    private HorizontalLayout buildHostRow() {
        HorizontalLayout row = ComponentFactory.getHorizontalLayout();
        row.setHeight("44px");
        row.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        row.addStyleName("docker-toolbar");

        row.addComponent(ComponentFactory.getStandardLabel("目标服务器："));
        hostCombo = new ComboBox<ConnectionInfo>();
        hostCombo.setWidth("220px");
        hostCombo.setItemCaptionGenerator(item -> item.getIdHost() + "  (" + item.getIdUser() + ")");
        hostCombo.setPlaceholder("选择一台已配置的服务器");
        hostCombo.addStyleName("field_box_standard_height");
        row.addComponent(hostCombo);

        row.addComponent(ComponentFactory.getStandardLabel("docker 命令："));
        prefixField = ComponentFactory.getStandardTtextField();
        prefixField.setWidth("350px");
        prefixField.setPlaceholder("留空自动探测");
        row.addComponent(prefixField);

        connectBtn = ComponentFactory.getPrimaryButtonWithType("连接", com.so.component.util.ButtonType.PRIMARY);
        connectBtn.setWidth("80px");
        connectBtn.setHeight("30px");
        row.addComponent(connectBtn);

        reloadCliBtn = ComponentFactory.getStandardButton("重测 Compose");
        reloadCliBtn.setWidth("136px");
        reloadCliBtn.setHeight("30px");
        reloadCliBtn.setEnabled(false);
        row.addComponent(reloadCliBtn);

        busyLabel = ComponentFactory.getStandardLabel("");
        busyLabel.addStyleName("docker-busy");
        row.addComponent(busyLabel);
        row.setExpandRatio(busyLabel, 1f);
        return row;
    }

    /**
     * 项目根目录单独一行：输入框 + 历史目录下拉。
     * <p>
     * 原来它挤在目标服务器那一行里，加上「重测 Compose」按钮之后整行长度过了 1280 屏
     * 的可视区（实测 1264 的可视宽下最后一个按钮被顶出去 50px），所以把这一项挪下来。
     * <p>
     * 下拉框是后来补的：一台机器上用过的根目录往往不止一个（换项目、换部署批次），
     * 而根目录决定列表里能看见哪些项目 —— 只靠输入框，换个目录就得手敲一遍全路径，
     * 换个会话还会忘。选项来自 {@link ComposePreferenceStore} 里按主机记的历史。
     */
    private HorizontalLayout buildBaseDirRow() {
        HorizontalLayout row = ComponentFactory.getHorizontalLayout();
        row.setHeight("40px");
        row.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        row.addComponent(ComponentFactory.getStandardLabel("项目根目录："));
        baseDirField = ComponentFactory.getStandardTtextField();
        baseDirField.setWidth("300px");
        baseDirField.setPlaceholder("留空用 $HOME/" + ComposeService.DEFAULT_BASE_DIR_NAME);
        row.addComponent(baseDirField);

        baseDirCombo = ComponentFactory.getStandardComboBox();
        // 360px 是量出来的：250px 下 "/root/logviewer-compose　（2 个项目）" 被截成
        // "/root/logviewer-compose..."，300px 下还差 40px（"（2 个…"），看不到项目数
        baseDirCombo.setWidth("360px");
        baseDirCombo.setPlaceholder("切换历史目录");
        baseDirCombo.setDescription("这台机器上用过的项目根目录，选中即切换并刷新下面的项目列表");
        baseDirCombo.setItemCaptionGenerator(ComposeBaseDirEntry::caption);
        row.addComponent(baseDirCombo);

        // 这一行现在有输入框 + 下拉 + 说明三样东西，1280 屏下说明只剩 500px 左右，
        // 原文案（"…占一个以项目名命名的子目录，compose 文件与 .env 都放里面。"，39 个字）
        // 会折成两行、顶出 40px 的行高。正文砍到一行，细节挪进 tooltip。
        Label hint = ComponentFactory.getStandardLabel("每个项目在根目录下占一个以项目名命名的子目录。");
        hint.addStyleName("docker-hint");
        hint.setDescription(".env 与 compose 文件都放在项目的子目录里；换目录请用输入框或右边的历史目录下拉。");
        row.addComponent(hint);
        hint.setWidth("100%");
        row.setExpandRatio(hint, 1f);
        return row;
    }

    private VerticalLayout buildDaemonNotice() {
        VerticalLayout box = new VerticalLayout();
        box.setWidth("100%");
        box.setHeight("220px");
        box.setSpacing(true);
        box.setMargin(true);
        box.addStyleName("docker-daemon-notice");

        daemonTitle = new Label("");
        daemonTitle.addStyleName("docker-daemon-title");
        daemonTitle.setWidth("100%");
        box.addComponent(daemonTitle);

        daemonText = ComponentFactory.getStandardTtextArea();
        daemonText.setReadOnly(true);
        daemonText.setWidth("100%");
        daemonText.setHeight("120px");
        daemonText.addStyleName("docker-daemon-text");
        box.addComponent(daemonText);

        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(true);
        daemonStartBtn = ComponentFactory.getPrimaryButtonWithType("启动 Docker 服务",
                com.so.component.util.ButtonType.SUCCESS);
        daemonStartBtn.setWidth("170px");
        daemonStartBtn.setHeight("30px");
        row.addComponent(daemonStartBtn);
        box.addComponent(row);
        return box;
    }

    private HorizontalLayout buildToolbar() {
        HorizontalLayout row = ComponentFactory.getHorizontalLayout();
        row.setHeight("44px");
        row.setSpacing(true);
        row.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        row.addStyleName("docker-toolbar");

        createBtn = ComponentFactory.getPrimaryButtonWithType("新建项目", com.so.component.util.ButtonType.SUCCESS);
        refreshBtn = ComponentFactory.getStandardButton("刷新");

        row.addComponent(createBtn);
        row.addComponent(linkAction(ACTION_START));
        row.addComponent(linkAction(ACTION_STOP));
        row.addComponent(linkAction(ACTION_RESUME));
        row.addComponent(linkAction(ACTION_RESTART));
        row.addComponent(linkAction(ACTION_REDEPLOY));
        row.addComponent(linkAction(ACTION_DELETE));
        row.addComponent(refreshBtn);
        row.setExpandRatio(refreshBtn, 1f);
        return row;
    }

    /** 工具栏动作全部作用在「表格里选中的那一行」上，所以按钮统一建、统一禁用 */
    private Button linkAction(final String action) {
        Button button = ComponentFactory.getStandardButton(action);
        button.setEnabled(false);
        button.addClickListener(e -> runProjectAction(action));
        projectActionButtons.add(button);
        return button;
    }

    private void buildGrid() {
        grid = new Grid<ComposeProject>();
        grid.setWidthFull();
        grid.setHeightFull();
        grid.addStyleName("grid_standard");
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);

        grid.addColumn(ComposeProject::getName).setCaption("项目名");
        grid.addComponentColumn(this::buildStatusCell).setCaption("状态");
        grid.addColumn(ComposeProject::getServiceCountText).setCaption("服务数");
        grid.addColumn(ComposeProject::getContainerCountText).setCaption("容器");
        grid.addColumn(ComposeProject::getFilesText).setCaption("Compose 文件");
        grid.addColumn(ComposeProject::getDirectory).setCaption("项目目录");
        grid.addColumn(ComposeProject::getCreatedAt).setCaption("创建时间");
        grid.addComponentColumn(this::buildRowActions).setCaption("操作");

        grid.addSelectionListener(e -> updateActionState());
    }

    private VerticalLayout buildStatusCell(ComposeProject project) {
        VerticalLayout box = new VerticalLayout();
        box.setSpacing(false);
        box.setMargin(false);
        box.addStyleName("compose-status-cell");

        Label state = ComponentFactory.getStandardLabel(project.getStatusLabel());
        state.addStyleName(project.getStatusStyle());
        box.addComponent(state);

        String detail = StrUtil.emptyToDefault(project.getStatusText(), "");
        if (project.getUnhealthyContainers() > 0) {
            detail = StrUtil.isBlank(detail) ? "" : detail + "　";
            detail = detail + "不健康 " + project.getUnhealthyContainers();
        }
        if (StrUtil.isNotBlank(detail)) {
            Label extra = ComponentFactory.getStandardLabel(detail);
            extra.addStyleName("docker-hint");
            box.addComponent(extra);
        }
        return box;
    }

    private HorizontalLayout buildRowActions(ComposeProject project) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        Button detail = ComponentFactory.getLinkButton("详情");
        detail.addClickListener(e -> openDetail(project));
        actions.addComponent(detail);

        Button reload = ComponentFactory.getLinkButton("刷新");
        reload.addClickListener(e -> {
            grid.select(project);
            refreshProjects();
        });
        actions.addComponent(reload);
        return actions;
    }

    @Override
    public void initContent() {
        candidateHosts = loadCandidateHosts();
        // 跳转带过来的主机可能既不在数据库也不在 remoteServerList.conf 里（配置文件刚被改过就是这种），
        // 补进候选，否则下拉框找不到它会静默回退成"未选择"。
        if (null != presetHost && null == matchHost(candidateHosts, presetHost)) {
            candidateHosts.add(presetHost);
        }
        hostCombo.setItems(candidateHosts);
        preference = prefStore.load(currentUser());
    }

    @Override
    public void registerHandler() {
        connectBtn.addClickListener(e -> connect());
        reloadCliBtn.addClickListener(e -> detectCliThenReload());
        daemonStartBtn.addClickListener(e -> openDaemonWindow(null));
        refreshBtn.addClickListener(e -> refreshProjects());
        createBtn.addClickListener(e -> openCreateWindow());
        grid.addItemClickListener(e -> {
            if (e.getMouseEventDetails().isDoubleClick()) {
                openDetail(e.getItem());
            }
        });
        // 换主机就换历史目录：同样是 /root/logviewer-compose，两台机器上的内容并不一样
        hostCombo.addValueChangeListener(e -> refreshBaseDirHistory(true));
        // 下拉选中即切换目录，已经连着的话顺手把列表刷成新目录下的项目
        baseDirCombo.addValueChangeListener(e -> {
            if (updatingBaseDirCombo || null == e.getValue()) {
                return;
            }
            String dir = StrUtil.trimToEmpty(e.getValue().getDir());
            if (dir.isEmpty() || dir.equals(StrUtil.trimToEmpty(baseDirField.getValue()))) {
                return;
            }
            baseDirField.setValue(dir);
            if (isCliReady()) {
                refreshProjects();
            }
        });
        // 默认选中上次用过的那台机器（注册完监听器再设值，让上面的历史目录逻辑跟着走一遍）
        applyRememberedHost();
        updateActionState();
    }

    /**
     * 默认选中上次连过的服务器。
     * <p>
     * 从菜单点进来时下拉框原来是空的：一来要重新找机器，二来 {@link #connect()} 会直接
     * 弹「请先选择目标服务器」。记住上次那台是有意义的 —— 这类页面的使用节奏通常就是
     * 反复盯同一台机器。只选中、不自动连接：菜单入口不该偷偷建 SSH 通道。
     */
    private void applyRememberedHost() {
        if (null == preference || StrUtil.isBlank(preference.getLastHost())) {
            return;
        }
        ConnectionInfo remembered = matchHost(candidateHosts, preference.getLastHost());
        if (null == remembered) {
            return;
        }
        if (null != hostCombo.getValue() && remembered.equals(hostCombo.getValue())) {
            refreshBaseDirHistory(true);
            return;
        }
        hostCombo.setValue(remembered);
    }

    /** compose 可用（已连上且 CLI 就绪），能发命令了 */
    private boolean isCliReady() {
        return null != compose && !executor.isClosed() && null != cliInfo && cliInfo.isInstalled();
    }

    /* ------------------------------------------------------------------ */
    /* 目标服务器候选                                                      */
    /* ------------------------------------------------------------------ */

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

    private void addHost(List<ConnectionInfo> list, Set<String> seen, ConnectionInfo info) {
        if (null == info || StrUtil.isBlank(info.getIdHost())) {
            return;
        }
        String key = info.getIdHost() + ":" + StrUtil.emptyToDefault(info.getCdPort(), "22");
        if (seen.add(key)) {
            list.add(info);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 界面偏好：上次用的主机 / 历史项目根目录                                */
    /* ------------------------------------------------------------------ */

    /**
     * 当前登录用户。取不到（比如组件还没挂到会话上）就退化为 {@code default}：
     * 偏好是锦上添花的东西，不值得为它抛异常。
     */
    private String currentUser() {
        try {
            String name = com.so.component.ComponentUtil.getCurrentUserName();
            return StrUtil.isBlank(name) ? "default" : name.trim();
        } catch (Exception e) {
            return "default";
        }
    }

    /** 主机键：host:port。带端口是因为同一台机器可能配了不同的 SSH 端口，那是两条独立记录。 */
    private static String hostKey(ConnectionInfo info) {
        if (null == info || StrUtil.isBlank(info.getIdHost())) {
            return null;
        }
        String port = StrUtil.isBlank(info.getCdPort()) ? "22" : info.getCdPort().trim();
        return info.getIdHost().trim() + ":" + port;
    }

    /** 按 host:port 找候选列表里对应的那一项 */
    private static ConnectionInfo matchHost(List<ConnectionInfo> list, String key) {
        if (null == list || StrUtil.isBlank(key)) {
            return null;
        }
        for (ConnectionInfo info : list) {
            if (key.equals(hostKey(info))) {
                return info;
            }
        }
        return null;
    }

    private static ConnectionInfo matchHost(List<ConnectionInfo> list, ConnectionInfo wanted) {
        return null == wanted ? null : matchHost(list, hostKey(wanted));
    }

    /**
     * 刷新「历史目录」下拉框的内容。
     *
     * @param fillField 是否顺带把输入框补成该主机最近用过的目录。
     *                  刚切主机、刚进页面时为 true；项目列表刷完（此时输入框就是真值）时为 false。
     */
    private void refreshBaseDirHistory(boolean fillField) {
        if (null == baseDirCombo) {
            return;
        }
        ConnectionInfo info = hostCombo.getValue();
        List<ComposeBaseDirEntry> history = (null == info || null == preference)
                ? new ArrayList<ComposeBaseDirEntry>() : preference.dirsOf(hostKey(info));
        // 传副本：preference 里的那个 List 后面还会被 rememberDir 改，直接交给 ComboBox 会跟
        // ListDataProvider 内部状态打架（表现是下拉弹层里少一项或者顺序错乱）
        updatingBaseDirCombo = true;
        try {
            baseDirCombo.setItems(new ArrayList<ComposeBaseDirEntry>(history));
            baseDirCombo.setValue(null);
        } finally {
            updatingBaseDirCombo = false;
        }
        if (!fillField || history.isEmpty()) {
            return;
        }
        String current = StrUtil.trimToEmpty(baseDirField.getValue());
        if (StrUtil.isBlank(current) || null == findDir(history, current)) {
            baseDirField.setValue(history.get(0).getDir());
        }
    }

    private static ComposeBaseDirEntry findDir(List<ComposeBaseDirEntry> list, String dir) {
        for (ComposeBaseDirEntry entry : list) {
            if (dir.equals(entry.getDir())) {
                return entry;
            }
        }
        return null;
    }

    /** 记下"这台机器 + 这个根目录"，并立刻落库 */
    private void rememberBaseDir(String host, String dir, int projectCount) {
        if (null == preference || StrUtil.isBlank(host) || StrUtil.isBlank(dir)) {
            return;
        }
        preference.rememberDir(host, dir, projectCount);
        prefStore.save(currentUser(), preference);
    }

    /** 记下"上次用的是这台机器"，并立刻落库 */
    private void rememberHost(String host) {
        if (null == preference || StrUtil.isBlank(host)) {
            return;
        }
        preference.setLastHost(host);
        prefStore.save(currentUser(), preference);
    }

    /* ------------------------------------------------------------------ */
    /* 从「免登录服务器列表」跳转过来时的预置主机                              */
    /* ------------------------------------------------------------------ */

    /**
     * 预置目标主机并自动连接。由服务器的「Compose 管理」按钮调用。
     * <p>
     * 连接不在这里发起：拿 bean 的时候组件还没挂到 UI 上，后面后台线程要回头用 UI 渲染。
     * 这里只记下"要连哪台"，等 {@link #attach()} 之后再动手。
     */
    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
        this.autoConnectPending = (null != info);
    }

    @Override
    public void attach() {
        super.attach();
        tryAutoConnect();
    }

    private void tryAutoConnect() {
        if (!autoConnectPending || null == presetHost || null == hostCombo || null == candidateHosts) {
            return;
        }
        ConnectionInfo target = matchHost(candidateHosts, presetHost);
        if (null == target) {
            target = presetHost;
            candidateHosts.add(target);
            hostCombo.setItems(candidateHosts);
        }
        // 先落标记再动手：连接失败也不能反复重连，一次就够
        autoConnectPending = false;
        // setValue 会触发主机变更监听 → 把输入框补成这台机器上次用的根目录，再拿着它去连
        hostCombo.setValue(target);
        connect();
    }

    /* ------------------------------------------------------------------ */
    /* 连接                                                                */
    /* ------------------------------------------------------------------ */

    private void connect() {
        final ConnectionInfo info = hostCombo.getValue();
        if (null == info) {
            Notification.show("请先选择目标服务器", Notification.Type.WARNING_MESSAGE);
            return;
        }
        closeExecutor();
        final String prefix = prefixField.getValue();
        final String baseDir = baseDirField.getValue();
        setBusy(true, "正在连接 " + info.getIdHost() + " …");
        final UI ui = UI.getCurrent();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DockerExecutor newExecutor = new DockerExecutor(info, prefix);
                    final DockerDaemonStatus daemonStatus = newExecutor.probeDaemon(true);
                    final ComposeService newCompose = new ComposeService(newExecutor);
                    final ComposeCliInfo cli = newCompose.cliInfo();
                    final String resolvedBaseDir = StrUtil.isNotBlank(baseDir)
                            ? baseDir.trim() : newCompose.defaultBaseDir();
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            executor = newExecutor;
                            service = new DockerService(newExecutor);
                            compose = newCompose;
                            cliInfo = cli;
                            setBusy(false, "");
                            // 连上了才算"用过这台机器"：连接失败不该污染下次的默认选中
                            rememberHost(hostKey(info));
                            if (StrUtil.isBlank(baseDirField.getValue())) {
                                baseDirField.setValue(resolvedBaseDir);
                            }
                            // 先把目录记进历史（项目数未知），这样即便 daemon 没运行、列表刷不出来，
                            // 这个目录也已经出现在下拉框里了
                            rememberBaseDir(hostKey(info), baseDirField.getValue().trim(), -1);
                            refreshBaseDirHistory(false);
                            reloadCliBtn.setEnabled(true);
                            applyDaemonStatus(daemonStatus);
                            if (daemonStatus.isDaemonRunning()) {
                                refreshProjects();
                            }
                        }
                    });
                } catch (final Exception e) {
                    log.error("连接 {} 失败：{}", info.getIdHost(), e.getMessage(), e);
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, "");
                            Notification.show("连接 " + info.getIdHost() + " 失败："
                                            + DockerUi.reason(e),
                                    Notification.Type.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }, "compose-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void detectCliThenReload() {
        if (null == compose) {
            Notification.show("请先选择目标服务器并点「连接」", Notification.Type.WARNING_MESSAGE);
            return;
        }
        compose.invalidateCli();
        setBusy(true, "正在重新检测 Compose …");
        final UI ui = UI.getCurrent();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ComposeCliInfo cli = compose.cliInfo();
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, "");
                            cliInfo = cli;
                            applyCliInfo(cli);
                            if (cli.isInstalled()) {
                                Notification.show("Compose 可用", cli.summary(), Notification.Type.HUMANIZED_MESSAGE);
                                refreshProjects();
                            }
                        }
                    });
                } catch (final Exception e) {
                    log.warn("重新检测 compose 失败：{}", e.getMessage());
                    ui.access(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, "");
                            Notification.show("检测失败：" + DockerUi.reason(e), Notification.Type.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }, "compose-detect");
        thread.setDaemon(true);
        thread.start();
    }

    /* ------------------------------------------------------------------ */
    /* 状态 -> 界面                                                        */
    /* ------------------------------------------------------------------ */

    private void applyDaemonStatus(DockerDaemonStatus status) {
        if (null == executor) {
            return;
        }
        boolean running = null != status && status.isDaemonRunning();
        envLabel.removeStyleName("docker-env-warn");
        if (running) {
            envLabel.setValue("已连接 " + executor.hostLabel() + "　docker 命令：`"
                    + executor.getCommandPrefix() + "`　" + status.summary());
            daemonNotice.setVisible(false);
            applyCliInfo(cliInfo);
        } else {
            envLabel.addStyleName("docker-env-warn");
            envLabel.setValue("已连接 " + executor.hostLabel() + "　docker 命令：`"
                    + executor.getCommandPrefix() + "`　"
                    + (null == status ? "docker 状态未知" : status.summary()));
            grid.setItems(new ArrayList<ComposeProject>());
            projects.clear();
            showDaemonNotice(status);
            updateActionState();
            openDaemonWindow(status);
        }
    }

    private void showDaemonNotice(DockerDaemonStatus status) {
        if (null == status) {
            daemonNotice.setVisible(false);
            return;
        }
        daemonNotice.setVisible(true);
        if (!status.isDockerInstalled()) {
            daemonTitle.setValue("目标机未安装 Docker");
            daemonText.setValue("当前系统没有检测到 docker，请先安装后再进行管理。\n\n" + status.diagnosis());
            daemonStartBtn.setVisible(false);
        } else {
            daemonTitle.setValue("Docker 服务未运行，已暂停所有 compose 命令");
            daemonText.setValue(status.diagnosis() + "\n\n启动命令：\n" + status.getStartCommandPreview());
            daemonStartBtn.setVisible(true);
            daemonStartBtn.setEnabled(!status.buildStartCommands().isEmpty());
        }
    }

    /** compose CLI 的可用性提示：没装就把安装命令直接摆出来 */
    private void applyCliInfo(ComposeCliInfo cli) {
        if (null == cli) {
            return;
        }
        if (!cli.isInstalled()) {
            String os = null == executor.currentDaemonStatus() ? "" : executor.currentDaemonStatus().getOsRelease();
            daemonNotice.setVisible(true);
            daemonTitle.setValue("目标机没有可用的 Docker Compose");
            daemonText.setValue(cli.getDiagnosis() + "\n\n安装命令：\n" + ComposeCliInfo.installHint(os));
            daemonStartBtn.setVisible(false);
        } else if (daemonNotice.isVisible() && null != executor.currentDaemonStatus()
                && executor.currentDaemonStatus().isDaemonRunning()) {
            daemonNotice.setVisible(false);
        }
        updateActionState();
    }

    private void openDaemonWindow(DockerDaemonStatus status) {
        if (null == executor || executor.isClosed()) {
            Notification.show("请先选择目标服务器并点「连接」", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (null != daemonWindow) {
            return;
        }
        final DockerDaemonStatus current = (null != status) ? status : executor.currentDaemonStatus();
        if (null == current || current.isDaemonRunning()) {
            return;
        }
        final DockerDaemonWindow window = new DockerDaemonWindow(executor, current,
                new DockerDaemonWindow.Started() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onDockerReady(DockerDaemonStatus now) {
                        daemonWindow = null;
                        applyDaemonStatus(now);
                        if (null != now && now.isDaemonRunning()) {
                            refreshProjects();
                        }
                    }
                });
        window.addCloseListener((com.vaadin.ui.Window.CloseListener) e -> daemonWindow = null);
        daemonWindow = window;
        UI ui = UI.getCurrent();
        if (null == ui) {
            daemonWindow = null;
            return;
        }
        ui.addWindow(window);
    }

    /* ------------------------------------------------------------------ */
    /* 项目列表                                                            */
    /* ------------------------------------------------------------------ */

    private void refreshProjects() {
        if (null == compose || executor.isClosed()) {
            Notification.show("请先连接目标服务器", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (null != cliInfo && !cliInfo.isInstalled()) {
            applyCliInfo(cliInfo);
            return;
        }
        final String baseDir = StrUtil.isNotBlank(baseDirField.getValue())
                ? baseDirField.getValue().trim() : compose.defaultBaseDir();
        async("读取 compose 项目", new DockerUi.Task<List<ComposeProject>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public List<ComposeProject> run() throws Exception {
                return compose.listProjects(baseDir);
            }
        }, new DockerUi.Done<List<ComposeProject>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(List<ComposeProject> list) {
                ComposeProject selected = grid.getSelectedItems().isEmpty()
                        ? null : grid.getSelectedItems().iterator().next();
                projects.clear();
                projects.addAll(list);
                grid.setItems(list);
                if (null != selected) {
                    for (ComposeProject project : list) {
                        if (project.getName().equals(selected.getName())) {
                            grid.select(project);
                            break;
                        }
                    }
                }
                int running = 0;
                for (ComposeProject project : list) {
                    if (ComposeProject.STATUS_RUNNING.equals(project.getStatus())) {
                        running++;
                    }
                }
                updateActionState();
                envLabel.setValue("已连接 " + executor.hostLabel() + "　docker 命令：`"
                        + executor.getCommandPrefix() + "`　" + (null == cliInfo ? "" : cliInfo.summary())
                        + "　项目根目录：" + baseDir + "　共 " + list.size() + " 个项目（运行中 " + running + "）");
                // 目录 + 项目数一起记：下拉框里那行「/root/xxx（3 个项目）」就是这么来的。
                // 放在 done 里（而不是刷新前）是为了记真实查到的数量，失败时不会写进假数据。
                ConnectionInfo current = hostCombo.getValue();
                rememberBaseDir(hostKey(current), baseDir, list.size());
                refreshBaseDirHistory(false);
            }
        });
    }

    private void runProjectAction(final String action) {
        final ComposeProject project = selectedProject();
        if (null == project) {
            return;
        }
        if (!project.isManaged() && (ACTION_DELETE.equals(action) || ACTION_REDEPLOY.equals(action))) {
            Notification.show("这是从 docker compose ls 发现的「外来项目」，"
                    + "系统不知道它的文件该写哪里，只支持启动 / 暂停 / 恢复 / 重启。",
                    Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (ACTION_DELETE.equals(action)) {
            confirmDelete(project);
            return;
        }
        if (ACTION_RESTART.equals(action) && !project.isManaged()) {
            // 无伤大雅，restart 对任何项目都成立
            log.debug("restart 外来项目 {}", project.getName());
        }
        if (ACTION_START.equals(action) && project.getServiceCount() <= 0 && project.getTotalContainers() <= 0) {
            // 没启动过且服务数未知时也照发，compose 自己会报错，用户能看到真实原因
            log.debug("首次启动项目 {}", project.getName());
        }
        final boolean build = ACTION_REDEPLOY.equals(action);
        async(action + project.getName(), new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                if (ACTION_START.equals(action)) {
                    return compose.up(project, null, false, false, true);
                }
                if (ACTION_STOP.equals(action)) {
                    return compose.stop(project, null);
                }
                if (ACTION_RESUME.equals(action)) {
                    return compose.start(project, null);
                }
                if (ACTION_RESTART.equals(action)) {
                    return compose.restart(project, null);
                }
                if (ACTION_REDEPLOY.equals(action)) {
                    return compose.redeploy(project, false);
                }
                throw new IOException("未知操作：" + action);
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String output) {
                String tail = StrUtil.trimToEmpty(output);
                if (tail.length() > 400) {
                    tail = tail.substring(tail.length() - 400);
                }
                Notification.show(action + " " + project.getName() + " 完成"
                                + (StrUtil.isBlank(tail) ? "" : "：" + tail),
                        Notification.Type.HUMANIZED_MESSAGE);
                grid.select(project);
                refreshProjects();
                refreshDetailWindows(project.getName());
            }
        });
    }

    private void confirmDelete(final ComposeProject project) {
        StringBuilder html = new StringBuilder("<h3>即将删除项目 ").append(project.getName()).append("</h3>")
                .append("项目目录：").append(project.getDirectory()).append("<br/>")
                .append("将执行 <b>docker compose down</b>，并按勾选项删除数据卷 / 本地镜像 / 项目目录。<br/>")
                .append("目录删除后 compose 文件不可恢复，操作不可撤销。");

        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("删除项目", html.toString(), "确认删除", "取消", true);
        final com.vaadin.ui.CheckBox volumeBox = new com.vaadin.ui.CheckBox("同时删除数据卷（-v，数据不可恢复）");
        final com.vaadin.ui.CheckBox imageBox = new com.vaadin.ui.CheckBox("同时删除本地构建的镜像（--rmi local）");
        final com.vaadin.ui.CheckBox dirBox = new com.vaadin.ui.CheckBox("删除项目目录（含 compose 文件）");
        dirBox.setValue(true);
        for (com.vaadin.ui.CheckBox box : new com.vaadin.ui.CheckBox[]{volumeBox, imageBox, dirBox}) {
            box.addStyleName("docker-dialog-checkbox");
        }
        win.getLayout().addComponent(volumeBox, 1);
        win.getLayout().addComponent(imageBox, 2);
        win.getLayout().addComponent(dirBox, 3);

        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                final boolean volumes = volumeBox.getValue();
                final boolean images = imageBox.getValue();
                final boolean dir = dirBox.getValue();
                win.close();
                async("删除项目 " + project.getName(), new DockerUi.Task<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String run() throws Exception {
                        return compose.deleteProject(project, volumes, images, dir);
                    }
                }, new DockerUi.Done<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void done(String output) {
                        Notification.show("项目 " + project.getName() + " 已删除",
                                Notification.Type.HUMANIZED_MESSAGE);
                        log.info("用户 {} 删除了 compose 项目 {}（目录 {}）",
                                com.so.component.ComponentUtil.getCurrentUserName(), project.getName(),
                                project.getDirectory());
                        refreshProjects();
                    }
                });
            }
        });
        win.showConfirmation();
    }

    private ComposeProject selectedProject() {
        if (grid.getSelectedItems().isEmpty()) {
            Notification.show("请先在列表里选中一个项目", Notification.Type.WARNING_MESSAGE);
            return null;
        }
        return grid.getSelectedItems().iterator().next();
    }

    private void updateActionState() {
        boolean connected = null != compose && !executor.isClosed();
        boolean cliReady = null != cliInfo && cliInfo.isInstalled();
        boolean selected = !grid.getSelectedItems().isEmpty();
        for (Button button : projectActionButtons) {
            button.setEnabled(connected && cliReady && selected);
        }
        if (null != createBtn) {
            createBtn.setEnabled(connected && cliReady);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 弹窗                                                                */
    /* ------------------------------------------------------------------ */

    private void openCreateWindow() {
        if (null == compose || executor.isClosed()) {
            Notification.show("请先连接目标服务器", Notification.Type.WARNING_MESSAGE);
            return;
        }
        ComposeProjectCreateWindow window = new ComposeProjectCreateWindow(this, compose,
                StrUtil.isNotBlank(baseDirField.getValue()) ? baseDirField.getValue().trim() : compose.defaultBaseDir());
        window.addCloseListener(e -> {
            if (window.isCreated()) {
                refreshProjects();
            }
        });
        window.show(UI.getCurrent());
    }

    private void openDetail(ComposeProject project) {
        if (null == compose || executor.isClosed()) {
            Notification.show("请先连接目标服务器", Notification.Type.WARNING_MESSAGE);
            return;
        }
        ComposeProjectWindow window = new ComposeProjectWindow(this, compose, project);
        detailWindows.add(window);
        window.addCloseListener(e -> detailWindows.remove(window));
        window.show(UI.getCurrent());
    }

    /** 项目数据变了之后，把开着的详情窗口也一起刷新，否则两个界面显示的状态会打架 */
    private void refreshDetailWindows(String projectName) {
        for (ComposeProjectWindow window : new ArrayList<ComposeProjectWindow>(detailWindows)) {
            if (window.getProjectName().equals(projectName)) {
                window.reloadAll();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 通用                                                                */
    /* ------------------------------------------------------------------ */

    private <T> void async(String action, DockerUi.Task<T> task, DockerUi.Done<T> done) {
        if (null == executor || executor.isClosed()) {
            Notification.show("连接已断开，请重新点「连接」建立通道后再试。", Notification.Type.WARNING_MESSAGE);
            return;
        }
        DockerUi.async(action, task, done, new DockerUi.Busy() {
            private static final long serialVersionUID = 1L;

            @Override
            public void setBusy(boolean busy, String text) {
                // 必须写全限定形式。不加 DockerComposeComponent.this 的话，
                // 这个名字会先被匿名类自己的 setBusy 命中 —— 无限递归、直接 StackOverflowError。
                // 想复查全工程有没有同类问题：scripts/scan-selfcall.ps1
                DockerComposeComponent.this.setBusy(busy, text);
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                if (DockerUi.isDaemonDown(e)) {
                    // 命令压根没发出去，弹「要不要启动 docker」比刷错误更解决问题
                    DockerDaemonStatus status = AbstractDockerPage.daemonStatusOf(e);
                    envLabel.addStyleName("docker-env-warn");
                    envLabel.setValue(DockerUi.daemonDownHint);
                    showDaemonNotice(status);
                    openDaemonWindow(status);
                }
            }
        });
    }

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

    public ComposeService getComposeService() {
        return compose;
    }

    public DockerService getDockerService() {
        return service;
    }

    public ComposeCliInfo getCliInfo() {
        return cliInfo;
    }

    public List<ComposeProject> getProjects() {
        return projects;
    }

    @Override
    public void detach() {
        closeExecutor();
        for (ComposeProjectWindow window : new ArrayList<ComposeProjectWindow>(detailWindows)) {
            try {
                window.close();
            } catch (Exception ignore) {
                // 关不掉不影响主流程
            }
        }
        detailWindows.clear();
        if (null != daemonWindow) {
            try {
                daemonWindow.close();
            } catch (Exception ignore) {
                // 同上
            }
            daemonWindow = null;
        }
        super.detach();
    }

    private void closeExecutor() {
        if (null != executor) {
            executor.close();
            executor = null;
            service = null;
            compose = null;
            cliInfo = null;
        }
        projects.clear();
        if (null != grid) {
            grid.setItems(new ArrayList<ComposeProject>());
        }
        if (null != reloadCliBtn) {
            reloadCliBtn.setEnabled(false);
        }
        if (CollectionUtil.isNotEmpty(projectActionButtons)) {
            updateActionState();
        }
    }
}
