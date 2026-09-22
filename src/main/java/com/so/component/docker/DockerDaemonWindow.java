package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.component.util.ButtonType;
import com.so.docker.DockerExecutor;
import com.so.docker.model.DockerDaemonStatus;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 「Docker 不可用」提示窗。
 * <p>
 * 两种情况共用这个窗口，因为用户的下一步动作是一样的 —— 都不该继续发 docker 命令：
 * <ol>
 *   <li><b>目标机上没装 docker</b>：直接说"当前系统没有 docker，请先安装后再进行管理"，
 *       并按探测到的发行版给出安装命令（CentOS 7 / Rocky / Ubuntu 各一份）；</li>
 *   <li><b>装了但服务没起</b>：给出未运行的原因，问用户要不要现在启动，
 *       并明确列出将要执行的命令（systemd → service → init 脚本）。</li>
 * </ol>
 * 启动失败时不吞错误：把 {@code systemctl is-active} 与 {@code journalctl -u docker}
 * 的末尾几行原样贴在窗口里，否则用户只能看到一个"exit 1"。
 */
public class DockerDaemonWindow extends Window {

    private static final long serialVersionUID = 1L;

    /** 服务就绪后的回调（把各页数据重新拉一遍） */
    public interface Started extends Serializable {
        void onDockerReady(DockerDaemonStatus status);
    }

    private final DockerExecutor executor;
    private final Started listener;

    private DockerDaemonStatus status;

    private final Label titleLabel;
    private final Label summaryLabel;
    private final TextArea diagnosisArea;
    private final TextArea detailArea;
    private final TextArea errorArea;
    private final TextArea commandArea;
    private final Label commandCaption;
    private final TextArea reportArea;
    private final Button startBtn;
    private final Button recheckBtn;

    public DockerDaemonWindow(DockerExecutor executor, DockerDaemonStatus status, Started listener) {
        this.executor = executor;
        this.status = (null == status) ? new DockerDaemonStatus() : status;
        this.listener = listener;

        setWidth("680px");
        setHeight("660px");
        setResizable(true);
        setModal(false);
        center();

        VerticalLayout root = new VerticalLayout();
        root.setWidth("100%");
        root.setHeight("100%");
        root.setMargin(true);
        root.setSpacing(true);

        titleLabel = new Label("");
        titleLabel.addStyleName("docker-daemon-title");
        titleLabel.setWidth("100%");
        root.addComponent(titleLabel);

        summaryLabel = ComponentFactory.getStandardLabel("");
        summaryLabel.addStyleName("docker-daemon-line");
        summaryLabel.setWidth("100%");
        root.addComponent(summaryLabel);

        diagnosisArea = readOnlyArea("100%", "130px");
        diagnosisArea.addStyleName("docker-daemon-text");
        root.addComponent(diagnosisArea);

        detailArea = readOnlyArea("100%", "80px");
        detailArea.addStyleName("docker-daemon-text");
        root.addComponent(detailArea);

        errorArea = readOnlyArea("100%", "70px");
        errorArea.addStyleName("docker-daemon-text");
        root.addComponent(errorArea);

        commandCaption = new Label("");
        commandCaption.addStyleName("docker-daemon-line");
        commandCaption.setWidth("100%");
        root.addComponent(commandCaption);

        commandArea = readOnlyArea("100%", "110px");
        commandArea.addStyleName("docker-daemon-cmd");
        root.addComponent(commandArea);

        reportArea = readOnlyArea("100%", "170px");
        reportArea.addStyleName("docker-daemon-text");
        reportArea.setVisible(false);
        root.addComponent(reportArea);

        HorizontalLayout buttons = new HorizontalLayout();
        buttons.setSpacing(true);
        buttons.setWidth("100%");
        buttons.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        startBtn = ComponentFactory.getPrimaryButtonWithType("启动 Docker 服务", ButtonType.SUCCESS);
        startBtn.setWidth("170px");
        startBtn.setHeight("30px");
        recheckBtn = ComponentFactory.getStandardButton("重新检测");
        recheckBtn.setWidth("120px");
        recheckBtn.setHeight("30px");
        Button closeBtn = ComponentFactory.getStandardButton("关闭");
        closeBtn.setWidth("90px");
        closeBtn.setHeight("30px");
        buttons.addComponents(startBtn, recheckBtn, closeBtn);
        root.addComponent(buttons);

        setContent(root);

        applyStatus(this.status);

        startBtn.addClickListener(e -> startDocker());
        recheckBtn.addClickListener(e -> recheck());
        closeBtn.addClickListener(e -> close());
    }

    /* ------------------------------------------------------------------ */
    /* 状态渲染                                                            */
    /* ------------------------------------------------------------------ */

    private void applyStatus(DockerDaemonStatus now) {
        this.status = (null == now) ? new DockerDaemonStatus() : now;

        if (!status.isDockerInstalled()) {
            titleLabel.setValue("目标机未安装 Docker");
            summaryLabel.setValue(status.summary());
            diagnosisArea.setValue("当前系统没有检测到 docker，请先安装后再进行管理。\n\n" + status.diagnosis());
            commandCaption.setValue("在目标机上安装 Docker（按探测到的发行版选择）：");
            commandArea.setValue(StrUtil.join("\n", installHintsOf(status)));
            errorArea.setVisible(false);
            startBtn.setVisible(false);
        } else {
            titleLabel.setValue("Docker 服务未运行");
            summaryLabel.setValue(status.summary());
            diagnosisArea.setValue(status.diagnosis());
            commandCaption.setValue("点击「启动 Docker 服务」将依次执行（失败自动换下一条）：");
            commandArea.setValue(status.getStartCommandPreview());
            errorArea.setVisible(true);
            errorArea.setValue(StrUtil.isBlank(status.getDaemonError())
                    ? "（目标机没有返回错误详情）"
                    : "docker 的原始报错：\n" + status.getDaemonError());
            startBtn.setVisible(true);
            startBtn.setEnabled(!status.buildStartCommands().isEmpty());
        }
        detailArea.setValue(detailLines(status));
        setCaption(titleLabel.getValue());
    }

    /** 探测到的环境细节，出问题时这几行往往能直接定位 */
    private static String detailLines(DockerDaemonStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append("docker 命令：").append(StrUtil.emptyToDefault(status.getBinaryPath(), "未探测到")).append('\n');
        sb.append("登录用户：").append(StrUtil.emptyToDefault(status.getLoginUser(), "?"))
                .append("(uid=").append(StrUtil.emptyToDefault(status.getUid(), "?")).append(")")
                .append("　免密 sudo：").append(status.isSudoAvailable() ? "可用" : "不可用").append('\n');
        sb.append("服务管理：");
        if (status.isSystemdUsable() && StrUtil.isNotBlank(status.getServiceUnit())) {
            sb.append("systemd / ").append(status.getServiceUnit());
        } else if (status.isServiceCmdAvailable()) {
            sb.append("service 命令");
        } else if (status.isSysvScriptAvailable()) {
            sb.append("/etc/init.d/docker");
        } else {
            sb.append("未找到服务脚本");
        }
        if (status.isDockerdBinary()) {
            sb.append("　（存在 dockerd 可执行文件）");
        }
        sb.append('\n');
        sb.append("dockerd 进程：").append(status.isDaemonProcess() ? "在" : "不在")
                .append("　发行版：").append(StrUtil.emptyToDefault(status.getOsRelease(), "未知"));
        return sb.toString();
    }

    /**
     * 各发行版的安装命令。
     * <p>
     * 静态方法是为了让主页面上的占位提示复用同一份文案。
     * 只按探测到的 {@code PRETTY_NAME} 给出最相关的那一段，探测不到才都列出来，
     * 免得用户在一堆无关命令里找。
     */
    public static List<String> installHintsOf(DockerDaemonStatus status) {
        DockerDaemonStatus current = (null == status) ? new DockerDaemonStatus() : status;
        List<String> hints = new ArrayList<String>();
        String os = StrUtil.trimToEmpty(current.getOsRelease()).toLowerCase();
        boolean rhel = os.contains("centos") || os.contains("red hat") || os.contains("rhel")
                || os.contains("rocky") || os.contains("almalinux") || os.contains("fedora")
                || os.contains("oracle") || os.contains("openeuler") || os.contains("anolis");
        boolean debian = os.contains("ubuntu") || os.contains("debian") || os.contains("kylin")
                || os.contains("uos") || os.contains("deepin");

        if (rhel || StrUtil.isBlank(os)) {
            hints.add("# CentOS 7（用系统仓库自带的 docker 即可）");
            hints.add("yum install -y docker && systemctl enable --now docker");
            hints.add("");
            hints.add("# Rocky Linux 8/9/10（官方 docker-ce）");
            hints.add("dnf install -y dnf-plugins-core");
            hints.add("dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo");
            hints.add("dnf install -y docker-ce docker-ce-cli containerd.io");
            hints.add("systemctl enable --now docker");
        }
        if (debian) {
            if (!hints.isEmpty()) {
                hints.add("");
            }
            hints.add("# Ubuntu 22.04 / Debian");
            hints.add("apt update");
            hints.add("apt install -y docker.io");
            hints.add("systemctl enable --now docker");
        }
        if (hints.isEmpty()) {
            hints.add("Rocky / CentOS：dnf install -y docker-ce　（CentOS 7 用 yum install -y docker）");
            hints.add("Ubuntu / Debian：apt install -y docker.io");
            hints.add("装完统一执行：systemctl enable --now docker");
        }
        hints.add("");
        hints.add("# 安装后让当前用户免 sudo 使用 docker（可选，重新登录后生效）");
        hints.add("usermod -aG docker " + StrUtil.emptyToDefault(current.getLoginUser(), "$USER"));
        hints.add("");
        hints.add("# 装完回到本页点「重新检测」即可开始管理");
        return hints;
    }

    /* ------------------------------------------------------------------ */
    /* 动作                                                                */
    /* ------------------------------------------------------------------ */

    private void startDocker() {
        if (!LoginView.checkPermission(Constants.UPDATE)) {
            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
            return;
        }
        if (null == executor || executor.isClosed()) {
            Notification.show("与目标机的连接已断开，请重新连接后再试", Notification.Type.WARNING_MESSAGE);
            return;
        }
        startBtn.setEnabled(false);
        recheckBtn.setEnabled(false);
        DockerUi.async("启动 Docker 服务", new DockerUi.Task<DockerExecutor.DaemonStartResult>() {
            private static final long serialVersionUID = 1L;

            @Override
            public DockerExecutor.DaemonStartResult run() throws Exception {
                return executor.startDaemon();
            }
        }, new DockerUi.Done<DockerExecutor.DaemonStartResult>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(DockerExecutor.DaemonStartResult result) {
                startBtn.setEnabled(true);
                recheckBtn.setEnabled(true);
                showReport(result.getReport());
                applyStatus(result.getStatus());
                if (result.isSuccess()) {
                    Notification.show("Docker 服务已启动",
                            "命令前缀：" + executor.getCommandPrefix(), Notification.Type.HUMANIZED_MESSAGE);
                    if (null != listener) {
                        listener.onDockerReady(result.getStatus());
                    }
                    close();
                } else {
                    Notification.show("Docker 服务未能启动", "请查看窗口下方的执行报告与日志",
                            Notification.Type.ERROR_MESSAGE);
                }
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                startBtn.setEnabled(true);
                recheckBtn.setEnabled(true);
                showReport(DockerUi.reason(e));
            }
        });
    }

    private void recheck() {
        if (null == executor || executor.isClosed()) {
            Notification.show("与目标机的连接已断开，请重新连接后再试", Notification.Type.WARNING_MESSAGE);
            return;
        }
        recheckBtn.setEnabled(false);
        startBtn.setEnabled(false);
        DockerUi.async("重新检测 Docker 状态", new DockerUi.Task<DockerDaemonStatus>() {
            private static final long serialVersionUID = 1L;

            @Override
            public DockerDaemonStatus run() throws Exception {
                return executor.probeDaemon(true);
            }
        }, new DockerUi.Done<DockerDaemonStatus>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void done(DockerDaemonStatus now) {
                recheckBtn.setEnabled(true);
                applyStatus(now);
                if (now.isDaemonRunning()) {
                    Notification.show("Docker 服务正在运行",
                            "服务端版本 " + StrUtil.emptyToDefault(now.getServerVersion(), "?"),
                            Notification.Type.HUMANIZED_MESSAGE);
                    if (null != listener) {
                        listener.onDockerReady(now);
                    }
                    close();
                } else if (!now.isDockerInstalled()) {
                    Notification.show("目标机仍未安装 docker", "请先按窗口里的命令安装后再试",
                            Notification.Type.WARNING_MESSAGE);
                } else {
                    Notification.show("Docker 服务仍未运行", DockerUi.daemonDownHint,
                            Notification.Type.WARNING_MESSAGE);
                }
            }
        }, new DockerUi.Failure() {
            private static final long serialVersionUID = 1L;

            @Override
            public void onFailure(Exception e) {
                recheckBtn.setEnabled(true);
                showReport("重新检测失败：" + DockerUi.reason(e));
            }
        });
    }

    private void showReport(String text) {
        reportArea.setValue(StrUtil.emptyToDefault(text, ""));
        reportArea.setVisible(true);
    }

    private TextArea readOnlyArea(String width, String height) {
        TextArea area = ComponentFactory.getStandardTtextArea();
        area.setReadOnly(true);
        area.setWidth(width);
        area.setHeight(height);
        return area;
    }
}
