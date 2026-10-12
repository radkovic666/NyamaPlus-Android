package fun.nyama.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONObject;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native enrollment/renewal only; normal playback screens and controls remain unchanged. */
public final class ActivationActivity extends Activity {
    private static final String REGISTER = "https://nyama.fun/register_now.php";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ActivationStore store;
    private Config config;
    private LinearLayout root;
    private TextView message;
    private EditText input;
    private Button resend;
    private ProgressBar progress;
    private ActivationResponse lastResponse;
    private String email = "", challenge = "", screen = "welcome", payment = "";
    private long resendAt, challengeExpiresAt;
    private boolean busy, destroyed;
    private int requestGeneration;
    private Runnable retry = this::checkAuthorization;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(LocaleHelper.wrap(base)); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        store = new ActivationStore(this);
        if (!config.isPhoneMode()) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // No OTP/challenge/billing/token data is persisted into an Activity saved-state Bundle.
        if (ActivationStore.managed(this)) checkAuthorization(); else welcome();
    }

    private String tr(String bg, String en) { return Config.LANG_EN.equals(config.language()) ? en : bg; }

    private void layout(String title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(0xFF080A0D);
        scroll.addView(root);
        setContentView(scroll);
        text("NYAMA+", 32).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text(title, 24);
        message = text("", 17);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(dp(40), dp(40)));
        input = null;
        resend = null;
    }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(7), 0, dp(7));
        root.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        b.setMinHeight(dp(56));
        b.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(focused ? 0xFF425D80 : 0xFF1B2636);
            bg.setStroke(dp(focused ? 3 : 1), focused ? Color.WHITE : 0xFF6D8CB4);
            view.setBackground(bg);
        });
        b.setBackgroundColor(0xFF1B2636);
        b.setOnClickListener(v -> { if (!busy) action.run(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(7), 0, 0);
        root.addView(b, p);
        return b;
    }

    private EditText field(String hint, int type, int maxLength) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xFFC0CAD8);
        field.setHint(hint);
        field.setInputType(type);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.addView(field, new LinearLayout.LayoutParams(config.isPhoneMode() ? -1 : dp(520), dp(64)));
        input = field;
        return field;
    }

    private void qr(String url) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 640, 640,
                    Collections.singletonMap(EncodeHintType.MARGIN, 4));
            int[] pixels = new int[640 * 640];
            for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++)
                pixels[y * 640 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            ImageView view = new ImageView(this);
            view.setImageBitmap(Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888));
            view.setContentDescription(tr("QR код за регистрация или подновяване", "Registration or renewal QR code"));
            int available = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 56;
            int height = (int) (getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
            int size = dp(Math.min(available, config.isPhoneMode() ? 260 : Math.max(160, Math.min(320, height - 210))));
            if (config.isPhoneMode()) root.addView(view, new LinearLayout.LayoutParams(size, size));
            else {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER);
                root.addView(row, new LinearLayout.LayoutParams(-1, -2));
                row.addView(view, new LinearLayout.LayoutParams(size, size));
                LinearLayout details = new LinearLayout(this);
                details.setOrientation(LinearLayout.VERTICAL);
                details.setGravity(Gravity.CENTER);
                details.setPadding(dp(24), 0, 0, 0);
                row.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
                root = details;
            }
        } catch (Exception invalid) { message.setText(tr("QR кодът не може да бъде показан. Опитайте отново.", "Cannot display the QR code. Try again.")); }
    }

    private void welcome() {
        screen = "welcome";
        challenge = "";
        layout("Добре дошли в Nyama+\nWelcome to Nyama+");
        qr(REGISTER);
        text(tr("Сканирайте QR кода, за да се регистрирате и да получите 14 дни безплатен достъп.",
                "Scan the QR code to register and receive 14 days of free access."), 18);
        if (config.isPhoneMode()) button(tr("Регистрация", "Register"), () -> openBrowser(REGISTER));
        button("Вече съм регистриран / I am registered already", this::emailScreen).requestFocus();
    }

    private void emailScreen() {
        screen = "email";
        layout(tr("Активиране на устройството", "Activate this device"));
        field(tr("Вашият имейл адрес", "Your email address"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 254)
                .setText(email);
        button(tr("Изпрати код", "Send code"), () -> {
            email = input.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { message.setText(error("invalid_input")); return; }
            requestCode();
        });
        button(tr("Назад", "Back"), this::welcome);
        input.requestFocus();
    }

    private void codeScreen() {
        screen = "code";
        layout(tr("Въведете шестцифрения код, изпратен на вашия имейл.", "Enter the six-digit code sent to your email."));
        field(tr("Шестцифрен код", "Six-digit code"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 6);
        button(tr("Потвърди", "Verify"), this::verify);
        resend = button(tr("Изпрати отново", "Resend code"), this::requestCode);
        button(tr("Назад", "Back"), this::emailScreen);
        input.requestFocus();
        tickCooldown();
    }

    private final Runnable cooldown = this::tickCooldown;
    private void tickCooldown() {
        main.removeCallbacks(cooldown);
        if (destroyed || resend == null) return;
        long seconds = Math.max(0, (resendAt - SystemClock.elapsedRealtime() + 999) / 1000);
        resend.setEnabled(!busy && seconds == 0);
        resend.setText(tr("Изпрати отново", "Resend code") + (seconds > 0 ? " (" + seconds + ")" : ""));
        if (seconds > 0) main.postDelayed(cooldown, 1000);
    }

    private void requestCode() {
        if (SystemClock.elapsedRealtime() < resendAt) {
            message.setText(tr("Изчакайте, преди да поискате нов код.", "Wait before requesting another code.")); return;
        }
        retry = this::requestCode;
        // A timed-out response may still have delivered an email; avoid immediately sending another.
        resendAt = SystemClock.elapsedRealtime() + 60000L;
        run(() -> {
            JSONObject response = controller().requestCode(email);
            String nextChallenge = response.getString("challenge_id");
            if (!nextChallenge.matches("[0-9a-f]{32}")) throw new ActivationApi.Failure("server_unavailable", 0);
            long now = SystemClock.elapsedRealtime();
            int lifetime = Math.min(600, Math.max(1, response.optInt("expires_in", 600)));
            int wait = Math.max(60, response.optInt("resend_after", 60));
            return () -> { challenge = nextChallenge; challengeExpiresAt = now + lifetime * 1000L;
                resendAt = now + wait * 1000L; codeScreen(); };
        });
    }

    private void verify() {
        String code = input.getText().toString();
        input.setText("");
        if (!code.matches("[0-9]{6}")) { message.setText(error("invalid_input")); return; }
        if (challenge.isEmpty() || SystemClock.elapsedRealtime() >= challengeExpiresAt) { message.setText(error("code_expired")); return; }
        run(() -> {
            ActivationResponse response = controller().verify(challenge, code);
            return () -> { challenge = ""; apply(response); };
        });
    }

    private void checkAuthorization() {
        screen = "checking";
        layout(tr("Проверка на абонамента…", "Checking subscription…"));
        retry = this::checkAuthorization;
        run(() -> {
            String token = store.token();
            if (token.isEmpty()) return this::welcome;
            ActivationResponse response = controller().config();
            return () -> apply(response);
        });
    }

    private void apply(ActivationResponse response) {
        lastResponse = response;
        ActivationAccess.accept(this, response);
        if (response.active) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        } else {
            payment = response.payment;
            screen = "renewal";
            layout(tr("Абонаментът ви изтече", "Your subscription has expired"));
            text(tr("За да продължите да гледате Nyama+, подновете своя абонамент.", "Renew your subscription to continue watching Nyama+."), 18);
            if (config.isPhoneMode()) button(tr("Поднови абонамента", "Renew subscription"), () -> openBrowser(payment));
            else {
                qr(payment);
                text(tr("Сканирайте QR кода, за да подновите абонамента.", "Scan the QR code to renew your subscription."), 18);
            }
            button(tr("Проверка на плащането", "Check payment"), this::checkAuthorization).requestFocus();
            text(tr("Ако вече сте платили, изчакайте обработката и проверете отново.", "If you have paid, allow processing time and check again."), 16);
            retry = this::checkAuthorization;
        }
    }

    private interface Work { Runnable execute() throws Exception; }

    private ActivationController controller() { return new ActivationController(new ActivationApi()::post, store); }

    private void run(Work work) {
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        int generation = ++requestGeneration;
        message.setText(tr("Моля, изчакайте…", "Please wait…"));
        io.execute(() -> {
            try {
                Runnable result = work.execute();
                main.post(() -> { if (!destroyed && generation == requestGeneration) {
                    busy = false; progress.setVisibility(View.GONE); result.run();
                } });
            } catch (Exception failure) {
                String code = failure instanceof ActivationApi.Failure ? ((ActivationApi.Failure) failure).code
                        : failure instanceof java.io.IOException ? "network_unavailable" : "server_unavailable";
                int wait = failure instanceof ActivationApi.Failure ? ((ActivationApi.Failure) failure).retryAfter : 0;
                boolean storedToken = false;
                try { storedToken = !store.token().isEmpty(); } catch (Exception ignored) { }
                boolean credentialAvailable = storedToken;
                String errorCode = code;
                main.post(() -> {
                    if (destroyed || generation != requestGeneration) return;
                    busy = false;
                    progress.setVisibility(View.GONE);
                    ActivationAccess.deny();
                    if ("rate_limited".equals(errorCode)) resendAt = SystemClock.elapsedRealtime() + Math.max(60, wait) * 1000L;
                    if ("code_expired".equals(errorCode)) challenge = "";
                    if ("activation_invalid".equals(errorCode)) welcome();
                    if (credentialAvailable && "code".equals(screen)) {
                        challenge = "";
                        screen = "checking";
                        layout(tr("Проверка на абонамента", "Check subscription"));
                        retry = this::checkAuthorization;
                    }
                    if ("checking".equals(screen)) {
                        button(tr("Опитай отново", "Try again"), retry).requestFocus();
                    }
                    message.setText(error(errorCode));
                    tickCooldown();
                });
            }
        });
    }

    private String error(String code) {
        switch (code) {
            case "account_not_found": return tr("Няма регистриран акаунт с този имейл. Върнете се към QR кода за регистрация.", "No account with this email. Return to the registration QR.");
            case "email_unverified": return tr("Потвърдете имейла за регистрация в Nyama.fun, преди да продължите.", "Confirm your Nyama.fun registration email first.");
            case "wrong_code": return tr("Неправилен код. Опитайте отново.", "Incorrect code. Try again.");
            case "code_expired": return tr("Кодът е изтекъл или вече е използван. Поискайте нов код.", "The code expired or was used. Request a new code.");
            case "inactive_account": return tr("Акаунтът е спрян или няма разрешен достъп. Свържете се с администратора.", "This account is blocked or ineligible. Contact the administrator.");
            case "device_limit_reached": return tr("Достигнат е лимитът за активирани устройства.", "The activated-device limit has been reached.");
            case "activation_invalid": return tr("Активирането е невалидно или отменено. Потвърдете имейла отново.", "Activation is invalid or revoked. Verify your email again.");
            case "rate_limited": return tr("Твърде много заявки. Изчакайте, преди да опитате отново.", "Too many requests. Wait before retrying.");
            case "mail_unavailable": return tr("Имейл услугата временно не е достъпна. Опитайте по-късно.", "Email delivery is temporarily unavailable. Try later.");
            case "invalid_input": return tr("Проверете въведения имейл или шестцифрен код.", "Check the email or six-digit code.");
            case "invalid_device_id": return tr("Идентификаторът на инсталацията не е приет. Опитайте отново.", "The installation ID was rejected. Try again.");
            case "https_required": return tr("Проблем с HTTPS настройките на сървъра. Свържете се с администратора.", "Server HTTPS configuration problem. Contact the administrator.");
            case "network_unavailable": return tr("Няма връзка със сървъра. Проверете интернет връзката и опитайте отново.", "Cannot connect securely. Check your internet connection and retry.");
            case "storage_unavailable": return tr("Защитеното хранилище не е достъпно. Опитайте отново.", "Secure storage is unavailable. Try again.");
            default: return tr("Сървърът временно не е достъпен или отговорът не е валиден. Опитайте отново.", "The server is unavailable or returned an invalid response. Try again.");
        }
    }

    private void openBrowser(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException missing) { message.setText(tr("Няма наличен браузър на устройството.", "No browser is available on this device.")); }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (busy) return;
        if ("code".equals(screen)) emailScreen();
        else if ("email".equals(screen)) welcome();
        else finishAndRemoveTask(); // Renewal/errors must never reveal cached playback behind the gate.
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if ("email".equals(screen)) {
            if (input != null) email = input.getText().toString();
            emailScreen();
        } else if ("code".equals(screen)) codeScreen();
        else if ("renewal".equals(screen) && lastResponse != null) apply(lastResponse);
        else if ("welcome".equals(screen)) welcome();
        else layout(tr("Проверка на абонамента…", "Checking subscription…"));
        if (busy) { progress.setVisibility(View.VISIBLE); message.setText(tr("Моля, изчакайте…", "Please wait…")); }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        requestGeneration++;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
}
