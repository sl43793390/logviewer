package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.docker.DockerExecutor;
import com.so.docker.DockerService;
import com.so.docker.model.DockerImage;
import com.so.docker.model.DockerNetwork;
import com.so.docker.model.DockerRunSpec;
import com.so.ui.ComponentFactory;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.CheckBox;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 创建容器（{@code docker run}）。
 * <p>
 * 只覆盖实际会用到的参数：名称 / 镜像 / 端口 / 环境变量 / 数据卷 / 网络 / 重启策略 / 启动命令。
 * 底部给了一份等价的命令行预览 —— 排查问题时把这条命令贴到服务器上跑一遍，
 * 比来回问"页面上到底传了什么"快得多。
 */
public class DockerCreateContainerWindow extends Window {

    private static final long serialVersionUID = 1L;

    private static final String SELINUX_NONE = "不加（默认）";
    private static final String SELINUX_SHARED = "z（多容器共享宿主目录）";
    private static final String SELINUX_PRIVATE = "Z（本容器独占宿主目录）";

    private final DockerService service;
    private final DockerExecutor executor;
    private final DockerContainerPage page;

    private TextField imageField;
    private ComboBox<String> imagePicker;
    private TextField nameField;
    private TextArea portArea;
    private TextArea envArea;
    private TextArea volumeArea;
    private ComboBox<String> selinuxCombo;
    private ComboBox<String> networkCombo;
    private ComboBox<String> restartCombo;
    private TextField commandField;
    private CheckBox autoRemoveBox;
    private CheckBox privilegedBox;
    private TextArea previewArea;
    private Button createBtn;

    public DockerCreateContainerWindow(DockerMgmtComponent owner, DockerContainerPage page, DockerService service) {
        super("创建容器");
        this.service = service;
        this.executor = owner.getExecutor();
        this.page = page;

        setWidth("1100px");
        setHeight("950px");
        setModal(true);
        setResizable(true);
        center();

        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);
        setContent(root);

        Panel formPanel = new Panel();
        formPanel.setWidth("100%");
        formPanel.setHeight("680px");
        root.addComponent(formPanel);

        FormLayout form = new FormLayout();
        form.setWidth("100%");
        form.setMargin(false);
        formPanel.setContent(form);

        form.addComponent(buildImageRow());
        nameField = ComponentFactory.getStandardTtextField("容器名称（留空由 docker 随机生成）");
        nameField.setWidth("600px");
        nameField.setPlaceholder("例如 my-nginx");
        form.addComponent(nameField);

        restartCombo = ComponentFactory.getStandardComboBox("重启策略");
        restartCombo.setItems("不自动重启（no）", "异常退出时重启（on-failure）",
                "总是重启（always）", "除非手动停止（unless-stopped）");
        restartCombo.setValue("不自动重启（no）");
        restartCombo.setWidth("300px");
        form.addComponent(restartCombo);

        networkCombo = ComponentFactory.getStandardComboBox("网络");
        networkCombo.setWidth("300px");
        networkCombo.setPlaceholder("不指定（使用默认 bridge）");
        form.addComponent(networkCombo);

        portArea = buildArea("端口映射（每行一个）", "8080:80\n8443:443/tcp", 62);
        form.addComponent(portArea);

        envArea = buildArea("环境变量（每行一个 KEY=VALUE）", "TZ=Asia/Shanghai\nJAVA_OPTS=-Xmx512m", 62);
        form.addComponent(envArea);

        volumeArea = buildArea("数据卷（每行一个 宿主路径:容器路径，或 卷名:容器路径）",
                "/data/app:/opt/app\nmydata:/var/lib/mysql", 62);
        form.addComponent(volumeArea);

        selinuxCombo = ComponentFactory.getStandardComboBox("SELinux 卷标签");
        selinuxCombo.setItems(SELINUX_NONE, SELINUX_SHARED, SELINUX_PRIVATE);
        selinuxCombo.setValue(SELINUX_NONE);
        selinuxCombo.setWidth("360px");
        form.addComponent(selinuxCombo);
        Label selinuxHint = new Label("只对「宿主路径:容器路径」这种绑定挂载生效；"
                + "Rocky / CentOS 默认 SELinux enforcing，不加标签时容器内会 permission denied。");
        selinuxHint.addStyleName("docker-hint");
        selinuxHint.setWidth("600px");
        form.addComponent(selinuxHint);

        commandField = ComponentFactory.getStandardTtextField("启动命令（留空用镜像默认，例如 nginx -g 'daemon off;'）");
        commandField.setWidth("600px");
        form.addComponent(commandField);

        HorizontalLayout options = new HorizontalLayout();
        options.setSpacing(true);
        autoRemoveBox = new CheckBox("容器退出后自动删除（--rm）");
        privilegedBox = new CheckBox("特权模式（--privileged）");
        options.addComponents(autoRemoveBox, privilegedBox);
        form.addComponent(options);
        Label privilegedHint = new Label("特权模式会去掉容器的大部分隔离，仅在内核调试、挂载设备等场景使用。");
        privilegedHint.addStyleName("docker-hint");
        form.addComponent(privilegedHint);

        // 命令预览
        Label previewTitle = ComponentFactory.getStandardLabel("等价的命令行（可直接复制到服务器执行）");
        root.addComponent(previewTitle);
        previewArea = ComponentFactory.getTextArea();
        previewArea.setHeight("110px");
        previewArea.setWidth("100%");
        previewArea.setReadOnly(true);
        root.addComponent(previewArea);

        HorizontalLayout buttons = new HorizontalLayout();
        buttons.setWidth("100%");
        buttons.setSpacing(true);
        buildButtons(buttons);
        root.addComponent(buttons);
        root.setExpandRatio(formPanel, 1f);

        loadReferenceData();
        refreshPreview();
    }

    private void buildButtons(HorizontalLayout buttons) {
        Button previewBtn = ComponentFactory.getStandardButton("刷新命令预览");
        previewBtn.setWidth("140px");
        previewBtn.addClickListener(e -> refreshPreview());
        Button cancelBtn = ComponentFactory.getStandardButton("取消");
        cancelBtn.setWidth("80px");
        cancelBtn.addClickListener(e -> close());
        createBtn = ComponentFactory.getPrimaryButtonWithType("创建", com.so.component.util.ButtonType.SUCCESS);
        createBtn.setWidth("100px");
        createBtn.addClickListener(e -> create());
        buttons.addComponents(previewBtn, createBtn, cancelBtn);
        buttons.setExpandRatio(cancelBtn, 1f);
        buttons.setComponentAlignment(cancelBtn, Alignment.MIDDLE_RIGHT);
        buttons.setComponentAlignment(createBtn, Alignment.MIDDLE_RIGHT);
        buttons.setComponentAlignment(previewBtn, Alignment.MIDDLE_LEFT);
    }

    private HorizontalLayout buildImageRow() {
        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(true);
        row.setWidth("100%");
        imageField = ComponentFactory.getStandardTtextField("镜像（必填）");
        imageField.setWidth("330px");
        imageField.setPlaceholder("nginx:1.25 或 registry.example.com/team/app:v1");
        imagePicker = ComponentFactory.getStandardComboBox("从本地镜像选择");
        imagePicker.setWidth("260px");
        imagePicker.setPlaceholder("选择后自动填入左侧");
        imagePicker.setItemCaptionGenerator(item -> item);
        imagePicker.addValueChangeListener(e -> {
            if (StrUtil.isNotEmpty(e.getValue())) {
                imageField.setValue(e.getValue());
            }
        });
        row.addComponents(imageField, imagePicker);
        return row;
    }

    private TextArea buildArea(String caption, String placeholder, int heightPx) {
        TextArea area = ComponentFactory.getStandardTtextArea();
        area.setCaption(caption);
        area.setWidth("640px");
        area.setHeight(heightPx + "px");
        area.setPlaceholder(placeholder);
        return area;
    }

    public void show() {
        UI.getCurrent().addWindow(this);
    }

    /** 本地镜像与网络列表：异步拉，弹窗先显示出来 */
    private void loadReferenceData() {
        DockerUi.async("读取镜像与网络列表", new DockerUi.Task<RefData>() {
            private static final long serialVersionUID = 1L;

            @Override
            public RefData run() throws Exception {
                RefData data = new RefData();
                try {
                    data.images = service.listImages(false);
                } catch (Exception e) {
                    log(e);
                }
                try {
                    data.networks = service.listNetworks(false);
                } catch (Exception e) {
                    log(e);
                }
                return data;
            }
        }, new DockerUi.Done<RefData>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(RefData data) {
                List<String> imageRefs = new ArrayList<String>();
                for (DockerImage image : data.images) {
                    if (!image.isDangling()) {
                        imageRefs.add(image.getReference());
                    }
                }
                imagePicker.setItems(imageRefs);

                List<String> networks = new ArrayList<String>();
                for (DockerNetwork network : data.networks) {
                    if (network.getName() != null && !network.getName().isEmpty()) {
                        networks.add(network.getName());
                    }
                }
                networks.add("host");
                networks.add("none");
                networkCombo.setItems(networks);
            }
        });
    }

    private static void log(Exception e) {
        org.slf4j.LoggerFactory.getLogger(DockerCreateContainerWindow.class)
                .warn("读取参考数据失败：{}", DockerUi.reason(e));
    }

    /** 拼出等价命令，用来核对参数 */
    private void refreshPreview() {
        DockerRunSpec spec = collectSpec(true);
        StringBuilder sb = new StringBuilder(executor.getCommandPrefix());
        for (String arg : spec.buildRunArguments()) {
            sb.append(' ').append(DockerExecutor.q(arg));
        }
        previewArea.setValue(sb.toString());
    }

    /**
     * 把界面上的输入收集成 {@link DockerRunSpec}。
     *
     * @param lenient true 表示这是预览，允许必填项为空
     */
    private DockerRunSpec collectSpec(boolean lenient) {
        DockerRunSpec spec = new DockerRunSpec();
        spec.setImage(StrUtil.trimToEmpty(imageField.getValue()));
        spec.setName(StrUtil.trimToEmpty(nameField.getValue()));
        spec.setPorts(DockerService.splitUserInput(portArea.getValue()));
        spec.setEnvs(DockerService.splitUserInput(envArea.getValue()));

        String label = selinuxLabel(selinuxCombo.getValue());
        List<String> volumes = new ArrayList<String>();
        for (String volume : DockerService.splitUserInput(volumeArea.getValue())) {
            volumes.add(DockerRunSpec.applySelinuxLabel(volume, label));
        }
        spec.setVolumes(volumes);

        String network = networkCombo.getValue();
        spec.setNetwork(StrUtil.trimToEmpty(network));
        spec.setRestartPolicy(parseRestartPolicy(restartCombo.getValue()));
        spec.setCommand(StrUtil.trimToEmpty(commandField.getValue()));
        spec.setAutoRemove(Boolean.TRUE.equals(autoRemoveBox.getValue()));
        spec.setPrivileged(Boolean.TRUE.equals(privilegedBox.getValue()));
        return spec;
    }

    private static String selinuxLabel(String display) {
        if (SELINUX_SHARED.equals(display)) {
            return "z";
        }
        if (SELINUX_PRIVATE.equals(display)) {
            return "Z";
        }
        return null;
    }

    private static String parseRestartPolicy(String display) {
        if (StrUtil.isBlank(display)) {
            return null;
        }
        int from = display.indexOf('（');
        int to = display.indexOf('）');
        if (from >= 0 && to > from) {
            return display.substring(from + 1, to);
        }
        return display.trim();
    }

    private void create() {
        final DockerRunSpec spec = collectSpec(false);
        if (StrUtil.isBlank(spec.getImage())) {
            Notification.show("镜像不能为空", Notification.Type.WARNING_MESSAGE);
            return;
        }
        String nameError = validateName(spec.getName());
        if (null != nameError) {
            Notification.show(nameError, Notification.Type.WARNING_MESSAGE);
            return;
        }
        String portError = validatePorts(spec.getPorts());
        if (null != portError) {
            Notification.show(portError, Notification.Type.WARNING_MESSAGE);
            return;
        }
        String envError = validateEnvs(spec.getEnvs());
        if (null != envError) {
            Notification.show(envError, Notification.Type.WARNING_MESSAGE);
            return;
        }

        createBtn.setEnabled(false);
        DockerUi.async("创建容器", new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                return service.createContainer(spec);
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String containerId) {
                createBtn.setEnabled(true);
                String shortId = containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
                Notification.show("容器创建成功：" + shortId, Notification.Type.HUMANIZED_MESSAGE);
                close();
                page.onContainerCreated();
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                createBtn.setEnabled(true);
            }
        });
    }

    private static String validateName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.\\-]*")) {
            return "容器名称只能由字母、数字、下划线、点和短横线组成，且不能以符号开头";
        }
        return null;
    }

    private static String validatePorts(List<String> ports) {
        for (String port : ports) {
            // 形如 8080:80、80、8080:80/udp、127.0.0.1:8080:80
            if (!port.matches("(([0-9.]+):)?([0-9]+:)?[0-9]+(/[a-z]+)?")) {
                return "端口映射格式不正确：" + port + "（正确写法如 8080:80 或 8443:443/tcp）";
            }
        }
        return null;
    }

    private static String validateEnvs(List<String> envs) {
        for (String env : envs) {
            if (env.indexOf('=') < 1) {
                return "环境变量格式不正确：" + env + "（应该是 KEY=VALUE）";
            }
        }
        return null;
    }

    /** 异步拿回来的镜像/网络列表 */
    private static class RefData implements Serializable {

        private static final long serialVersionUID = 1L;

        private List<DockerImage> images = new ArrayList<DockerImage>();
        private List<DockerNetwork> networks = new ArrayList<DockerNetwork>();
    }
}
