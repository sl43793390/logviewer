package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.component.util.ColorEnum;
import com.so.docker.DockerExecutor;
import com.so.docker.model.DockerImage;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.CheckBox;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.Grid;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.RadioButtonGroup;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.TextField;
import com.vaadin.ui.Upload;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 镜像管理：本地列表、拉取、构建、导出/导入、删除、清理悬空镜像。
 */
public class DockerImagePage extends AbstractDockerPage {

    private static final long serialVersionUID = 1L;

    private Grid<DockerImage> grid;
    private CheckBox showAllBox;
    private List<DockerImage> allImages = new ArrayList<DockerImage>();

    public DockerImagePage(DockerMgmtComponent owner, DockerExecutor executor) {
        super(owner, executor);
    }

    @Override
    protected void buildToolbar(HorizontalLayout toolbar) {
        Button refresh = ComponentFactory.getStandardButton("刷新");
        refresh.setWidth("80px");
        Button pull = ComponentFactory.getPrimaryButtonWithType("拉取镜像", com.so.component.util.ButtonType.PRIMARY);
        pull.setWidth("110px");
        Button build = ComponentFactory.getStandardButton("构建镜像");
        build.setWidth("100px");
        Button load = ComponentFactory.getStandardButton("导入镜像");
        load.setWidth("100px");
        Button prune = ComponentFactory.getButtonWithColor("清理悬空镜像", ColorEnum.YELLOW);
        prune.setWidth("130px");
        for (Button button : new Button[]{refresh, pull, build, load, prune}) {
            button.setHeight("30px");
            toolbar.addComponent(button);
        }

        showAllBox = new CheckBox("显示中间层镜像（-a）");
        toolbar.addComponent(showAllBox);
        toolbar.setExpandRatio(showAllBox, 1f);

        buildGrid();
        contentLayout.addComponent(grid);
        contentLayout.setExpandRatio(grid, 1f);

        refresh.addClickListener(e -> reload());
        showAllBox.addValueChangeListener(e -> reload());
        pull.addClickListener(e -> showWindow(new PullWindow()));
        build.addClickListener(e -> showWindow(new BuildWindow()));
        load.addClickListener(e -> showWindow(new LoadWindow()));
        prune.addClickListener(e -> confirmPrune());
    }

    private void buildGrid() {
        grid = new Grid<DockerImage>();
        grid.setSizeFull();
        grid.addStyleName("grid_standard");
        grid.setSelectionMode(Grid.SelectionMode.MULTI);

        grid.addColumn(DockerImage::getShortId).setCaption("镜像 ID").setWidth(120);
        grid.addColumn(DockerImage::getRepository).setCaption("仓库").setWidth(280);
        grid.addColumn(DockerImage::getTag).setCaption("标签").setWidth(130);
        grid.addColumn(DockerImage::getSize).setCaption("大小").setWidth(100);
        grid.addColumn(DockerImage::getCreatedAt).setCaption("创建时间").setWidth(200);
        grid.addComponentColumn(this::buildRowActions).setCaption("操作").setWidth(200);
    }

    private HorizontalLayout buildRowActions(DockerImage image) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        Button export = ComponentFactory.getLinkButton("导出");
        StreamResource resource = new StreamResource(new ImageSaveStreamSource(image), buildTarName(image));
        resource.setCacheTime(0);
        new FileDownloader(resource).extend(export);
        actions.addComponent(export);

        Button delete = ComponentFactory.getLinkButton("删除");
        delete.addClickListener(e -> confirmDelete(java.util.Collections.singletonList(image)));
        actions.addComponent(delete);
        return actions;
    }

    private String buildTarName(DockerImage image) {
        String base = image.getReference().replace('/', '_').replace(':', '_');
        return base + ".tar";
    }

    @Override
    protected void reload() {
        final boolean all = Boolean.TRUE.equals(showAllBox.getValue());
        runAsync("读取镜像列表", new DockerTask<List<DockerImage>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public List<DockerImage> run() throws Exception {
                return service.listImages(all);
            }
        }, new DockerTaskDone<List<DockerImage>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(List<DockerImage> images) {
                allImages = images;
                grid.setItems(images);
                int dangling = 0;
                for (DockerImage image : images) {
                    if (image.isDangling()) {
                        dangling++;
                    }
                }
                showStatus("共 " + images.size() + " 个镜像，其中悬空镜像 " + dangling
                        + " 个（悬空镜像不会被任何容器引用，可用「清理悬空镜像」回收）");
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 删除 / 清理                                                         */
    /* ------------------------------------------------------------------ */

    private void confirmDelete(final List<DockerImage> targets) {
        if (!LoginView.checkPermission(Constants.DELETE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (DockerImage image : targets) {
            names.append(image.getReference()).append("（").append(image.getShortId()).append("）<br/>");
        }
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("删除镜像",
                        "<h3>即将删除以下镜像</h3>" + names
                                + "<br/>被容器引用的镜像必须勾选「强制删除」才能移除（容器会保留，变成悬空状态）。",
                        "确认删除", "取消", true);
        final CheckBox forceBox = new CheckBox("强制删除（-f）");
        forceBox.addStyleName("docker-dialog-checkbox");
        win.getLayout().addComponent(forceBox, 1);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                final boolean force = forceBox.getValue();
                win.close();
                runAsync("删除镜像", new DockerTask<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String run() throws Exception {
                        StringBuilder report = new StringBuilder();
                        for (DockerImage image : targets) {
                            try {
                                service.removeImage(image.getId(), force);
                                report.append("已删除 ").append(image.getReference()).append("；");
                                log.info("用户 {} 删除了镜像 {}",
                                        com.so.component.ComponentUtil.getCurrentUserName(), image.getReference());
                            } catch (Exception e) {
                                report.append(image.getReference()).append(" 删除失败（")
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
        com.so.component.util.ConfirmationDialogPopupWindow win =
                new com.so.component.util.ConfirmationDialogPopupWindow("清理悬空镜像",
                        "将执行 <b>docker image prune -f</b>，删除所有仓库/标签为 &lt;none&gt; 的悬空镜像。<br/>"
                                + "悬空镜像不会被任何容器引用，删除是安全的。",
                        "执行清理", "取消", true);
        win.getYesButton().addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(Button.ClickEvent event) {
                win.close();
                runAsync("清理悬空镜像", new DockerTask<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String run() throws Exception {
                        return service.pruneImages(false);
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

    /** docker save 的输出直接流给浏览器 */
    private class ImageSaveStreamSource implements StreamResource.StreamSource {

        private static final long serialVersionUID = 1L;

        private final DockerImage image;

        ImageSaveStreamSource(DockerImage image) {
            this.image = image;
        }

        @Override
        public InputStream getStream() {
            try {
                return service.openImageSaveStream(image.getReference());
            } catch (Exception e) {
                // 下载请求线程上不能弹 Notification
                log.error("导出镜像 {} 失败：{}", image.getReference(), e.getMessage());
                return null;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 拉取                                                                */
    /* ------------------------------------------------------------------ */

    private class PullWindow extends Window {

        private static final long serialVersionUID = 1L;

        private final TextField refField;
        private Button pullBtn;

        PullWindow() {
            super("拉取镜像");
            setWidth("600px");
            setHeight("300px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            FormLayout form = new FormLayout();
            form.setWidth("100%");
            refField = ComponentFactory.getStandardTtextField("镜像地址（registry/repository:tag）");
            refField.setWidth("520px");
            refField.setPlaceholder("nginx:1.25  或  registry.example.com/team/app:v1.2.3");
            form.addComponent(refField);
            Label hint = new Label("不写 registry 时默认从 Docker Hub 拉取；"
                    + "内网仓库请写全地址，例如 harbor.example.com/base/openjdk:8-jre。");
            hint.addStyleName("docker-hint");
            hint.setWidth("520px");
            form.addComponent(hint);
            root.addComponent(form);

            HorizontalLayout buttons = new HorizontalLayout();
            buttons.setSpacing(true);
            buttons.setWidth("100%");
            pullBtn = ComponentFactory.getPrimaryButtonWithType("开始拉取", com.so.component.util.ButtonType.PRIMARY);
            pullBtn.setWidth("110px");
            Button cancel = ComponentFactory.getStandardButton("取消");
            cancel.setWidth("80px");
            buttons.addComponents(pullBtn, cancel);
            buttons.setExpandRatio(cancel, 1f);
            buttons.setComponentAlignment(cancel, Alignment.MIDDLE_RIGHT);
            root.addComponent(buttons);

            cancel.addClickListener(e -> close());
            pullBtn.addClickListener(e -> doPull());
        }

        private void doPull() {
            final String reference = refField.getValue();
            if (StrUtil.isBlank(reference)) {
                Notification.show("请填写镜像地址", Notification.Type.WARNING_MESSAGE);
                return;
            }
            pullBtn.setEnabled(false);
            pullBtn.setCaption("拉取中…");
            DockerUi.async("拉取镜像 " + reference, new DockerUi.Task<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public String run() throws Exception {
                    return service.pullImage(reference);
                }
            }, new DockerUi.Done<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(String output) {
                    Notification.show("拉取完成：" + StrUtil.emptyToDefault(output, "成功"),
                            Notification.Type.HUMANIZED_MESSAGE);
                    close();
                    reload();
                }
            }, new DockerUi.Failure() {
                private static final long serialVersionUID = 1L;

                @Override
                public void onFailure(Exception e) {
                    pullBtn.setEnabled(true);
                    pullBtn.setCaption("开始拉取");
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* 构建                                                                */
    /* ------------------------------------------------------------------ */

    private class BuildWindow extends Window {

        private static final long serialVersionUID = 1L;

        private static final String MODE_DOCKERFILE = "上传 Dockerfile 构建";
        private static final String MODE_GIT = "从 Git 仓库构建";

        private final TextField tagField;
        private final RadioButtonGroup<String> modeGroup;
        private final LocalTempFileReceiver receiver = new LocalTempFileReceiver();
        private final Label fileLabel;
        private final TextField dockerfileNameField;
        private final TextField gitField;
        private final CheckBox noCacheBox;
        private Button buildBtn;

        BuildWindow() {
            super("构建镜像");
            setWidth("700px");
            setHeight("560px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            FormLayout form = new FormLayout();
            form.setWidth("100%");
            tagField = ComponentFactory.getStandardTtextField("镜像标签（必填）");
            tagField.setWidth("600px");
            tagField.setPlaceholder("myapp:1.0.0");
            form.addComponent(tagField);

            modeGroup = new RadioButtonGroup<String>("构建方式");
            modeGroup.setItems(MODE_DOCKERFILE, MODE_GIT);
            modeGroup.setValue(MODE_DOCKERFILE);
            form.addComponent(modeGroup);

            Upload upload = new Upload("选择 Dockerfile", receiver);
            upload.setButtonCaption("选择文件");
            upload.setHeight("30px");
            upload.addSucceededListener(receiver);
            form.addComponent(upload);
            fileLabel = new Label("尚未选择文件");
            fileLabel.addStyleName("docker-hint");
            form.addComponent(fileLabel);
            receiver.setOnReceived(new Runnable() {
                private static final long serialVersionUID = 1L;

                @Override
                public void run() {
                    fileLabel.setValue("已选择：" + receiver.getFileName() + "（" + receiver.getSize() + " 字节）");
                }
            });

            dockerfileNameField = ComponentFactory.getStandardTtextField("在上下文中的文件名");
            dockerfileNameField.setValue("Dockerfile");
            dockerfileNameField.setWidth("260px");
            form.addComponent(dockerfileNameField);

            gitField = ComponentFactory.getStandardTtextField("Git 仓库地址");
            gitField.setWidth("600px");
            gitField.setPlaceholder("https://github.com/user/repo.git#main:subdir");
            form.addComponent(gitField);

            Label gitHint = new Label("从 Git 构建需要 Docker 18.09 以上并启用 BuildKit；"
                    + "可带分支与子目录：#分支:子目录。内网环境请确认宿主机能访问该仓库。");
            gitHint.addStyleName("docker-hint");
            gitHint.setWidth("600px");
            form.addComponent(gitHint);

            noCacheBox = new CheckBox("不使用构建缓存（--no-cache）");
            form.addComponent(noCacheBox);
            root.addComponent(form);

            HorizontalLayout buttons = new HorizontalLayout();
            buttons.setWidth("100%");
            buttons.setSpacing(true);
            buildBtn = ComponentFactory.getPrimaryButtonWithType("开始构建", com.so.component.util.ButtonType.PRIMARY);
            buildBtn.setWidth("110px");
            Button cancel = ComponentFactory.getStandardButton("取消");
            cancel.setWidth("80px");
            buttons.addComponents(buildBtn, cancel);
            buttons.setExpandRatio(cancel, 1f);
            buttons.setComponentAlignment(cancel, Alignment.MIDDLE_RIGHT);
            root.addComponent(buttons);
            root.setExpandRatio(form, 1f);

            modeGroup.addValueChangeListener(e -> applyMode());
            applyMode();
            cancel.addClickListener(e -> {
                receiver.cleanup();
                close();
            });
            buildBtn.addClickListener(e -> doBuild());
        }

        private void applyMode() {
            boolean git = MODE_GIT.equals(modeGroup.getValue());
            gitField.setVisible(git);
            dockerfileNameField.setVisible(!git);
        }

        private void doBuild() {
            final String tag = tagField.getValue();
            if (StrUtil.isBlank(tag)) {
                Notification.show("请填写镜像标签", Notification.Type.WARNING_MESSAGE);
                return;
            }
            final boolean noCache = Boolean.TRUE.equals(noCacheBox.getValue());
            final boolean git = MODE_GIT.equals(modeGroup.getValue());
            final String gitUrl = gitField.getValue();
            final File dockerfile = receiver.getFile();
            final String dockerfileName = dockerfileNameField.getValue();

            if (git && StrUtil.isBlank(gitUrl)) {
                Notification.show("请填写 Git 仓库地址", Notification.Type.WARNING_MESSAGE);
                return;
            }
            if (!git && null == dockerfile) {
                Notification.show("请先选择 Dockerfile", Notification.Type.WARNING_MESSAGE);
                return;
            }

            buildBtn.setEnabled(false);
            buildBtn.setCaption("构建中…");
            DockerUi.async("构建镜像 " + tag, new DockerUi.Task<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public String run() throws Exception {
                    if (git) {
                        return service.buildImage(tag, gitUrl.trim(), null, noCache);
                    }
                    return service.buildFromDockerfile(tag, dockerfile, dockerfileName, noCache);
                }
            }, new DockerUi.Done<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(String output) {
                    Notification.show("构建完成：" + StrUtil.emptyToDefault(tail(output), "成功"),
                            Notification.Type.HUMANIZED_MESSAGE);
                    receiver.cleanup();
                    close();
                    reload();
                }
            }, new DockerUi.Failure() {
                private static final long serialVersionUID = 1L;

                @Override
                public void onFailure(Exception e) {
                    buildBtn.setEnabled(true);
                    buildBtn.setCaption("开始构建");
                }
            });
        }

        /** 构建日志很长，通知里只放最后几行 */
        private String tail(String output) {
            if (StrUtil.isBlank(output)) {
                return "";
            }
            String[] lines = output.split("\\R");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(lines[i].trim());
            }
            return sb.toString();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 导入                                                                */
    /* ------------------------------------------------------------------ */

    private class LoadWindow extends Window {

        private static final long serialVersionUID = 1L;

        private final LocalTempFileReceiver receiver = new LocalTempFileReceiver();
        private final Label fileLabel;
        private Button loadBtn;

        LoadWindow() {
            super("导入镜像");
            setWidth("620px");
            setHeight("320px");
            setModal(true);
            center();

            VerticalLayout root = new VerticalLayout();
            root.setSizeFull();
            root.setMargin(true);
            root.setSpacing(true);
            setContent(root);

            root.addComponent(ComponentFactory.getStandardLabel(
                    "上传由 docker save 导出的镜像包（.tar / .tar.gz），执行 docker load -i 导入。"));

            Upload upload = new Upload("选择镜像包", receiver);
            upload.setButtonCaption("选择文件");
            upload.setHeight("30px");
            upload.addSucceededListener(receiver);
            root.addComponent(upload);
            fileLabel = new Label("尚未选择文件");
            fileLabel.addStyleName("docker-hint");
            root.addComponent(fileLabel);
            receiver.setOnReceived(new Runnable() {
                private static final long serialVersionUID = 1L;

                @Override
                public void run() {
                    fileLabel.setValue("已选择：" + receiver.getFileName() + "（" + receiver.getSize() + " 字节）");
                }
            });

            TextArea hint = ComponentFactory.getTextArea("说明");
            hint.setHeight("70px");
            hint.setReadOnly(true);
            hint.setValue("镜像包会先上传到目标服务器 /tmp 下的临时目录，导入完成后自动清理。"
                    + "大镜像的传输时间取决于应用服务器到目标机器的带宽。");
            root.addComponent(hint);

            HorizontalLayout buttons = new HorizontalLayout();
            buttons.setWidth("100%");
            buttons.setSpacing(true);
            loadBtn = ComponentFactory.getPrimaryButtonWithType("开始导入", com.so.component.util.ButtonType.PRIMARY);
            loadBtn.setWidth("110px");
            Button cancel = ComponentFactory.getStandardButton("取消");
            cancel.setWidth("80px");
            buttons.addComponents(loadBtn, cancel);
            buttons.setExpandRatio(cancel, 1f);
            buttons.setComponentAlignment(cancel, Alignment.MIDDLE_RIGHT);
            root.addComponent(buttons);
            root.setExpandRatio(hint, 1f);

            cancel.addClickListener(e -> {
                receiver.cleanup();
                close();
            });
            loadBtn.addClickListener(e -> doLoad());
        }

        private void doLoad() {
            final File file = receiver.getFile();
            if (null == file) {
                Notification.show("请先选择镜像包", Notification.Type.WARNING_MESSAGE);
                return;
            }
            loadBtn.setEnabled(false);
            loadBtn.setCaption("导入中…");
            DockerUi.async("导入镜像 " + receiver.getFileName(), new DockerUi.Task<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public String run() throws Exception {
                    try {
                        return service.loadImage(file);
                    } finally {
                        receiver.cleanup();
                    }
                }
            }, new DockerUi.Done<String>() {
                private static final long serialVersionUID = 1L;

                @Override
                public void done(String output) {
                    Notification.show("导入完成：" + StrUtil.emptyToDefault(output, "成功"),
                            Notification.Type.HUMANIZED_MESSAGE);
                    close();
                    reload();
                }
            }, new DockerUi.Failure() {
                private static final long serialVersionUID = 1L;

                @Override
                public void onFailure(Exception e) {
                    loadBtn.setEnabled(true);
                    loadBtn.setCaption("开始导入");
                }
            });
        }
    }

}
