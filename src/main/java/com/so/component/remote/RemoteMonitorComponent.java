package com.so.component.remote;

import cn.hutool.core.util.NumberUtil;
import com.so.component.CommonComponent;
import com.so.entity.ConnectionInfo;
import com.so.ui.ComponentFactory;
import com.so.util.Constants;
import com.so.util.SSHClientUtil;
import com.vaadin.addon.charts.Chart;
import com.vaadin.addon.charts.model.*;
import com.vaadin.addon.charts.model.style.SolidColor;
import com.vaadin.ui.Label;
import com.vaadin.ui.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 服务器监控页面
 *
 * @author Administrator
 */
@Service
@Scope("prototype")
public class RemoteMonitorComponent extends CommonComponent {

    private static final Logger log = LoggerFactory.getLogger(RemoteMonitorComponent.class);

    private Panel mainPanel;
    private VerticalLayout contentLayout;
    private VerticalLayout monitorVerticalLayout;
    private ConnectionInfo currentConnectionInfo;
    private SSHClientUtil clientUtil;
    private Chart cupChart;
    private Chart diskChart;
    private Chart memoryChart;
    private UI currentUI;
    private ListSeries cupSeries;
    private ListSeries diskSerial;
    private ListSeries memorySeries;
    /** SSH 建链失败的原因，直接展示在页面上，避免只看到"数据不可用" */
    private String connectErrorMessage;

    @Override
    public void initLayout() {
        currentUI = UI.getCurrent();
        mainPanel = new Panel();
        contentLayout = new VerticalLayout();
        setCompositionRoot(mainPanel);
        mainPanel.setContent(contentLayout);
        contentLayout.setWidth("100%");
        contentLayout.setHeight("700px");
        initMainLayout();
        initMonitorLayout();
    }

    /**
     * 布局
     */
    private void initMainLayout() {
        contentLayout.removeAllComponents();
        HorizontalLayout horizontalLayout = ComponentFactory.getHorizontalLayout();
        horizontalLayout.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        Label lb = ComponentFactory.getStandardLabel(currentConnectionInfo.getIdHost());

        Label pathLb = ComponentFactory.getStandardLabel("主机：");
        horizontalLayout.addComponent(pathLb);
        horizontalLayout.addComponent(lb);
        horizontalLayout.setExpandRatio(lb, 1);
        contentLayout.addComponent(horizontalLayout);
        //链接服务器
        try {
            if (null == clientUtil && null != currentConnectionInfo) {
                clientUtil = SSHClientUtil.connect(currentConnectionInfo);
            }
        } catch (Exception e) {
            connectErrorMessage = e.getMessage();
            log.error("连接 {} 失败：{}", currentConnectionInfo.getIdHost(), e.getMessage());
            Notification.show("连接 " + currentConnectionInfo.getIdHost() + " 失败：" + e.getMessage(),
                    Notification.Type.ERROR_MESSAGE);
        }
    }

    @Override
    public void detach() {
        super.detach();
        // 连接失败时 clientUtil 为 null，原来这里会 NPE 并把异常吞在 detach 流程里
        if (null != clientUtil) {
            clientUtil.closeConnection();
        }
    }

    /**
     * 监控图表页面
     */
    private void initMonitorLayout() {
        if (null != monitorVerticalLayout) {
            contentLayout.removeComponent(monitorVerticalLayout);
        }
        monitorVerticalLayout = new VerticalLayout();
        contentLayout.addComponent(monitorVerticalLayout);
        contentLayout.setExpandRatio(monitorVerticalLayout, 1);
        HorizontalLayout firstLayout = ComponentFactory.getHorizontalLayout();
        monitorVerticalLayout.addComponent(firstLayout);
        firstLayout.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        // SSH 连接建不起来时 clientUtil 为 null，原来后面每个 updateXxx 都会 NPE，整页白屏
        if (null == clientUtil) {
            String host = (null == currentConnectionInfo) ? "" : currentConnectionInfo.getIdHost();
            monitorVerticalLayout.addComponent(
                    ComponentFactory.getStandardLabel("未能建立到 " + host + " 的 SSH 连接，监控数据不可用"));
            if (null != connectErrorMessage) {
                monitorVerticalLayout.addComponent(
                        ComponentFactory.getStandardLabel("失败原因：" + connectErrorMessage));
            }
            return;
        }
        //CUP使用率
        Chart chart = createCupUseageChart();
        firstLayout.addComponent(chart);
        //磁盘使用情况
        Chart diskChart = createDiskUsageChart();
        firstLayout.addComponent(diskChart);
        //内存使用率
        Chart memoryChart = createMemoryUsageChart();
        firstLayout.addComponent(memoryChart);

        //其它指标
        HorizontalLayout secondLayout = ComponentFactory.getHorizontalLayout();
        monitorVerticalLayout.addComponent(secondLayout);
        monitorVerticalLayout.setExpandRatio(secondLayout, 1);
    }

    private Chart createCupUseageChart() {
        cupChart = new Chart(ChartType.SOLIDGAUGE);
        cupChart.setWidth("300px");
        cupChart.setHeight("300px");
        Configuration conf = cupChart.getConfiguration();
        conf.setTitle("CPU使用率(%)");

        Pane pane = conf.getPane();
        pane.setSize("125%");           // For positioning tick labels
        pane.setCenter("50%", "70%"); // Move center lower
        pane.setStartAngle(-90);        // Make semi-circle
        pane.setEndAngle(90);           // Make semi-circle

        Background bkg = new Background();
        bkg.setBackgroundColor(new SolidColor("#eeeeee")); // Gray
        bkg.setInnerRadius("60%");  // To make it an arc and not circle
        bkg.setOuterRadius("100%"); // Default - not necessary
        bkg.setShape("solid");        // solid or arc
        pane.setBackground(bkg);

        YAxis yaxis = new YAxis();
        yaxis.setTitle("使用率");
// The limits are mandatory
        yaxis.setMin(0);
        yaxis.setMax(100);
// Configure ticks and labels
        yaxis.setTickInterval(10);  // At 0, 100, and 200
        yaxis.getLabels().setY(-5); // Move 16 px upwards
        yaxis.setGridLineWidth(0); // Disable grid
        yaxis.setStops(new Stop(0.1f, SolidColor.GREEN),
                new Stop(0.5f, SolidColor.YELLOW),
                new Stop(0.8f, SolidColor.RED));

        conf.addyAxis(yaxis);

        cupSeries = new ListSeries("cpu usage",0);
        cupChart.getConfiguration().addSeries(cupSeries);
        updateCupUsage();
        cupChart.drawChart();
        return cupChart;
    }

    private void updateCupUsage() {
        if (null == clientUtil) {
            return;
        }
        try {
            String s = clientUtil.executeCommand(Constants.CUP_CMD);
            if (null == s) {
                return;
            }
            String replace = s.replace("%", "").trim();
            // 命令输出为空 / 非数字（如命令不存在返回 "command not found"）不应该让整个 UI 抛异常
            if (!NumberUtil.isNumber(replace)) {
                log.warn("CPU 使用率命令返回了非数字内容：{}", s.trim());
                return;
            }
            cupSeries.updatePoint(0, Double.parseDouble(replace));
        } catch (IOException e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * 磁盘使用率
     *
     * @return
     */
    private Chart createDiskUsageChart() {
        diskChart = new Chart(ChartType.SOLIDGAUGE);
        diskChart.setWidth("300px");
        diskChart.setHeight("300px");
        Configuration conf = diskChart.getConfiguration();
        conf.setTitle("磁盘使用率(%)");

        Pane pane = conf.getPane();
        pane.setSize("125%");           // For positioning tick labels
        pane.setCenter("50%", "70%"); // Move center lower
        pane.setStartAngle(-90);        // Make semi-circle
        pane.setEndAngle(90);           // Make semi-circle

        Background bkg = new Background();
        bkg.setBackgroundColor(new SolidColor("#eeeeee")); // Gray
        bkg.setInnerRadius("60%");  // To make it an arc and not circle
        bkg.setOuterRadius("100%"); // Default - not necessary
        bkg.setShape("solid");        // solid or arc
        pane.setBackground(bkg);

        YAxis yaxis = new YAxis();
        yaxis.setTitle("使用率");
//        yaxis.getTitle().setY(-80); // Move 70 px upwards from center

// The limits are mandatory
        yaxis.setMin(0);
        yaxis.setMax(100);

// Configure ticks and labels
        yaxis.setTickInterval(10);  // At 0, 100, and 200
        yaxis.getLabels().setY(-5); // Move 16 px upwards
        yaxis.setGridLineWidth(0); // Disable grid
        yaxis.setStops(new Stop(0.1f, SolidColor.GREEN),
                new Stop(0.5f, SolidColor.YELLOW),
                new Stop(0.8f, SolidColor.RED));

        conf.addyAxis(yaxis);

        diskSerial = new ListSeries("dis useage:", 0);
        diskChart.getConfiguration().addSeries(diskSerial);
        updateDiskUsage();
        diskChart.drawChart();
        return diskChart;
    }

    private void updateDiskUsage() {
        if (null == clientUtil) {
            return;
        }
        try {
            String s = clientUtil.executeCommand("df -h");
            if (null == s) {
                return;
            }
            // df -h 的列间距不保证是单个空格，用 \s+ 切分再取百分比列
            for (String line : s.split("\\R")) {
                if (!line.trim().endsWith("/")) {
                    continue;
                }
                String[] cells = line.trim().split("\\s+");
                if (cells.length < 2) {
                    continue;
                }
                String usage = cells[cells.length - 2].replace("%", "").trim();
                if (!NumberUtil.isNumber(usage)) {
                    continue;
                }
                diskSerial.updatePoint(0, Double.parseDouble(usage));
                break;
            }
        } catch (IOException e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * 磁盘使用率
     *
     * @return
     */
    private Chart createMemoryUsageChart() {
        memoryChart = new Chart(ChartType.SOLIDGAUGE);
        memoryChart.setWidth("300px");
        memoryChart.setHeight("300px");
        Configuration conf = memoryChart.getConfiguration();
        conf.setTitle("内存使用率(%)");

        Pane pane = conf.getPane();
        pane.setSize("125%");           // For positioning tick labels
        pane.setCenter("50%", "70%"); // Move center lower
        pane.setStartAngle(-90);        // Make semi-circle
        pane.setEndAngle(90);           // Make semi-circle

        Background bkg = new Background();
        bkg.setBackgroundColor(new SolidColor("#eeeeee")); // Gray
        bkg.setInnerRadius("60%");  // To make it an arc and not circle
        bkg.setOuterRadius("100%"); // Default - not necessary
        bkg.setShape("solid");        // solid or arc
        pane.setBackground(bkg);

        YAxis yaxis = new YAxis();
        yaxis.setTitle("使用率");
        yaxis.getTitle().setY(-80); // Move 70 px upwards from center

// The limits are mandatory
        yaxis.setMin(0);
        yaxis.setMax(100);

// Configure ticks and labels
        yaxis.setTickInterval(10);  // At 0, 100, and 200
        yaxis.getLabels().setY(-10); // Move 16 px upwards
        yaxis.setGridLineWidth(0); // Disable grid
        yaxis.setStops(new Stop(0.1f, SolidColor.GREEN),
                new Stop(0.5f, SolidColor.YELLOW),
                new Stop(0.8f, SolidColor.RED));

        conf.addyAxis(yaxis);

        memorySeries = new ListSeries("Mem usage", 0);
        memoryChart.getConfiguration().addSeries(memorySeries);
        updateMemUsage();
        memoryChart.drawChart();
        return memoryChart;
    }

    private void updateMemUsage() {
        if (null == clientUtil) {
            return;
        }
        try {
            String s = clientUtil.executeCommand("free -m");
            if (null == s) {
                return;
            }
            for (String line : s.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("Mem")) {
                    continue;
                }
                // free -m 的列是右对齐空格，列宽不固定。
                // 原实现按固定的 7 个空格切分，切出来的是好几列拼在一起，parseDouble 必然抛 NumberFormatException
                String[] cells = trimmed.split("\\s+");
                if (cells.length < 4) {
                    continue;
                }
                if (!NumberUtil.isNumber(cells[1]) || !NumberUtil.isNumber(cells[3])) {
                    log.warn("free -m 输出无法解析：{}", trimmed);
                    return;
                }
                double totalMemNum = Double.parseDouble(cells[1]);
                double freeMemNum = Double.parseDouble(cells[3]);
                if (totalMemNum <= 0) {
                    return;
                }
                double ratio = ((totalMemNum - freeMemNum) / totalMemNum) * 100;
                memorySeries.updatePoint(0, NumberUtil.round(ratio, 2).doubleValue());
                return;
            }
        } catch (IOException e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }

    @Override
    public void initContent() {
    }

    @Override
    public void registerHandler() {
        BackgroundThread backgroundThread = new BackgroundThread();
        backgroundThread.start();

        this.addDetachListener((DetachListener) event -> backgroundThread.interrupt());
    }

    class BackgroundThread extends Thread {

        /** 4800 * 3s ≈ 4 小时 */
        private static final int MAX_TICKS = 4800;

        BackgroundThread() {
            // 必须是守护线程：否则 detach 后万一没被中断，会一直挂着不释放
            setDaemon(true);
            setName("remote-monitor-refresh");
        }

        @Override
        public void run() {
            int count = 0;
            try {
                while (count < MAX_TICKS) {
                    Thread.sleep(3000);
                    // 原实现在 currentUI 为 null 时只 interrupt() 而没有 return，
                    // 紧接着仍然执行 currentUI.access(...)，直接 NullPointerException
                    if (null == currentUI) {
                        log.info("监控页面已关闭，停止刷新");
                        return;
                    }
                    currentUI.access(new Runnable() {
                        @Override
                        public void run() {
                            updateCupUsage();
                            updateDiskUsage();
                            updateMemUsage();
                        }
                    });
                    count++;
                }
                log.info("监控刷新已达最大次数（{} 次），停止刷新", MAX_TICKS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("监控刷新线程已被中断");
            } catch (UIDetachedException e) {
                log.info("监控页面已 detach，停止刷新");
            } catch (Exception e) {
                log.error(ExceptionUtils.getStackTrace(e));
            }
        }
    }

    public ConnectionInfo getCurrentConnectionInfo() {
        return currentConnectionInfo;
    }

    public void setCurrentConnectionInfo(ConnectionInfo currentConnectionInfo) {
        this.currentConnectionInfo = currentConnectionInfo;
    }
}
