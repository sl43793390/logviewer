package com.so.component.util;

import com.vaadin.server.ThemeResource;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;

/**
 * Generic Yes/No confirmation dialog. Add {@link ConfirmationEventListener} to
 * perform actions when confirmation or rejection is given.
 */
public class ConfirmationDialogPopupWindow extends PopupWindow {

    private static final long serialVersionUID = 1L;

    protected VerticalLayout layout;
    protected Label descriptionLabel;
    protected Button yesButton;
    protected Button noButton;

    public ConfirmationDialogPopupWindow(String title, String description, String sureCaption, String cancleCaption,
                                         boolean isClosable) {
        setWidth("429px");
        // 高度不能写死 190px：删除用户这类文案是多行 HTML（好几个 <br/>），
        // 内容一旦超过 190px 就会把按钮行顶出内容区、叠在提示文字上。
        // 交给内容决定高度，文案多长都不会互相压。
        setHeightUndefined();
        setModal(true);
        setResizable(true);
        this.setClosable(isClosable);

        layout = new VerticalLayout();
        layout.setMargin(true);
        layout.setSpacing(true);
        layout.setWidth("100%");
        layout.addStyleName("common-popWindow-style");

        setContent(layout);

        if (title != null) {
            setCaption(title);
        } else {
            setCaption("提示");
        }

        initLabel(description);
        initButtons(sureCaption, cancleCaption);
    }

    public ConfirmationDialogPopupWindow(String description, String sureCaption, String cancleCaption) {
        this(null, description, sureCaption, cancleCaption, true);
    }

    /**
     * Show the confirmation popup.
     */
    public void showConfirmation() {
//		yesButton.focus();
        UI.getCurrent().addWindow(this);
    }

    protected void initButtons(String sureCaption, String cancleCaption) {
        if (sureCaption != null) {
            yesButton = new Button(sureCaption);
        } else {
            yesButton = new Button("保存");
        }
//        yesButton.addClickListener(new ClickListener() {
//            private static final long serialVersionUID = 1L;
//
//            @Override
//            public void buttonClick(ClickEvent event) {
//                close();
//                fireEvent(new ConfirmationEvent(ConfirmationDialogPopupWindow.this, true));
//            }
//        });

        if (cancleCaption != null) {
            noButton = new Button(cancleCaption);
        } else {
            noButton = new Button("不保存");
        }
        noButton.addClickListener(new ClickListener() {
            private static final long serialVersionUID = 1L;
            @Override
            public void buttonClick(ClickEvent event) {
                close();
                fireEvent(new ConfirmationEvent(ConfirmationDialogPopupWindow.this, false));
            }
        });
        Label blankLabelOther = new Label();
        blankLabelOther.setWidth("10px");

        Label blankLabel = new Label();
        blankLabel.setWidth("10px");

        HorizontalLayout buttonLayout = new HorizontalLayout(blankLabel, yesButton, blankLabelOther, noButton);
        buttonLayout.setWidth("100%");
        buttonLayout.setComponentAlignment(yesButton, Alignment.BOTTOM_RIGHT);
        buttonLayout.setComponentAlignment(noButton, Alignment.BOTTOM_RIGHT);
        buttonLayout.setExpandRatio(blankLabel, 1.0f);

        layout.addComponent(buttonLayout);
    }

    protected void initLabel(String description) {
        descriptionLabel = new Label(description, ContentMode.HTML);
        descriptionLabel.addStyleName("common-popWindow-content");
        // 宽度不能写死 390px：弹窗总宽 429px，扣掉内边距和左侧图标本来就放不下，
        // 长文案会被挤成更多行。让它占满消息行的剩余宽度。
        descriptionLabel.setWidth("100%");

        Label iconLabel = new Label();
        iconLabel.setIcon(new ThemeResource("img/sure.png"));

        HorizontalLayout msgLayout = new HorizontalLayout(iconLabel, descriptionLabel);
        msgLayout.setWidth("100%");
        msgLayout.setSpacing(true);
        msgLayout.setExpandRatio(descriptionLabel, 1.0f);
        layout.addComponent(msgLayout);
    }

    public Button getYesButton() {
        return yesButton;
    }

    /**
     * 弹出窗的内容容器，供调用方插入额外控件（例如删除容器时的「强制删除」勾选框）。
     * <p>
     * 结构是：[图标 + 描述文案] [按钮行]，往 index 1 处插入就会落在文案和按钮之间。
     */
    public VerticalLayout getLayout() {
        return layout;
    }

    public void setYesButton(Button yesButton) {
        this.yesButton = yesButton;
    }

    public Button getNoButton() {
        return noButton;
    }

    public void setNoButton(Button noButton) {
        this.noButton = noButton;
    }
}
