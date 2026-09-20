package com.so.component.remote;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.so.component.CommonComponent;
import com.so.component.ComponentUtil;
import com.so.component.util.ColorEnum;
import com.so.component.util.CommonWindow;
import com.so.component.util.ConfirmationDialogPopupWindow;
import com.so.component.util.RemoteFileUploaderForSshj;
import com.so.entity.ConnectionInfo;
import com.so.entity.RemoteFileInfo;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.so.util.MyJSchUtil;
import com.so.util.SSHClientUtil;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.*;
import net.schmizz.sshj.xfer.FilePermission;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 远程文件管理
 */
@Service
@Scope("prototype")
public class RemoteFileMgmtComponent extends CommonComponent {

    private static final Logger log = LoggerFactory.getLogger(RemoteFileMgmtComponent.class);

    private Panel mainPanel;
    private VerticalLayout contentLayout;
    private ConnectionInfo addr;
    private String hostName;
    private Grid<RemoteFileInfo> grid;
    private Button backBtn;
    private Label pathLb;
    private Session jschSession;
    private ChannelSftp channelSftp;
    private LinkedList<String> pathList = new LinkedList();
    private Button batchRemoveBtn;
    private Button batchMoveBtn;
    private Button createDirBtn;
    private SSHClientUtil clientUtil;
    private Button createFileBtn;
    private Button uploadFileBtn;
    private Button renameFileBtn;
    private Button commandBtn;

    @Override
    public void initLayout() {
        mainPanel = new Panel();
        contentLayout = new VerticalLayout();
        setCompositionRoot(mainPanel);
        mainPanel.setContent(contentLayout);
        contentLayout.setWidth("100%");
        contentLayout.setHeight("700px");
        readyToConnect();
        initMainLayout();
//        加载目录
        navigateTo(null);
    }

    /**
     * 进入某个目录：统一维护「导航历史 + 路径显示 + 内容加载」。
     * <p>
     * 原来历史栈在 initGridContent 和点击事件里各 add 一次，每个目录入栈两遍，
     * 「返回」会回到当前目录而不是上一级。
     */
    private void navigateTo(String path) {
        String target = StrUtil.isBlank(path) ? resolveStartDir() : path;
        initGridContent(target);
        pathList.add(target);
        pathLb.setValue(target);
    }

    /** 默认打开的目录：root 用户进 /root，其他进自己的家目录 */
    private String resolveStartDir() {
        return "root".equals(addr.getIdUser()) ? "/root" : "/home/" + addr.getIdUser();
    }

    private void initMainLayout() {
        HorizontalLayout horizontalLayout = ComponentFactory.getHorizontalLayout();
        HorizontalLayout batchLayout = ComponentFactory.getHorizontalLayoutRight();
        horizontalLayout.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        backBtn = ComponentFactory.getImageButton();
        backBtn.setIcon(VaadinIcons.BACKSPACE);
        horizontalLayout.addComponent(backBtn);
        pathLb = ComponentFactory.getStandardLabel("");
        horizontalLayout.addComponent(pathLb);
        batchMoveBtn = ComponentFactory.getStandardButton("批量移动");
        batchRemoveBtn = ComponentFactory.getButtonWithColor("批量删除", ColorEnum.RED);
        createDirBtn = ComponentFactory.getStandardButton("创建目录");
        createFileBtn = ComponentFactory.getStandardButton("创建文件");
        renameFileBtn = ComponentFactory.getStandardButton("重命名");
        commandBtn = ComponentFactory.getStandardButton("执行命令");
        uploadFileBtn = ComponentFactory.getStandardButton("上传文件");
        batchLayout.addComponent(batchMoveBtn);
        batchLayout.addComponent(batchRemoveBtn);
        batchLayout.addComponent(createDirBtn);
        batchLayout.addComponent(createFileBtn);
        batchLayout.addComponent(renameFileBtn);
        batchLayout.addComponent(commandBtn);
        batchLayout.addComponent(uploadFileBtn);
        batchLayout.setExpandRatio(batchMoveBtn, 1);
        horizontalLayout.addComponent(batchLayout);
        horizontalLayout.setComponentAlignment(batchLayout, Alignment.MIDDLE_RIGHT);
        horizontalLayout.setExpandRatio(batchLayout, 1);
        contentLayout.addComponent(horizontalLayout);

        grid = new Grid<RemoteFileInfo>();
        contentLayout.addComponent(grid);
        contentLayout.setExpandRatio(grid, 1);
        grid.setWidthFull();
        grid.setHeightFull();
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        grid.addComponentColumn(file -> {
            if (!Boolean.TRUE.equals(file.getIsFile())) {
                Button b = ComponentFactory.getLinkButton(file.getFileName());
                // 历史栈由 navigateTo 统一维护，这里不要再 add，否则会重复入栈
                b.addClickListener(e -> navigateTo(file.getCurrentPath()));
                return b;
            }
            return ComponentFactory.getStandardLabel(file.getFileName());
        }).setCaption("名称");
        grid.addColumn(RemoteFileInfo::getPermission).setCaption("权限");
        grid.addColumn(RemoteFileInfo::getUserName).setCaption("用户");
        grid.addColumn(RemoteFileInfo::getSize).setCaption("大小");
        grid.addColumn(RemoteFileInfo::getLastModify).setCaption("修改时间");
//        grid.addComponentColumn(file -> {
//            if (!file.getIsFile()) {
//                Button b = ComponentFactory.getLinkButton("进入");
//                b.addClickListener(e -> {
//                    initGridContent(file.getCurrentPath());
//                    pathList.add(file.getCurrentPath());
//                    pathLb.setValue(file.getCurrentPath());
//                });
//                return b;
//            }
//            return null;
//        }).setCaption("打开");
        grid.addComponentColumn(file -> {
            if (!Boolean.TRUE.equals(file.getIsFile())) {
                RemoteFileUploaderForSshj loader = new RemoteFileUploaderForSshj();
                loader.setChannelSftp(channelSftp);
                loader.setParentPath(file.getCurrentPath());
                Upload upload = new Upload("上传", loader);
                upload.setImmediateMode(true);
                upload.addStartedListener(event -> {
                    if (!LoginView.checkPermission(Constants.UPLOAD)) {
                        Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
                        throw new RuntimeException("权限不足，终止上传");
                    }
                });
                upload.setButtonCaption("上传");
                upload.setHeight("30px");
                upload.addSucceededListener(loader);
                return upload;
            }
            return null;
        }).setCaption("上传");
        grid.addComponentColumn(file -> {
            if (Boolean.TRUE.equals(file.getIsFile())) {
                Button b = ComponentFactory.getLinkButton("下载");
                FileDownloader fileDownloader = new FileDownloader(new StreamResource(new FileStreamResource(file), file.getFileName()));
                fileDownloader.extend(b);
                return b;
            }
            return null;
        }).setCaption("下载");
        grid.addComponentColumn(file -> {
            Button deleteBtn = ComponentFactory.getButtonWithColor("删除", ColorEnum.RED);
            deleteBtn.addClickListener(e -> {
                ConfirmationDialogPopupWindow win = new ConfirmationDialogPopupWindow("确认", "危险操作！确认是否删除！", "确认", "取消", true);
                win.getYesButton().addClickListener(c -> {
                    try {
                        if (!LoginView.checkPermission(Constants.DELETE)) {
                            Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
                            return;
                        }
                        if (Boolean.TRUE.equals(file.getIsFile())) {
                            channelSftp.rm(file.getCurrentPath());
                        } else {
//                            Notification.show("提示：", "为安全起见，暂不支持删除目录", Notification.Type.WARNING_MESSAGE);
                            channelSftp.rmdir(file.getCurrentPath());
                        }
                        log.info("用户：{},删除了文件：{}", ComponentUtil.getCurrentUserName(), file.getCurrentPath());
                        win.close();
                        Notification.show("提示：", "删除成功", Notification.Type.WARNING_MESSAGE);
                        initGridContent(file.getParentPath());
                    } catch (SftpException ex) {
                        log.error("删除文件错误" + ExceptionUtils.getStackTrace(ex));
                        Notification.show("提示：", "删除失败，请查看日志", Notification.Type.WARNING_MESSAGE);
                    }
                });
                win.showConfirmation();
            });
            return deleteBtn;
        }).setCaption("删除");
    }

    private void initGridContent(String path) {
        //创建链接获取用户目录列表
        if (null == channelSftp) {
            Notification.show("SFTP 连接未建立，请关闭该标签页后重试", Notification.Type.ERROR_MESSAGE);
            return;
        }
        String target = StrUtil.isBlank(path) ? resolveStartDir() : path;
        try {
            Vector<ChannelSftp.LsEntry> remoteResourceInfos;
            try {
                remoteResourceInfos = channelSftp.ls(target);
            } catch (Exception e) {
                log.error(ExceptionUtils.getStackTrace(e));
                String message = e.getMessage();
                if (null != message && message.contains("Permission denied")) {
                    Notification.show("权限不足", Notification.Type.ERROR_MESSAGE);
                } else {
                    Notification.show("读取目录失败：" + target, Notification.Type.ERROR_MESSAGE);
                }
                return;
            }
            grid.setItems(convertRemoteFileInfo(remoteResourceInfos, target));
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
            Notification.show("获取目录数据错误，请及时查看日志！：" + addr.getIdHost(), Notification.Type.ERROR_MESSAGE);
        }
    }

    private List<RemoteFileInfo> convertRemoteFileInfo(Vector<ChannelSftp.LsEntry> remoteResourceInfos,String parentPath) {
        List<RemoteFileInfo> fileInfos = new ArrayList<>();
        // 上层 catch 掉异常后 remoteResourceInfos 可能是 null，原实现直接 for 会 NPE
        if (remoteResourceInfos == null) {
            return fileInfos;
        }
        for (ChannelSftp.LsEntry in : remoteResourceInfos) {
            if (!in.getFilename().startsWith(".")) {
                RemoteFileInfo fileInfo = new RemoteFileInfo();
                fileInfo.setFileName(in.getFilename());
                String permissionsString = in.getAttrs().getPermissionsString();
//                String perm = getPermissionString(permissions);
                fileInfo.setPermission(permissionsString);
                fileInfo.setLastModify(DateUtil.format(new Date(in.getAttrs().getATime() * 1000L), "yyyy-MM-dd HH:mm:ss"));
                if (in.getAttrs().getSize() <= 1024) {
                    fileInfo.setSize(in.getAttrs().getSize() + "b");
                } else if (in.getAttrs().getSize() < 1024 * 1024) {
                    fileInfo.setSize(in.getAttrs().getSize() / 1024 + "kb");
                } else if (in.getAttrs().getSize() < 1024 * 1024 * 1024) {
                    fileInfo.setSize(in.getAttrs().getSize() / 1024 / 1024 + "mb");
                } else {
                    fileInfo.setSize(in.getAttrs().getSize() / 1024 / 1024 / 1024 + "Gb");
                }
                fileInfo.setUserName(parseUserName(in.getLongname()));
                fileInfo.setParentPath(parentPath);
                String currentPath = null;
                if (parentPath.equals("/")){
                    currentPath = parentPath + in.getFilename();
                }else{
                    currentPath = parentPath + "/" + in.getFilename();
                }
                fileInfo.setCurrentDir(currentPath);
                fileInfo.setIsFile(!in.getAttrs().isDir());
                fileInfos.add(fileInfo);
            }
        }
        return fileInfos;
    }

    /**
     * 从 ls 的 longname 中解析属主名。
     * <p>
     * 原实现按两个空格切分后直接取 s[1]/s[2]，列宽变化或属主名含空格时就是数组越界，
     * 这里改为按任意空白切分，并对长度做校验。
     *
     * @param longname 形如 {@code -rw-r-----, 1, sl, sl, 524329, Jul, 15, 11:29, cas.log.1}
     * @return 属主名，解析不出返回 null
     */
    private String parseUserName(String longname) {
        if (StrUtil.isEmpty(longname)) {
            return null;
        }
        String[] parts = longname.trim().split("\\s+");
        // 期望格式：权限 链接数 属主 属组 大小 月份 日 时间/年份 文件名
        if (parts.length < 4) {
            log.warn("解析属主失败，longname 格式不符合预期：{}", longname);
            return null;
        }
        String owner = parts[2];
        if (NumberUtil.isNumber(owner)) {
            // 属主列显示的是 uid 时，真实名字在下一列
            owner = parts[3];
        }
        return owner;
    }

    private String getPermissionString(Set<FilePermission> permissions) {
        List<FilePermission> collect1 = permissions.stream().filter(f -> f.name().length() == 5).collect(Collectors.toList());
        List<String> usr = collect1.stream().filter(f -> f.name().startsWith("USR")).map(m -> m.name().split("_")[1]).collect(Collectors.toList());
        List<String> usrGroup = collect1.stream().filter(f -> f.name().startsWith("GRP")).map(m -> m.name().split("_")[1]).collect(Collectors.toList());
        List<String> other = collect1.stream().filter(f -> f.name().startsWith("OTH")).map(m -> m.name().split("_")[1]).collect(Collectors.toList());
        return usr.toString().replace(",", "") + usrGroup.toString().replace(",", "") + other.toString().replace(",", "");
    }

    @Override
    public void detach() {
        super.detach();
        // 连接失败时 channelSftp / clientUtil 可能为 null，原实现直接调用会 NPE
        if (channelSftp != null) {
            channelSftp.disconnect();
            channelSftp = null;
        }
        if (clientUtil != null) {
            clientUtil.closeConnection();
            clientUtil = null;
        }
    }

    @Override
    public void initContent() {
        try {
            if (null == clientUtil) {
                clientUtil = SSHClientUtil.connect(addr);
            }
        } catch (Exception e) {
            clientUtil = null;
            log.error("连接 {} 失败：{}", addr.getIdHost(), e.getMessage());
            Notification.show("连接 " + addr.getIdHost() + " 失败：" + e.getMessage(), Notification.Type.ERROR_MESSAGE);
        }
    }

    @Override
    public void registerHandler() {
        backBtn.addClickListener(e -> {
            if (pathList.size() <= 1) {
                Notification.show("已经是顶层目录", Notification.Type.HUMANIZED_MESSAGE);
                return;
            }
            pathList.removeLast();
            String prePath = pathList.peekLast();
            initGridContent(prePath);
            pathLb.setValue(prePath);
        });
        batchMoveBtn.addClickListener(e -> {
            AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
            CommonWindow win = new CommonWindow("移动信息", "500px", "220px", abs);
            abs.setHeightFull();
            FormLayout lay = new FormLayout();
            lay.setHeight("100px");
            TextField usernameField = ComponentFactory.getStandardTtextField("移动到：");
            usernameField.setWidth("375px");
            usernameField.setPlaceholder("填写目标地址的绝对路径");
            lay.addComponent(usernameField);
            Button confirmBtn = ComponentFactory.getStandardButton("确定");
            confirmBtn.addClickListener(e1 -> {
                if (StrUtil.isNotEmpty(usernameField.getValue())) {
                    Set<RemoteFileInfo> selectedItems = grid.getSelectedItems();
                    if (CollectionUtil.isNotEmpty(selectedItems)) {
                        for (RemoteFileInfo item : selectedItems) {
                            try {
                                String cmd = "mv " + item.getCurrentPath() + " " + usernameField.getValue().trim();
                                String s = clientUtil.executeCommand(cmd);
                                System.out.println(s);
                                log.warn(ComponentUtil.getCurrentUserName() +"移动了"+cmd);
                            } catch (IOException ex) {
                                log.error(ExceptionUtils.getStackTrace(ex));
                                log.error("批量移动发生错误");
                                Notification.show("移动失败", Notification.Type.ERROR_MESSAGE);
                            }
                        }
                    }
                }
                win.close();
                initGridContent(pathLb.getValue());
            });
            abs.addComponent(lay,"top:0px;left:20px;");
            abs.addComponent(confirmBtn, "top:120px;right:20px;");
            UI.getCurrent().addWindow(win);
        });

        batchRemoveBtn.addClickListener(e -> {
            if (CollectionUtil.isEmpty(grid.getSelectedItems())){
                Notification.show("请选择要删除的文件或目录！", Notification.Type.WARNING_MESSAGE);
                return;
            }
            AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
            CommonWindow win = new CommonWindow("移动信息", "500px", "270px", abs);
            abs.setHeightFull();
            FormLayout lay = new FormLayout();
            lay.setHeight("100px");
            Label usernameField = ComponentFactory.getStandardLabel("<h3>当前操作为危险操作，不可撤销，<br><div> </div>请谨慎确认！！！</h3>");
            usernameField.setWidth("375px");
            usernameField.setContentMode(ContentMode.HTML);
            lay.addComponent(usernameField);
            Button confirmBtn = ComponentFactory.getStandardButton("确定删除");
            confirmBtn.addClickListener(e1 -> {
                if (!LoginView.checkPermission(Constants.DELETE)){
                    Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
                    return;
                }
                    Set<RemoteFileInfo> selectedItems = grid.getSelectedItems();
                    if (CollectionUtil.isNotEmpty(selectedItems)) {
                        for (RemoteFileInfo item : selectedItems) {
                            try {
                                log.warn(item.toString());
                                if (Boolean.TRUE.equals(item.getIsFile())){
                                    channelSftp.rm( item.getCurrentPath());
                                }else{
                                    channelSftp.rmdir(item.getCurrentPath());
                                }
                                log.warn(ComponentUtil.getCurrentUserName() +"删除了"+item.getFileName());
                            } catch (Exception ex) {
                                log.error("批量删除发生错误", ex);
                                // ex.getMessage() 可能为 null
                                String msg = ex.getMessage();
                                if (null != msg && msg.contains("Failure")){
                                    Notification.show("目录不为空，无法删除",Notification.Type.ERROR_MESSAGE);
                                }else{
                                    Notification.show("删除失败：" + msg, Notification.Type.ERROR_MESSAGE);
                                }
                            }
                        }
                    }
                    win.close();
                    initGridContent(pathLb.getValue());
            });
            abs.addComponent(lay,"top:0px;left:20px;");
            abs.addComponent(confirmBtn, "top:160px;right:20px;");
            UI.getCurrent().addWindow(win);
        });

        createDirBtn.addClickListener(e -> {
            AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
            CommonWindow win = new CommonWindow("创建目录"+pathLb.getValue(), "500px", "220px", abs);
            abs.setHeightFull();
            FormLayout lay = new FormLayout();
            lay.setHeight("100px");
            TextField usernameField = ComponentFactory.getStandardTtextField("目录名：");
            usernameField.setWidth("375px");
            lay.addComponent(usernameField);
            Button confirmBtn = ComponentFactory.getStandardButton("创建");
            confirmBtn.addClickListener(e1 -> {
                if (StrUtil.isNotEmpty(usernameField.getValue())) {
                    try {
                        String inputPath = usernameField.getValue().trim();
                        if (pathLb.getValue().equals("/") && inputPath.startsWith("/")){
                            channelSftp.mkdir(inputPath);
                        } else  if (pathLb.getValue().equals("/") && !inputPath.startsWith("/")){
                            channelSftp.mkdir("/" + inputPath);
                        } else if (inputPath.startsWith("/")){//说明指定了绝对路径，使用绝对路径
                            channelSftp.mkdir(inputPath);
                        }else{
                            channelSftp.mkdir(pathLb.getValue() +"/" + inputPath);
                        }
                        initGridContent(pathLb.getValue());
                        log.warn(ComponentUtil.getCurrentUserName() +"创建了目录："+inputPath);
                    } catch (SftpException ex) {
                        log.error(ExceptionUtils.getStackTrace(ex));
                        log.error("创建目录失败");
                        Notification.show(ex.getMessage(), Notification.Type.ERROR_MESSAGE);
                    }
                }
                win.close();
            });
            abs.addComponent(lay,"top:0px;left:20px;");
            abs.addComponent(confirmBtn, "top:120px;right:20px;");
            UI.getCurrent().addWindow(win);
        });
        createFileBtn.addClickListener(e -> {
            AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
            CommonWindow win = new CommonWindow("创建文件"+pathLb.getValue(), "500px", "300px", abs);
            abs.setHeightFull();
            FormLayout lay = new FormLayout();
            lay.setHeight("100px");
            TextField usernameField = ComponentFactory.getStandardTtextField("文件名：");
            usernameField.setWidth("375px");
            lay.addComponent(usernameField);
            Button confirmBtn = ComponentFactory.getStandardButton("创建");
            confirmBtn.addClickListener(e1 -> {
                if (StrUtil.isNotEmpty(usernameField.getValue())) {
                    try {
                        String inputFilename = usernameField.getValue().trim();
                        String cmd;
                        if (inputFilename.startsWith("/")){//说明指定了绝对路径，使用绝对路径
                            cmd = "touch " + inputFilename;
                        }else{//在当前目录下创建文件
                            cmd = "touch " + pathLb.getValue() +"/" + inputFilename;
                        }
                        String s = clientUtil.executeCommand(cmd);
                        System.out.println(s);
                        log.warn(ComponentUtil.getCurrentUserName() +"创建了文件："+cmd);
                        initGridContent(pathLb.getValue());
                    } catch (IOException ex) {
                        log.error(ExceptionUtils.getStackTrace(ex));
                        log.error("创建文件失败");
                        Notification.show("创建失败"+ex.getMessage(), Notification.Type.ERROR_MESSAGE);
                    }
                }
                win.close();
            });
            abs.addComponent(lay,"top:0px;left:20px;");
            abs.addComponent(confirmBtn, "top:120px;right:20px;");
            UI.getCurrent().addWindow(win);
        });
        renameFileBtn.addClickListener(c ->{
            if (CollectionUtil.isEmpty(grid.getSelectedItems()) || grid.getSelectedItems().size() >1){
                Notification.show("请选择要重命名的一个文件或目录！", Notification.Type.WARNING_MESSAGE);
                return;
            }
            AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
            CommonWindow win = new CommonWindow("重命名文件"+pathLb.getValue(), "500px", "300px", abs);
            abs.setHeightFull();
            FormLayout lay = new FormLayout();
            lay.setHeight("100px");
            TextField usernameField = ComponentFactory.getStandardTtextField("新文件名：");
            usernameField.setWidth("375px");
            lay.addComponent(usernameField);
            Button confirmBtn = ComponentFactory.getStandardButton("确定");
            confirmBtn.addClickListener(e1 -> {
                if (StrUtil.isNotEmpty(usernameField.getValue())) {
                    try {
                        String newName = usernameField.getValue().trim();
                        String cmd;
                        if (newName.startsWith("/")){
                            Notification.show("文件或目录名不能带有斜杠/", Notification.Type.ERROR_MESSAGE);
                            return;
                        }else{//在当前目录下
                            Set<RemoteFileInfo> selectedItems = grid.getSelectedItems();
                            Optional<RemoteFileInfo> first = selectedItems.stream().findFirst();
                            String currentPath = first.get().getCurrentPath();

                            cmd = "cd "+pathLb.getValue()+";mv " + currentPath +" " + newName;
                        }
                        String s = clientUtil.executeCommand(cmd);
                        System.out.println(s);
                        log.warn(ComponentUtil.getCurrentUserName() +"重命名了文件："+cmd);
                        initGridContent(pathLb.getValue());
                    } catch (IOException ex) {
                        log.error(ExceptionUtils.getStackTrace(ex));
                        log.error("重命名文件失败");
                        Notification.show("重命名失败"+ex.getMessage(), Notification.Type.ERROR_MESSAGE);
                    }
                }
                win.close();
            });
            abs.addComponent(lay,"top:0px;left:20px;");
            abs.addComponent(confirmBtn, "top:120px;right:20px;");
            UI.getCurrent().addWindow(win);
        });
        commandBtn.addClickListener(c ->{
            AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
            CommonWindow win = new CommonWindow("在当前目录下执行命令："+pathLb.getValue(), "660px", "700px", abs);
            abs.setHeightFull();
            FormLayout lay = new FormLayout();
            lay.setHeight("450px");
            TextArea commandText = ComponentFactory.getTextArea("命令：");
            TextArea resultText = ComponentFactory.getTextArea("结果：");
            commandText.setWidth("560px");
            commandText.setHeight("200px");
            resultText.setWidth("560px");
            lay.addComponent(commandText);
            lay.addComponent(resultText);
            Button confirmBtn = ComponentFactory.getStandardButton("执行");
            confirmBtn.addClickListener(e1 -> {
                if (StrUtil.isNotEmpty(commandText.getValue())) {
                    try {
                        String cmd = commandText.getValue().trim();
                        String res = clientUtil.executeCommand("cd "+pathLb.getValue()+";"+cmd);
                        resultText.setValue(res);
                        System.out.println(res);
                        log.warn(ComponentUtil.getCurrentUserName() +"执行了命令："+cmd);
                        initGridContent(pathLb.getValue());
                    } catch (IOException ex) {
                        log.error(ExceptionUtils.getStackTrace(ex));
                        log.error("执行命令失败");
                        resultText.setValue(ex.getMessage());
                        Notification.show("执行命令失败"+ex.getMessage(), Notification.Type.ERROR_MESSAGE);
                    }
                }
            });
            abs.addComponent(lay,"top:0px;left:20px;");
            abs.addComponent(confirmBtn, "top:600px;right:20px;");
            UI.getCurrent().addWindow(win);
        });
        uploadFileBtn.addClickListener(e ->{
            AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
            CommonWindow win = new CommonWindow("上传文件到："+pathLb.getValue(), "500px", "300px", abs);
            abs.setHeightFull();
            Label usernameField = ComponentFactory.getStandardLabel("上传文件到："+pathLb.getValue()+"目录下");
            abs.addComponent(usernameField,"left:10px;top:10px;");
            //添加上传组件
            RemoteFileUploaderForSshj loader = new RemoteFileUploaderForSshj();
            loader.setChannelSftp(channelSftp);
            loader.setParentPath(pathLb.getValue());
            Upload upload = new Upload("", loader);
            upload.setImmediateMode(true);
            upload.addStartedListener(event -> {
                if (!LoginView.checkPermission(Constants.UPLOAD)) {
                    Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
                    throw new RuntimeException("权限不足，终止上传");
                }
            });
            upload.addFinishedListener(new Upload.FinishedListener() {
                @Override
                public void uploadFinished(Upload.FinishedEvent event) {
                    win.close();
                }
            });
            upload.setButtonCaption("上传");
            upload.setHeight("30px");
            upload.addSucceededListener(loader);
            abs.addComponent(upload,"top:50px;right:20px;");
            UI.getCurrent().addWindow(win);
        });
    }

    public ConnectionInfo getAddr() {
        return addr;
    }

    public void setAddr(ConnectionInfo addr) {
        this.addr = addr;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    class FileStreamResource implements StreamResource.StreamSource {

        private static final long serialVersionUID = 6327185867459484865L;
        private RemoteFileInfo filePathInfo;

        public FileStreamResource(RemoteFileInfo filePathInfo) {
            this.filePathInfo = filePathInfo;
        }

        @Override
        public InputStream getStream() {
            InputStream inputStream;
            try {
                inputStream = channelSftp.get(filePathInfo.getCurrentPath());
                return inputStream;
            } catch (SftpException e) {
                log.error("下载文件出现错误");
                log.error(ExceptionUtils.getStackTrace(e));
            }
            return null;
        }

    }

    private void readyToConnect() {
        try {
            if (null == addr) {
                throw new IllegalStateException("连接信息为空");
            }
            if (null == jschSession) {
                // cdKeyPath 可能是空串而不是 null（页面上传框清空后就是这样），
                // 用 == null 判断会把空串当成"配了秘钥"，JSch 拿到一个空路径必然认证失败。
                if (StrUtil.isBlank(addr.getCdKeyPath())) {
                    //无秘钥连接
                    jschSession = JschUtil.createSession(hostName, Integer.parseInt(addr.getCdPort()), addr.getIdUser(), addr.getCdPassword());
                } else {
                    //秘钥连接
                    jschSession = JschUtil.createSession(hostName, Integer.parseInt(addr.getCdPort()), addr.getIdUser(), addr.getCdKeyPath(), addr.getCdPassword() == null ? null : addr.getCdPassword().getBytes());
                }
            }
            jschSession.setTimeout(1800);
            openSftpChannel();
        } catch (Exception e) {
            // 原来 NumberFormatException 之后仍会执行 jschSession.setTimeout(...)，会话为 null 时 NPE；
            // JSchException 又被包成 RuntimeException 直接冒到 UI 层
            log.error("连接远程主机 {} 失败：{}", hostName, e.getMessage(), e);
            Notification.show("连接失败请检查配置：" + e.getMessage(), Notification.Type.ERROR_MESSAGE);
        }
    }

    private void openSftpChannel() {
        if (null == channelSftp) {
            try {
                    channelSftp = MyJSchUtil.openSftpChannel(jschSession);
                    channelSftp.connect();
            } catch (JSchException e) {
                log.error(ExceptionUtils.getStackTrace(e));
            }
        }
    }

    public Session getJschSession() {
        return jschSession;
    }

    public void setJschSession(Session jschSession) {
        this.jschSession = jschSession;
    }

    public void jumpPath(String path){
        if (StrUtil.isBlank(path)) {
            return;
        }
        initGridContent(path);
        int n = StrUtil.lastIndexOf(path, "/", path.length(), true);
        // n 可能为 -1（没有 /）或 0（根下的一级目录），原来直接 substring(0, n) 会抛 StringIndexOutOfBoundsException
        if (n > 0) {
            String parent = path.substring(0, n);
            if (StrUtil.isNotEmpty(parent)) {
                pathList.add(parent);
            }
        }
        pathList.add(path);
        pathLb.setValue(path);
    }
}
