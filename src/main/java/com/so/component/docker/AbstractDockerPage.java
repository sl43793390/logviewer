package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.component.CommonComponent;
import com.so.docker.DockerDaemonDownException;
import com.so.docker.DockerExecutor;
import com.so.docker.DockerService;
import com.so.docker.model.DockerDaemonStatus;
import com.so.ui.ComponentFactory;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * Docker 管理页面的公共骨架。
 * <p>
 * <b>所有 docker 命令都在后台线程里跑。</b>docker CLI 每条命令都是 100ms 起步的远程调用，
 * 放在 Vaadin 请求线程里会直接把界面卡死；而且这个项目开了 {@code @Push}，
 * 后台线程做完之后用 {@code UI.access(...)} 回主线程刷界面是标准做法。
 * <p>
 * 页面的生命周期仍然是 {@code initLayout → initContent → registerHandler}：
 * {@code initContent} 只负责"发起一次异步加载"，不等数据回来，
 * 所以打开页面的动作是立刻返回的，数据到了再自己往 Grid 里塞。
 * <p>
 * <b>失败后不再无限重试。</b>子类如果覆写 {@link #onFailure(Exception)} 并在里面
 * {@code reload()}，必须先用 {@link #allowAutoReload()} 封顶：目标机 docker 没运行时，
 * "加载失败 → 重新加载 → 又失败"会变成死循环（2026-09-22 的日志里 60ms 一条 docker 命令）。
 * 连接类故障（{@link #isConnectivityFailure(Throwable)}）一律不重试，
 * 交给 {@link DockerMgmtComponent#promptDockerUnavailable} 弹窗处理。
 */
public abstract class AbstractDockerPage extends CommonComponent {

    private static final long serialVersionUID = 1L;

    /** 一次加载最多允许自动重试几轮；超过就停手，把决定权还给用户 */
    private static final int MAX_AUTO_RELOAD = 1;

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final DockerMgmtComponent owner;
    protected final DockerExecutor executor;
    protected final DockerService service;

    protected Panel mainPanel;
    protected VerticalLayout contentLayout;
    protected HorizontalLayout toolbar;
    protected Label statusLabel;

    /** 连续失败次数，成功一次就清零 */
    private int consecutiveFailures;

    protected AbstractDockerPage(DockerMgmtComponent owner, DockerExecutor executor) {
        this.owner = owner;
        this.executor = executor;
        this.service = new DockerService(executor);
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void initLayout() {
        mainPanel = new Panel();
        mainPanel.setSizeFull();
        setCompositionRoot(mainPanel);
        setSizeFull();

        contentLayout = new VerticalLayout();
        contentLayout.setSizeFull();
        contentLayout.setMargin(false);
        contentLayout.setSpacing(false);
        mainPanel.setContent(contentLayout);

        toolbar = new HorizontalLayout();
        toolbar.setWidth("100%");
        toolbar.setHeight("44px");
        toolbar.setSpacing(true);
        toolbar.addStyleName("docker-toolbar");
        toolbar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        contentLayout.addComponent(toolbar);

        statusLabel = ComponentFactory.getStandardLabel("");
        statusLabel.addStyleName("docker-status");
        statusLabel.setWidth("100%");
        HorizontalLayout statusRow = new HorizontalLayout(statusLabel);
        statusRow.setWidth("100%");
        statusRow.setHeight("24px");
        contentLayout.addComponent(statusRow);

        buildToolbar(toolbar);
    }

    @Override
    public void initContent() {
        reload();
    }

    @Override
    public void registerHandler() {
        // 按钮在 buildToolbar 里直接绑定，这里没有额外的延迟绑定
    }

    /** 构建工具栏按钮 */
    protected abstract void buildToolbar(HorizontalLayout toolbar);

    /** 异步加载数据并刷新界面 */
    protected abstract void reload();

    /** 页面关闭时释放资源，子类按需覆写 */
    protected void onDetach() {
    }

    @Override
    public void detach() {
        try {
            onDetach();
        } catch (Exception e) {
            log.warn("关闭 {} 时出错：{}", getClass().getSimpleName(), e.getMessage());
        }
        super.detach();
    }

    /* ------------------------------------------------------------------ */
    /* 异步执行                                                            */
    /* ------------------------------------------------------------------ */

    /** 后台任务，允许抛受检异常 */
    protected interface DockerTask<T> extends DockerUi.Task<T> {
    }

    /** 没有返回值的后台任务（start / stop / rm 这类写操作） */
    protected interface DockerAction extends Serializable {
        void run() throws Exception;
    }

    /** 任务成功后在 UI 线程上执行的回调 */
    protected interface DockerTaskDone<T> extends DockerUi.Done<T> {
    }

    /**
     * 在后台线程执行任务，成功后回 UI 线程执行 onDone。
     * <p>
     * 线程与 {@code UI.access} 的细节统一交给 {@link DockerUi}，
     * 这里只负责把「正在执行」的提示挂到页面顶部的状态条上。
     */
    protected <T> void runAsync(String action, DockerTask<T> task, final DockerTaskDone<T> onDone) {
        // 连接已经断掉（用户切走了标签页）就别再起线程了，
        // 否则只会在日志里留下一堆 Disconnected
        if (null == executor || executor.isClosed()) {
            showStatus("连接已断开，请重新点「连接」建立通道后再试。");
            return;
        }
        DockerTaskDone<T> wrapper = new DockerTaskDone<T>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(T value) throws Exception {
                consecutiveFailures = 0;
                if (null != onDone) {
                    onDone.done(value);
                }
            }
        };
        DockerUi.async(action, task, wrapper, new DockerUi.Busy() {
            private static final long serialVersionUID = 1L;

            @Override
            public void setBusy(boolean busy, String text) {
                owner.setBusy(busy, text);
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                consecutiveFailures++;
                AbstractDockerPage.this.onFailure(e);
            }
        });
    }

    /** 无返回值的后台任务（写操作） */
    protected void runAction(final String action, final DockerAction task) {
        runAsync(action, new DockerTask<Void>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Void run() throws Exception {
                task.run();
                return null;
            }
        }, null);
    }

    /** 任务失败后回 UI 线程，子类可覆写做兜底刷新 */
    protected void onFailure(Exception e) {
        if (DockerUi.isDaemonDown(e)) {
            DockerDaemonStatus status = daemonStatusOf(e);
            showStatus(null == status ? DockerUi.daemonDownHint : status.summary());
            if (null != owner) {
                owner.promptDockerUnavailable(status);
            }
        }
    }

    /**
     * 还允许自动重试吗？
     * <p>
     * 每次失败会自增计数，成功后清零。第一次失败可以兜底刷新一次，
     * 再失败就停手 —— 这是防止"失败即重载"死循环的最后一道闸。
     */
    protected boolean allowAutoReload() {
        return consecutiveFailures <= MAX_AUTO_RELOAD;
    }

    /**
     * 是不是"连接层面"的故障：daemon 没运行、SSH 断了、连接被关掉。
     * 这类情况重试一万次也是一样的结果，只能让用户去处理。
     */
    protected boolean isConnectivityFailure(Throwable e) {
        if (DockerUi.isDaemonDown(e)) {
            return true;
        }
        String message = StrUtil.trimToEmpty(null == e ? null : e.getMessage());
        String lower = message.toLowerCase();
        return lower.contains("disconnected")
                || lower.contains("connection reset")
                || lower.contains("broken pipe")
                || lower.contains("cannot connect to the docker daemon")
                || message.contains("连接已关闭")
                || message.contains("连接未建立")
                || message.contains("SSH 连接未建立");
    }

    /** 从异常里把 daemon 状态抠出来（可能是直接抛出，也可能被包了一层） */
    protected static DockerDaemonStatus daemonStatusOf(Throwable e) {
        if (e instanceof DockerDaemonDownException) {
            return ((DockerDaemonDownException) e).getStatus();
        }
        Throwable cause = (null == e) ? null : e.getCause();
        if (cause instanceof DockerDaemonDownException) {
            return ((DockerDaemonDownException) cause).getStatus();
        }
        return null;
    }

    /** 用户主动点「刷新」：顺手把 daemon 状态缓存也清掉，否则会拿着 10 秒前的"不可用"继续快失败 */
    protected void userReload() {
        if (null != executor) {
            executor.invalidateDaemon();
        }
        reload();
    }

    protected void notifyError(String prefix, Exception e) {
        Notification.show(prefix + "：" + reason(e), Notification.Type.ERROR_MESSAGE);
    }

    protected static String reason(Throwable e) {
        return DockerUi.reason(e);
    }

    protected void showStatus(String text) {
        if (null != statusLabel) {
            statusLabel.setValue(StrUtil.emptyToDefault(text, ""));
        }
    }

    /**
     * 把弹窗挂到当前 UI 上。
     * <p>
     * Vaadin 8 的 {@code Window} 没有被 {@code show()} 这个 API（那是 Vaadin 7 的写法），
     * 必须走 {@code UI.addWindow}。统一收在这里，是因为这个项目里所有弹窗都继承
     * {@code Window} 而不是自己的 {@code PopupWindow}，逐个类补一个 show() 太啰嗦。
     */
    protected void showWindow(Window window) {
        if (null == window) {
            return;
        }
        UI ui = UI.getCurrent();
        if (null == ui) {
            log.warn("没有可用的 UI，无法打开弹窗：{}", window.getCaption());
            return;
        }
        ui.addWindow(window);
    }
}
