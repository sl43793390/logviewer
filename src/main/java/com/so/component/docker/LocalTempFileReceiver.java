package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.docker.DockerService;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Upload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 把上传的文件先落到本机临时目录。
 * <p>
 * 用于「上传 Dockerfile 构建」「导入镜像包」「上传文件到容器」这几处：
 * 它们的共同点是本地文件要先完整落盘，之后才有一整套远程动作
 * （SFTP 推送到宿主机 → docker build / load / cp）。
 * <p>
 * 临时文件由调用方负责删除；不放在 webapp 目录下，避免被 Vaadin 当成静态资源暴露。
 */
public class LocalTempFileReceiver implements Upload.Receiver, Upload.SucceededListener {

    private static final Logger log = LoggerFactory.getLogger(LocalTempFileReceiver.class);

    private static final String SUB_DIR = "logviewer-docker-upload";

    private final String subDir;
    private File received;
    private String errorMessage;
    /** 上传完成后通知界面刷新文件名显示 */
    private Runnable onReceived;

    public LocalTempFileReceiver() {
        this(SUB_DIR);
    }

    public LocalTempFileReceiver(String subDir) {
        this.subDir = StrUtil.blankToDefault(subDir, SUB_DIR);
    }

    public void setOnReceived(Runnable onReceived) {
        this.onReceived = onReceived;
    }

    @Override
    public OutputStream receiveUpload(String filename, String mimeType) {
        errorMessage = null;
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), subDir);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建临时目录：" + dir.getAbsolutePath());
            }
            received = new File(dir, DockerService.sanitizeFileName(filename));
            return new FileOutputStream(received);
        } catch (IOException e) {
            errorMessage = e.getMessage();
            log.error("接收上传文件失败：{}", e.getMessage());
            Notification.show("接收上传文件失败：" + e.getMessage(), Notification.Type.ERROR_MESSAGE);
            return null;
        }
    }

    @Override
    public void uploadSucceeded(Upload.SucceededEvent event) {
        log.info("已接收上传文件：{}", null == received ? "(空)" : received.getAbsolutePath());
        if (null != onReceived) {
            onReceived.run();
        }
    }

    /** 已接收的本地临时文件，未上传时为 null */
    public File getFile() {
        return received;
    }

    public String getFileName() {
        return null == received ? "" : received.getName();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getSize() {
        return null == received ? 0L : received.length();
    }

    /** 用完后清理，避免临时目录越积越多 */
    public void cleanup() {
        if (null != received && received.exists() && !received.delete()) {
            log.warn("删除临时文件 {} 失败", received.getAbsolutePath());
        }
        received = null;
    }
}
