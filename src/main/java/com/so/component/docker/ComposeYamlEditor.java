package com.so.component.docker;

import com.vaadin.annotations.JavaScript;
import com.vaadin.ui.CssLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * YAML 在线编辑控件（带行号 + 语法高亮）。
 * <p>
 * 高亮不是服务端算出来的，而是浏览器里一层 {@code <pre>} 叠在 {@code <textarea>} 底下
 * （见 {@code VAADIN/js/compose-yaml-editor.js} 顶部的说明）。服务端这边只负责
 * 把 DOM 结构摆好、把脚本挂上去、把值读出来。之所以自己写而不是引 CodeMirror/Monaco：
 * 内网机房没有外网，静态资源必须本地托管，为改一份 compose 文件塞几 MB 依赖不划算。
 * <p>
 * <b>定位元素用 class 不用 id。</b>Vaadin 8 的 {@code Component#setId} 到底把 id 落在
 * 哪一层（widget 还是 caption 外层）取决于组件的 DOM 结构，而 class 一定在元素上，
 * 所以每个实例生成一个随机后缀的 class 作为「把手」。
 * <p>
 * <b>脚本挂了也不影响可用性。</b>输入层的文字变透明是脚本成功建好高亮层之后才加上的
 * （CSS 类 {@code compose-editor-ready}）；脚本没跑起来就是一个普通的等宽文本框。
 */
@JavaScript({"vaadin://js/compose-yaml-editor.js"})
public class ComposeYamlEditor extends CssLayout {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ComposeYamlEditor.class);

    /** JS 侧用来定位这个实例的把手 */
    private final String key;
    private final Label gutter;
    private final TextArea area;

    public ComposeYamlEditor(String initialText, String height) {
        this.key = UUID.randomUUID().toString().substring(0, 8);
        setWidth("100%");
        setHeight(height);
        addStyleName("compose-editor-shell");
        addStyleName("compose-editor-inst-" + key);

        gutter = new Label("1");
        gutter.setWidth("46px");
        gutter.setHeight("100%");
        gutter.addStyleName("compose-editor-gutter");
        gutter.addStyleName("compose-editor-gutter-inst-" + key);
        addComponent(gutter);

        area = new TextArea();
        area.addStyleName("compose-editor-input");
        area.setValue(null == initialText ? "" : initialText);
        addComponent(area);

        addAttachListener(new AttachListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void attach(AttachEvent event) {
                runAttachScript();
            }
        });
        addDetachListener(new DetachListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void detach(DetachEvent event) {
                execute("window.ComposeYamlEditor && window.ComposeYamlEditor.detach('" + ComposeYamlEditor.this.key + "');");
            }
        });
    }

    /**
     * 挂载脚本。
     * <p>
     * 带重试：{@code @JavaScript} 声明的资源在客户端是「组件初始化前加载」，
     * 但服务端这个时刻发出的脚本调用排在同一份响应里，先后顺序不该拿来赌。
     */
    private void runAttachScript() {
        String script = "(function(){var n=0;var tick=function(){"
                + "if(window.ComposeYamlEditor&&window.ComposeYamlEditor.attach('" + key + "')){return;}"
                + "if(n++<40){window.setTimeout(tick,50);}};tick();})();";
        execute(script);
    }

    private void execute(String script) {
        try {
            // 这里必须写全限定名：类上的 @JavaScript 注解来自 com.vaadin.annotations，
            // 与本包的执行入口 com.vaadin.ui.JavaScript 同名，简名会撞车
            com.vaadin.ui.JavaScript js = com.vaadin.ui.JavaScript.getCurrent();
            if (null != js) {
                js.execute(script);
            }
        } catch (Exception e) {
            log.debug("执行编辑器脚本失败：{}", e.getMessage());
        }
    }

    /** 当前编辑器里的文本（服务端这一份是最后一次同步过来的值） */
    public String getValue() {
        String value = area.getValue();
        return null == value ? "" : value;
    }

    /** 服务端主动改内容：浏览器侧的轮询（200ms）会发现值变了并重绘，这里不需要额外通知 */
    public void setValue(String text) {
        area.setValue(null == text ? "" : text);
    }

    /** 让输入框拿到焦点，用户点「编辑」之后可以直接敲 */
    public void focus() {
        try {
            area.focus();
        } catch (Exception e) {
            log.debug("聚焦编辑器失败：{}", e.getMessage());
        }
    }

    public TextArea getTextArea() {
        return area;
    }

    public String getKey() {
        return key;
    }
}
