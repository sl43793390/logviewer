package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.docker.DockerExecutor;
import com.so.docker.DockerService;
import com.so.docker.model.DockerImage;
import com.so.ui.ComponentFactory;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 创建容器。
 * <p>
 * <b>只做一件事：把一条完整的 {@code docker run} 命令原样丢到目标宿主机上执行。</b>
 * 原来这里是一整套表单（名称 / 端口 / 环境变量 / 卷 / 网络 / 重启策略 / SELinux 标签），
 * 问题是它只能表达一部分参数，稍复杂一点（{@code --network host}、{@code --ip}、
 * 多个 {@code --label}、{@code --cap-add}）就写不出来，用户还得回头去服务器上手敲。
 * 改成命令直填之后，界面不再需要理解参数语义，用户手里那条命令是什么就是什么。
 * <p>
 * 界面上方保留「本机已有的镜像名」清单，只是为了省掉"回头去镜像页复制一下"这一步。
 * <p>
 * 允许执行的命令范围（只看 {@code run} / {@code create}、拦住 shell 连接符）由
 * {@link DockerService#normalizeRunCommand(String)} 把关 —— 这里不做二次判断，
 * 校验规则只有一份。
 */
public class DockerCreateContainerWindow extends Window {

    private static final long serialVersionUID = 1L;

    private static final String COMMAND_PLACEHOLDER =
            "docker run -d --name my-nginx \\\n"
                    + "  -p 8080:80 \\\n"
                    + "  -v /data/nginx/html:/usr/share/nginx/html:z \\\n"
                    + "  -e TZ=Asia/Shanghai \\\n"
                    + "  --restart unless-stopped \\\n"
                    + "  nginx:1.25";

    private final DockerService service;
    private final DockerExecutor executor;
    private final DockerContainerPage page;
    private final DockerMgmtComponent owner;

    private Grid<DockerImage> imageGrid;
    private Label imageStatus;
    private Button copyAllBtn;
    /** 当前展示的镜像，供「复制全部镜像名」用 */
    private List<DockerImage> localImages = new ArrayList<DockerImage>();
    private TextArea commandArea;
    private Label effectiveLabel;
    private Button createBtn;

    public DockerCreateContainerWindow(DockerMgmtComponent owner, DockerContainerPage page, DockerService service) {
        super("创建容器");
        this.service = service;
        this.executor = service.getExecutor();
        this.page = page;
        this.owner = owner;

        setWidth("1040px");
        // 高度按内容量算过：两行说明 + 镜像清单 200 + 命令框 240 + 按钮，余量留给窄屏钳高
        setHeight("720px");
        setModal(true);
        setResizable(true);
        center();

        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);
        setContent(root);

        root.addComponent(buildHint());

        /* ---------------- 本机镜像：可看可复制 ---------------- */
        HorizontalLayout imageHeader = new HorizontalLayout();
        imageHeader.setWidth("100%");
        imageHeader.setSpacing(true);
        imageHeader.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        Label imageTitle = ComponentFactory.getStandardLabel(
                "目标机已有镜像（点「复制」把镜像名放进剪贴板，再粘到下面的命令里）");
        imageStatus = ComponentFactory.getStandardLabel("正在读取镜像列表…");
        copyAllBtn = ComponentFactory.getStandardButton("复制全部镜像名");
        copyAllBtn.setWidth("150px");
        copyAllBtn.setEnabled(false);
        copyAllBtn.addClickListener(e -> copyAllImages());
        imageHeader.addComponents(imageTitle, copyAllBtn, imageStatus);
        imageHeader.setExpandRatio(imageTitle, 1f);
        root.addComponent(imageHeader);

        buildImageGrid();
        root.addComponent(imageGrid);

        /* ---------------- 命令 ---------------- */
        Label commandTitle = ComponentFactory.getStandardLabel("启动命令（粘贴完整的 docker run 命令，点「创建」即在目标机上执行）");
        commandTitle.setContentMode(ContentMode.HTML);
        root.addComponent(commandTitle);

        // 高度写死而不是用 expandRatio：TextArea 走 100% 高度时要靠连接器把内层
        // textarea 元素也撑开，主题里只要有一条规则没跟上就会塌成两行高，风险不值当
        commandArea = ComponentFactory.getStandardTtextArea();
        commandArea.setWidth("100%");
        commandArea.setHeight("240px");
        commandArea.addStyleName("docker-create-command");
        commandArea.setPlaceholder(COMMAND_PLACEHOLDER);
        root.addComponent(commandArea);

        effectiveLabel = ComponentFactory.getStandardLabel("尚未填写命令");
        effectiveLabel.addStyleName("docker-hint");
        root.addComponent(effectiveLabel);

        HorizontalLayout buttons = new HorizontalLayout();
        buttons.setWidth("100%");
        buttons.setSpacing(true);
        buildButtons(buttons);
        root.addComponent(buttons);

        commandArea.addValueChangeListener(e -> refreshEffective());
        loadImages();
    }

    private Label buildHint() {
        Label hint = new Label("这个窗口只执行一条命令：下方文本框里贴什么，就在目标宿主机上跑什么，"
                + "界面不会改写任何参数。端口、数据卷、环境变量、网络、SELinux 标签都写在命令里。"
                + "<br/>只放行 <b>docker run</b> / <b>docker create</b>；要敲别的命令请走「SSH 管理」。");
        hint.setContentMode(ContentMode.HTML);
        hint.addStyleName("docker-hint");
        return hint;
    }

    private void buildImageGrid() {
        imageGrid = new Grid<DockerImage>();
        imageGrid.setWidth("100%");
        imageGrid.setHeight("200px");
        imageGrid.addStyleName("grid_standard");
        imageGrid.addColumn(DockerImage::getReference).setCaption("镜像名称").setWidth(480);
        imageGrid.addColumn(image -> StrUtil.emptyToDefault(image.getSize(), "-")).setCaption("大小").setWidth(110);
        imageGrid.addComponentColumn(image -> {
            Button copy = ComponentFactory.getLinkButton("复制");
            copy.addClickListener(e -> copyToClipboard(image.getReference(), "已复制镜像名：" + image.getReference()));
            HorizontalLayout actions = new HorizontalLayout(copy);
            actions.setSpacing(true);
            return actions;
        }).setCaption("操作").setWidth(90);
    }

    private void buildButtons(HorizontalLayout buttons) {
        Button clearBtn = ComponentFactory.getStandardButton("清空命令");
        clearBtn.setWidth("110px");
        clearBtn.addClickListener(e -> {
            commandArea.setValue("");
            refreshEffective();
        });
        Button cancelBtn = ComponentFactory.getStandardButton("取消");
        cancelBtn.setWidth("80px");
        cancelBtn.addClickListener(e -> close());
        createBtn = ComponentFactory.getPrimaryButtonWithType("创建", com.so.component.util.ButtonType.SUCCESS);
        createBtn.setWidth("100px");
        createBtn.addClickListener(e -> create());

        buttons.addComponents(clearBtn, cancelBtn, createBtn);
        buttons.setExpandRatio(clearBtn, 1f);
        buttons.setComponentAlignment(cancelBtn, Alignment.MIDDLE_RIGHT);
        buttons.setComponentAlignment(createBtn, Alignment.MIDDLE_RIGHT);
    }

    public void show() {
        UI.getCurrent().addWindow(this);
    }

    /* ================================================================== */
    /* 镜像列表                                                            */
    /* ================================================================== */

    private void loadImages() {
        DockerUi.async("读取本地镜像", new DockerUi.Task<List<DockerImage>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public List<DockerImage> run() throws Exception {
                return service.listImages(false);
            }
        }, new DockerUi.Done<List<DockerImage>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(List<DockerImage> images) {
                // 悬空镜像没有可用的引用名（reference 就是那串 ID），列出来只会干扰复制
                List<DockerImage> usable = new ArrayList<DockerImage>();
                for (DockerImage image : images) {
                    if (!image.isDangling()) {
                        usable.add(image);
                    }
                }
                localImages = usable;
                imageGrid.setItems(usable);
                copyAllBtn.setEnabled(!usable.isEmpty());
                imageStatus.setValue(usable.isEmpty()
                        ? "这台机器上还没有镜像，先在「镜像」页拉取一个，或直接用完整的仓库地址。"
                        : "共 " + usable.size() + " 个");
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                imageStatus.setValue("镜像列表读取失败：" + DockerUi.reason(e) + "（不影响直接粘贴命令创建）");
            }
        });
    }

    /** 一次把所有镜像名复制走，省得一个个点 */
    private void copyAllImages() {
        StringBuilder sb = new StringBuilder();
        for (DockerImage image : localImages) {
            sb.append(image.getReference()).append('\n');
        }
        copyToClipboard(StrUtil.trimToEmpty(sb.toString()), "已复制 " + localImages.size() + " 个镜像名");
    }

    private void copyToClipboard(String text, String message) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        DockerUi.copyToClipboard(text);
        Notification.show(message, Notification.Type.HUMANIZED_MESSAGE);
    }

    /* ================================================================== */
    /* 创建                                                                */
    /* ================================================================== */

    /** 把命令规范化后回显成「实际会在宿主机上执行的命令」，顺手把校验错误说清楚 */
    private void refreshEffective() {
        String raw = commandArea.getValue();
        if (StrUtil.isBlank(raw)) {
            effectiveLabel.setValue("尚未填写命令");
            return;
        }
        try {
            String subCommand = DockerService.normalizeRunCommand(raw);
            effectiveLabel.setValue("实际执行：" + executor.getCommandPrefix() + " " + subCommand);
        } catch (IOException e) {
            effectiveLabel.setValue("命令有问题：" + e.getMessage());
        }
    }

    private void create() {
        final String raw = commandArea.getValue();
        // 先按同一套规则校验一遍，把错误挡在发命令之前
        try {
            DockerService.normalizeRunCommand(raw);
        } catch (IOException e) {
            Notification.show(e.getMessage(), Notification.Type.WARNING_MESSAGE);
            return;
        }
        createBtn.setEnabled(false);
        DockerUi.async("创建容器", new DockerUi.Task<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String run() throws Exception {
                return service.runDockerCommand(raw);
            }
        }, new DockerUi.Done<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(String output) {
                createBtn.setEnabled(true);
                Notification.show("容器创建成功：" + summarize(output), Notification.Type.HUMANIZED_MESSAGE);
                close();
                page.onContainerCreated();
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                createBtn.setEnabled(true);
                // daemon 没起来的话，错误提示之外还得把「是否启动 Docker 服务」的窗口弹出来，
                // 否则用户只能看到一个红色 toast，不知道该干什么
                if (DockerUi.isDaemonDown(e) && null != owner) {
                    owner.promptDockerUnavailable(AbstractDockerPage.daemonStatusOf(e));
                }
            }
        });
    }

    /** {@code docker run -d} 成功时只回一个容器 ID，截短了当提示；其他输出也取第一行 */
    private static String summarize(String output) {
        String first = "";
        for (String line : StrUtil.emptyToDefault(output, "").split("\\R")) {
            if (StrUtil.isNotBlank(line)) {
                first = line.trim();
                break;
            }
        }
        if (StrUtil.isBlank(first)) {
            return "命令已执行（无输出）";
        }
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }
}
