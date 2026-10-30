package app.zipper.knot.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.zipper.knot.Knot;
import app.zipper.knot.R;
import app.zipper.knot.SettingsStore;
import app.zipper.knot.utils.LineDBUtils;
import app.zipper.knot.utils.LineTheme;
import app.zipper.knot.utils.ModuleResources;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Shows who read one of my messages, reproducing LINE's reaction user list (chat_ui_reaction_sheet
 * / chat_ui_reaction_sheet_item / MessageReactionSheet, LINE 26.16.0): a bottom sheet with rounded
 * top corners, a centred bold title, a close button top-right and a vertical list of avatar + name
 * rows.
 */
public class ReadListViewer {

  private static final String LINE_PKG = "jp.naver.line.android";
  private static final String PROFILE_CDN = "https://profile.line-scdn.net/";
  private static final int AVATAR_DP = 33;
  private static final int TITLE_HEIGHT_DP = 55;
  private static final int TITLE_TEXT_SP = 17;
  private static final int ITEM_NAME_SP = 14;
  private static final int ITEM_PADDING_H_DP = 16;
  private static final int ITEM_NAME_MARGIN_DP = 12;
  private static final int ITEM_AVATAR_TOP_MARGIN_DP = 8;
  private static final int ITEM_AVATAR_BOTTOM_MARGIN_DP = 9;
  private static final int CLOSE_SIZE_DP = 30;
  private static final int CLOSE_TOP_MARGIN_DP = 13;
  private static final int CLOSE_RIGHT_MARGIN_DP = 8;
  private static final int SHEET_RADIUS_DP = 14;
  private static final int SHEET_PEEK_HEIGHT_DP = 336;
  private static final float DIM_AMOUNT = 0.7f; // MessageReactionSheet backgroundDimAmount
  private static final int BOTTOM_SHEET_STATE_EXPANDED = 3;

  private static final LruCache<String, Bitmap> AVATAR_CACHE = new LruCache<>(64);
  private static final java.util.Set<String> IN_FLIGHT =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
  private static final ExecutorService AVATAR_POOL = Executors.newFixedThreadPool(2);
  private static volatile boolean lineGlideAvailable = true;

  public static void show(View anchor, String messageId) {
    Activity activity = activity(anchor.getContext());
    if (activity == null || messageId == null) return;
    new Thread(
            () -> {
              List<Reader> readers = loadReaders(messageId);
              WeakReference<Activity> ref = new WeakReference<>(activity);
              activity.runOnUiThread(
                  () -> {
                    Activity a = ref.get();
                    if (a == null || a.isFinishing() || a.isDestroyed()) return;
                    if (readers.isEmpty()) {
                      Toast.makeText(
                              a, ModuleResources.get(R.string.read_list_empty), Toast.LENGTH_SHORT)
                          .show();
                      return;
                    }
                    try {
                      display(a, readers);
                    } catch (Throwable t) {
                      Knot.log("Knot: read list display failed: " + t);
                    }
                  });
            })
        .start();
  }

  private static final class Reader {
    final String mid;
    final String name;
    final String picturePath; // null when the contact has no picture

    Reader(String mid, String name, String picturePath) {
      this.mid = mid;
      this.name = name;
      this.picturePath = picturePath;
    }
  }

  // Reader order follows the read history's insertion order, i.e. the order reads arrived in
  private static List<Reader> loadReaders(String messageId) {
    List<Reader> readers = new ArrayList<>();
    try {
      String chatId = LineDBUtils.resolveOwnMessageChatId(messageId);
      if (chatId == null) return readers;
      JSONObject chat = SettingsStore.loadReadHistory().optJSONObject("c").optJSONObject(chatId);
      JSONObject message = chat == null ? null : chat.optJSONObject("m").optJSONObject(messageId);
      JSONObject recorded = message == null ? null : message.optJSONObject("r");
      if (recorded == null) return readers;

      Iterator<String> keys = recorded.keys();
      while (keys.hasNext()) {
        String mid = keys.next();
        if (mid == null) continue;
        JSONObject info = recorded.optJSONObject(mid);
        String stored = info == null ? null : info.optString("n", null);
        String name = LineDBUtils.resolveMemberName(mid);
        readers.add(
            new Reader(
                mid,
                name != null && !name.isEmpty()
                    ? name
                    : stored != null && !stored.isEmpty() ? stored : mid,
                LineDBUtils.resolveMemberPicturePath(mid)));
      }
    } catch (Throwable t) {
      Knot.log("Knot: read list load failed: " + t);
    }
    return readers;
  }

  private static void display(Activity activity, List<Reader> readers) {
    LineTheme.invalidate();

    LinearLayout sheet = new LinearLayout(activity);
    sheet.setOrientation(LinearLayout.VERTICAL);
    sheet.setBackground(sheetBackground(activity));

    final Dialog[] dialogRef = new Dialog[1];
    sheet.addView(
        header(
            activity,
            () -> {
              Dialog dialog = dialogRef[0];
              if (dialog != null) dialog.dismiss();
            }));

    RecyclerView list = new RecyclerView(activity);
    list.setLayoutManager(new LinearLayoutManager(activity));
    list.setAdapter(new ReaderAdapter(activity, readers));
    sheet.addView(
        list,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    Dialog dialog = bottomSheet(activity, sheet);
    dialogRef[0] = dialog;
    dialog.show();
    applyDim(dialog);
  }

  // chat_ui_reaction_sheet: rounded-top background, centred bold title, close button top-right
  private static View header(Activity activity, Runnable onClose) {
    FrameLayout header = new FrameLayout(activity);
    header.setLayoutParams(
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, TITLE_HEIGHT_DP)));

    TextView title = new TextView(activity);
    title.setText(ModuleResources.get(R.string.read_list_title));
    title.setTextSize(TITLE_TEXT_SP);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(lineColor(activity, "primaryText", LineTheme.primaryTextColor(activity)));
    title.setGravity(Gravity.CENTER);
    header.addView(
        title,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER_HORIZONTAL));

    ImageView close = new ImageView(activity);
    Drawable icon = lineDrawable(activity, "chat_ui_reaction_sheet_close_button_selector");
    if (icon == null) icon = closeX(activity);
    close.setImageDrawable(icon);
    close.setContentDescription(lineString(activity, "access_close"));
    FrameLayout.LayoutParams closeLp =
        new FrameLayout.LayoutParams(
            dp(activity, CLOSE_SIZE_DP), dp(activity, CLOSE_SIZE_DP), Gravity.TOP | Gravity.END);
    closeLp.topMargin = dp(activity, CLOSE_TOP_MARGIN_DP);
    closeLp.rightMargin = dp(activity, CLOSE_RIGHT_MARGIN_DP);
    close.setOnClickListener(v -> onClose.run());
    header.addView(close, closeLp);
    return header;
  }

  private static Drawable sheetBackground(Activity activity) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(
        lineColor(activity, "primaryBackground", LineTheme.backgroundColor(activity)));
    float radius = dp(activity, SHEET_RADIUS_DP);
    background.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
    return background;
  }

  // MessageReactionSheet extends Theme.Design.Light.BottomSheetDialog with peek height 336dp
  private static Dialog bottomSheet(Activity activity, View sheet) {
    try {
      // The material classes ship inside LINE's APK, so resolve them through LINE's loader
      Class<?> cls =
          activity
              .getClassLoader()
              .loadClass("com.google.android.material.bottomsheet.BottomSheetDialog");
      // LINE's own MessageReactionSheet style: dim 0.7, transparent window background
      int styleId = identifier(activity, "MessageReactionSheet", "style");
      Dialog dialog =
          styleId != 0
              ? (Dialog) cls.getConstructor(Context.class, int.class).newInstance(activity, styleId)
              : (Dialog) cls.getConstructor(Context.class).newInstance(activity);
      Method setContentView = cls.getMethod("setContentView", View.class);
      setContentView.invoke(dialog, sheet);
      Method getBehavior = cls.getMethod("getBehavior");
      Object behavior = getBehavior.invoke(dialog);
      int peek = lineDimen(activity, "chat_ui_reactionsheet_peek_height", SHEET_PEEK_HEIGHT_DP);
      behavior.getClass().getMethod("setPeekHeight", int.class).invoke(behavior, peek);
      behavior
          .getClass()
          .getMethod("setState", int.class)
          .invoke(behavior, BOTTOM_SHEET_STATE_EXPANDED);
      return dialog;
    } catch (Throwable t) {
      Knot.log("Knot: BottomSheetDialog unavailable, falling back: " + t);
      Dialog dialog = new Dialog(activity, LineTheme.dialogTheme(activity));
      dialog.setContentView(sheet);
      dialog.setCancelable(true);
      dialog.getWindow().setGravity(Gravity.BOTTOM);
      dialog
          .getWindow()
          .setLayout(
              ViewGroup.LayoutParams.MATCH_PARENT,
              lineDimen(activity, "chat_ui_reactionsheet_peek_height", SHEET_PEEK_HEIGHT_DP));
      return dialog;
    }
  }

  private static void applyDim(Dialog dialog) {
    try {
      if (dialog.getWindow() != null) dialog.getWindow().setDimAmount(DIM_AMOUNT);
    } catch (Throwable ignored) {
    }
  }

  /** Rows per chat_ui_reaction_sheet_item: 33dp avatar, 14sp name, 16dp horizontal padding. */
  // LINE's own avatar pipeline (wh1.f + Glide, LINE 26.16.0): the main request is the picture
  // path as a preview URL, with the mid-based contact model chained in as thumbnail/error and
  // circle-cropped through rn.k/rn.m. Names are obfuscated per version, so any failure disables
  // this path for the process and the URL fallback takes over.
  private static boolean loadWithLineGlide(
      Activity activity, ImageView view, String mid, String picturePath) {
    if (!lineGlideAvailable) return false;
    // LINE's reaction sheet passes "" when the contact has no picture (hs1.f.onBindViewHolder)
    if (picturePath == null) picturePath = "";
    try {
      ClassLoader cl = activity.getClassLoader();
      Class<?> glideCls = cl.loadClass("com.bumptech.glide.o");
      Object glide =
          cl.loadClass("com.bumptech.glide.c").getMethod("e", Context.class).invoke(null, activity);
      Class<?> loaderIface = cl.loadClass("wh1.e");
      Object loader = cl.loadClass("wh1.f").getConstructor(glideCls).newInstance(glide);

      @SuppressWarnings({"unchecked", "rawtypes"})
      Object friendList =
          Enum.valueOf((Class<? extends Enum>) (Class) cl.loadClass("yh1.a"), "FRIEND_LIST");
      Object modelRequest =
          loaderIface
              .getMethod("b", Context.class, String.class, cl.loadClass("yh1.a"))
              .invoke(loader, activity, mid, friendList);

      // rn.k = CenterCrop, rn.m = CircleCrop; the transforms go on the model-based request
      // before it is chained in as thumbnail/error, exactly as tx1.a.c does in LINE 26.16.0
      Class<?> transformCls = cl.loadClass("in.n");
      Object transforms = java.lang.reflect.Array.newInstance(transformCls, 2);
      java.lang.reflect.Array.set(
          transforms, 0, cl.loadClass("rn.k").getConstructor().newInstance());
      java.lang.reflect.Array.set(
          transforms, 1, cl.loadClass("rn.m").getConstructor().newInstance());
      Object transformed =
          cl.loadClass("ao.a")
              .getMethod("J", transforms.getClass())
              .invoke(modelRequest, transforms);

      Object mainRequest =
          loaderIface.getMethod("f", String.class, String.class).invoke(loader, mid, picturePath);

      Class<?> requestCls = cl.loadClass("com.bumptech.glide.n");
      Object chained = requestCls.getMethod("i0", requestCls).invoke(mainRequest, transformed);
      chained = requestCls.getMethod("Q", requestCls).invoke(chained, transformed);
      requestCls.getMethod("R", ImageView.class).invoke(chained, view);
      return true;
    } catch (Throwable t) {
      lineGlideAvailable = false;
      return false;
    }
  }

  private static final class ReaderAdapter extends RecyclerView.Adapter<ReaderAdapter.Holder> {

    private final Activity activity;
    private final List<Reader> readers;

    ReaderAdapter(Activity activity, List<Reader> readers) {
      this.activity = activity;
      this.readers = readers;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
      RelativeLayout item = new RelativeLayout(activity);
      int paddingH = dp(activity, ITEM_PADDING_H_DP);
      item.setPadding(paddingH, 0, paddingH, 0);

      ImageView avatar = new ImageView(activity);
      avatar.setId(View.generateViewId());
      avatar.setClipToOutline(true);
      avatar.setOutlineProvider(circleProvider());
      RelativeLayout.LayoutParams avatarLp =
          new RelativeLayout.LayoutParams(dp(activity, AVATAR_DP), dp(activity, AVATAR_DP));
      avatarLp.topMargin = dp(activity, ITEM_AVATAR_TOP_MARGIN_DP);
      avatarLp.bottomMargin = dp(activity, ITEM_AVATAR_BOTTOM_MARGIN_DP);
      avatarLp.addRule(RelativeLayout.CENTER_VERTICAL);
      item.addView(avatar, avatarLp);

      TextView name = new TextView(activity);
      name.setTextSize(ITEM_NAME_SP);
      name.setTextColor(lineColor(activity, "primaryText", LineTheme.primaryTextColor(activity)));
      name.setMaxLines(1);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      RelativeLayout.LayoutParams nameLp =
          new RelativeLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      nameLp.addRule(RelativeLayout.RIGHT_OF, avatar.getId());
      nameLp.addRule(RelativeLayout.CENTER_VERTICAL);
      nameLp.leftMargin = dp(activity, ITEM_NAME_MARGIN_DP);
      item.addView(name, nameLp);

      return new Holder(item, avatar, name);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
      Reader reader = readers.get(position);
      holder.name.setText(reader.name);
      if (loadWithLineGlide(activity, holder.avatar, reader.mid, reader.picturePath)) return;
      Bitmap cached = AVATAR_CACHE.get(reader.mid);
      if (cached != null) {
        holder.avatar.setImageBitmap(cached);
        return;
      }
      holder.avatar.setImageBitmap(placeholder(activity, reader.name));
      queueDownload(reader);
    }

    @Override
    public int getItemCount() {
      return readers.size();
    }

    // One background fetch per member; rows refresh as pictures arrive
    private void queueDownload(Reader reader) {
      if (reader.picturePath == null || reader.picturePath.isEmpty()) return;
      if (IN_FLIGHT.add(reader.mid)) {
        AVATAR_POOL.execute(
            () -> {
              Bitmap bitmap = download(reader.picturePath, dp(activity, AVATAR_DP) * 2);
              IN_FLIGHT.remove(reader.mid);
              if (bitmap == null) return;
              AVATAR_CACHE.put(reader.mid, bitmap);
              activity.runOnUiThread(this::notifyDataSetChanged);
            });
      }
    }

    // Mirrors mp8.b.j: an absolute URL is used as-is, otherwise the path is a token on the
    // profile CDN and the small "preview" size is what LINE's reaction sheet requests
    private static Bitmap download(String picturePath, int sizePx) {
      try {
        String url =
            picturePath.startsWith("http") ? picturePath : PROFILE_CDN + picturePath + "/preview";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (InputStream input = connection.getInputStream()) {
          Bitmap bitmap = BitmapFactory.decodeStream(input);
          if (bitmap == null) return null;
          if (bitmap.getWidth() > sizePx) {
            bitmap =
                Bitmap.createScaledBitmap(
                    bitmap,
                    sizePx,
                    Math.round(sizePx * bitmap.getHeight() / (float) bitmap.getWidth()),
                    true);
          }
          return bitmap;
        } finally {
          connection.disconnect();
        }
      } catch (Throwable t) {
        return null;
      }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
      final ImageView avatar;
      final TextView name;

      Holder(View item, ImageView avatar, TextView name) {
        super(item);
        this.avatar = avatar;
        this.name = name;
      }
    }
  }

  private static ViewOutlineProvider circleProvider() {
    return new ViewOutlineProvider() {
      @Override
      public void getOutline(View view, Outline outline) {
        int size = Math.min(view.getWidth(), view.getHeight());
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), size / 2f);
      }
    };
  }

  // LINE's sheet_ic_x_normal: 30dp X, 1.55dp stroke
  private static Drawable closeX(Activity activity) {
    int size = dp(activity, CLOSE_SIZE_DP);
    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.55f * activity.getResources().getDisplayMetrics().density);
    paint.setColor(lineColor(activity, "octonaryAltNeutralFill", 0xFF111111));
    int inset = Math.round(size * 0.25f);
    canvas.drawLine(inset, inset, size - inset, size - inset, paint);
    canvas.drawLine(size - inset, inset, inset, size - inset, paint);
    return new BitmapDrawable(activity.getResources(), bitmap);
  }

  private static Bitmap placeholder(Activity activity, String name) {
    int size = dp(activity, AVATAR_DP);
    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(0xFFDDDDDD);
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
    paint.setColor(Color.WHITE);
    paint.setTextSize(size * 0.4f);
    paint.setTextAlign(Paint.Align.CENTER);
    String initial = name == null || name.isEmpty() ? "?" : name.substring(0, 1);
    paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    int y = Math.round(size / 2f - (paint.descent() + paint.ascent()) / 2);
    canvas.drawText(initial, size / 2f, y, paint);
    return bitmap;
  }

  private static int lineColor(Context context, String name, int fallback) {
    try {
      int id = context.getResources().getIdentifier(name, "color", LINE_PKG);
      if (id != 0) return context.getColor(id);
    } catch (Throwable ignored) {
    }
    return fallback;
  }

  private static int identifier(Context context, String name, String type) {
    try {
      return context.getResources().getIdentifier(name, type, LINE_PKG);
    } catch (Throwable ignored) {
    }
    return 0;
  }

  private static int lineDimen(Context context, String name, int fallbackDp) {
    try {
      int id = context.getResources().getIdentifier(name, "dimen", LINE_PKG);
      if (id != 0) return context.getResources().getDimensionPixelSize(id);
    } catch (Throwable ignored) {
    }
    return dp(context, fallbackDp);
  }

  private static Drawable lineDrawable(Context context, String name) {
    try {
      int id = context.getResources().getIdentifier(name, "drawable", LINE_PKG);
      if (id != 0) {
        if (Build.VERSION.SDK_INT >= 22) {
          return context.getDrawable(id);
        }
        return context.getResources().getDrawable(id);
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static String lineString(Context context, String name) {
    try {
      int id = context.getResources().getIdentifier(name, "string", LINE_PKG);
      if (id != 0) return context.getString(id);
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density + 0.5f);
  }

  private static Activity activity(Context context) {
    while (context instanceof ContextWrapper) {
      if (context instanceof Activity) return (Activity) context;
      Context base = ((ContextWrapper) context).getBaseContext();
      if (base == context) break;
      context = base;
    }
    return null;
  }
}
