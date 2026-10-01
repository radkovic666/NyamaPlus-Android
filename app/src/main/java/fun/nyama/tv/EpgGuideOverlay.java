package fun.nyama.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class EpgGuideOverlay extends FrameLayout {
    public interface Listener { void onClosed(); }

    private final Listener listener;
    private final List<EpgEvent> events = new ArrayList<>();
    private final ListView list;
    private final EventAdapter adapter = new EventAdapter();
    private final ImageView logo;
    private final TextView channelName;
    private final TextView title;
    private final TextView time;
    private final TextView description;
    private final TextView status;
    private int selected = 0;
    private Channel channel;
    private final boolean compact;
    private final boolean portraitPhone;

    public EpgGuideOverlay(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        Config config = new Config(context);
        this.compact = config.isPhoneMode();
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int heightDp = Math.round(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
        this.portraitPhone = compact && heightDp > widthDp;
        setVisibility(GONE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0x55000000);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0x33000000);
        addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(compact ? 12 : 30), dp(compact ? 6 : 10), dp(compact ? 12 : 30), dp(compact ? 6 : 10));
        top.setBackgroundColor(0xC00D0F13);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(compact ? 60 : 74)));

        logo = new ImageView(context);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        top.addView(logo, new LinearLayout.LayoutParams(dp(compact ? 58 : 84), dp(compact ? 42 : 54)));

        channelName = text("", compact ? 18 : 23, Color.WHITE, true);
        channelName.setPadding(dp(16), 0, 0, 0);
        top.addView(channelName, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView help = text(context.getString(R.string.epg_guide_help), compact ? 11 : 13, 0xFFB8BEC8, false);
        help.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        help.setVisibility(portraitPhone ? GONE : VISIBLE);
        top.addView(help, new LinearLayout.LayoutParams(dp(compact ? 190 : 280), -1));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(portraitPhone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(compact ? 8 : 16), dp(compact ? 7 : 14), dp(compact ? 8 : 12), dp(compact ? 7 : 14));
        left.setBackgroundColor(0xB814171C);
        body.addView(left, portraitPhone ? new LinearLayout.LayoutParams(-1, 0, 0.58f) : new LinearLayout.LayoutParams(0, -1, 0.56f));

        list = new ListView(context);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(adapter);
        left.addView(list, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout detail = new LinearLayout(context);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(compact ? 14 : 30), dp(compact ? 10 : 24), dp(compact ? 14 : 30), dp(compact ? 10 : 24));
        detail.setBackgroundColor(0xC00D0F13);
        body.addView(detail, portraitPhone ? new LinearLayout.LayoutParams(-1, 0, 0.42f) : new LinearLayout.LayoutParams(0, -1, 0.44f));

        status = text("", 13, UiTheme.accent(context), true);
        detail.addView(status);
        time = text("", compact ? 12 : 15, 0xFFBBC1CA, false);
        time.setPadding(0, dp(8), 0, 0);
        detail.addView(time);
        title = text("", compact ? 18 : 25, Color.WHITE, true);
        title.setPadding(0, dp(8), 0, dp(16));
        detail.addView(title);
        description = text("", compact ? 13 : 17, 0xFFD3D7DE, false);
        description.setGravity(Gravity.TOP | Gravity.START);
        detail.addView(description, new LinearLayout.LayoutParams(-1, 0, 1f));

        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                selected = pos;
                updateDetail();
                adapter.notifyDataSetChanged();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected = position;
            updateDetail();
            adapter.notifyDataSetChanged();
        });
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void show(Channel channel) {
        this.channel = channel;
        events.clear();
        events.addAll(EpgRepository.events(channel));
        channelName.setText(getContext().getString(R.string.epg_for_channel, channel == null ? "" : channel.name));
        if (channel != null) LogoRepository.load(getContext(), channel.logo, logo);
        else logo.setImageDrawable(null);

        long now = System.currentTimeMillis();
        EpgEvent canonicalCurrent = EpgRepository.current(channel);
        selected = 0;
        boolean selectedCurrent = false;
        for (int i = 0; i < events.size(); i++) {
            EpgEvent e = events.get(i);
            if (sameEvent(e, canonicalCurrent)) {
                selected = i;
                selectedCurrent = true;
                break;
            }
        }
        if (!selectedCurrent) {
            for (int i = 0; i < events.size(); i++) {
                EpgEvent e = events.get(i);
                if (e.startMs > now) { selected = i; break; }
            }
        }
        setVisibility(VISIBLE);
        adapter.notifyDataSetChanged();
        if (!events.isEmpty()) list.setSelection(selected);
        list.requestFocus();
        updateDetail();
    }

    public void hide() {
        if (!isOpen()) return;
        setVisibility(GONE);
        if (listener != null) listener.onClosed();
    }

    public boolean handleKey(KeyEvent event) {
        if (!isOpen()) return false;
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) return consumes(code);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { hide(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP) {
            move(-1); return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN) {
            move(1); return true;
        }
        return consumes(code);
    }

    private boolean consumes(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return true;
            default:
                return false;
        }
    }

    private void move(int delta) {
        if (events.isEmpty()) return;
        selected = Math.max(0, Math.min(events.size() - 1, selected + delta));
        list.setSelection(selected);
        updateDetail();
        adapter.notifyDataSetChanged();
    }

    private void updateDetail() {
        if (events.isEmpty() || selected < 0 || selected >= events.size()) {
            status.setText("");
            time.setText("");
            title.setText(getContext().getString(R.string.epg_no_events));
            description.setText("");
            return;
        }
        EpgEvent event = events.get(selected);
        long now = System.currentTimeMillis();
        EpgEvent canonicalCurrent = EpgRepository.current(channel);
        if (sameEvent(event, canonicalCurrent)) {
            status.setText(getContext().getString(R.string.epg_current));
            status.setTextColor(0xFF4CAF50);
        } else if (event.startMs <= now) {
            // An overlapping older interval must not become a second "current" programme.
            status.setText(getContext().getString(R.string.epg_past));
            status.setTextColor(0xFFFF9800);
        } else {
            status.setText(getContext().getString(R.string.epg_future));
            status.setTextColor(0xFF9EA4AD);
        }
        time.setText(format(event.startMs) + " - " + format(event.stopMs));
        title.setText(event.title.isEmpty() ? getContext().getString(R.string.untitled_programme) : event.title);
        description.setText(event.description.isEmpty() ? getContext().getString(R.string.no_description) : event.description);
    }

    private boolean sameEvent(EpgEvent a, EpgEvent b) {
        if (a == null || b == null) return false;
        return a == b || (a.startMs == b.startMs
                && a.stopMs == b.stopMs
                && a.title.equals(b.title));
    }

    private String format(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private TextView text(String value, int baseSp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(UiTheme.sp(getContext(), baseSp));
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class EventAdapter extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public Object getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView when;
            TextView name;
            TextView state;
            if (convertView instanceof LinearLayout && ((LinearLayout) convertView).getChildCount() == 3) {
                row = (LinearLayout) convertView;
                when = (TextView) row.getChildAt(0);
                name = (TextView) row.getChildAt(1);
                state = (TextView) row.getChildAt(2);
            } else {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(compact ? 8 : 14), dp(compact ? 4 : 7), dp(compact ? 8 : 14), dp(compact ? 4 : 7));
                row.setMinimumHeight(dp(compact ? 50 : 64));
                when = text("", compact ? 12 : 15, 0xFFBCC2CB, true);
                row.addView(when, new LinearLayout.LayoutParams(dp(compact ? 100 : 128), -1));
                name = text("", compact ? 14 : 18, Color.WHITE, false);
                row.addView(name, new LinearLayout.LayoutParams(0, -1, 1f));
                state = text("", compact ? 10 : 12, 0xFF9EA4AD, true);
                state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                row.addView(state, new LinearLayout.LayoutParams(dp(compact ? 72 : 100), -1));
            }

            EpgEvent event = events.get(position);
            long now = System.currentTimeMillis();
            EpgEvent canonicalCurrent = EpgRepository.current(channel);
            when.setText(format(event.startMs) + " - " + format(event.stopMs));
            name.setText(event.title.isEmpty() ? getContext().getString(R.string.untitled_programme) : event.title);
            if (sameEvent(event, canonicalCurrent)) {
                state.setText(getContext().getString(R.string.epg_current));
                state.setTextColor(0xFF4CAF50);
            } else if (event.startMs <= now) {
                state.setText(getContext().getString(R.string.epg_past));
                state.setTextColor(0xFFFF9800);
            } else {
                state.setText(getContext().getString(R.string.epg_future));
                state.setTextColor(0xFF9EA4AD);
            }
            row.setBackground(position == selected ? rounded(0xCC303640, 8) : rounded(Color.TRANSPARENT, 8));
            return row;
        }
    }
}
