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
public class ConfirmationDialogSingleButtonPopupWindow extends PopupWindow {

	private static final long serialVersionUID = 1L;

	protected VerticalLayout layout;
	protected Label descriptionLabel;
	protected Button yesButton;

	public ConfirmationDialogSingleButtonPopupWindow(String title, String description, String sureCaption, boolean isClosable) {
		setWidth("429px");
		setHeight("190px");
		setModal(true);
		setResizable(true);
		this.setClosable(isClosable);

		layout = new VerticalLayout();
		layout.setMargin(true);
		layout.setSpacing(true);
		layout.setSizeFull();
		layout.addStyleName("common-popWindow-style");

		setContent(layout);

		if (title != null) {
			setCaption(title);
		} else {
			setCaption("提示");
		}

		initLabel(description);
		initButtons(sureCaption);
	}

	public ConfirmationDialogSingleButtonPopupWindow(String description, String sureCaption, String cancleCaption) {
		this(null, description, sureCaption, true);
	}

	/**
	 * Show the confirmation popup.
	 */
	public void showConfirmation() {
//		yesButton.focus();
		UI.getCurrent().addWindow(this);
	}

	protected void initButtons(String sureCaption) {
		if (sureCaption != null) {
			yesButton = new Button(sureCaption);
		} else {
			yesButton = new Button("确定");
		}
		yesButton.addClickListener(new ClickListener() {
			private static final long serialVersionUID = 1L;

			public void buttonClick(ClickEvent event) {
				close();
				fireEvent(new ConfirmationEvent(ConfirmationDialogSingleButtonPopupWindow.this, true));
			}
		});

		Label blankLabelOther = new Label();
		blankLabelOther.setWidth("10px");

		Label blankLabel = new Label();
		blankLabel.setWidth("10px");

		HorizontalLayout buttonLayout = new HorizontalLayout(blankLabel, yesButton);
		buttonLayout.setWidth("100%");
		buttonLayout.setComponentAlignment(yesButton, Alignment.BOTTOM_RIGHT);
		buttonLayout.setExpandRatio(blankLabel, 1.0f);

		layout.addComponent(buttonLayout);
	}

	protected void initLabel(String description) {
		descriptionLabel = new Label(description, ContentMode.HTML);
		descriptionLabel.addStyleName("common-popWindow-content");
		descriptionLabel.setWidth("390px");

		Label iconLabel = new Label();
		iconLabel.setIcon(new ThemeResource("img/sure.png"));

		HorizontalLayout msgLayout = new HorizontalLayout(iconLabel, descriptionLabel);
		layout.addComponent(msgLayout);
	}
}
