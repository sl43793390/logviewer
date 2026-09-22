package com.so.component.docker;

import com.so.component.CommonComponent;
import com.so.ui.ComponentFactory;
import com.vaadin.ui.Label;
import com.vaadin.ui.Panel;
import com.vaadin.ui.VerticalLayout;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * Docker-Compose 管理（预留占位）。
 * <p>
 * 按要求暂时只留入口不做实现：真正的「项目列表 / up / down / logs / 在线编辑 compose 文件」
 * 等能力后续再补。之所以现在就占个位置，是为了菜单结构一次成型，
 * 后面加功能时不用再动 {@code LogCheckView}。
 */
@Service
@Scope("prototype")
public class DockerComposeComponent extends CommonComponent {

    private static final long serialVersionUID = 1L;

    private static final String TODO_TEXT = "规划中的能力：\n"
            + "  · compose 项目列表与状态（docker compose ls）\n"
            + "  · 项目启动 / 停止 / 重启 / 重建\n"
            + "  · 在线编辑 docker-compose.yml 并校验\n"
            + "  · 项目级日志聚合查看\n"
            + "  · 单服务伸缩（scale）";

    @Override
    public void initLayout() {
        Panel mainPanel = new Panel();
        mainPanel.setWidth("100%");
        mainPanel.setHeight("560px");
        setCompositionRoot(mainPanel);
        setWidth("100%");

        VerticalLayout root = new VerticalLayout();
        root.setSizeFull();
        root.setMargin(true);
        root.setSpacing(true);
        mainPanel.setContent(root);

        root.addComponent(ComponentFactory.getStandardTitle("Docker-Compose 管理"));

        Label placeholder = new Label("该功能正在开发中，暂未开放。");
        placeholder.addStyleName("docker-placeholder");
        root.addComponent(placeholder);

        Label hint = new Label("环境提示：docker compose 在 Rocky Linux 8 上默认不随 docker-ce 安装，"
                + "需要额外装 docker-compose-plugin（v2，推荐）或 python3-docker-compose（v1，已停止维护）；"
                + "Ubuntu 22.04 装 docker-compose-plugin 即可。装好后 docker compose version 能打印出版本号。"
                + "Rocky 8 仓库里的 docker 版本较低（19.03），如果后续要用 compose v2 的新语法，"
                + "可能需要先把 docker 升到 20.10 以上。");
        hint.addStyleName("docker-hint");
        hint.setWidth("860px");
        root.addComponent(hint);

        Label todo = new Label(TODO_TEXT);
        todo.addStyleName("docker-hint");
        todo.setWidth("860px");
        root.addComponent(todo);
        root.setExpandRatio(todo, 1f);
    }

    @Override
    public void initContent() {
        // 占位页没有数据要加载
    }

    @Override
    public void registerHandler() {
        // 占位页没有交互
    }
}
