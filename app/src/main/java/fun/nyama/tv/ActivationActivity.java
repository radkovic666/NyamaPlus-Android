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
import java.util.concurrent.Future;

/** Native enrollment/renewal only; normal playback screens and controls remain unchanged. */
public final class ActivationActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    ExecutorService io = Executors.newSingleThreadExecutor();
    ActivationController pairingController; // Optional injected boundary for deterministic mock/UI tests.
    private ActivationApi pairingApi;
    private PairingSession pairing;
    private Future<?> pairingRequest;
    private boolean visible, pairingBusy, pairingError;
    private int pairingGeneration;
    private long pairingRetryAt;
    private String pairingStatus = "";
    private String pairingErrorCode = "server_unavailable";
    private final Runnable enablePairRetry = this::enablePairRetry;
    private final Runnable poll = this::pollPairing;
    private final Runnable expirePairing = () -> pairingFailure(new ActivationApi.Failure("pairing_expired", 0));
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
        main.removeCallbacks(cooldown);
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
        stopPairing();
        pairing = null;
        pairingStatus = "";
        pairingError = false;
        screen = "welcome";
        challenge = "";
        renderPairing();
        if (visible) startPairing();
    }

    private void renderPairing() {
        layout(tr("Добре дошли в Nyama+", "Welcome to Nyama+"));
        text(tr("Сканирайте QR кода, за да се регистрирате и активирате устройството.",
                "Scan the QR code to register and activate this device."), 18);
        if (pairing != null) {
            qr(pairing.qrUrl);
            // Keep the waiting status below the QR, including the TV side-by-side layout.
            message = text(pairingStatus.isEmpty() ? tr("Очакваме регистрация и потвърждение...",
                    "Waiting for registration and confirmation...") : pairingStatus, 17);
            if (config.isPhoneMode()) button(tr("Регистрация", "Register"), () -> openBrowser(pairing.qrUrl));
        } else {
            message.setText(tr("Зареждане на QR кода…", "Loading QR code…"));
            progress.setVisibility(View.VISIBLE);
        }
        button("Вече съм регистриран / I am registered already", this::emailScreen).requestFocus();
    }

    private ActivationController pairingController() {
        if (pairingController != null) return pairingController;
        pairingApi = new ActivationApi();
        return new ActivationController(pairingApi::post, store);
    }

    private void startPairing() {
        if (!visible || destroyed || !"welcome".equals(screen) || pairingBusy || pairingError) return;
        if (SystemClock.elapsedRealtime() < pairingRetryAt) {
            pairingError = true;
            pairingErrorCode = "rate_limited";
            renderPairingError();
            return;
        }
        if (pairing != null) { schedulePoll(0); return; }
        long started = SystemClock.elapsedRealtime();
        runPairing(controller -> {
            PairingSession next = controller.startPairing(started);
            return () -> {
                pairing = next;
                renderPairing();
                schedulePoll(next.pollAfter * 1000L);
            };
        });
    }

    private void pollPairing() {
        if (pairing == null || !visible || pairingBusy || pairingError || !"welcome".equals(screen)) return;
        if (SystemClock.elapsedRealtime() >= pairing.expiresAt) { expirePairing.run(); return; }
        PairingSession current = pairing;
        runPairing(controller -> {
            PairingResult result = controller.pollPairing(current);
            return () -> {
                if (result.activated()) {
                    try {
                        // The generation was checked on the main thread before this callback.
                        // Save first, then provision; a config/storage failure uses config retry
                        // with the saved token instead of enrolling the customer a second time.
                        ActivationResponse response = controller.completePairing(current, result);
                        stopPairing();
                        pairing = null;
                        apply(response);
                    } catch (Exception failure) {
                        stopPairing();
                        pairing = null;
                        try {
                            if (controller.hasToken()) checkAuthorization();
                            else pairingFailure(new ActivationApi.Failure("storage_unavailable", 0));
                        } catch (Exception storageFailure) {
                            pairingFailure(new ActivationApi.Failure("storage_unavailable", 0));
                        }
                    }
                } else {
                    pairingStatus = "waiting_for_confirmation".equals(result.status)
                            ? tr("Регистрацията е получена!\nПотвърдете имейла си, за да активирате Nyama+.",
                                 "Registration received!\nConfirm your email to activate Nyama+.")
                            : tr("Очакваме да сканирате QR кода...", "Waiting for you to scan the QR code...");
                    message.setText(pairingStatus);
                    schedulePoll(result.pollAfter * 1000L);
                }
            };
        });
    }

    private interface PairWork { Runnable execute(ActivationController controller) throws Exception; }

    private void runPairing(PairWork work) {
        if (pairingBusy) return;
        pairingBusy = true;
        int generation = ++pairingGeneration;
        ActivationController controller = pairingController();
        pairingRequest = io.submit(() -> {
            try {
                Runnable result = work.execute(controller);
                main.post(() -> {
                    if (!acceptPairing(generation)) return;
                    pairingBusy = false;
                    pairingRequest = null;
                    progress.setVisibility(View.GONE);
                    result.run();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (!acceptPairing(generation)) return;
                    pairingBusy = false;
                    pairingRequest = null;
                    pairingFailure(failure);
                });
            }
        });
    }

    private boolean acceptPairing(int generation) {
        return !destroyed && visible && "welcome".equals(screen) && generation == pairingGeneration;
    }

    private void enablePairRetry() {
        if (!destroyed && visible && pairingError && resend != null) resend.setEnabled(true);
    }

    private void schedulePoll(long delay) {
        main.removeCallbacks(poll);
        main.removeCallbacks(expirePairing);
        main.removeCallbacks(enablePairRetry);
        if (pairing == null || !visible || pairingError) return;
        long remaining = pairing.expiresAt - SystemClock.elapsedRealtime();
        if (remaining <= 0) { expirePairing.run(); return; }
        main.postDelayed(expirePairing, remaining);
        main.postDelayed(poll, Math.max(delay, pairingRetryAt - SystemClock.elapsedRealtime()));
    }

    private void stopPairing() {
        pairingGeneration++;
        main.removeCallbacks(poll);
        main.removeCallbacks(expirePairing);
        main.removeCallbacks(enablePairRetry);
        if (pairingApi != null) { pairingApi.cancel(); pairingApi = null; }
        if (pairingRequest != null) { pairingRequest.cancel(true); pairingRequest = null; }
        pairingBusy = false;
    }

    private void pairingFailure(Exception failure) {
        stopPairing();
        pairingError = true;
        String code = failure instanceof ActivationApi.Failure ? ((ActivationApi.Failure) failure).code
                : failure instanceof java.io.IOException ? "network_unavailable" : "server_unavailable";
        int wait = failure instanceof ActivationApi.Failure ? ((ActivationApi.Failure) failure).retryAfter : 0;
        pairingErrorCode = code;
        pairingRetryAt = Math.max(pairingRetryAt, SystemClock.elapsedRealtime()
                + Math.max(wait, "rate_limited".equals(code) ? 60 : 3) * 1000L);
        boolean invalid = "pairing_expired".equals(code) || "pairing_invalid".equals(code)
                || "activation_invalid".equals(code) || "device_limit_reached".equals(code)
                || "inactive_account".equals(code);
        if (invalid) pairing = null;
        renderPairingError();
    }

    private void renderPairingError() {
        String code = pairingErrorCode;
        renderPairing();
        progress.setVisibility(View.GONE);
        message.setText(error(code));
        boolean refresh = "pairing_expired".equals(code) || "pairing_invalid".equals(code);
        Button retryPair = button(refresh ? tr("Генерирай нов QR код", "Generate a new QR code")
                : tr("Опитайте отново", "Try again"), () -> {
            if (SystemClock.elapsedRealtime() < pairingRetryAt) return;
            pairingError = false;
            if (pairing != null && SystemClock.elapsedRealtime() >= pairing.expiresAt) pairing = null;
            renderPairing();
            startPairing();
        });
        resend = retryPair;
        retryPair.setEnabled(SystemClock.elapsedRealtime() >= pairingRetryAt);
        main.postDelayed(enablePairRetry,
                Math.max(1, pairingRetryAt - SystemClock.elapsedRealtime()));
        if (pairing != null) main.postDelayed(expirePairing, Math.max(1, pairing.expiresAt - SystemClock.elapsedRealtime()));
    }

    private void emailScreen() {
        stopPairing();
        pairing = null;
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
        stopPairing();
        pairing = null;
        lastResponse = response;
        if (!ActivationAccess.accept(this, response)) {
            screen = "checking";
            layout(tr("Проверка на абонамента", "Check subscription"));
            message.setText(error("storage_unavailable"));
            button(tr("Опитайте отново", "Try again"), this::checkAuthorization).requestFocus();
            return;
        }
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
            case "device_limit_reached": return tr("Достигнат е лимитът за активирани устройства. Управлявайте устройствата в акаунта си или използвайте активиране с имейл.", "The device limit has been reached. Manage your account devices or use email activation.");
            case "pairing_expired": return tr("QR кодът изтече.", "The QR code expired.");
            case "pairing_invalid": return tr("QR кодът вече не е валиден. Генерирайте нов QR код.", "This QR code is no longer valid. Generate a new QR code.");
            case "activation_invalid": return tr("Активирането е невалидно или отменено. Потвърдете имейла отново.", "Activation is invalid or revoked. Verify your email again.");
            case "rate_limited": return tr("Твърде много заявки. Изчакайте, преди да опитате отново.", "Too many requests. Wait before retrying.");
            case "mail_unavailable": return tr("Имейл услугата временно не е достъпна. Опитайте по-късно.", "Email delivery is temporarily unavailable. Try later.");
            case "invalid_input": return tr("Проверете въведения имейл или шестцифрен код.", "Check the email or six-digit code.");
            case "invalid_device_id": return tr("Идентификаторът на инсталацията не е приет. Опитайте отново.", "The installation ID was rejected. Try again.");
            case "https_required": return tr("Проблем с HTTPS настройките на сървъра. Свържете се с администратора.", "Server HTTPS configuration problem. Contact the administrator.");
            case "network_unavailable": return tr("Няма връзка със сървъра. Опитайте отново.", "No connection to the server. Try again.");
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
        else { stopPairing(); finishAndRemoveTask(); } // Never reveal cached playback behind the gate.
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if ("email".equals(screen)) {
            if (input != null) email = input.getText().toString();
            emailScreen();
        } else if ("code".equals(screen)) codeScreen();
        else if ("renewal".equals(screen) && lastResponse != null) apply(lastResponse);
        else if ("welcome".equals(screen)) {
            if (!pairingError) renderPairing(); // Rotation preserves the current in-memory session.
        }
        else layout(tr("Проверка на абонамента…", "Checking subscription…"));
        if (busy) { progress.setVisibility(View.VISIBLE); message.setText(tr("Моля, изчакайте…", "Please wait…")); }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopPairing();
        pairing = null;
        requestGeneration++;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if ("welcome".equals(screen)) {
            if (pairing != null && SystemClock.elapsedRealtime() >= pairing.expiresAt) expirePairing.run();
            else if (pairingError) {
                // Rebuild retry controls/timers that were removed on pause.
                renderPairingError();
            } else startPairing();
        }
    }

    @Override protected void onPause() {
        visible = false;
        stopPairing();
        super.onPause();
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
}
