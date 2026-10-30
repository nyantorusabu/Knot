package app.zipper.knot.hooks;

import android.view.View;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import app.zipper.knot.SettingsStore;
import app.zipper.knot.ui.ReadListViewer;
import io.github.libxposed.api.XposedInterface;

/**
 * Opens the read-user list (LINE's reaction list design) when the read label of one of my own
 * messages is tapped. The label is located by LINE's own {@code chat_ui_row_read_count} view id.
 */
public class ReadListHook implements BaseHook {

  private static final String LINE_PKG = "jp.naver.line.android";
  private static volatile int readCountId;

  @Override
  public void hook(KnotConfig config, LoadParam param) throws Throwable {
    LineVersion.Config cfg = LineVersion.get();
    if (cfg == null || cfg.unsend.chatMessageViewHolderClass.isEmpty()) return;
    try {
      Knot.hookAll(
          Reflect.findClass(cfg.unsend.chatMessageViewHolderClass, param.classLoader),
          cfg.unsend.methodBind,
          chain -> {
            Object result = chain.proceed();
            try {
              attach(chain, cfg);
            } catch (Throwable t) {
              Knot.log("Knot: read list bind failed: " + t);
            }
            return result;
          });
    } catch (Throwable t) {
      Knot.log("Knot: read list hook failed: " + t);
    }
  }

  // Runs after every message row bind, so listeners follow row recycling without stale ids
  private static void attach(XposedInterface.Chain chain, LineVersion.Config cfg) throws Throwable {
    if (!SettingsStore.get("record_read_history", false)) return;

    Object data = chain.getArg(cfg.unsend.methodBindIndex);
    Object common = data == null ? null : Reflect.callMethod(data, cfg.unsend.methodGetCommonData);
    View root = (View) Reflect.callMethod(chain.getThisObject(), cfg.unsend.methodGetItemView);
    if (common == null || root == null) return;

    String msgId = messageId(common, cfg);
    if (msgId == null) return;

    int labelId = resolveReadCountId(root);
    if (labelId == 0) return;
    View label = root.findViewById(labelId);
    if (label == null) return;

    label.setOnClickListener(v -> ReadListViewer.show(v, msgId));
  }

  // Resource ids are stable for a running app process, so the identifier lookup runs only once
  private static int resolveReadCountId(View root) {
    int id = readCountId;
    if (id == 0) {
      id = root.getResources().getIdentifier("chat_ui_row_read_count", "id", LINE_PKG);
      if (id == 0) return 0;
      readCountId = id;
    }
    return id;
  }

  // Same id form as UnsendProtector/ChatJumpUtil: the server id used as the read-history key
  private static String messageId(Object common, LineVersion.Config cfg) throws Throwable {
    String msgId = (String) Reflect.getObjectField(common, cfg.unsend.chatMessageIdField);
    if ((msgId == null || msgId.isEmpty()) && !cfg.unsend.chatMessageServerIdLongField.isEmpty()) {
      long serverId = Reflect.getLongField(common, cfg.unsend.chatMessageServerIdLongField);
      if (serverId > 0) msgId = String.valueOf(serverId);
    }
    return (msgId == null || msgId.isEmpty()) ? null : msgId;
  }
}
