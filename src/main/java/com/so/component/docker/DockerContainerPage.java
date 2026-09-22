package com.so.component.docker;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.so.component.util.ColorEnum;
import com.so.docker.DockerExecutor;
import com.so.docker.model.DockerContainer;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.UI;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器管理。
 * <p>
 * 列表固定用 {@code docker ps -a} 拉全量，状态筛选在本地做：一次远程调用就够，
 * 比按筛选条件分别发命令少一趟往返，切换筛选也不用再等网络。
 */
public class DockerContainerPage extends AbstractDockerPage {

    private static final long serialVersionUID = 1L;

    private static final String FILTER_RUNNING = "运行中";
    private static final String FILTER_STOPPED = "已停止";
    private static final String FILTER_ALL = "全部";

    private Grid<DockerContainer> grid;
    private ComboBox<String> filterCombo;
    private Button refreshBtn;
    private Button createBtn;
    private Button batchDeleteBtn;
    private Button pruneBtn;

    private List<DockerContainer> allContainers = new ArrayList<DockerContainer>();

    public DockerContainerPage(DockerMgmtComponent owner, DockerExecutor executor) {
        super(owner, executor);
    }

    @Override
    protected void buildToolbar(HorizontalLayout toolbar) {
        filterCombo = new ComboBox<String>();
        filterCombo.setItems(FILTER_RUNNING, FILTER_STOPPED, FILTER_ALL);
        filterCombo.setValue(FILTER_ALL);
        filterCombo.setWidth("130px");
        filterCombo.addStyleName("field_box_standard_height");
        filterCombo.setTextInputAllowed(false);
        filterCombo.setEmptySelectionAllowed(false);

        refreshBtn = ComponentFactory.getStandardButton("刷新");
        refreshBtn.setWidth("80px");
        createBtn = ComponentFactory.getPrimaryButtonWithType("创建容器", com.so.component.util.ButtonType.SUCCESS);
        createBtn.setWidth("110px");
        batchDeleteBtn = ComponentFactory.getButtonWithColor("批量删除", ColorEnum.RED);
        batchDeleteBtn.setWidth("100px");
        pruneBtn = ComponentFactory.getStandardButton("清理已停止");
        pruneBtn.setWidth("110px");

        for (Button button : new Button[]{refreshBtn, createBtn, batchDeleteBtn, pruneBtn}) {
            button.setHeight("30px");
            toolbar.addComponent(button);
        }
        toolbar.setExpandRatio(pruneBtn, 1f);
        toolbar.addComponent(filterCombo);
        toolbar.addComponent(ComponentFactory.getStandardLabel("状态筛选"));

        buildGrid();
        contentLayout.addComponent(grid);
        contentLayout.setExpandRatio(grid, 1f);

        refreshBtn.addClickListener(e -> userReload());
        filterCombo.addValueChangeListener(e -> applyFilter());
        createBtn.addClickListener(e -> new DockerCreateContainerWindow(owner, this, service).show());
        batchDeleteBtn.addClickListener(e -> batchDelete());
        pruneBtn.addClickListener(e -> pruneStopped());
    }

    private void buildGrid() {
        grid = new Grid<DockerContainer>();
        grid.setWidthFull();
        grid.setHeightFull();
        grid.addStyleName("grid_standard");
        grid.setSelectionMode(Grid.SelectionMode.MULTI);

        grid.addColumn(DockerContainer::getName).setCaption("名称").setWidth(200);
        grid.addColumn(DockerContainer::getImage).setCaption("镜像").setWidth(220);

        // 状态列显示中文状态 + docker 原始 Status（"Up 3 hours" 这种能看到运行了多久）
        grid.addComponentColumn(container -> {
            Label state = ComponentFactory.getStandardLabel(container.getStateLabel());
            state.setWidth("70px");
            if (container.isRunning()) {
                state.addStyleName("docker-state-running");
            } else if (container.isPaused()) {
                state.addStyleName("docker-state-paused");
            } else {
                state.addStyleName("docker-state-stopped");
            }
            Label detail = ComponentFactory.getStandardLabel(StrUtil.emptyToDefault(container.getStatus(), ""));
            HorizontalLayout row = new HorizontalLayout(state, detail);
            row.setSpacing(true);
            return row;
        }).setCaption("状态").setWidth(250);

        grid.addColumn(container -> StrUtil.emptyToDefault(container.getPorts(), "-"))
                .setCaption("端口映射").setWidth(280);
        grid.addColumn(DockerContainer::getCreatedAt).setCaption("创建时间").setWidth(200);

        grid.addComponentColumn(this::buildRowActions).setCaption("操作").setWidth(330);
    }

    private HorizontalLayout buildRowActions(DockerContainer container) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        actions.addComponent(linkButton("详情", e -> new DockerContainerDetailWindow(container, service).show()));

        if (container.isUp()) {
            actions.addComponent(linkButton("停止", e -> simpleAction("停止容器 " + container.getName(),
                    () -> service.stopContainer(container.getId()))));
        } else {
            actions.addComponent(linkButton("启动", e -> simpleAction("启动容器 " + container.getName(),
                    () -> service.startContainer(container.getId()))));
        }
        if (container.isRunning()) {
            actions.addComponent(linkButton("重启", e -> simpleAction("重启容器 " + container.getName(),
                    () -> service.restartContainer(container.getId()))));
        }
        if (container.isPaused()) {
            actions.addComponent(linkButton("恢复", e -> simpleAction("恢复容器 " + container.getName(),
                    () -> service.unpauseContainer(container.getId()))));
        } else if (container.isRunning()) {
            actions.addComponent(linkButton("暂停", e -> simpleAction("暂停容器 " + container.getName(),
                    () -> service.pauseContainer(container.getId()))));
        }
        Button delete = ComponentFactory.getLinkButton("删除");
        delete.addClickListener(e -> confirmDelete(java.util.Collections.singletonList(container)));
        actions.addComponent(delete);
        return actions;
    }

    private Button linkButton(String caption, Button.ClickListener listener) {
        Button button = ComponentFactory.getLinkButton(caption);
        button.addClickListener(listener);
        return button;
    }

    /** 启停之类的一步操作：执行成功后刷新列表 */
    private void simpleAction(String action, DockerAction task) {
        runAction(action, task);
    }

    @Override
    protected void onFailure(Exception e) {
        /*
         * 这里原来是无条件 reload()，而 reload() 失败又会回调回来 ——
         * daemon 没启动时就是"reload → 失败 → reload"的死循环，
         * 60ms 就往目标机砸一条 ssh 命令。所以分两类处理：
         *
         * 1) 连接类故障（daemon 未运行、SSH 已断开）：一个字节都不发，
         *    交给父类去弹「是否启动 Docker 服务」；
         * 2) 普通失败（比如容器刚被别人删掉）：允许兜底刷新一次，
         *    第二次就停手，把决定权还给用户。
         */
        if (isConnectivityFailure(e)) {
            super.onFailure(e);
            return;
        }
        if (allowAutoReload()) {
            reload();
        } else {
            showStatus("刷新失败，已停止自动重试，请点「刷新」重新加载。");
        }
    }

    @Override
    protected void reload() {
        runAsync("读取容器列表", new DockerTask<List<DockerContainer>>() {
            @Override
            public List<DockerContainer> run() throws Exception {
                return service.listContainers(true);
            }
        }, new DockerTaskDone<List<DockerContainer>>() {
            @Override
            public void done(List<DockerContainer> containers) {
                allContainers = containers;
                applyFilter();
            }
        });
    }

    private void applyFilter() {
        String filter = filterCombo.getValue();
        List<DockerContainer> visible = new ArrayList<DockerContainer>();
        for (DockerContainer container : allContainers) {
            if (FILTER_RUNNING.equals(filter)) {
                if (container.isUp()) {
                    visible.add(container);
                }
            } else if (FILTER_STOPPED.equals(filter)) {
                if (!container.isUp()) {
                    visible.add(container);
                }
            } else {
                visible.add(container);
            }
        }
        grid.setItems(visible);

        int running = 0;
        int paused = 0;
        for (DockerContainer container : allContainers) {
            if (container.isRunning()) {
                running++;
            } else if (container.isPaused()) {
                paused++;
            }
        }
        showStatus("共 " + allContainers.size() + " 个容器：运行 " + running + "，暂停 " + paused
                + "，停止 " + (allContainers.size() - running - paused)
                + "　（当前显示 " + visible.size() + " 个）");
    }

    /* ------------------------------------------------------------------ */
    /* 删除                                                                */
    /* ------------------------------------------------------------------ */

    private void batchDelete() {
        java.util.Set<DockerContainer> selected = grid.getSelectedItems();
        if (CollectionUtil.isEmpty(selected)) {
            Notification.show("请先勾选要删除的容器", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (!LoginView.checkPermission(Constants.DELETE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        confirmDelete(new ArrayList<DockerContainer>(selected));
    }

    private void confirmDelete(final List<DockerContainer> targets) {
        if (!LoginView.checkPermission(Constants.DELETE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (DockerContainer container : targets) {
            names.append(container.getName()).append("（").append(container.getShortId()).append("）<br/>");
        }
        StringBuilder html = new StringBuilder("<h3>即将删除以下 ").append(targets.size()).append(" 个容器</h3>")
                .append(names)
                .append("<br/>运行中的容器必须勾选「强制删除」才会被强杀后移除，操作不可撤销。");

        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("删除容器", html.toString(), "确认删除", "取消", true);
        final com.vaadin.ui.CheckBox forceBox = new com.vaadin.ui.CheckBox("强制删除（-f，运行中也会被强杀）");
        final com.vaadin.ui.CheckBox volumeBox = new com.vaadin.ui.CheckBox("同时删除匿名数据卷（-v）");
        forceBox.addStyleName("docker-dialog-checkbox");
        volumeBox.addStyleName("docker-dialog-checkbox");
        // index 1 插在「提示文案」和「确认/取消按钮」之间，不要加在按钮下面
        win.getLayout().addComponent(forceBox, 1);
        win.getLayout().addComponent(volumeBox, 2);

        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                final boolean force = forceBox.getValue();
                final boolean removeVolumes = volumeBox.getValue();
                win.close();
                runAsync("删除容器", new DockerTask<String>() {
                    @Override
                    public String run() throws Exception {
                        StringBuilder report = new StringBuilder();
                        for (DockerContainer container : targets) {
                            try {
                                service.removeContainer(container.getId(), force, removeVolumes);
                                report.append("已删除 ").append(container.getName()).append("；");
                                log.info("用户 {} 删除了容器 {}（force={}）",
                                        com.so.component.ComponentUtil.getCurrentUserName(), container.getName(), force);
                            } catch (Exception e) {
                                report.append(container.getName()).append(" 删除失败（")
                                        .append(reason(e)).append("）；");
                            }
                        }
                        return report.toString();
                    }
                }, new DockerTaskDone<String>() {
                    @Override
                    public void done(String report) {
                        Notification.show(report, Notification.Type.HUMANIZED_MESSAGE);
                        reload();
                    }
                });
            }
        });
        win.showConfirmation();
    }

    private void pruneStopped() {
        if (!LoginView.checkPermission(Constants.DELETE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("清理已停止容器",
                        "将执行 <b>docker container prune -f</b>，删除所有已停止的容器及其匿名卷。<br/>操作不可撤销。",
                        "执行清理", "取消", true);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                win.close();
                runAsync("清理已停止容器", new DockerTask<String>() {
                    @Override
                    public String run() throws Exception {
                        return service.prune("containers");
                    }
                }, new DockerTaskDone<String>() {
                    @Override
                    public void done(String result) {
                        Notification.show("清理完成：" + StrUtil.emptyToDefault(result, "无输出"),
                                Notification.Type.HUMANIZED_MESSAGE);
                        reload();
                    }
                });
            }
        });
        win.showConfirmation();
    }

    /** 供创建容器弹窗在成功后回调 */
    void onContainerCreated() {
        reload();
    }
}
