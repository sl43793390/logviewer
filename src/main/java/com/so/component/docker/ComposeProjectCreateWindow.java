package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.docker.ComposeService;
import com.so.docker.model.ComposeCliInfo;
import com.so.docker.model.ComposeProject;
import com.so.docker.model.ComposeTemplate;
import com.so.ui.ComponentFactory;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.Upload;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新建 Compose 项目。
 * <p>
 * 四种起手方式：模板库 / 空白骨架 / 本地上传 yml / 从 Git 仓库拉取。
 * 无论哪种，最终都落到「一组文件 + 一个 .env」上，点创建时一次写到目标机的项目目录。
 * <p>
 * <b>创建前可以先校验。</b>「校验」页签会把当前编辑的内容写进目标机的一个临时目录，
 * 跑一次真正的 {@code docker compose config}，把变量替换后的最终配置和错误原样展示出来 ——
 * 这样用户不用先建一个坏项目再回头改。临时目录用完即删，不会在项目根目录留垃圾。
 */
public class ComposeProjectCreateWindow extends Window {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ComposeProjectCreateWindow.class);

    private static final String SOURCE_TEMPLATE = "从模板库生成";
    private static final String SOURCE_BLANK = "空白骨架";
    private static final String SOURCE_UPLOAD = "上传本地 yml";
    private static final String SOURCE_GIT = "从 Git 仓库拉取";
    private static final String ADD_FILE = "＋ 新增文件…";

    private final DockerComposeComponent owner;
    private final ComposeService compose;
    private final String baseDir;

    private TextField nameField;
    private TextField descField;
    private Label pathLabel;
    private ComboBox<String> sourceCombo;
    private ComboBox<String> templateCombo;
    private Upload upload;
    private TextField repoField;
    private TextField branchField;
    private Button pullBtn;

    private ComboBox<String> fileCombo;
    private TextField newFileField;
    private ComposeYamlEditor editor;
    private TextArea envArea;
    private Label varLabel;
    private TextArea resultArea;
    private Label resultLabel;

    /** 各文件的草稿：切文件、切页签前先落回这里，避免服务端还没收到最后一次输入 */
    private final Map<String, String> drafts = new LinkedHashMap<String, String>();
    private String currentFile;

    private boolean created;

    public ComposeProjectCreateWindow(DockerComposeComponent owner, ComposeService compose, String baseDir) {
        super("新建 Compose 项目");
        this.owner = owner;
        this.compose = compose;
        this.baseDir = StrUtil.emptyToDefault(baseDir, "/opt/" + ComposeService.DEFAULT_BASE_DIR_NAME);

        setWidth("1280px");
        setHeight("900px");
        setModal(true);
        setResizable(true);
        center();

        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);
        setContent(root);

        TabSheet tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.addTab(buildBasicTab(), "1. 基本信息");
        tabs.addTab(buildFileTab(), "2. compose 文件");
        tabs.addTab(buildEnvTab(), "3. .env 变量");
        tabs.addTab(buildCheckTab(), "4. 校验预览");
        root.addComponent(tabs);
        root.setExpandRatio(tabs, 1f);

        HorizontalLayout footer = new HorizontalLayout();
        footer.setSpacing(true);
        footer.setWidth("100%");
        footer.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        Button create = ComponentFactory.getPrimaryButtonWithType("创建项目", com.so.component.util.ButtonType.SUCCESS);
        create.setWidth("120px");
        Button cancel = ComponentFactory.getStandardButton("取消");
        cancel.setWidth("80px");
        Label hint = ComponentFactory.getStandardLabel("项目名同时是目录名与 compose 项目名，只能用"
                + "小写字母、数字、短横线、下划线。");
        hint.addStyleName("docker-hint");
        footer.addComponents(create, cancel, hint);
        footer.setExpandRatio(hint, 1f);
        root.addComponent(footer);

        create.addClickListener(e -> doCreate());
        cancel.addClickListener(e -> close());

        applyTemplate(ComposeTemplates.byName("MySQL 8"), false);
    }

    public boolean isCreated() {
        return created;
    }

    public void show(UI ui) {
        if (null == ui) {
            return;
        }
        ui.addWindow(this);
    }

    /* ================================================================== */
    /* 页签 1：基本信息                                                    */
    /* ================================================================== */

    private VerticalLayout buildBasicTab() {
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);

        HorizontalLayout nameRow = new HorizontalLayout();
        nameRow.setSpacing(true);
        nameRow.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        nameField = ComponentFactory.getStandardTtextField("项目名：");
        nameField.setWidth("260px");
        nameField.setPlaceholder("如 my-app");
        nameRow.addComponent(nameField);
        pathLabel = ComponentFactory.getStandardLabel("项目目录：" + baseDir + "/");
        pathLabel.addStyleName("docker-hint");
        nameRow.addComponent(pathLabel);
        nameRow.setExpandRatio(pathLabel, 1f);
        root.addComponent(nameRow);

        nameField.addValueChangeListener(e -> pathLabel.setValue("项目目录：" + baseDir + "/"
                + StrUtil.emptyToDefault(e.getValue(), "").trim()));

        descField = ComponentFactory.getStandardTtextField("项目描述：");
        descField.setWidth("800px");
        descField.setPlaceholder("可选，写清楚这个项目干什么，后面列表里能一眼认出来");
        root.addComponent(descField);

        root.addComponent(ComponentFactory.getHsplitLine());

        HorizontalLayout sourceRow = new HorizontalLayout();
        sourceRow.setSpacing(true);
        sourceRow.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        sourceCombo = ComponentFactory.getStandardComboBox("起手方式：");
        sourceCombo.setItems(SOURCE_TEMPLATE, SOURCE_BLANK, SOURCE_UPLOAD, SOURCE_GIT);
        sourceCombo.setValue(SOURCE_TEMPLATE);
        sourceCombo.setWidth("200px");
        sourceCombo.setTextInputAllowed(false);
        sourceCombo.setEmptySelectionAllowed(false);
        sourceRow.addComponent(sourceCombo);
        root.addComponent(sourceRow);

        // 模板
        templateCombo = ComponentFactory.getStandardComboBox("模板：");
        templateCombo.setWidth("240px");
        templateCombo.setTextInputAllowed(false);
        templateCombo.setEmptySelectionAllowed(false);
        List<String> names = new ArrayList<String>();
        for (ComposeTemplate template : ComposeTemplates.all()) {
            names.add(template.getName());
        }
        templateCombo.setItems(names);
        templateCombo.setValue("MySQL 8");
        Button applyTemplateBtn = ComponentFactory.getStandardButton("套用（覆盖当前编辑内容）");
        applyTemplateBtn.setWidth("210px");

        HorizontalLayout templateRow = new HorizontalLayout();
        templateRow.setSpacing(true);
        templateRow.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        templateRow.addComponents(templateCombo, applyTemplateBtn);
        Label templateDesc = ComponentFactory.getStandardLabel("");
        templateDesc.addStyleName("docker-hint");
        templateDesc.setWidth("100%");
        templateRow.addComponent(templateDesc);
        templateRow.setExpandRatio(templateDesc, 1f);
        root.addComponent(templateRow);

        templateCombo.addValueChangeListener(e -> {
            ComposeTemplate template = ComposeTemplates.byName(e.getValue());
            templateDesc.setValue(null == template ? "" : template.getDescription());
        });

        // 上传
        HorizontalLayout uploadRow = new HorizontalLayout();
        uploadRow.setSpacing(true);
        uploadRow.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        YamlUploadReceiver receiver = new YamlUploadReceiver();
        upload = new Upload("选择本地文件：", receiver);
        upload.setButtonCaption("上传 yml");
        upload.setHeight("30px");
        upload.addSucceededListener(receiver);
        Label uploadHint = ComponentFactory.getStandardLabel(
                "上传的文件内容会填进当前选中的 compose 文件里（不会直接传到服务器），确认后再点创建。");
        uploadHint.addStyleName("docker-hint");
        uploadRow.addComponents(upload, uploadHint);
        uploadRow.setExpandRatio(uploadHint, 1f);
        root.addComponent(uploadRow);

        // Git
        HorizontalLayout gitRow = new HorizontalLayout();
        gitRow.setSpacing(true);
        gitRow.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        repoField = ComponentFactory.getStandardTtextField("仓库地址：");
        repoField.setWidth("400px");
        repoField.setPlaceholder("git@192.168.1.10:ops/compose.git 或 https://…");
        branchField = ComponentFactory.getStandardTtextField("分支：");
        branchField.setWidth("150px");
        branchField.setValue("master");
        pullBtn = ComponentFactory.getPrimaryButtonWithType("拉取到项目目录", com.so.component.util.ButtonType.PRIMARY);
        pullBtn.setWidth("150px");
        gitRow.addComponents(repoField, branchField, pullBtn);
        root.addComponent(gitRow);

        Label gitHint = ComponentFactory.getStandardLabel(
                "从 Git 拉取会立刻在项目目录里执行 git clone（目录已存在会先清空），"
                        + "拉取完成后仓库里的 compose 文件会自动填入「compose 文件」页签，再点创建把它登记进系统。"
                        + "需要目标机能访问该 Git 仓库（内网仓库请用 ssh 地址 + 部署密钥）。");
        gitHint.addStyleName("docker-hint");
        gitHint.setWidth("100%");
        gitHint.setHeight("50px");
        root.addComponent(gitHint);

        Runnable refresh = () -> {
            String source = sourceCombo.getValue();
            templateRow.setVisible(SOURCE_TEMPLATE.equals(source));
            uploadRow.setVisible(SOURCE_UPLOAD.equals(source));
            gitRow.setVisible(SOURCE_GIT.equals(source));
            gitHint.setVisible(SOURCE_GIT.equals(source));
        };
        sourceCombo.addValueChangeListener(e -> refresh.run());
        refresh.run();

        applyTemplateBtn.addClickListener(e -> applyTemplate(ComposeTemplates.byName(templateCombo.getValue()), true));
        pullBtn.addClickListener(e -> doGitPull());

        root.addComponent(ComponentFactory.getHsplitLine());
        Label tip = ComponentFactory.getStandardLabel(
                "模板里的端口、密码、版本号都是 ${VAR:-默认值} 形式，改「3. .env 变量」页签即可，不用动 yml。");
        tip.addStyleName("docker-hint");
        tip.setWidth("100%");
        root.addComponent(tip);
        root.setExpandRatio(tip, 1f);
        return root;
    }

    /* ================================================================== */
    /* 页签 2：文件                                                        */
    /* ================================================================== */

    private VerticalLayout buildFileTab() {
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        fileCombo = ComponentFactory.getStandardComboBox("当前文件：");
        fileCombo.setWidth("260px");
        fileCombo.setTextInputAllowed(false);
        fileCombo.setEmptySelectionAllowed(false);
        newFileField = ComponentFactory.getStandardTtextField();
        newFileField.setWidth("240px");
        newFileField.setPlaceholder("如 docker-compose.override.yml");
        Button addFile = ComponentFactory.getStandardButton("新增文件");
        addFile.setWidth("100px");
        Button removeFile = ComponentFactory.getStandardButton("删除文件");
        removeFile.setWidth("100px");
        bar.addComponents(fileCombo, newFileField, addFile, removeFile);
        root.addComponent(bar);

        Label hint = ComponentFactory.getStandardLabel(
                "用「新增文件」可以叠加多个 compose 文件（-f base.yml -f override.yml）。"
                        + "顺序就是上面下拉框的顺序，后面的文件会覆盖前面的同名配置。"
                        + "标签页支持缩进（Tab / 回车自动缩进），左边有行号。");
        hint.addStyleName("docker-hint");
        hint.setWidth("100%");
        root.addComponent(hint);

        editor = new ComposeYamlEditor("", "630px");
        root.addComponent(editor);
        root.setExpandRatio(editor, 1f);

        drafts.put(ComposeService.DEFAULT_FILE, ComposeTemplates.BLANK);
        currentFile = ComposeService.DEFAULT_FILE;
        fileCombo.setItems(new ArrayList<String>(drafts.keySet()));
        fileCombo.setValue(currentFile);
        editor.setValue(drafts.get(currentFile));

        fileCombo.addValueChangeListener(e -> switchFile(e.getValue()));
        addFile.addClickListener(e -> addFile(StrUtil.trimToEmpty(newFileField.getValue())));
        removeFile.addClickListener(e -> removeFile(fileCombo.getValue()));
        return root;
    }

    private void switchFile(String next) {
        if (StrUtil.isBlank(next) || next.equals(currentFile)) {
            return;
        }
        stashCurrent();
        currentFile = next;
        editor.setValue(StrUtil.emptyToDefault(drafts.get(next), ""));
        refreshVarCheck();
    }

    private void stashCurrent() {
        if (null != currentFile && null != editor) {
            drafts.put(currentFile, editor.getValue());
        }
    }

    private void addFile(String name) {
        if (StrUtil.isBlank(name)) {
            Notification.show("请先填文件名", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (name.contains("/") || name.contains(" ")) {
            Notification.show("文件名不能包含空格或斜杠", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (drafts.containsKey(name)) {
            Notification.show("文件已存在", Notification.Type.WARNING_MESSAGE);
            return;
        }
        stashCurrent();
        drafts.put(name, "");
        fileCombo.setItems(new ArrayList<String>(drafts.keySet()));
        fileCombo.setValue(name);
        newFileField.setValue("");
        switchFile(name);
    }

    private void removeFile(String name) {
        if (StrUtil.isBlank(name)) {
            return;
        }
        if (drafts.size() <= 1) {
            Notification.show("至少要保留一个 compose 文件", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (ComposeService.DEFAULT_FILE.equals(name)) {
            Notification.show("基础文件 " + ComposeService.DEFAULT_FILE + " 不能删除，"
                    + "叠加文件是可以删的", Notification.Type.WARNING_MESSAGE);
            return;
        }
        drafts.remove(name);
        currentFile = null;
        String first = drafts.keySet().iterator().next();
        fileCombo.setItems(new ArrayList<String>(drafts.keySet()));
        fileCombo.setValue(first);
        switchFile(first);
    }

    /* ================================================================== */
    /* 页签 3：.env                                                       */
    /* ================================================================== */

    private VerticalLayout buildEnvTab() {
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);

        Label hint = ComponentFactory.getStandardLabel(
                "KEY=VALUE 一行一个，# 开头是注释。compose 的取值顺序是：shell 环境变量 → .env → yml 里的默认值，"
                        + "所以 .env 里的值会覆盖 ${VAR:-默认值} 的默认值。");
        hint.addStyleName("docker-hint");
        hint.setWidth("100%");
        root.addComponent(hint);

        envArea = ComponentFactory.getTextArea();
        envArea.setSizeFull();
        envArea.setHeight("480px");
        envArea.addStyleName("docker-inspect-area");
        // 默认的 LAZY 模式要等失焦才回传，改完 .env 想看变量检查结果得点别处一下；
        // 500ms 的间隔足够「停下来时自动更新」，又不会每敲一个键发一次请求
        // Vaadin 8 里 TextChangeEventMode/setTextChangeTimeout 这套 API 已经换成 ValueChangeMode 了，
        // 用旧名字编译不过（AbstractTextField 里没有 TextChangeEventMode 这个类型）
        envArea.setValueChangeMode(com.vaadin.shared.ui.ValueChangeMode.LAZY);
        envArea.setValueChangeTimeout(500);
        envArea.setValue("# 环境变量（KEY=VALUE，# 开头是注释）\n"
                + "# 这个文件里的值会覆盖 compose 文件里 ${VAR:-默认值} 的默认值\n");
        root.addComponent(envArea);
        root.setExpandRatio(envArea, 1f);

        varLabel = ComponentFactory.getStandardLabel("");
        varLabel.addStyleName("compose-warn-label");
        varLabel.setWidth("100%");
        root.addComponent(varLabel);
        envArea.addValueChangeListener(e -> refreshVarCheck());
        return root;
    }

    /** 变量检查：找 yml 里引用了、但 .env 里没给值、又没有默认值的变量 —— 这类会导致 config 直接报错 */
    private void refreshVarCheck() {
        if (null == varLabel) {
            return;
        }
        stashCurrent();
        Map<String, String> env = ComposeService.parseEnv(envArea.getValue());
        List<String> missing = new ArrayList<String>();
        List<String> used = new ArrayList<String>();
        for (Map.Entry<String, String> entry : drafts.entrySet()) {
            String text = entry.getValue();
            for (String name : ComposeService.extractVariables(text)) {
                if (!used.contains(name)) {
                    used.add(name);
                }
                // 有默认值的写法 ${VAR:-x} 不会被 extractVariables 区分，这里再单独看一眼
                if (!env.containsKey(name) && !hasDefault(text, name) && !missing.contains(name)) {
                    missing.add(name);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("compose 文件里引用了 ").append(used.size()).append(" 个变量");
        if (used.isEmpty()) {
            sb.append("（如果确实不需要变量可以不写 .env）");
        } else if (missing.isEmpty()) {
            sb.append("，都已在 .env 或默认值中提供");
        } else {
            sb.append("，其中 ").append(missing.size()).append(" 个既没写默认值、.env 里也没有：")
                    .append(StrUtil.join(", ", missing));
        }
        varLabel.setValue(sb.toString());
    }

    private static boolean hasDefault(String text, String name) {
        String pattern = "${" + name + ":-";
        return text.contains(pattern);
    }

    /* ================================================================== */
    /* 页签 4：校验                                                        */
    /* ================================================================== */

    private VerticalLayout buildCheckTab() {
        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);

        HorizontalLayout bar = new HorizontalLayout();
        bar.setSpacing(true);
        bar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        Button check = ComponentFactory.getPrimaryButtonWithType("校验并预览最终配置",
                com.so.component.util.ButtonType.PRIMARY);
        check.setWidth("200px");
        Label hint = ComponentFactory.getStandardLabel(
                "会在目标机上建一个临时目录跑真正的 docker compose config，把多文件叠加、变量替换之后的"
                        + "最终配置和报错原样带回来，用完立刻删除临时目录。");
        hint.addStyleName("docker-hint");
        bar.addComponents(check, hint);
        bar.setExpandRatio(hint, 1f);
        root.addComponent(bar);

        resultLabel = ComponentFactory.getStandardLabel("还没有校验过");
        resultLabel.setWidth("100%");
        root.addComponent(resultLabel);

        resultArea = ComponentFactory.getTextArea();
        resultArea.setSizeFull();
        resultArea.setReadOnly(true);
        resultArea.addStyleName("docker-inspect-area");
        root.addComponent(resultArea);
        root.setExpandRatio(resultArea, 1f);

        check.addClickListener(e -> doCheck());
        return root;
    }

    private void doCheck() {
        final Map<String, String> files = currentFiles();
        if (files.isEmpty()) {
            Notification.show("请先填写 compose 文件内容", Notification.Type.WARNING_MESSAGE);
            return;
        }
        final String env = envArea.getValue();
        resultLabel.setValue("正在校验 …");
        resultLabel.removeStyleName("compose-warn-label");
        owner.setBusy(true, "正在校验 compose 配置 …");
        DockerUi.async("校验 compose 配置",
                new DockerUi.Task<ComposeService.ExecOutcome>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public ComposeService.ExecOutcome run() throws Exception {
                        return compose.validatePreview(files, env);
                    }
                }, new DockerUi.Done<ComposeService.ExecOutcome>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void done(ComposeService.ExecOutcome outcome) {
                        owner.setBusy(false, "");
                        if (outcome.isOk()) {
                            resultLabel.setValue("✓ 校验通过，下面是变量替换后的最终配置"
                                    + (StrUtil.isBlank(outcome.getStderr()) ? ""
                                    : "（compose 警告：" + outcome.getStderr().trim() + "）"));
                            resultArea.setValue(outcome.getStdout());
                        } else {
                            resultLabel.addStyleName("compose-warn-label");
                            resultLabel.setValue("✗ 校验失败（退出码 " + outcome.getExitCode() + "）");
                            resultArea.setValue(outcome.getErrorText());
                        }
                    }
                }, new DockerUi.Failure() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onFailure(Exception e) {
                        owner.setBusy(false, "");
                        resultLabel.addStyleName("compose-warn-label");
                        resultLabel.setValue("校验失败：" + DockerUi.reason(e));
                        resultArea.setValue(StrUtil.emptyToDefault(e.getMessage(), ""));
                    }
                });
    }

    /* ================================================================== */
    /* 创建 / Git 拉取                                                     */
    /* ================================================================== */

    private Map<String, String> currentFiles() {
        stashCurrent();
        Map<String, String> files = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : drafts.entrySet()) {
            if (StrUtil.isNotBlank(entry.getValue())) {
                files.put(entry.getKey(), entry.getValue());
            }
        }
        return files;
    }

    private void doGitPull() {
        final String name = StrUtil.trimToEmpty(nameField.getValue());
        final String repo = StrUtil.trimToEmpty(repoField.getValue());
        final String branch = StrUtil.trimToEmpty(branchField.getValue());
        try {
            ComposeService.checkName(name);
        } catch (Exception e) {
            Notification.show(e.getMessage(), Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (StrUtil.isBlank(repo)) {
            Notification.show("请填写 Git 仓库地址", Notification.Type.WARNING_MESSAGE);
            return;
        }
        final ComposeProject project = new ComposeProject();
        project.setName(name);
        project.setDirectory(baseDir + "/" + name);
        project.setManaged(true);
        project.setFiles(new ArrayList<String>(Arrays.asList(ComposeService.DEFAULT_FILE)));
        owner.setBusy(true, "正在从 Git 拉取 " + name + " …");
        DockerUi.async("从 Git 拉取项目",
                new DockerUi.Task<Void>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Void run() throws Exception {
                        compose.syncFromGit(project, repo, branch);
                        return null;
                    }
                }, new DockerUi.Done<Void>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void done(Void value) {
                        owner.setBusy(false, "");
                        Map<String, String> loaded = new LinkedHashMap<String, String>();
                        try {
                            Map<String, String> read = compose.readProjectFiles(project);
                            for (Map.Entry<String, String> entry : read.entrySet()) {
                                loaded.put(entry.getKey(), entry.getValue());
                            }
                        } catch (Exception e) {
                            log.warn("读取拉取下来的 compose 文件失败：{}", e.getMessage());
                        }
                        if (loaded.isEmpty()) {
                            Notification.show("拉取完成，但仓库里没有找到 docker-compose.yml / compose.yml",
                                    Notification.Type.WARNING_MESSAGE);
                        } else {
                            drafts.clear();
                            drafts.putAll(loaded);
                            currentFile = null;
                            String first = drafts.keySet().iterator().next();
                            fileCombo.setItems(new ArrayList<String>(drafts.keySet()));
                            fileCombo.setValue(first);
                            switchFile(first);
                            Notification.show("拉取完成，已载入 " + loaded.size() + " 个 compose 文件",
                                    Notification.Type.HUMANIZED_MESSAGE);
                        }
                    }
                }, new DockerUi.Failure() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onFailure(Exception e) {
                        owner.setBusy(false, "");
                        Notification.show("拉取失败：" + DockerUi.reason(e),
                                Notification.Type.ERROR_MESSAGE);
                    }
                });
    }

    private void doCreate() {
        final String name = StrUtil.trimToEmpty(nameField.getValue());
        try {
            ComposeService.checkName(name);
        } catch (Exception e) {
            Notification.show(e.getMessage(), Notification.Type.WARNING_MESSAGE);
            return;
        }
        final Map<String, String> files = currentFiles();
        if (files.isEmpty()) {
            Notification.show("compose 文件不能为空", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (!files.containsKey(ComposeService.DEFAULT_FILE)) {
            Notification.show("必须包含一个基础文件 " + ComposeService.DEFAULT_FILE + "，"
                    + "否则 compose 找不到默认配置", Notification.Type.WARNING_MESSAGE);
            return;
        }
        final String desc = StrUtil.trimToEmpty(descField.getValue());
        final String env = envArea.getValue();
        owner.setBusy(true, "正在创建项目 " + name + " …");
        DockerUi.async("创建 compose 项目",
                new DockerUi.Task<ComposeProject>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public ComposeProject run() throws Exception {
                        return compose.createProject(baseDir, name, desc, files, env);
                    }
                }, new DockerUi.Done<ComposeProject>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void done(ComposeProject project) {
                        owner.setBusy(false, "");
                        created = true;
                        Notification.show("项目 " + project.getName() + " 已创建",
                                "目录：" + project.getDirectory() + "，可以在列表里启动它了。",
                                Notification.Type.HUMANIZED_MESSAGE);
                        log.info("用户 {} 创建了 compose 项目 {}（目录 {}，文件 {}）",
                                com.so.component.ComponentUtil.getCurrentUserName(), project.getName(),
                                project.getDirectory(), project.getFiles());
                        close();
                    }
                }, new DockerUi.Failure() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onFailure(Exception e) {
                        owner.setBusy(false, "");
                        Notification.show("创建失败：" + DockerUi.reason(e),
                                Notification.Type.ERROR_MESSAGE);
                    }
                });
    }

    /* ================================================================== */
    /* 模板 / 上传                                                         */
    /* ================================================================== */

    private void applyTemplate(ComposeTemplate template, boolean notify) {
        if (null == template) {
            return;
        }
        if (!drafts.containsKey(ComposeService.DEFAULT_FILE)) {
            drafts.clear();
            drafts.put(ComposeService.DEFAULT_FILE, "");
        }
        drafts.put(ComposeService.DEFAULT_FILE, template.getYaml());
        if (StrUtil.isNotBlank(template.getEnv())) {
            envArea.setValue(template.getEnv());
        }
        currentFile = null;
        fileCombo.setItems(new ArrayList<String>(drafts.keySet()));
        fileCombo.setValue(ComposeService.DEFAULT_FILE);
        switchFile(ComposeService.DEFAULT_FILE);
        if (StrUtil.isBlank(nameField.getValue())) {
            String suggestion = template.getName().toLowerCase().replaceAll("[^a-z0-9]+", "-");
            suggestion = suggestion.replaceAll("^-+|-+$", "");
            if (StrUtil.isNotBlank(suggestion)) {
                nameField.setValue(suggestion);
            }
        }
        if (notify) {
            Notification.show("已套用模板 " + template.getName(), Notification.Type.HUMANIZED_MESSAGE);
        }
    }

    /** 上传本地 yml：只把内容取到浏览器这一侧，不落远端，用户确认后再创建 */
    private class YamlUploadReceiver implements Upload.Receiver, Upload.SucceededListener {

        private static final long serialVersionUID = 1L;

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private String fileName = "";

        @Override
        public OutputStream receiveUpload(String filename, String mimeType) {
            buffer.reset();
            fileName = StrUtil.emptyToDefault(filename, "docker-compose.yml");
            return buffer;
        }

        @Override
        public void uploadSucceeded(Upload.SucceededEvent event) {
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            if (StrUtil.isBlank(text)) {
                Notification.show("上传的文件是空的", Notification.Type.WARNING_MESSAGE);
                return;
            }
            stashCurrent();
            drafts.put(ComposeService.DEFAULT_FILE, text);
            currentFile = null;
            fileCombo.setItems(new ArrayList<String>(drafts.keySet()));
            fileCombo.setValue(ComposeService.DEFAULT_FILE);
            switchFile(ComposeService.DEFAULT_FILE);
            refreshVarCheck();
            Notification.show("已载入 " + fileName + "（" + text.split("\n").length + " 行）",
                    Notification.Type.HUMANIZED_MESSAGE);
        }
    }

    /* ================================================================== */

    /** 兜底：万一有没被 switchFile 覆盖到的入口（比如用户直接关窗），创建时也要拿得到最新内容 */
    @Override
    public void detach() {
        try {
            stashCurrent();
        } catch (Exception e) {
            log.debug("窗口关闭时保存草稿失败：{}", e.getMessage());
        }
        super.detach();
    }

    /** 创建前展示用：目标机的 compose CLI 是否可用 */
    public boolean cliReady() {
        ComposeCliInfo cli = owner.getCliInfo();
        return null != cli && cli.isInstalled();
    }

    /** 供外部（脚本/调试）读取当前编辑内容的入口 */
    public Map<String, String> getDrafts() {
        return drafts;
    }
}
