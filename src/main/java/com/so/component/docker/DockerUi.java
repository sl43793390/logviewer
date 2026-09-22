package com.so.component.docker;

import cn.hutool.core.util.StrUtil;
import com.so.docker.DockerDaemonDownException;
import com.vaadin.ui.Notification;
import com.vaadin.ui.UI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * Docker 页面的后台执行工具。
 * <p>
 * docker 命令全是 100ms 起步的远程调用，绝不能在 Vaadin 请求线程里同步执行
 * （项目里 {@code RemoteMonitorComponent} 已经踩过：请求线程里做阻塞 IO 会让整个界面僵住）。
 * 统一走「后台线程执行 + {@code UI.access} 回主线程刷界面」，
 * 页面开了 {@code @Push}，所以不需要等下一次浏览器请求就能刷新。
 * <p>
 * 放在这里而不是各个页面里各写一份，是因为弹窗（创建容器、详情）不在
 * {@code CommonComponent} 的生命周期里，拿不到页面那套辅助方法。
 * <p>
 * <b>失败日志的噪音控制。</b>{@link DockerDaemonDownException} 表示"daemon 没运行，
 * 命令压根没发出去"——这不是程序错误，日志里只留一行 warn，不打堆栈、不弹红色错误，
 * 由页面弹「是否启动 Docker 服务」的窗口来处理。原来这里对所有异常都
 * {@code log.error(msg, e)} 并弹错，daemon 没起时同一条栈能刷满整个日志文件。
 */
public final class DockerUi {

    private static final Logger log = LoggerFactory.getLogger(DockerUi.class);

    /** 后台任务，允许抛受检异常 */
    public interface Task<T> extends Serializable {
        T run() throws Exception;
    }

    /** 任务成功后在 UI 线程执行 */
    public interface Done<T> extends Serializable {
        void done(T value) throws Exception;
    }

    /** 任务失败后在 UI 线程执行 */
    public interface Failure extends Serializable {
        void onFailure(Exception e);
    }

    /** 「正在执行」提示的开关 */
    public interface Busy extends Serializable {
        void setBusy(boolean busy, String text);
    }

    private DockerUi() {
    }

    public static <T> void async(String action, Task<T> task, Done<T> done) {
        async(action, task, done, null, null);
    }

    public static <T> void async(String action, Task<T> task, Done<T> done, Failure onFailure) {
        async(action, task, done, null, onFailure);
    }

    public static <T> void async(final String action, final Task<T> task, final Done<T> done,
                                 final Busy busy, final Failure onFailure) {
        final UI ui = UI.getCurrent();
        if (null == ui) {
            log.warn("没有可用的 UI，跳过任务：{}", action);
            return;
        }
        if (null != busy) {
            busy.setBusy(true, action + " …");
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final T result = task.run();
                    access(ui, new Runnable() {
                        @Override
                        public void run() {
                            if (null != busy) {
                                busy.setBusy(false, "");
                            }
                            if (null == done) {
                                return;
                            }
                            try {
                                done.done(result);
                            } catch (Exception e) {
                                notifyError(action + " 处理结果出错", e);
                            }
                        }
                    });
                } catch (final Exception e) {
                    final boolean daemonDown = isDaemonDown(e);
                    if (daemonDown) {
                        log.warn("{} 已中止：{}", action, e.getMessage());
                    } else {
                        log.error("{} 失败：{}", action, e.getMessage(), e);
                    }
                    access(ui, new Runnable() {
                        @Override
                        public void run() {
                            if (null != busy) {
                                busy.setBusy(false, "");
                            }
                            if (!daemonDown) {
                                Notification.show(action + " 失败：" + reason(e), Notification.Type.ERROR_MESSAGE);
                            } else if (null == onFailure) {
                                // 调用方没有失败回调（例如详情弹窗里的一次性操作），
                                // 这里给一句人话，避免"点了没反应"
                                Notification.show(daemonDownHint, Notification.Type.WARNING_MESSAGE);
                            }
                            if (null != onFailure) {
                                try {
                                    onFailure.onFailure(e);
                                } catch (Exception ignore) {
                                    log.warn("失败回调本身出错：{}", ignore.getMessage());
                                }
                            }
                        }
                    });
                }
            }
        }, "docker-" + action);
        thread.setDaemon(true);
        thread.start();
    }

    /** daemon 不可用时给用户看的一句话 */
    public static final String daemonDownHint = "目标机的 docker 服务未运行，已停止继续发送命令。";

    /** 判断异常是不是"daemon 不可用"这一类（连命令都没发出去） */
    public static boolean isDaemonDown(Throwable e) {
        if (e instanceof DockerDaemonDownException) {
            return true;
        }
        // 兜底：某些路径会把原因包在别的 IOException 里
        Throwable cause = (null == e) ? null : e.getCause();
        return cause instanceof DockerDaemonDownException;
    }

    public static void notifyError(String prefix, Throwable e) {
        Notification.show(prefix + "：" + reason(e), Notification.Type.ERROR_MESSAGE);
    }

    public static String reason(Throwable e) {
        if (null == e) {
            return "未知错误";
        }
        return StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName());
    }

    private static void access(UI ui, Runnable runnable) {
        try {
            ui.access(runnable);
        } catch (Exception e) {
            // UI 已 detach（用户关掉了标签页 / 弹窗），忽略
            log.debug("UI 已关闭，跳过回调：{}", e.getMessage());
        }
    }
}
