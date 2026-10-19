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
    private String pairingStatus = "";
    private String pairingErrorCode = "server_unavailable";
    private final Runnable poll = this::pollPairing;
    private final Runnable expirePairing = () -> pairingFailure(new ActivationApi.Failure("pairing_expired", 0));
    private ActivationStore store;
    private Config config;
    private LinearLayout root;
    private TextView message;
    private EditText input;
    private Button resend, sendCode, verifyCode;
    private int cooldownMessageAction = -1;
    private ProgressBar progress;
    private ActivationResponse lastResponse;
    private String email = "", challenge = "", screen = "welcome", payment = "";
    private long challengeExpiresAt;
    private boolean busy, destroyed;
    private final Runnable renewalCheck = () -> {
        if (!visible || destroyed || !"renewal".equals(screen) || config.isPhoneMode()) return;
        if (!busy) checkAuthorization();
        scheduleRenewalCheck();
    };

    private void scheduleRenewalCheck() {
        main.removeCallbacks(renewalCheck);
        if (visible && !destroyed && "renewal".equals(screen) && !config.isPhoneMode())
            main.postDelayed(renewalCheck, 45000L);
    }
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
        ActivationResponse renewal = ActivationAccess.takePendingRenewal();
        if (renewal != null) apply(renewal);
        else if (ActivationStore.managed(this)) checkAuthorization();
        else if (state != null && ("email".equals(state.getString("activation_screen")) || "code".equals(state.getString("activation_screen")))) emailScreen();
        else welcome();
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
        resend = sendCode = verifyCode = null;
        cooldownMessageAction = -1;
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
        if (config.isPhoneMode()) return;
        try {
            ImageView view = qrImage(url);
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

    private ImageView qrImage(String url) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 640, 640,
                Collections.singletonMap(EncodeHintType.MARGIN, 4));
        int[] pixels = new int[640 * 640];
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++)
            pixels[y * 640 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        ImageView view = new ImageView(this);
        view.setImageBitmap(Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888));
        view.setContentDescription(tr("QR код за регистрация или подновяване", "Registration or renewal QR code"));
        return view;
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
        boolean phone = config.isPhoneMode();
        // Presentation only: pairing, polling, URLs and secure storage remain unchanged.
        layout(tr("Добре дошли в Nyama+", "Welcome to Nyama+"));
        root.setTag("activation-welcome");
        root.removeAllViews();
        TextView brand = text("NYAMA+", phone ? 36 : 40);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD); brand.setTextColor(UiTheme.accent(this));
        text(tr("Добре дошли в Nyama+", "Welcome to Nyama+"), phone ? 23 : 26);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(phone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable background = new GradientDrawable(); background.setColor(0xFF111820);
        background.setCornerRadius(dp(18)); background.setStroke(dp(1), 0xFF293747); card.setBackground(background);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1,-2); cardParams.topMargin=dp(12);
        root.addView(card,cardParams);
        if (!phone) {
            int width = Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
            int height = Math.round(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density);
            int qrSize = dp(Math.max(180,Math.min(420,Math.min(Math.round(width*0.38f),height-185))));
            FrameLayout qrFrame = new FrameLayout(this); qrFrame.setTag("welcome-registration-qr");
            card.addView(qrFrame,new LinearLayout.LayoutParams(qrSize,qrSize));
            if (pairing != null) try { qrFrame.addView(qrImage(pairing.qrUrl),new FrameLayout.LayoutParams(-1,-1)); }
            catch (Exception invalid) {
                TextView unavailable=new TextView(this);unavailable.setTextColor(Color.WHITE);unavailable.setGravity(Gravity.CENTER);
                unavailable.setText(tr("QR кодът не може да бъде показан. Опитайте отново.","Cannot display the QR code. Try again."));
                qrFrame.addView(unavailable,new FrameLayout.LayoutParams(-1,-1));
            }
        }
        LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
        if (!phone) details.setPadding(dp(28),0,0,0);
        card.addView(details,phone ? new LinearLayout.LayoutParams(-1,-2) : new LinearLayout.LayoutParams(0,-2,1f));
        root=details;
        text(tr("Нов потребител", "New user"),22).setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        text(phone ? tr("Регистрирайте се и активирайте това устройство в браузъра.",
                "Register and activate this device in your browser.")
                : tr("Сканирайте QR кода, за да се регистрирате и активирате устройството.",
                "Scan the QR code to register and activate this device."),18);
        Button registration = null;
        if (phone) {
            final String target = pairing == null ? "" : pairing.qrUrl;
            registration=button(tr("Нов потребител / Регистрация", "New user / Register"),()->openBrowser(target));
            registration.setTag("activation-registration"); registration.setEnabled(pairing!=null); welcomeButton(registration,true);
        }
        message=text(pairing == null ? tr("Подготвяме регистрацията…", "Preparing registration…")
                : pairingStatus.isEmpty() ? tr("Очакваме регистрация и потвърждение...", "Waiting for registration and confirmation...") : pairingStatus,17);
        progress=new ProgressBar(this);progress.setVisibility(pairing==null?View.VISIBLE:View.GONE);
        root.addView(progress,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout separator = new LinearLayout(this); separator.setGravity(Gravity.CENTER_VERTICAL);
        separator.setPadding(0,dp(18),0,dp(12)); separator.setTag("activation-path-separator");
        for (int i=0;i<3;i++) {
            if(i==1) { TextView or=new TextView(this);or.setText(tr("или","or"));or.setTextColor(0xFF9FAEBF);or.setPadding(dp(16),0,dp(16),0);separator.addView(or); }
            else { View line=new View(this);line.setBackgroundColor(0xFF344353);separator.addView(line,new LinearLayout.LayoutParams(0,dp(1),1f)); }
        }
        root.addView(separator,new LinearLayout.LayoutParams(-1,-2));
        Button registered=button("Вече съм регистриран / I am registered already",this::emailScreen);
        welcomeButton(registered,false);
        if(registration!=null&&registration.isEnabled()) registration.requestFocus(); else registered.requestFocus();
    }

    private void welcomeButton(Button button, boolean primary) {
        View.OnFocusChangeListener style = (view,focused)->{
            GradientDrawable background=new GradientDrawable();background.setCornerRadius(dp(12));
            int accent=UiTheme.accent(this); background.setColor(primary?accent:0xFF1B2636);
            background.setStroke(dp(focused?3:1),focused?Color.WHITE:primary?accent:0xFF405267);
            view.setBackground(background);button.setTextColor(primary?UiTheme.contrastingTextColor(accent):Color.WHITE);
        };
        button.setOnFocusChangeListener(style);style.onFocusChange(button,button.hasFocus());
    }

    private ActivationController pairingController() {
        if (pairingController != null) return pairingController;
        pairingApi = new ActivationApi();
        return new ActivationController(pairingApi::post, store);
    }

    private void startPairing() {
        if (!visible || destroyed || !"welcome".equals(screen) || pairingBusy || pairingError) return;
        if (ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, SystemClock.elapsedRealtime()) > 0L) {
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
                            : config.isPhoneMode() ? tr("Отворете регистрацията в браузъра...", "Open registration in your browser...")
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
                long observedAt = SystemClock.elapsedRealtime();
                if (failure instanceof ActivationApi.Failure) ActivationCooldowns.observe(ActivationCooldowns.PAIRING,
                        ((ActivationApi.Failure) failure).retryAfter, observedAt, "rate_limited".equals(((ActivationApi.Failure) failure).code));
                main.post(() -> {
                    if (!acceptPairing(generation)) return;
                    pairingBusy = false;
                    pairingRequest = null;
                    pairingFailure(failure, observedAt);
                });
            }
        });
    }

    private boolean acceptPairing(int generation) {
        return !destroyed && visible && "welcome".equals(screen) && generation == pairingGeneration;
    }

    private void schedulePoll(long delay) {
        main.removeCallbacks(poll);
        main.removeCallbacks(expirePairing);
        if (pairing == null || !visible || pairingError) return;
        long remaining = pairing.expiresAt - SystemClock.elapsedRealtime();
        if (remaining <= 0) { expirePairing.run(); return; }
        main.postDelayed(expirePairing, remaining);
        main.postDelayed(poll, Math.max(delay, ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, SystemClock.elapsedRealtime()) * 1000L));
    }

    private void stopPairing() {
        pairingGeneration++;
        main.removeCallbacks(poll);
        main.removeCallbacks(expirePairing);
        if (pairingApi != null) { pairingApi.cancel(); pairingApi = null; }
        if (pairingRequest != null) { pairingRequest.cancel(true); pairingRequest = null; }
        pairingBusy = false;
    }

    private void pairingFailure(Exception failure) { pairingFailure(failure, SystemClock.elapsedRealtime()); }

    private void pairingFailure(Exception failure, long observedAt) {
        stopPairing();
        pairingError = true;
        String code = failure instanceof ActivationApi.Failure ? ((ActivationApi.Failure) failure).code
                : failure instanceof java.io.IOException ? "network_unavailable" : "server_unavailable";
        int wait = failure instanceof ActivationApi.Failure ? ((ActivationApi.Failure) failure).retryAfter : 0;
        pairingErrorCode = code;
        ActivationCooldowns.observe(ActivationCooldowns.PAIRING, wait, observedAt, "rate_limited".equals(code));
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
        Button retryPair = button(refresh ? config.isPhoneMode() ? tr("Нова регистрационна връзка", "New registration link")
                : tr("Генерирай нов QR код", "Generate a new QR code")
                : tr("Опитайте отново", "Try again"), () -> {
            if (ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, SystemClock.elapsedRealtime()) > 0L) return;
            pairingError = false;
            if (pairing != null && SystemClock.elapsedRealtime() >= pairing.expiresAt) pairing = null;
            renderPairing();
            startPairing();
        });
        resend = retryPair;
        if ("rate_limited".equals(code) || ActivationCooldowns.rateLimited(ActivationCooldowns.PAIRING, SystemClock.elapsedRealtime()))
            cooldownMessageAction = ActivationCooldowns.PAIRING;
        tickCooldown();
        if (pairing != null) main.postDelayed(expirePairing, Math.max(1, pairing.expiresAt - SystemClock.elapsedRealtime()));
    }

    private void emailScreen() {
        stopPairing();
        pairing = null;
        screen = "email";
        layout(tr("Активиране на устройството", "Activate this device"));
        field(tr("Вашият имейл адрес", "Your email address"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 254)
                .setText(email);
        sendCode = button(tr("Изпрати код", "Send code"), () -> {
            email = input.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { message.setText(error("invalid_input")); return; }
            requestCode();
        });
        button(tr("Назад", "Back"), this::welcome);
        input.requestFocus();
        restoreCooldownMessage();
        tickCooldown();
    }

    private void codeScreen() {
        screen = "code";
        layout(tr("Въведете шестцифрения код, изпратен на вашия имейл.", "Enter the six-digit code sent to your email."));
        field(tr("Шестцифрен код", "Six-digit code"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 6);
        verifyCode = button(tr("Потвърди", "Verify"), this::verify);
        resend = button(tr("Изпрати отново", "Resend code"), this::requestCode);
        button(tr("Назад", "Back"), this::emailScreen);
        input.requestFocus();
        restoreCooldownMessage();
        tickCooldown();
    }

    private final Runnable cooldown = this::tickCooldown;
    private void restoreCooldownMessage() {
        long now = SystemClock.elapsedRealtime();
        if ("code".equals(screen) && ActivationCooldowns.rateLimited(ActivationCooldowns.VERIFY, now))
            cooldownMessageAction = ActivationCooldowns.VERIFY;
        else if (ActivationCooldowns.rateLimited(ActivationCooldowns.EMAIL, now))
            cooldownMessageAction = ActivationCooldowns.EMAIL;
    }

    private String rateLimitMessage(long seconds) {
        if (seconds <= 0L) return tr("Твърде много заявки. Можете да опитате отново.", "Too many requests. You can try again.");
        return tr("Твърде много заявки. Опитайте отново след ", "Too many requests. Try again in ")
                + ActivationCooldowns.format(seconds) + ".";
    }

    private void tickCooldown() {
        main.removeCallbacks(cooldown);
        if (destroyed) return;
        long now = SystemClock.elapsedRealtime();
        long seconds = 0L, nextTick = 0L;
        if ("welcome".equals(screen) && pairingError) {
            seconds = ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, now);
            nextTick = ActivationCooldowns.nextTickDelay(ActivationCooldowns.PAIRING, now);
            if (resend != null) resend.setEnabled(!pairingBusy && seconds == 0L);
        } else if ("email".equals(screen) || "code".equals(screen)) {
            long emailWait = ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, now);
            long verifyWait = ActivationCooldowns.remaining(ActivationCooldowns.VERIFY, now);
            if (sendCode != null) sendCode.setEnabled(!busy && emailWait == 0L);
            if (resend != null) {
                resend.setEnabled(!busy && emailWait == 0L);
                resend.setText(tr("Изпрати отново", "Resend code") + (emailWait > 0L ? " (" + ActivationCooldowns.format(emailWait) + ")" : ""));
            }
            if (verifyCode != null) verifyCode.setEnabled(!busy && verifyWait == 0L);
            seconds = Math.max(emailWait, verifyWait);
            long emailTick = ActivationCooldowns.nextTickDelay(ActivationCooldowns.EMAIL, now);
            long verifyTick = ActivationCooldowns.nextTickDelay(ActivationCooldowns.VERIFY, now);
            nextTick = emailTick == 0L ? verifyTick : verifyTick == 0L ? emailTick : Math.min(emailTick, verifyTick);
        }
        if (!busy && cooldownMessageAction >= 0)
            message.setText(rateLimitMessage(ActivationCooldowns.remaining(cooldownMessageAction, now)));
        // Rebuilding the UI must not shift second boundaries or postpone enabling at zero.
        if (seconds > 0L && visible) main.postDelayed(cooldown, Math.max(1L, nextTick));
    }

    private void requestCode() {
        if (ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, SystemClock.elapsedRealtime()) > 0L) {
            tickCooldown(); return;
        }
        retry = this::requestCode;
        run(ActivationCooldowns.EMAIL, () -> {
            JSONObject response = controller().requestCode(email);
            String nextChallenge = response.getString("challenge_id");
            if (!nextChallenge.matches("[0-9a-f]{32}")) throw new ActivationApi.Failure("server_unavailable", 0);
            long now = SystemClock.elapsedRealtime();
            int lifetime = Math.min(600, Math.max(1, response.optInt("expires_in", 600)));
            int wait = ActivationApi.retrySeconds(response, 0);
            ActivationCooldowns.observe(ActivationCooldowns.EMAIL, wait, now);
            return () -> { challenge = nextChallenge; challengeExpiresAt = now + lifetime * 1000L;
                codeScreen(); };
        });
    }

    private void verify() {
        if (ActivationCooldowns.remaining(ActivationCooldowns.VERIFY, SystemClock.elapsedRealtime()) > 0L) { tickCooldown(); return; }
        String code = input.getText().toString();
        input.setText("");
        if (!code.matches("[0-9]{6}")) { message.setText(error("invalid_input")); return; }
        if (challenge.isEmpty() || SystemClock.elapsedRealtime() >= challengeExpiresAt) { message.setText(error("code_expired")); return; }
        run(ActivationCooldowns.VERIFY, () -> {
            ActivationResponse response = controller().verify(challenge, code);
            return () -> { challenge = ""; apply(response); };
        });
    }

    private void checkAuthorization() {
        main.removeCallbacks(renewalCheck);
        if (config.isPhoneMode() || !"renewal".equals(screen)) {
            screen = "checking";
            layout(tr("Проверка на абонамента…", "Checking subscription…"));
        }
        retry = this::checkAuthorization;
        run(-1, () -> {
            if (!controller().hasToken()) return this::welcome;
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
            layout(config.isPhoneMode() ? tr("Абонаментът ви изтече", "Your subscription has expired")
                    : "trial".equals(response.subscription.phase)
                    ? tr("Пробният Ви период изтече", "Your trial has expired")
                    : tr("Абонаментът Ви е изтекъл", "Your subscription has expired"));
            text(tr("За да продължите да гледате Nyama+, подновете своя абонамент.", "Renew your subscription to continue watching Nyama+."), 18);
            if (config.isPhoneMode()) button(tr("Поднови абонамента", "Renew subscription"), () -> openBrowser(payment));
            else {
                qr(payment);
                text(tr("Сканирайте QR кода, за да подновите абонамента.", "Scan the QR code to renew your subscription."), 18);
            }
            button(tr(config.isPhoneMode() ? "Проверка на плащането" : "Проверка за плащане", "Check payment"), this::checkAuthorization).requestFocus();
            text(tr("Ако вече сте платили, изчакайте обработката и проверете отново.", "If you have paid, allow processing time and check again."), 16);
            retry = this::checkAuthorization;
            scheduleRenewalCheck();
        }
    }

    private interface Work { Runnable execute() throws Exception; }

    ActivationController emailController; // Optional mock transport; never a production authentication override.
    private ActivationController controller() { return emailController != null ? emailController : new ActivationController(new ActivationApi()::post, store); }

    private void run(int action, Work work) {
        if (busy) return;
        busy = true;
        cooldownMessageAction = -1;
        progress.setVisibility(View.VISIBLE);
        int generation = ++requestGeneration;
        message.setText(tr("Моля, изчакайте…", "Please wait…"));
        tickCooldown();
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
                long observedAt = SystemClock.elapsedRealtime();
                if (action >= 0) ActivationCooldowns.observe(action, wait, observedAt, "rate_limited".equals(code));
                boolean storedToken = false;
                try { storedToken = !store.token().isEmpty(); } catch (Exception ignored) { }
                boolean credentialAvailable = storedToken;
                String errorCode = code;
                main.post(() -> {
                    if (destroyed || generation != requestGeneration) return;
                    busy = false;
                    progress.setVisibility(View.GONE);
                    ActivationAccess.deny();
                    if ("code_expired".equals(errorCode)) challenge = "";
                    if ("activation_invalid".equals(errorCode)) welcome();
                    if ("renewal".equals(screen) && ("inactive_account".equals(errorCode) || "email_unverified".equals(errorCode))) {
                        screen = "checking";
                        layout(tr("Достъп до профила", "Account access"));
                        retry = this::checkAuthorization;
                    }
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
                    if ("rate_limited".equals(errorCode) && action >= 0 && wait > 0) cooldownMessageAction = action;
                    tickCooldown();
                    scheduleRenewalCheck();
                });
            }
        });
    }

    private String error(String code) {
        switch (code) {
            case "account_not_found": return config.isPhoneMode()
                    ? tr("Няма регистриран акаунт с този имейл. Върнете се към регистрацията.","No account with this email. Return to registration.")
                    : tr("Няма регистриран акаунт с този имейл. Върнете се към QR кода за регистрация.", "No account with this email. Return to the registration QR.");
            case "email_unverified": return tr("Потвърдете имейла за регистрация в Nyama.fun, преди да продължите.", "Confirm your Nyama.fun registration email first.");
            case "wrong_code": return tr("Неправилен код. Опитайте отново.", "Incorrect code. Try again.");
            case "code_expired": return tr("Кодът е изтекъл или вече е използван. Поискайте нов код.", "The code expired or was used. Request a new code.");
            case "inactive_account": return tr("Акаунтът е спрян или няма разрешен достъп. Свържете се с администратора.", "This account is blocked or ineligible. Contact the administrator.");
            case "device_limit_reached": return tr("Достигнат е лимитът за активирани устройства. Управлявайте устройствата в акаунта си или използвайте активиране с имейл.", "The device limit has been reached. Manage your account devices or use email activation.");
            case "pairing_expired": return config.isPhoneMode()?tr("Регистрационната връзка изтече.","The registration link expired.")
                    :tr("QR кодът изтече.", "The QR code expired.");
            case "pairing_invalid": return config.isPhoneMode()?tr("Регистрационната връзка вече не е валидна. Заявете нова връзка.","The registration link is no longer valid. Request a new link.")
                    :tr("QR кодът вече не е валиден. Генерирайте нов QR код.", "This QR code is no longer valid. Generate a new QR code.");
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
            if (pairingError) renderPairingError(); else renderPairing(); // Rotation preserves the current in-memory session.
        }
        else layout(tr("Проверка на абонамента…", "Checking subscription…"));
        if (busy) { progress.setVisibility(View.VISIBLE); message.setText(tr("Моля, изчакайте…", "Please wait…")); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("activation_screen", screen); // No email, OTP, challenge, token or billing data.
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
        scheduleRenewalCheck();
        tickCooldown();
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
        main.removeCallbacks(renewalCheck);
        main.removeCallbacks(cooldown);
        stopPairing();
        super.onPause();
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
}
