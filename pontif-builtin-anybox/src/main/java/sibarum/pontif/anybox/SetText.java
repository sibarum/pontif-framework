package sibarum.pontif.anybox;

import sibarum.atchung.Topic;

/**
 * The {@code pontif.gui/SetText} command on its way from the program's lane to the GUI thread: the widget {@code id}
 * now says {@code text}. A state topic keyed by the widget, because a widget's text is a piece of state: within one
 * tick only the last write to each widget reaches the frame (vexelray-framework's {@code docs/threading.md}, T4.1).
 */
record SetText(String id, String text) {
    static final Topic<SetText> TOPIC = Topic.state(SetText.class, SetText::id);
}
