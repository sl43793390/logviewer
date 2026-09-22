package com.so.component.docker;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.so.component.util.ColorEnum;
import com.so.docker.DockerExecutor;
import com.so.docker.model.DockerContainer;
import com.so.docker.model.DockerNetwork;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.CheckBox;
import com.vaadin.ui.ComboBox;
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
import java.util.List;

/**
 * 网络管理：列表、创建、删除、查看接入容器、连接/断开容器。
 * <p>
 * 子网/网关/接入容器这几个字段 {@code docker network ls} 不提供，必须逐个 inspect。
 * inspect 走的是分批（每批 40 个）的 {@code docker network inspect a b c}，
 * 而不是一网络一次调用——机器上网络一多，往返次数会直接决定列表打开要等几秒。
 */
public class DockerNetworkPage extends AbstractDockerPage {

    private static final long serialVersionUID = 1L;

    /** docker 内置网络，默认网络允许删（用户可能就是想清掉），但界面上要提示 */
    private static final String BUILTIN_BRIDGE = "bridge";
    private static final String BUILTIN_HOST = "host";
    private static final String BUILTIN_NONE = "none";

    private Grid<DockerNetwork> grid;
    private CheckBox detailBox;
    private List<DockerNetwork> allNetworks = new ArrayList<DockerNetwork>();

    public DockerNetworkPage(DockerMgmtComponent owner, DockerExecutor executor) {
        super(owner, executor);
    }

    @Override
    protected void buildToolbar(HorizontalLayout toolbar) {
        Button refresh = ComponentFactory.getStandardButton("刷新");
        refresh.setWidth("80px");
        Button create = ComponentFactory.getPrimaryButtonWithType("创建网络",
                com.so.component.util.ButtonType.PRIMARY);
        create.setWidth("100px");
        Button prune = ComponentFactory.getButtonWithColor("清理未使用网络", ColorEnum.YELLOW);
        prune.setWidth("130px");

        for (Button button : new Button[]{refresh, create, prune}) {
            button.setHeight("30px");
            toolbar.addComponent(button);
        }

        detailBox = new CheckBox("加载子网 / 接入容器");
        detailBox.setValue(Boolean.TRUE);
        toolbar.addComponent(detailBox);
        toolbar.setExpandRatio(detailBox, 1f);

        buildGrid();
        contentLayout.addComponent(grid);
        contentLayout.setExpandRatio(grid, 1f);

        refresh.addClickListener(e -> reload());
        detailBox.addValueChangeListener(e -> reload());
        create.addClickListener(e -> showWindow(new CreateNetworkWindow()));
        prune.addClickListener(e -> confirmPrune());
    }

    private void buildGrid() {
        grid = new Grid<DockerNetwork>();
        grid.setSizeFull();
        grid.addStyleName("grid_standard");
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);

        grid.addColumn(DockerNetwork::getShortId).setCaption("网络 ID");
        grid.addColumn(DockerNetwork::getName).setCaption("名称");
        grid.addColumn(DockerNetwork::getDriver).setCaption("驱动");
        grid.addColumn(DockerNetwork::getScope).setCaption("范围");
        grid.addColumn(DockerNetwork::getSubnet).setCaption("子网");
        grid.addColumn(DockerNetwork::getGateway).setCaption("网关");
        grid.addColumn(network -> String.valueOf(network.getContainers().size()))
                .setCaption("容器数").setWidth(80);
        grid.addComponentColumn(this::buildRowActions).setCaption("操作").setWidth(260);
    }

    private HorizontalLayout buildRowActions(DockerNetwork network) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        Button detail = ComponentFactory.getLinkButton("详情");
        detail.addClickListener(e -> showWindow(new NetworkDetailWindow(network)));
        actions.addComponent(detail);

        Button connect = ComponentFactory.getLinkButton("连接容器");
        connect.addClickListener(e -> showWindow(new ConnectWindow(network, true)));
        actions.addComponent(connect);

        Button disconnect = ComponentFactory.getLinkButton("断开容器");
        disconnect.addClickListener(e -> showWindow(new ConnectWindow(network, false)));
        actions.addComponent(disconnect);

        Button delete = ComponentFactory.getLinkButton("删除");
        delete.addClickListener(e -> confirmDelete(network));
        actions.addComponent(delete);
        return actions;
    }

    @Override
    protected void reload() {
        final boolean details = Boolean.TRUE.equals(detailBox.getValue());
        runAsync("读取网络列表", new DockerTask<List<DockerNetwork>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public List<DockerNetwork> run() throws Exception {
                return service.listNetworks(details);
            }
        }, new DockerTaskDone<List<DockerNetwork>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(List<DockerNetwork> networks) {
                allNetworks = networks;
                grid.setItems(networks);
                int inUse = 0;
                for (DockerNetwork network : networks) {
                    if (CollectionUtil.isNotEmpty(network.getContainers())) {
                        inUse++;
                    }
                }
                showStatus("共 " + networks.size() + " 个网络，其中 " + inUse + " 个有容器接入"
                        + "（bridge / host / none 是 docker 内置网络）");
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 删除 / 清理                                                         */
    /* ------------------------------------------------------------------ */

    private void confirmDelete(final DockerNetwork network) {
        if (!LoginView.checkPermission(Constants.DELETE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        boolean builtin = isBuiltin(network.getName());
        String extra = "";
        if (CollectionUtil.isNotEmpty(network.getContainers())) {
            extra = "<br/><b>该网络还有 " + network.getContainers().size()
                    + " 个容器接入，需要先断开这些容器才能删除。</b>";
        }
        if (builtin) {
            extra += "<br/><b>" + network.getName() + " 是 docker 内置网络，删除后可能影响后续容器创建。</b>";
        }

        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("删除网络",
                        "即将删除网络 <b>" + network.getName() + "</b>（" + network.getShortId() + "）"
                                + extra,
                        "确认删除", "取消", true);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                win.close();
                runAsync("删除网络", new DockerTask<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String run() throws Exception {
                        service.removeNetwork(network.getId());
                        log.info("用户 {} 删除了网络 {}",
                                com.so.component.ComponentUtil.getCurrentUserName(), network.getName());
                        return null;
                    }
                }, new DockerTaskDone<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void done(String ignored) {
                        Notification.show("网络 " + network.getName() + " 已删除",
                                Notification.Type.HUMANIZED_MESSAGE);
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
        int candidates = 0;
        for (DockerNetwork network : allNetworks) {
            if (CollectionUtil.isEmpty(network.getContainers()) && !isBuiltin(network.getName())) {
                candidates++;
            }
        }
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("清理未使用网络",
                        "将执行 <b>docker network prune -f</b>，删除所有没有容器接入的自定义网络"
                                + "（bridge / host / none 这类内置网络不受影响）。<br/>"
                                + "当前列表中有 <b>" + candidates + "</b> 个自定义网络处于空闲状态。",
                        "执行清理", "取消", true);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                win.close();
                runAsync("清理未使用网络", new DockerTask<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String run() throws Exception {
                        return service.prune("networks");
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

    private static boolean isBuiltin(String name) {
        return BUILTIN_BRIDGE.equals(name) || BUILTIN_HOST.equals(name) || BUILTIN_NONE.equals(name);
    }

    /* ------------------------------------------------------------------ */
    /* 创建                                                                */
    /* ------------------------------------------------------------------ */

    private class CreateNetworkWindow extends Window {

        private static final long serialVersionUID = 1L;

        private final TextField nameField;
        private final TextField driverField;
        private final TextField subnetField;
        private final TextField gatewayField;
        private Button createBtn;

        CreateNetworkWindow() {
            super("创建网络");
            setWidth("640px");
            setHeight("480px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            FormLayout form = new FormLayout();
            form.setWidth("100%");

            nameField = ComponentFactory.getStandardTtextField("网络名（必填）");
            nameField.setWidth("560px");
            nameField.setPlaceholder("myapp-net");
            form.addComponent(nameField);

            driverField = ComponentFactory.getStandardTtextField("驱动");
            driverField.setWidth("260px");
            driverField.setValue("bridge");
            form.addComponent(driverField);

            subnetField = ComponentFactory.getStandardTtextField("子网（CIDR，选填）");
            subnetField.setWidth("300px");
            subnetField.setPlaceholder("172.20.0.0/16");
            form.addComponent(subnetField);

            gatewayField = ComponentFactory.getStandardTtextField("网关（选填）");
            gatewayField.setWidth("260px");
            gatewayField.setPlaceholder("172.20.0.1");
            form.addComponent(gatewayField);

            Label hint = new Label("驱动一般用 bridge。子网必须与宿主机上现有网络不冲突，"
                    + "且要在 docker 的 default-address-pool 之外，否则 docker 会报 "
                    + "\"Pool overlaps with other one on this address space\"。"
                    + "填了子网再填网关时，网关要落在该子网段内。");
            hint.addStyleName("docker-hint");
            hint.setWidth("560px");
            form.addComponent(hint);
            root.addComponent(form);
            root.setExpandRatio(form, 1f);

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

            cancel.addClickListener(e -> close());
            createBtn.addClickListener(e -> doCreate());
        }

        private void doCreate() {
            final String name = nameField.getValue();
            if (StrUtil.isBlank(name)) {
                Notification.show("请填写网络名", Notification.Type.WARNING_MESSAGE);
                return;
            }
            final String driver = driverField.getValue();
            final String subnet = subnetField.getValue();
            final String gateway = gatewayField.getValue();
            createBtn.setEnabled(false);
            createBtn.setCaption("创建中…");
            DockerUi.async("创建网络 " + name, new DockerUi.Task<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public String run() throws Exception {
                    service.createNetwork(name, driver, subnet, gateway);
                    return null;
                }
            }, new DockerUi.Done<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(String ignored) {
                    Notification.show("网络 " + name + " 创建成功",
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
    /* 连接 / 断开                                                         */
    /* ------------------------------------------------------------------ */

    private class ConnectWindow extends Window {

        private static final long serialVersionUID = 1L;

        private final DockerNetwork network;
        private final boolean connectMode;
        private final ComboBox<DockerContainer> containerCombo;
        private final TextField aliasField;
        private Button execBtn;

        ConnectWindow(DockerNetwork network, boolean connectMode) {
            super((connectMode ? "连接容器到网络 - " : "从网络断开容器 - ") + network.getName());
            this.network = network;
            this.connectMode = connectMode;
            setWidth("640px");
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

            containerCombo = ComponentFactory.getStandardComboBox("容器");
            containerCombo.setWidth("560px");
            containerCombo.setItemCaptionGenerator(item -> item.getName() + "  (" + item.getShortId() + ")");
            containerCombo.setPlaceholder("从容器列表中选择");
            form.addComponent(containerCombo);

            aliasField = ComponentFactory.getStandardTtextField("网络别名（选填）");
            aliasField.setWidth("300px");
            aliasField.setPlaceholder("db");
            form.addComponent(aliasField);
            aliasField.setVisible(connectMode);

            Label hint = new Label(connectMode
                    ? "别名用于让同一网络内的其它容器通过这个名字访问它（相当于额外加一条 DNS 记录）。"
                    + "要生效需要容器本身支持，不填也可以。"
                    : "断开正在运行中的容器需要勾选「强制断开」，否则 docker 会拒绝操作。"
                    + "断开后容器会失去该网络内的 DNS 解析与互通能力。");
            hint.addStyleName("docker-hint");
            hint.setWidth("560px");
            form.addComponent(hint);

            Label warn = new Label("正在加载容器列表…");
            warn.addStyleName("docker-hint");
            form.addComponent(warn);
            root.addComponent(form);
            root.setExpandRatio(form, 1f);

            HorizontalLayout buttons = new HorizontalLayout();
            buttons.setWidth("100%");
            buttons.setSpacing(true);
            execBtn = ComponentFactory.getPrimaryButtonWithType(connectMode ? "连接" : "断开",
                    com.so.component.util.ButtonType.PRIMARY);
            execBtn.setWidth("100px");
            execBtn.setEnabled(false);
            Button cancel = ComponentFactory.getStandardButton("取消");
            cancel.setWidth("80px");
            buttons.addComponents(execBtn, cancel);
            buttons.setExpandRatio(cancel, 1f);
            buttons.setComponentAlignment(cancel, Alignment.MIDDLE_RIGHT);
            root.addComponent(buttons);

            cancel.addClickListener(e -> close());
            execBtn.addClickListener(e -> doExec());

            loadContainers(warn);
        }

        /** 容器列表异步拉，避免弹窗打开时卡一下 */
        private void loadContainers(final Label status) {
            DockerUi.async("读取容器列表", new DockerUi.Task<List<DockerContainer>>() {
                private static final long serialVersionUID = 1L;

                @Override
                public List<DockerContainer> run() throws Exception {
                    return service.listContainers(true);
                }
            }, new DockerUi.Done<List<DockerContainer>>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(List<DockerContainer> containers) {
                    if (CollectionUtil.isEmpty(containers)) {
                        status.setValue("这台机器上还没有任何容器。");
                        return;
                    }
                    containerCombo.setItems(containers);
                    status.setValue("共 " + containers.size() + " 个容器可选。");
                    execBtn.setEnabled(true);
                }
            }, new DockerUi.Failure() {
                private static final long serialVersionUID = 1L;

                @Override
                public void onFailure(Exception e) {
                    status.setValue("读取容器列表失败：" + reason(e));
                }
            });
        }

        private void doExec() {
            final DockerContainer container = containerCombo.getValue();
            if (null == container) {
                Notification.show("请先选择容器", Notification.Type.WARNING_MESSAGE);
                return;
            }
            final String alias = aliasField.getValue();
            execBtn.setEnabled(false);
            final String actionText = (connectMode ? "连接容器 " : "断开容器 ") + container.getName();
            DockerUi.async(actionText, new DockerUi.Task<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public String run() throws Exception {
                    if (connectMode) {
                        service.connectNetwork(network.getId(), container.getId(), alias);
                    } else {
                        service.disconnectNetwork(network.getId(), container.getId(), false);
                    }
                    return null;
                }
            }, new DockerUi.Done<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(String ignored) {
                    Notification.show(container.getName() + (connectMode ? " 已接入网络 " : " 已从网络断开 ")
                            + network.getName(), Notification.Type.HUMANIZED_MESSAGE);
                    close();
                    reload();
                }
            }, new DockerUi.Failure() {
                private static final long serialVersionUID = 1L;

                @Override
                public void onFailure(Exception e) {
                    execBtn.setEnabled(true);
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* 详情                                                                */
    /* ------------------------------------------------------------------ */

    private class NetworkDetailWindow extends Window {

        private static final long serialVersionUID = 1L;

        NetworkDetailWindow(DockerNetwork network) {
            super("网络详情 - " + network.getName());
            setWidth("760px");
            setHeight("520px");
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
            sb.append("网络名   : ").append(StrUtil.emptyToDefault(network.getName(), "-")).append('\n');
            sb.append("网络 ID  : ").append(StrUtil.emptyToDefault(network.getId(), "-")).append('\n');
            sb.append("驱动     : ").append(StrUtil.emptyToDefault(network.getDriver(), "-")).append('\n');
            sb.append("范围     : ").append(StrUtil.emptyToDefault(network.getScope(), "-")).append('\n');
            sb.append("子网     : ").append(StrUtil.emptyToDefault(network.getSubnet(), "-")).append('\n');
            sb.append("网关     : ").append(StrUtil.emptyToDefault(network.getGateway(), "-")).append('\n');
            sb.append("内部网络 : ").append("true".equalsIgnoreCase(network.getInternal()) ? "是" : "否")
                    .append('\n');
            sb.append('\n');
            sb.append("接入的容器：\n");
            if (CollectionUtil.isEmpty(network.getContainers())) {
                sb.append("  （暂无容器接入）\n");
            } else {
                for (String item : network.getContainers()) {
                    sb.append("  - ").append(item).append('\n');
                }
            }
            if (isBuiltin(network.getName())) {
                sb.append('\n');
                sb.append("说明：这是 docker 内置网络，" + network.getName()
                        + " 网络的子网/网关随宿主机配置而定，删掉之后新建容器可能无法正常联网。\n");
            }
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
