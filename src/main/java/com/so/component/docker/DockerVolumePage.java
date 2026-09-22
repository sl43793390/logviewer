package com.so.component.docker;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.so.component.util.ColorEnum;
import com.so.docker.DockerExecutor;
import com.so.docker.model.DockerVolume;
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
import com.vaadin.ui.TextArea;
import com.vaadin.ui.TextField;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 数据卷管理：列表、创建、删除、查看挂载容器、清理未使用卷。
 * <p>
 * 卷的「被谁用着」不是 docker 直接给的能力：{@code docker volume ls} 只有名字和驱动，
 * 要判断在用必须反查容器。所以列表加载时一次性拉一遍 {@code docker ps -a}，
 * 在内存里把 name 映射成容器名列表（不是逐个卷去 inspect，N 个卷等于 N 次 SSH 往返，
 * 机器一多就是十几秒）。
 */
public class DockerVolumePage extends AbstractDockerPage {

    private static final long serialVersionUID = 1L;

    private Grid<DockerVolume> grid;
    private CheckBox showUsageBox;
    private List<DockerVolume> allVolumes = new ArrayList<DockerVolume>();

    public DockerVolumePage(DockerMgmtComponent owner, DockerExecutor executor) {
        super(owner, executor);
    }

    @Override
    protected void buildToolbar(HorizontalLayout toolbar) {
        Button refresh = ComponentFactory.getStandardButton("刷新");
        refresh.setWidth("80px");
        Button create = ComponentFactory.getPrimaryButtonWithType("创建卷",
                com.so.component.util.ButtonType.PRIMARY);
        create.setWidth("100px");
        Button batchDelete = ComponentFactory.getButtonWithColor("删除选中", ColorEnum.RED);
        batchDelete.setWidth("100px");
        Button prune = ComponentFactory.getButtonWithColor("清理未使用卷", ColorEnum.YELLOW);
        prune.setWidth("120px");

        for (Button button : new Button[]{refresh, create, batchDelete, prune}) {
            button.setHeight("30px");
            toolbar.addComponent(button);
        }

        showUsageBox = new CheckBox("统计挂载容器");
        showUsageBox.setValue(Boolean.TRUE);
        toolbar.addComponent(showUsageBox);
        toolbar.setExpandRatio(showUsageBox, 1f);

        buildGrid();
        contentLayout.addComponent(grid);
        contentLayout.setExpandRatio(grid, 1f);

        refresh.addClickListener(e -> reload());
        showUsageBox.addValueChangeListener(e -> reload());
        create.addClickListener(e -> showWindow(new CreateVolumeWindow()));
        batchDelete.addClickListener(e -> confirmDelete(selectedVolumes()));
        prune.addClickListener(e -> confirmPrune());
    }

    private void buildGrid() {
        grid = new Grid<DockerVolume>();
        grid.setSizeFull();
        grid.addStyleName("grid_standard");
        grid.setSelectionMode(Grid.SelectionMode.MULTI);

        grid.addColumn(DockerVolume::getName).setCaption("卷名").setWidth(260);
        grid.addColumn(DockerVolume::getDriver).setCaption("驱动").setWidth(90);
        grid.addColumn(DockerVolume::getScope).setCaption("范围").setWidth(90);
        grid.addColumn(DockerVolume::getMountpoint).setCaption("宿主机路径").setWidth(320);
        grid.addColumn(DockerVolume::getUsedByText).setCaption("挂载容器").setWidth(220);
        grid.addColumn(DockerVolume::getCreatedAt).setCaption("创建时间").setWidth(190);
        grid.addComponentColumn(this::buildRowActions).setCaption("操作").setWidth(170);
    }

    private HorizontalLayout buildRowActions(DockerVolume volume) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        Button detail = ComponentFactory.getLinkButton("详情");
        detail.addClickListener(e -> showWindow(new VolumeDetailWindow(volume)));
        actions.addComponent(detail);

        Button delete = ComponentFactory.getLinkButton("删除");
        delete.addClickListener(e -> confirmDelete(Collections.singletonList(volume)));
        actions.addComponent(delete);
        return actions;
    }

    private List<DockerVolume> selectedVolumes() {
        Set<DockerVolume> selected = grid.getSelectedItems();
        return new ArrayList<DockerVolume>(selected);
    }

    @Override
    protected void reload() {
        final boolean withUsage = Boolean.TRUE.equals(showUsageBox.getValue());
        runAsync("读取数据卷", new DockerTask<List<DockerVolume>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public List<DockerVolume> run() throws Exception {
                return service.listVolumes(withUsage);
            }
        }, new DockerTaskDone<List<DockerVolume>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(List<DockerVolume> volumes) {
                allVolumes = volumes;
                grid.setItems(volumes);
                int free = 0;
                for (DockerVolume volume : volumes) {
                    if (CollectionUtil.isEmpty(volume.getUsedBy())) {
                        free++;
                    }
                }
                showStatus("共 " + volumes.size() + " 个数据卷，其中未被任何容器挂载的 " + free
                        + " 个（可用「清理未使用卷」回收）");
                grid.deselectAll();
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 删除 / 清理                                                         */
    /* ------------------------------------------------------------------ */

    private void confirmDelete(final List<DockerVolume> targets) {
        if (!LoginView.checkPermission(Constants.DELETE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (CollectionUtil.isEmpty(targets)) {
            Notification.show("请先勾选要删除的数据卷", Notification.Type.WARNING_MESSAGE);
            return;
        }

        StringBuilder names = new StringBuilder();
        boolean anyInUse = false;
        for (DockerVolume volume : targets) {
            names.append(volume.getName());
            if (CollectionUtil.isNotEmpty(volume.getUsedBy())) {
                names.append("（使用中：").append(volume.getUsedByText()).append("）");
                anyInUse = true;
            }
            names.append("<br/>");
        }

        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("删除数据卷",
                        "<h3>即将删除以下数据卷</h3>" + names
                                + "<br/>删除卷会连同卷里的数据一起清掉，且不可恢复。"
                                + (anyInUse ? "<br/><b>选中的卷中有容器正在使用：普通删除会被 docker 拒绝，"
                                + "只有勾选「强制删除」才能移除。</b>" : ""),
                        "确认删除", "取消", true);
        final CheckBox forceBox = new CheckBox("强制删除（-f，使用中的卷也能删）");
        forceBox.addStyleName("docker-dialog-checkbox");
        win.getLayout().addComponent(forceBox, 1);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                final boolean force = forceBox.getValue();
                win.close();
                runAsync("删除数据卷", new DockerTask<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String run() {
                        StringBuilder report = new StringBuilder();
                        for (DockerVolume volume : targets) {
                            try {
                                service.removeVolume(volume.getName(), force);
                                report.append("已删除 ").append(volume.getName()).append("；");
                                log.info("用户 {} 删除了数据卷 {}",
                                        com.so.component.ComponentUtil.getCurrentUserName(), volume.getName());
                            } catch (Exception e) {
                                report.append(volume.getName()).append(" 删除失败（")
                                        .append(reason(e)).append("）；");
                            }
                        }
                        return report.toString();
                    }
                }, new DockerTaskDone<String>() {
                    private static final long serialVersionUID = 1L;

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

    private void confirmPrune() {
        if (!LoginView.checkPermission(Constants.DELETE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        int candidate = 0;
        for (DockerVolume volume : allVolumes) {
            if (CollectionUtil.isEmpty(volume.getUsedBy())) {
                candidate++;
            }
        }
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("清理未使用数据卷",
                        "将执行 <b>docker volume prune -f</b>。"
                                + (candidate > 0
                                ? "当前列表里有 <b>" + candidate + "</b> 个卷没有任何容器挂载，"
                                : "当前列表里的卷都处于挂载状态，")
                                + "<br/>未被任何容器（含已停止容器）引用的卷会被删除，卷内数据一并清除，不可恢复。",
                        "执行清理", "取消", true);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                win.close();
                runAsync("清理未使用数据卷", new DockerTask<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String run() throws Exception {
                        return service.pruneVolumes();
                    }
                }, new DockerTaskDone<String>() {
                    private static final long serialVersionUID = 1L;

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

    /* ------------------------------------------------------------------ */
    /* 创建                                                                */
    /* ------------------------------------------------------------------ */

    private class CreateVolumeWindow extends Window {

        private static final long serialVersionUID = 1L;

        private final TextField nameField;
        private final TextField driverField;
        private Button createBtn;

        CreateVolumeWindow() {
            super("创建数据卷");
            setWidth("620px");
            setHeight("330px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            FormLayout form = new FormLayout();
            form.setWidth("100%");
            nameField = ComponentFactory.getStandardTtextField("卷名（必填）");
            nameField.setWidth("540px");
            nameField.setPlaceholder("myapp-data");
            form.addComponent(nameField);

            driverField = ComponentFactory.getStandardTtextField("驱动");
            driverField.setWidth("260px");
            driverField.setValue("local");
            form.addComponent(driverField);

            Label hint = new Label("留空或填 local 使用本地驱动。远程/特殊驱动（nfs、glusterfs 等）"
                    + "需要在宿主机上先装好对应的 volume plugin，否则 docker 会直接报错。");
            hint.addStyleName("docker-hint");
            hint.setWidth("540px");
            form.addComponent(hint);
            root.addComponent(form);

            HorizontalLayout buttons = new HorizontalLayout();
            buttons.setWidth("100%");
            buttons.setSpacing(true);
            createBtn = ComponentFactory.getPrimaryButtonWithType("创建",
                    com.so.component.util.ButtonType.PRIMARY);
            createBtn.setWidth("100px");
            Button cancel = ComponentFactory.getStandardButton("取消");
            cancel.setWidth("80px");
            buttons.addComponents(createBtn, cancel);
            buttons.setExpandRatio(cancel, 1f);
            buttons.setComponentAlignment(cancel, Alignment.MIDDLE_RIGHT);
            root.addComponent(buttons);
            root.setExpandRatio(form, 1f);

            cancel.addClickListener(e -> close());
            createBtn.addClickListener(e -> doCreate());
        }

        private void doCreate() {
            final String name = nameField.getValue();
            if (StrUtil.isBlank(name)) {
                Notification.show("请填写卷名", Notification.Type.WARNING_MESSAGE);
                return;
            }
            final String driver = driverField.getValue();
            createBtn.setEnabled(false);
            createBtn.setCaption("创建中…");
            DockerUi.async("创建数据卷 " + name, new DockerUi.Task<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public String run() throws Exception {
                    service.createVolume(name, driver);
                    return null;
                }
            }, new DockerUi.Done<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(String ignored) {
                    Notification.show("数据卷 " + name + " 创建成功",
                            Notification.Type.HUMANIZED_MESSAGE);
                    close();
                    reload();
                }
            }, new DockerUi.Failure() {
                private static final long serialVersionUID = 1L;

                @Override
                public void onFailure(Exception e) {
                    createBtn.setEnabled(true);
                    createBtn.setCaption("创建");
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* 详情                                                                */
    /* ------------------------------------------------------------------ */

    private class VolumeDetailWindow extends Window {

        private static final long serialVersionUID = 1L;

        VolumeDetailWindow(DockerVolume volume) {
            super("数据卷详情 - " + volume.getName());
            setWidth("760px");
            setHeight("480px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            TextArea area = ComponentFactory.getStandardTtextArea();
            area.setSizeFull();
            area.setReadOnly(true);
            StringBuilder sb = new StringBuilder();
            sb.append("卷名       : ").append(StrUtil.emptyToDefault(volume.getName(), "-")).append('\n');
            sb.append("驱动       : ").append(StrUtil.emptyToDefault(volume.getDriver(), "-")).append('\n');
            sb.append("范围       : ").append(StrUtil.emptyToDefault(volume.getScope(), "-")).append('\n');
            sb.append("创建时间   : ").append(StrUtil.emptyToDefault(volume.getCreatedAt(), "-")).append('\n');
            sb.append("宿主机路径 : ").append(StrUtil.emptyToDefault(volume.getMountpoint(), "-")).append('\n');
            sb.append('\n');
            sb.append("挂载该卷的容器：\n");
            if (CollectionUtil.isEmpty(volume.getUsedBy())) {
                sb.append("  （没有容器正在使用该卷）\n");
            } else {
                for (String name : volume.getUsedBy()) {
                    sb.append("  - ").append(name).append('\n');
                }
            }
            sb.append('\n');
            sb.append("提示：卷的实际数据存放在宿主机的「宿主机路径」目录下，"
                    + "该路径由 docker 管理，不建议手工改动。");
            area.setValue(sb.toString());
            root.addComponent(area);
            root.setExpandRatio(area, 1f);

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
