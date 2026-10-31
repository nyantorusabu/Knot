package app.zipper.knot.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import app.zipper.knot.Knot;
import app.zipper.knot.R;
import app.zipper.knot.SettingsStore;
import app.zipper.knot.utils.LineDBUtils;
import app.zipper.knot.utils.LineTheme;
import app.zipper.knot.utils.ModuleResources;
import java.io.InputStream;
import java.lang.ref.WeakReference;
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
  private static final int ANIMATION_MS = 240;

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

    Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCancelable(true);
    dialog.setCanceledOnTouchOutside(true);
    FrameLayout overlay = new FrameLayout(activity);
    int peek = lineDimen(activity, "chat_ui_reactionsheet_peek_height", SHEET_PEEK_HEIGHT_DP);
    SheetDrag drag = new SheetDrag(activity, dialog, overlay, sheet, peek);
    overlay.setOnClickListener(v -> drag.close());
    sheet.setClickable(true);
    View header = header(activity, drag::close);
    sheet.addView(header);

    ListView list = new ListView(activity);
    list.setDivider(null);
    list.setAdapter(new ReaderAdapter(activity, readers));
    sheet.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

    FrameLayout.LayoutParams sheetParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, peek, Gravity.BOTTOM);
    overlay.addView(sheet, sheetParams);
    header.setOnTouchListener(drag);

    dialog.setContentView(overlay);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(DIM_AMOUNT);
    }
    dialog.show();
    if (window != null) {
      window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
    sheet.setVisibility(View.INVISIBLE);
    sheet.post(
        () -> {
          if (!dialog.isShowing()) return;
          sheet.setTranslationY(sheet.getHeight());
          sheet.setVisibility(View.VISIBLE);
          sheet.animate().translationY(0).setDuration(ANIMATION_MS).start();
        });
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

  private static final class SheetDrag implements View.OnTouchListener {
    private final Dialog dialog;
    private final View overlay;
    private final View sheet;
    private final int desiredPeek;
    private final int touchSlop;
    private final int flingVelocity;
    private VelocityTracker velocity;
    private ValueAnimator animator;
    private float downY;
    private int startHeight;
    private boolean dragging;
    private boolean closing;

    SheetDrag(Activity activity, Dialog dialog, View overlay, View sheet, int peek) {
      this.dialog = dialog;
      this.overlay = overlay;
      this.sheet = sheet;
      desiredPeek = peek;
      ViewConfiguration config = ViewConfiguration.get(activity);
      touchSlop = config.getScaledTouchSlop();
      flingVelocity = Math.max(config.getScaledMinimumFlingVelocity(), dp(activity, 500));
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          if (closing) return true;
          cancelAnimation();
          sheet.animate().cancel();
          sheet.setTranslationY(0);
          downY = event.getRawY();
          startHeight = sheet.getLayoutParams().height;
          dragging = false;
          if (velocity != null) velocity.recycle();
          velocity = VelocityTracker.obtain();
          track(event);
          return true;
        case MotionEvent.ACTION_MOVE:
          if (velocity == null) return true;
          track(event);
          float delta = event.getRawY() - downY;
          if (Math.abs(delta) > touchSlop) dragging = true;
          if (dragging) setHeight(Math.round(startHeight - delta));
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          if (velocity == null) return true;
          track(event);
          velocity.computeCurrentVelocity(1000);
          float speed = velocity.getYVelocity();
          velocity.recycle();
          velocity = null;
          if (!dragging) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
            return true;
          }
          int height = sheet.getLayoutParams().height;
          int max = maxHeight();
          int peek = Math.min(desiredPeek, max);
          int target;
          if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            target = height > (peek + max) / 2 ? max : peek;
          } else if (speed < -flingVelocity) {
            target = height < peek / 2 ? peek : max;
          } else if (speed > flingVelocity) {
            target = height > peek + touchSlop ? peek : 0;
          } else if (height < peek / 2) {
            target = 0;
          } else {
            target = height > (peek + max) / 2 ? max : peek;
          }
          snap(target);
          return true;
        default:
          return true;
      }
    }

    private int maxHeight() {
      return Math.max(1, overlay.getHeight());
    }

    // Header-local coordinates change as the sheet moves; track velocity in screen coordinates.
    private void track(MotionEvent event) {
      MotionEvent screenEvent = MotionEvent.obtain(event);
      screenEvent.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
      velocity.addMovement(screenEvent);
      screenEvent.recycle();
    }

    private void setHeight(int height) {
      ViewGroup.LayoutParams params = sheet.getLayoutParams();
      params.height = Math.max(0, Math.min(maxHeight(), height));
      sheet.setLayoutParams(params);
    }

    private void cancelAnimation() {
      if (animator != null) {
        animator.cancel();
        animator = null;
      }
    }

    void close() {
      if (!closing) snap(0);
    }

    private void snap(int target) {
      cancelAnimation();
      closing = target == 0;
      animator = ValueAnimator.ofInt(sheet.getLayoutParams().height, target);
      animator.setDuration(ANIMATION_MS);
      animator.addUpdateListener(animation -> setHeight((Integer) animation.getAnimatedValue()));
      animator.addListener(
          new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
              cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
              if (!cancelled && target == 0 && dialog.isShowing()) dialog.dismiss();
            }
          });
      animator.start();
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
      Knot.log("Knot: LINE profile image loader unavailable; using URL fallback: " + t);
      return false;
    }
  }

  private static final class ReaderAdapter extends BaseAdapter {

    private final Activity activity;
    private final List<Reader> readers;

    ReaderAdapter(Activity activity, List<Reader> readers) {
      this.activity = activity;
      this.readers = readers;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      Holder holder;
      if (convertView == null) {
        holder = createHolder();
        convertView = holder.item;
        convertView.setTag(holder);
      } else {
        holder = (Holder) convertView.getTag();
      }
      bind(holder, readers.get(position));
      return convertView;
    }

    private Holder createHolder() {
      RelativeLayout item = new RelativeLayout(activity);
      int paddingH = dp(activity, ITEM_PADDING_H_DP);
      item.setPadding(paddingH, 0, paddingH, 0);

      ImageView avatar = new ImageView(activity);
      avatar.setId(View.generateViewId());
      avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
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

    private void bind(Holder holder, Reader reader) {
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
    public int getCount() {
      return readers.size();
    }

    @Override
    public Reader getItem(int position) {
      return readers.get(position);
    }

    @Override
    public long getItemId(int position) {
      return position;
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

    private static final class Holder {
      final View item;
      final ImageView avatar;
      final TextView name;

      Holder(View item, ImageView avatar, TextView name) {
        this.item = item;
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
