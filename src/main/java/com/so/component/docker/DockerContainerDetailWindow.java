package com.so.component.docker;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.so.docker.DockerExecutor;
import com.so.docker.DockerService;
import com.so.docker.DockerTerminalRegistry;
import com.so.docker.model.DockerContainer;
import com.so.docker.model.DockerFileInfo;
import com.so.docker.model.DockerStat;
import com.so.ui.ComponentFactory;
import com.so.util.Constants;
import com.vaadin.addon.charts.Chart;
import com.vaadin.addon.charts.model.AxisType;
import com.vaadin.addon.charts.model.ChartType;
import com.vaadin.addon.charts.model.Configuration;
import com.vaadin.addon.charts.model.DataSeries;
import com.vaadin.addon.charts.model.DataSeriesItem;
import com.vaadin.addon.charts.model.Marker;
import com.vaadin.addon.charts.model.PlotOptionsSpline;
import com.vaadin.addon.charts.model.XAxis;
import com.vaadin.addon.charts.model.YAxis;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.server.ExternalResource;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.BrowserFrame;
import com.vaadin.ui.Button;
import com.vaadin.ui.CheckBox;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.CssLayout;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.ProgressBar;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.Upload;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * 容器详情：inspect 可视化 / 实时日志 / Web 终端 / 资源曲线 / 容器内文件。
 * <p>
 * 五个页签放在一个弹窗里而不是拆成五个菜单项，是因为它们都围绕"这一个容器"，
 * 切换时不用反复回到列表再点进去。
 */
public class DockerContainerDetailWindow extends Window {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(DockerContainerDetailWindow.class);

    private static final String TERMINAL_PAGE = "VAADIN/themes/mytheme/terminal.html";
    private static final int MONITOR_INTERVAL_MS = 3000;
    private static final String SHELL_AUTO = "自动探测";
    private static final String TAIL_ALL = "全部";

    private static final String SHOW_LOGS = "显示日志";
    private static final String RELOAD_LOGS = "重新加载日志";
    private static final String CONNECT = "连接";
    private static final String RECONNECT = "重新连接";

    /**
     * 通道就绪的兜底等待上限。
     * <p>
     * iframe 里的页面在自己连上之前对服务端是不可见的，万一它压根没连上
     * （xterm.js 没加载起来、目标机网络不通），遮罩就会一直转圈 ——
     * 那和"点了没反应"是同一种体验，所以到点就换成排查提示。
     */
    private static final long READY_TIMEOUT_MS = 30000L;

    private final DockerContainer container;
    private final DockerService service;
    private final DockerExecutor executor;
    private final UI ui;

    private TabSheet tabs;

    /* 详情 */
    private TextArea inspectArea;

    /* 日志 */
    private ComboBox<String> tailCombo;
    private CheckBox timestampsBox;
    private Button showLogsBtn;
    private LoadMask logsMask;
    private String logToken;
    /** 每次点「显示日志」自增，用来判断异步回调属于哪一次加载（旧回调直接丢弃） */
    private int logLoadSeq;
    private boolean logReady;

    /* 终端 */
    private ComboBox<String> shellCombo;
    private Button connectBtn;
    private LoadMask consoleMask;
    private String execToken;
    private int execLoadSeq;
    private boolean execReady;
    private volatile String detectedShell = "/bin/sh";

    /* 监控 */
    private Chart usageChart;
    private Chart netChart;
    private DataSeries cpuSeries;
    private DataSeries memSeries;
    private DataSeries netInSeries;
    private DataSeries netOutSeries;
    private Label statLabel;
    private Button monitorToggleBtn;
    private volatile boolean monitoring;
    private Thread monitorThread;
    private DockerStat lastStat;
    private long lastSampleAt;

    /* 文件 */
    private Grid<DockerFileInfo> fileGrid;
    private TextField pathField;
    private Label fileStatus;

    public DockerContainerDetailWindow(DockerContainer container, DockerService service) {
        super("容器详情 - " + container.getName());
        this.container = container;
        this.service = service;
        this.executor = service.getExecutor();
        this.ui = UI.getCurrent();

        setWidth("1200px");
        setHeight("760px");
        setModal(true);
        setResizable(true);
        center();

        tabs = new TabSheet();
        tabs.setSizeFull();
        setContent(tabs);

        tabs.addTab(buildSummaryTab(), "详情");
        tabs.addTab(buildLogsTab(), "日志");
        tabs.addTab(buildConsoleTab(), "终端");
        tabs.addTab(buildMonitorTab(), "资源监控");
        tabs.addTab(buildFileTab(), "文件");

        // 容器里的 shell 只有进容器才知道，先异步探一次，探到之后终端页签用真实值
        DockerUi.async("探测容器 shell", new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                return service.resolveContainerShell(DockerContainerDetailWindow.this.container.getId());
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String shell) {
                detectedShell = shell;
            }
        });
    }

    public void show() {
        UI.getCurrent().addWindow(this);
    }

    /**
     * 带初始页签的构造：compose 的容器列表里「日志 / 终端 / 监控」是分开的入口，
     * 点进去应该直接落在对应的页签上，而不是每次都先看到「详情」。
     *
     * @param initialTab 页签标题（详情 / 日志 / 终端 / 资源监控 / 文件），为空则保持默认
     */
    public DockerContainerDetailWindow(DockerContainer container, DockerService service, String initialTab) {
        this(container, service);
        if (StrUtil.isBlank(initialTab)) {
            return;
        }
        for (int i = 0; i < tabs.getComponentCount(); i++) {
            if (initialTab.equals(tabs.getTab(i).getCaption())) {
                tabs.setSelectedTab(i);
                return;
            }
        }
    }

    @Override
    public void detach() {
        monitoring = false;
        if (null != monitorThread) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        // 让还在等超时的看门狗线程失效：它们醒来后会比对序号，比对不上就什么都不做
        logLoadSeq++;
        execLoadSeq++;
        // 回收本窗口用掉的两个终端凭证
        DockerTerminalRegistry.release(logToken);
        DockerTerminalRegistry.release(execToken);
        super.detach();
    }

    /* ================================================================== */
    /* 详情                                                               */
    /* ================================================================== */

    private VerticalLayout buildSummaryTab() {
        VerticalLayout root = fullLayout();

        HorizontalLayout summary = new HorizontalLayout();
        summary.setSpacing(true);
        summary.setWidth("100%");
        summary.addComponents(
                ComponentFactory.getStandardLabel("名称：" + container.getName()),
                ComponentFactory.getStandardLabel("ID：" + container.getShortId()),
                ComponentFactory.getStandardLabel("镜像：" + container.getImage()),
                ComponentFactory.getStandardLabel("状态：" + container.getStateLabel() + "（" + container.getStatus() + "）"));
        root.addComponent(summary);

        root.addComponent(ComponentFactory.getStandardLabel(
                "端口映射：" + StrUtil.emptyToDefault(container.getPorts(), "无") + "　创建时间：" + container.getCreatedAt()));
        root.addComponent(ComponentFactory.getStandardLabel(
                "启动命令：" + StrUtil.emptyToDefault(container.getCommand(), "无")));

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setWidth("100%");
        Button refresh = ComponentFactory.getStandardButton("刷新 inspect");
        refresh.setWidth("130px");
        Button copy = ComponentFactory.getStandardButton("复制 JSON");
        copy.setWidth("100px");
        bar.addComponents(refresh, copy);
        root.addComponent(bar);

        inspectArea = ComponentFactory.getTextArea();
        inspectArea.setSizeFull();
        inspectArea.setReadOnly(true);
        inspectArea.addStyleName("docker-inspect-area");
        root.addComponent(inspectArea);
        root.setExpandRatio(inspectArea, 1f);

        refresh.addClickListener(e -> loadInspect());
        copy.addClickListener(e -> {
            String text = inspectArea.getValue();
            if (StrUtil.isBlank(text)) {
                Notification.show("还没有加载内容", Notification.Type.WARNING_MESSAGE);
                return;
            }
            // 内网多为 http，navigator.clipboard 不可用，走 Vaadin 自带的剪贴板方案
            DockerUi.copyToClipboard(text);
            Notification.show("已复制到剪贴板", Notification.Type.HUMANIZED_MESSAGE);
        });

        loadInspect();
        return root;
    }

    private void loadInspect() {
        DockerUi.async("读取容器详情", new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                return service.inspect(container.getId());
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String json) {
                inspectArea.setValue(json);
            }
        });
    }

    /* ================================================================== */
    /* 日志                                                               */
    /* ================================================================== */

    private VerticalLayout buildLogsTab() {
        VerticalLayout root = fullLayout();

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setHeight("40px");
        bar.setWidth("100%");
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        tailCombo = new ComboBox<String>();
        tailCombo.setItems("100", "500", "2000", "10000", TAIL_ALL);
        tailCombo.setValue("500");
        tailCombo.setWidth("110px");
        tailCombo.setTextInputAllowed(false);
        tailCombo.setEmptySelectionAllowed(false);

        timestampsBox = new CheckBox("显示时间戳");

        // 不再打开页签就自动加载：日志要新开一条 SSH 通道，真机上十几秒才出内容，
        // 期间 iframe 里是纯黑的一片 —— 用户会以为按钮没生效。改成显式点一次，
        // 期间用圆形的等待进度条遮住 iframe。
        showLogsBtn = ComponentFactory.getPrimaryButtonWithType(SHOW_LOGS, com.so.component.util.ButtonType.PRIMARY);
        showLogsBtn.setWidth("130px");
        Button download = ComponentFactory.getStandardButton("下载日志");
        download.setWidth("100px");

        Label hint = ComponentFactory.getStandardLabel(
                "点「显示日志」后开始实时跟踪 docker logs -f（首次连接要新建 SSH 通道，十几秒属正常）。"
                        + "只读视图，搜索用 Ctrl+F，导出整个缓冲区用终端工具栏的「导出」。");
        hint.addStyleName("docker-hint");

        bar.addComponents(tailCombo, ComponentFactory.getStandardLabel("显示末"), timestampsBox, showLogsBtn, download);
        root.addComponent(bar);
        root.addComponent(hint);

        logsMask = new LoadMask();
        logsMask.setIdle("点击上方「" + SHOW_LOGS + "」按钮开始加载容器日志。<br/>"
                + "首次连接要在目标机上新建一条 SSH 通道，通常要十几秒，进度圈转完就会出现日志。<br/>"
                + "容器本身没有新日志时窗口是空的，属正常现象。");
        root.addComponent(logsMask.getHost());
        root.setExpandRatio(logsMask.getHost(), 1f);

        // 下载走 StreamResource：真机上日志可能有几十万行，比在浏览器里拼字符串可靠
        StreamResource resource = new StreamResource(new LogStreamSource(), "container-" + container.getName() + ".log");
        resource.setCacheTime(0);
        FileDownloader downloader = new FileDownloader(resource);
        downloader.extend(download);

        showLogsBtn.addClickListener(e -> reloadLogFrame());
        return root;
    }

    private void reloadLogFrame() {
        if (null == logsMask) {
            return;
        }
        final int seq = ++logLoadSeq;
        logReady = false;
        showLogsBtn.setEnabled(false);
        logsMask.setBusy("正在连接 " + cn.hutool.http.HtmlUtil.escape(executor.hostLabel()) + " 并拉取日志…<br/>"
                + "首次连接要新建 SSH 通道，请稍候（十几秒属正常）。");

        DockerTerminalRegistry.release(logToken);
        final DockerTerminalRegistry.Spec spec = new DockerTerminalRegistry.Spec(
                DockerTerminalRegistry.Kind.LOGS,
                executor.getInfo(),
                executor.getCommandPrefix(),
                container.getId(),
                container.getName(),
                parseTail(),
                Boolean.TRUE.equals(timestampsBox.getValue()),
                null);
        // 监听器要在登记之前挂好：websocket 那头的握手随时可能回来
        bindLoadCallbacks(spec, true, seq);
        logToken = DockerTerminalRegistry.register(spec);
        watchReadyTimeout(true, seq);
        // ro=1 只读、eol=1 把 LF 当 CRLF：docker logs 没有 tty，输出只有 \n
        logsMask.load(TERMINAL_PAGE + "?ws=/ws/docker&ro=1&eol=1&token=" + logToken);
    }

    private int parseTail() {
        String value = tailCombo.getValue();
        if (TAIL_ALL.equals(value) || StrUtil.isBlank(value)) {
            // -1 表示不传 --tail，取全量
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 500;
        }
    }

    /** 日志导出：直接从 SSH 命令通道把 docker logs 的输出流给浏览器 */
    private class LogStreamSource implements StreamResource.StreamSource {

        private static final long serialVersionUID = 1L;

        @Override
        public InputStream getStream() {
            try {
                return service.openLogsStream(container.getId(), parseTail(),
                        Boolean.TRUE.equals(timestampsBox.getValue()));
            } catch (Exception e) {
                // 这里跑在 Vaadin 的下载请求线程上，Page.getCurrent() 可能为 null，
                // 调 Notification.show 会 NPE，只能记日志
                log.error("导出容器 {} 日志失败：{}", container.getName(), e.getMessage());
                return null;
            }
        }
    }

    /* ================================================================== */
    /* 终端                                                               */
    /* ================================================================== */

    private VerticalLayout buildConsoleTab() {
        VerticalLayout root = fullLayout();

        if (!container.isRunning()) {
            root.addComponent(ComponentFactory.getStandardLabel(
                    "容器当前状态为「" + container.getStateLabel() + "」，无法进入交互终端。"
                            + "docker exec 只能作用在运行中的容器上，请先到列表里启动它。"));
            return root;
        }

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setHeight("40px");
        bar.setWidth("100%");
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        shellCombo = new ComboBox<String>();
        shellCombo.setItems(SHELL_AUTO, "/bin/bash", "/bin/sh", "/bin/ash");
        shellCombo.setValue(SHELL_AUTO);
        shellCombo.setWidth("180px");
        shellCombo.setTextInputAllowed(false);
        shellCombo.setEmptySelectionAllowed(false);
        // 与日志页签同样的道理：docker exec -it 也要现开一条带 pty 的 SSH 通道，
        // 打开页签就自动连接只会让人对着黑屏等十几秒。改成点「连接」再连。
        connectBtn = ComponentFactory.getPrimaryButtonWithType(CONNECT, com.so.component.util.ButtonType.PRIMARY);
        connectBtn.setWidth("110px");
        bar.addComponents(shellCombo, connectBtn);
        Label hint = ComponentFactory.getStandardLabel(
                "点「连接」后进入容器终端（等价于 ssh 到宿主机执行 docker exec -it <容器> <shell>，"
                        + "首次连接要十几秒）；退出该 shell 后本会话即结束。");
        hint.addStyleName("docker-hint");
        root.addComponent(bar);
        root.addComponent(hint);

        consoleMask = new LoadMask();
        consoleMask.setIdle("点击上方「" + CONNECT + "」按钮进入容器终端。<br/>"
                + "等价于在宿主机上执行 docker exec -it，首次连接要在目标机上新建一条带 pty 的 SSH 通道，<br/>"
                + "通常要十几秒，进度圈转完就会进入容器。");
        root.addComponent(consoleMask.getHost());
        root.setExpandRatio(consoleMask.getHost(), 1f);

        connectBtn.addClickListener(e -> reloadExecFrame());
        shellCombo.addValueChangeListener(e -> {
            // 还没连过时换 shell 只是改个选择，不用去建通道
            if (execReady) {
                reloadExecFrame();
            }
        });
        return root;
    }

    private void reloadExecFrame() {
        if (null == consoleMask) {
            return;
        }
        final int seq = ++execLoadSeq;
        execReady = false;
        connectBtn.setEnabled(false);
        consoleMask.setBusy("正在进入容器 " + cn.hutool.http.HtmlUtil.escape(container.getName()) + "…<br/>"
                + "首次连接要在目标机上新建一条带 pty 的 SSH 通道，请稍候（十几秒属正常）。");

        DockerTerminalRegistry.release(execToken);
        final DockerTerminalRegistry.Spec spec = new DockerTerminalRegistry.Spec(
                DockerTerminalRegistry.Kind.EXEC,
                executor.getInfo(),
                executor.getCommandPrefix(),
                container.getId(),
                container.getName(),
                -1,
                false,
                resolveShell());
        bindLoadCallbacks(spec, false, seq);
        execToken = DockerTerminalRegistry.register(spec);
        watchReadyTimeout(false, seq);
        // 交互终端需要 tty，服务端用 shell 通道 + pty，所以不能用 eol 模式
        consoleMask.load(TERMINAL_PAGE + "?ws=/ws/docker&token=" + execToken);
    }

    /* ================================================================== */
    /* 加载遮罩：iframe 里的 xterm 页面连上之前，别让用户对着黑屏等         */
    /* ================================================================== */

    /** 给一次加载挂上「连上了 / 没连上」两个回调 */
    private void bindLoadCallbacks(final DockerTerminalRegistry.Spec spec, final boolean logsTab, final int seq) {
        spec.setReadyListener(new Runnable() {
            @Override
            public void run() {
                onChannelReady(logsTab, seq);
            }
        });
        spec.setFailListener(new DockerTerminalRegistry.FailListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFail(String message) {
                onChannelFailed(logsTab, seq, message);
            }
        });
    }

    /** websocket 线程回调，切回 UI 线程再动界面 */
    private void onChannelReady(final boolean logsTab, final int seq) {
        safeAccess(new Runnable() {
            @Override
            public void run() {
                if (logsTab) {
                    if (seq != logLoadSeq || null == logsMask) {
                        return;
                    }
                    logReady = true;
                    logsMask.hide();
                    showLogsBtn.setCaption(RELOAD_LOGS);
                    showLogsBtn.setEnabled(true);
                } else {
                    if (seq != execLoadSeq || null == consoleMask) {
                        return;
                    }
                    execReady = true;
                    consoleMask.hide();
                    connectBtn.setCaption(RECONNECT);
                    connectBtn.setEnabled(true);
                }
            }
        });
    }

    private void onChannelFailed(final boolean logsTab, final int seq, final String message) {
        safeAccess(new Runnable() {
            @Override
            public void run() {
                // 遮罩文字走 ContentMode.HTML，而 message 是 SSH 异常原文（远端可控），必须转义
                String text = "连接失败：" + cn.hutool.http.HtmlUtil.escape(StrUtil.emptyToDefault(message, "未知原因"))
                        + "<br/>可以再点一次重试。";
                if (logsTab) {
                    if (seq != logLoadSeq || null == logsMask) {
                        return;
                    }
                    logsMask.setIdle(text);
                    showLogsBtn.setEnabled(true);
                } else {
                    if (seq != execLoadSeq || null == consoleMask) {
                        return;
                    }
                    consoleMask.setIdle(text);
                    connectBtn.setEnabled(true);
                }
            }
        });
    }

    /** 到点还没收到「已连上」就把遮罩文案换成排查提示，别让它一直转圈 */
    private void watchReadyTimeout(final boolean logsTab, final int seq) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(READY_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                safeAccess(new Runnable() {
                    @Override
                    public void run() {
                        String text = "等了 " + (READY_TIMEOUT_MS / 1000) + " 秒还没连上："
                                + "目标机可能网络较慢，或 docker 没有响应。<br/>"
                                + "可以再点一次上面的按钮重试；若一直连不上，"
                                + "请先到「SSH 管理」确认这台机器能正常登录。";
                        if (logsTab) {
                            if (seq != logLoadSeq || logReady || null == logsMask) {
                                return;
                            }
                            logsMask.setIdle(text);
                            showLogsBtn.setEnabled(true);
                        } else {
                            if (seq != execLoadSeq || execReady || null == consoleMask) {
                                return;
                            }
                            consoleMask.setIdle(text);
                            connectBtn.setEnabled(true);
                        }
                    }
                });
            }
        }, "docker-term-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * iframe + 加载遮罩。
     * <p>
     * 遮罩是<b>盖</b>在 iframe 上的，不是把 iframe 藏起来：{@code setVisible(false)}
     * 会让 Vaadin 把 iframe 从 DOM 里摘掉，websocket 根本不会发起，那就永远连不上了。
     * 所以两者都绝对定位铺满同一个容器，收到「通道就绪」回调后再把遮罩移除。
     */
    private class LoadMask implements Serializable {

        private static final long serialVersionUID = 1L;

        private final CssLayout host = new CssLayout();
        private final BrowserFrame frame = new BrowserFrame();
        private final CssLayout mask = new CssLayout();
        private final CssLayout maskInner = new CssLayout();
        private final Label maskText = new Label();
        private final ProgressBar busy = new ProgressBar();

        LoadMask() {
            host.setSizeFull();
            host.addStyleName("docker-term-host");

            frame.setSizeFull();
            frame.addStyleName("docker-term-frame");

            // 不确定态的 ProgressBar 在 Valo 主题里就是一个旋转的圆圈，
            // 尺寸由主题的 !important 规则决定，这里不要设宽高（设了会被 !important 压回去，
            // 反而变成 240x12 的椭圆）
            busy.setIndeterminate(true);
            busy.addStyleName("docker-load-bar");
            busy.setVisible(false);

            maskText.setContentMode(ContentMode.HTML);
            maskText.addStyleName("docker-term-mask-text");
            maskText.setWidth("100%");

            maskInner.addStyleName("docker-term-mask-inner");
            maskInner.addComponents(maskText, busy);

            mask.addStyleName("docker-term-mask");
            mask.addComponent(maskInner);

            host.addComponents(frame, mask);
        }

        CssLayout getHost() {
            return host;
        }

        /** 还没开始加载 / 加载完但需要提示：只有文字 */
        void setIdle(String html) {
            busy.setVisible(false);
            maskText.setValue(StrUtil.emptyToDefault(html, ""));
            mask.setVisible(true);
        }

        /** 正在加载：文字 + 圆形等待进度条 */
        void setBusy(String html) {
            maskText.setValue(StrUtil.emptyToDefault(html, ""));
            busy.setVisible(true);
            mask.setVisible(true);
        }

        void hide() {
            mask.setVisible(false);
        }

        void load(String url) {
            frame.setSource(new ExternalResource(url));
        }
    }

    private String resolveShell() {
        String chosen = shellCombo.getValue();
        if (StrUtil.isBlank(chosen) || SHELL_AUTO.equals(chosen)) {
            return detectedShell;
        }
        return chosen;
    }

    /* ================================================================== */
    /* 资源监控                                                            */
    /* ================================================================== */

    private VerticalLayout buildMonitorTab() {
        VerticalLayout root = fullLayout();

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setHeight("40px");
        bar.setWidth("100%");
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        monitorToggleBtn = ComponentFactory.getPrimaryButtonWithType("开始采集", com.so.component.util.ButtonType.PRIMARY);
        monitorToggleBtn.setWidth("110px");
        Button clear = ComponentFactory.getStandardButton("清空曲线");
        clear.setWidth("100px");
        bar.addComponents(monitorToggleBtn, clear);
        root.addComponent(bar);

        if (!container.isRunning()) {
            root.addComponent(ComponentFactory.getStandardLabel(
                    "容器未运行，docker stats 拿不到数据。启动容器后再回来查看。"));
            monitorToggleBtn.setEnabled(false);
            clear.setEnabled(false);
            return root;
        }

        statLabel = ComponentFactory.getStandardLabel("尚未开始采集");
        statLabel.addStyleName("docker-hint");
        root.addComponent(statLabel);

        usageChart = createChart("CPU / 内存使用率 (%)", "使用率 (%)", true);
        netChart = createChart("网络 IO", "速率 (KB/s)", false);
        cpuSeries = addSeries(usageChart, "CPU");
        memSeries = addSeries(usageChart, "内存");
        netInSeries = addSeries(netChart, "入站");
        netOutSeries = addSeries(netChart, "出站");
        // 系列是在初次 drawChart 之后挂上去的，这里再画一次让图例和空坐标系先出来
        usageChart.drawChart();
        netChart.drawChart();

        HorizontalLayout charts = new HorizontalLayout(usageChart, netChart);
        charts.setWidth("100%");
        charts.setHeight("290px");
        charts.setSpacing(true);
        root.addComponent(charts);
        root.setExpandRatio(charts, 1f);

        monitorToggleBtn.addClickListener(e -> toggleMonitor());
        clear.addClickListener(e -> clearSeries());
        return root;
    }

    private Chart createChart(String title, String yTitle, boolean percentAxis) {
        Chart chart = new Chart(ChartType.SPLINE);
        chart.setWidth("100%");
        chart.setHeight("280px");
        Configuration conf = chart.getConfiguration();
        conf.setTitle(title);
        XAxis xAxis = new XAxis();
        xAxis.setType(AxisType.DATETIME);
        conf.addxAxis(xAxis);
        YAxis yAxis = new YAxis();
        yAxis.setMin(0);
        yAxis.setTitle(yTitle);
        if (percentAxis) {
            yAxis.setMax(100);
        }
        conf.addyAxis(yAxis);
        conf.getLegend().setEnabled(true);
        chart.drawChart();
        return chart;
    }

    private DataSeries addSeries(Chart chart, String name) {
        PlotOptionsSpline plot = new PlotOptionsSpline();
        plot.setMarker(new Marker(false));
        DataSeries series = new DataSeries(name);
        series.setPlotOptions(plot);
        chart.getConfiguration().addSeries(series);
        return series;
    }

    private void clearSeries() {
        for (DataSeries series : Arrays.asList(cpuSeries, memSeries, netInSeries, netOutSeries)) {
            series.clear();
        }
        usageChart.drawChart();
        netChart.drawChart();
        lastStat = null;
        lastSampleAt = 0;
        statLabel.setValue("曲线已清空");
    }

    private void toggleMonitor() {
        if (monitoring) {
            monitoring = false;
            if (null != monitorThread) {
                monitorThread.interrupt();
                monitorThread = null;
            }
            monitorToggleBtn.setCaption("开始采集");
            statLabel.setValue("采集已停止" + (null == lastStat ? "" : "　最后一次：" + describe(lastStat)));
            return;
        }
        monitoring = true;
        monitorToggleBtn.setCaption("停止采集");
        monitorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (monitoring) {
                    try {
                        Thread.sleep(MONITOR_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!monitoring) {
                        return;
                    }
                    final DockerStat stat;
                    try {
                        List<DockerStat> list = service.stats(Collections.singletonList(container.getId()));
                        stat = list.isEmpty() ? null : list.get(0);
                    } catch (final Exception e) {
                        monitoring = false;
                        safeAccess(new Runnable() {
                            @Override
                            public void run() {
                                monitorToggleBtn.setCaption("开始采集");
                                statLabel.setValue("采集中断：" + DockerUi.reason(e));
                            }
                        });
                        return;
                    }
                    if (null == stat) {
                        continue;
                    }
                    final long now = System.currentTimeMillis();
                    // 网络速率要用两次采样的差值算，第一次没有基准，记 0
                    long deltaMs = (lastSampleAt == 0) ? MONITOR_INTERVAL_MS : (now - lastSampleAt);
                    double netIn = 0d;
                    double netOut = 0d;
                    if (null != lastStat && deltaMs > 0) {
                        netIn = Math.max(0, stat.getNetInputBytes() - lastStat.getNetInputBytes()) * 1000d / deltaMs / 1024d;
                        netOut = Math.max(0, stat.getNetOutputBytes() - lastStat.getNetOutputBytes()) * 1000d / deltaMs / 1024d;
                    }
                    final double finalNetIn = netIn;
                    final double finalNetOut = netOut;
                    lastStat = stat;
                    lastSampleAt = now;

                    safeAccess(new Runnable() {
                        @Override
                        public void run() {
                            Date at = new Date(now);
                            // 不传 shift：曲线保留本次打开窗口后的全部采样点，停止采集后还能回看
                            cpuSeries.add(new DataSeriesItem(at, round(stat.getCpuPercent())), true, false);
                            memSeries.add(new DataSeriesItem(at, round(stat.getMemPercent())), true, false);
                            netInSeries.add(new DataSeriesItem(at, round(finalNetIn)), true, false);
                            netOutSeries.add(new DataSeriesItem(at, round(finalNetOut)), true, false);
                            statLabel.setValue(describe(stat) + "　网络入 " + NumberUtil.round(finalNetIn, 1)
                                    + " KB/s，出 " + NumberUtil.round(finalNetOut, 1) + " KB/s");
                        }
                    });
                }
            }
        }, "docker-stats-" + container.getShortId());
        monitorThread.setDaemon(true);
        monitorThread.start();
        statLabel.setValue("采集中（每 " + (MONITOR_INTERVAL_MS / 1000) + " 秒一次）…");
    }

    private void safeAccess(Runnable runnable) {
        try {
            ui.access(runnable);
        } catch (Exception e) {
            log.debug("UI 已关闭，跳过监控刷新：{}", e.getMessage());
        }
    }

    private static double round(double value) {
        return NumberUtil.round(value, 2).doubleValue();
    }

    private static String describe(DockerStat stat) {
        return "CPU " + stat.getCpuText()
                + "　内存 " + stat.getMemUsageText() + "（" + stat.getMemPercentText() + "）"
                + "　块IO " + stat.getBlockIoText();
    }

    /* ================================================================== */
    /* 容器内文件                                                          */
    /* ================================================================== */

    private VerticalLayout buildFileTab() {
        VerticalLayout root = fullLayout();

        if (!container.isRunning()) {
            root.addComponent(ComponentFactory.getStandardLabel(
                    "列出容器内目录需要执行 docker exec，容器未运行时不可用（已停止容器的文件"
                            + "仍可通过 docker cp 导出，需要时请在宿主机上手工操作）。"));
            return root;
        }

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setHeight("40px");
        bar.setWidth("100%");
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        Button up = ComponentFactory.getImageButton();
        up.setIcon(VaadinIcons.BACKSPACE);
        up.setWidth("40px");
        pathField = ComponentFactory.getStandardTtextField();
        pathField.setWidth("360px");
        pathField.setValue("/");
        Button go = ComponentFactory.getStandardButton("进入");
        go.setWidth("80px");
        Button refresh = ComponentFactory.getStandardButton("刷新");
        refresh.setWidth("80px");

        TempFileReceiver receiver = new TempFileReceiver();
        Upload upload = new Upload("上传到此目录", receiver);
        upload.setButtonCaption("上传文件");
        upload.setHeight("30px");
        upload.addSucceededListener(receiver);

        bar.addComponents(up, pathField, go, refresh, upload);
        root.addComponent(bar);

        fileStatus = ComponentFactory.getStandardLabel("");
        fileStatus.addStyleName("docker-hint");
        root.addComponent(fileStatus);

        fileGrid = new Grid<DockerFileInfo>();
        fileGrid.setSizeFull();
        fileGrid.addStyleName("grid_standard");
        fileGrid.addComponentColumn(file -> {
            if (file.isDirectory()) {
                Button into = ComponentFactory.getLinkButton(file.getName() + "/");
                into.addClickListener(e -> navigateTo(file.getPath()));
                return into;
            }
            Label name = ComponentFactory.getStandardLabel(file.getName());
            if (file.isSymlink()) {
                name.addStyleName("docker-symlink");
            }
            return name;
        }).setCaption("名称").setWidth(280);
        fileGrid.addColumn(DockerFileInfo::getPermission).setCaption("权限").setWidth(120);
        fileGrid.addColumn(DockerFileInfo::getOwner).setCaption("属主").setWidth(90);
        fileGrid.addColumn(DockerFileInfo::getSize).setCaption("大小").setWidth(100);
        fileGrid.addColumn(DockerFileInfo::getModifyTime).setCaption("修改时间").setWidth(140);
        fileGrid.addComponentColumn(this::buildFileActions).setCaption("操作").setWidth(100);

        root.addComponent(fileGrid);
        root.setExpandRatio(fileGrid, 1f);

        up.addClickListener(e -> goUp());
        go.addClickListener(e -> navigateTo(pathField.getValue()));
        refresh.addClickListener(e -> navigateTo(pathField.getValue()));
        return root;
    }

    private HorizontalLayout buildFileActions(DockerFileInfo file) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        if (file.isDirectory()) {
            return actions;
        }
        Button download = ComponentFactory.getLinkButton("下载");
        StreamResource resource = new StreamResource(new ContainerFileStreamSource(file), file.getName());
        resource.setCacheTime(0);
        FileDownloader downloader = new FileDownloader(resource);
        downloader.extend(download);
        actions.addComponent(download);
        return actions;
    }

    private void navigateTo(String path) {
        final String target = StrUtil.isBlank(path) ? "/" : path.trim();
        DockerUi.async("读取容器目录 " + target, new DockerUi.Task<List<DockerFileInfo>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public List<DockerFileInfo> run() throws Exception {
                return service.listContainerFiles(container.getId(), target);
            }
        }, new DockerUi.Done<List<DockerFileInfo>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(List<DockerFileInfo> files) {
                fileGrid.setItems(files);
                pathField.setValue(target);
                fileStatus.setValue("当前目录 " + target + "　共 " + files.size() + " 项"
                        + "　（这里是容器内的路径，和宿主机的目录不是一回事）");
            }
        });
    }

    private void goUp() {
        String current = pathField.getValue();
        if (StrUtil.isBlank(current) || "/".equals(current.trim())) {
            Notification.show("已经是根目录", Notification.Type.HUMANIZED_MESSAGE);
            return;
        }
        String trimmed = current.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int idx = trimmed.lastIndexOf('/');
        navigateTo(idx <= 0 ? "/" : trimmed.substring(0, idx));
    }

    /** 下载容器内文件：先 docker cp 到宿主机临时文件，SFTP 拉回后流给浏览器，读完即删 */
    private class ContainerFileStreamSource implements StreamResource.StreamSource {

        private static final long serialVersionUID = 1L;

        private final DockerFileInfo file;

        ContainerFileStreamSource(DockerFileInfo file) {
            this.file = file;
        }

        @Override
        public InputStream getStream() {
            try {
                final File local = service.downloadContainerFile(container.getId(), file.getPath(), file.getName());
                return new FilterInputStream(new FileInputStream(local)) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        try {
                            Files.deleteIfExists(local.toPath());
                        } catch (IOException e) {
                            log.warn("删除本地临时文件 {} 失败：{}", local.getAbsolutePath(), e.getMessage());
                        }
                    }
                };
            } catch (Exception e) {
                // 同上：下载请求线程上不能弹 Notification
                log.error("下载容器 {} 的文件 {} 失败：{}", container.getName(), file.getPath(), e.getMessage());
                return null;
            }
        }
    }

    /**
     * 上传：Vaadin 先把文件收到本机临时目录，上传完成后推到容器里。
     * <p>
     * 不直接边收边推，是因为 {@code docker cp} 需要宿主机上有一个完整的文件；
     * 收完再推，出错时至少能明确是"上传到宿主机失败"还是"拷进容器失败"。
     */
    private class TempFileReceiver implements Upload.Receiver, Upload.SucceededListener {

        private static final long serialVersionUID = 1L;

        private File target;

        @Override
        public OutputStream receiveUpload(String filename, String mimeType) {
            try {
                File dir = new File(System.getProperty("java.io.tmpdir"), "logviewer-docker-upload");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建临时目录：" + dir.getAbsolutePath());
                }
                target = new File(dir, DockerService.sanitizeFileName(filename));
                return new FileOutputStream(target);
            } catch (IOException e) {
                log.error("接收上传文件失败：{}", e.getMessage());
                Notification.show("接收上传文件失败：" + e.getMessage(), Notification.Type.ERROR_MESSAGE);
                return null;
            }
        }

        @Override
        public void uploadSucceeded(Upload.SucceededEvent event) {
            if (null == target) {
                return;
            }
            final File localFile = target;
            final String dir = StrUtil.emptyToDefault(pathField.getValue(), "/");
            DockerUi.async("上传文件到容器", new DockerUi.Task<Void>() {
                private static final long serialVersionUID = 1L;

                @Override
                public Void run() throws Exception {
                    try {
                        service.uploadContainerFile(container.getId(), dir, localFile);
                    } finally {
                        Files.deleteIfExists(localFile.toPath());
                    }
                    return null;
                }
            }, new DockerUi.Done<Void>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(Void value) {
                    Notification.show("已上传到 " + dir, Notification.Type.HUMANIZED_MESSAGE);
                    navigateTo(dir);
                }
            });
        }
    }

    /* ================================================================== */

    private VerticalLayout fullLayout() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setMargin(true);
        layout.setSpacing(true);
        return layout;
    }

}
