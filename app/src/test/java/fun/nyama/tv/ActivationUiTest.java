package fun.nyama.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Simulated Android UI/compatibility checks, not Android TV hardware tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ActivationUiTest {
    private Context context;
    private static final String PAYMENT = "https://nyama.fun/subscription_payment.php?token=synthetic-account-token";

    @Before public void before() {
        ActivationCooldowns.clear();
        context = RuntimeEnvironment.getApplication();
        ActivationAccess.deny();
        context.getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
        context.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        phone();
    }

    private void phone() {
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true);
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_LEANBACK, false);
    }

    private void tv() { Shadows.shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_LEANBACK, true); }

    private ActivationActivity welcome() { return PairingUiTest.welcome(); }

    private static List<View> views(View root) {
        List<View> result = new ArrayList<>(); result.add(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) result.addAll(views(((ViewGroup) root).getChildAt(i)));
        return result;
    }

    private static Button button(Activity activity, String contains) {
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof Button && ((Button) view).getText().toString().contains(contains)) return (Button) view;
        throw new AssertionError("Button missing: " + contains);
    }

    private static String text(Activity activity) {
        StringBuilder text = new StringBuilder();
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof TextView) {
            text.append(((TextView) view).getText()).append('\n');
            if (((TextView) view).getHint() != null) text.append(((TextView) view).getHint()).append('\n');
        }
        return text.toString();
    }

    private static Bitmap bitmap(Activity activity) {
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof ImageView && ((ImageView) view).getDrawable() instanceof BitmapDrawable)
            return ((BitmapDrawable) ((ImageView) view).getDrawable()).getBitmap();
        throw new AssertionError("QR missing");
    }

    private static String decode(Bitmap bitmap) throws Exception {
        int w = bitmap.getWidth(), h = bitmap.getHeight(); int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)))).getText();
    }

    private static void expired(ActivationActivity activity) throws Exception {
        Method method = ActivationActivity.class.getDeclaredMethod("apply", ActivationResponse.class); method.setAccessible(true);
        method.invoke(activity, new ActivationResponse(new JSONObject().put("ok", true).put("access_status", "subscription_expired")
                .put("renewal", new JSONObject().put("subscription_payment_url", PAYMENT))));
    }

    @Test public void phoneAutomaticallyDetected() { assertTrue(new fun.nyama.tv.Config(context).isPhoneMode()); }
    @Test public void leanbackAutomaticallyDetectedAsTv() { tv(); assertFalse(new fun.nyama.tv.Config(context).isPhoneMode()); }
    @Test public void boxWithoutTouchscreenAutomaticallyDetectedAsTv() {
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, false);
        assertFalse(new fun.nyama.tv.Config(context).isPhoneMode());
    }
    @Test public void existingExplicitDevicePreferenceIsPreserved() {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context); config.setDeviceMode(fun.nyama.tv.Config.MODE_PHONE); tv();
        assertTrue(config.isPhoneMode()); config.setDeviceMode(fun.nyama.tv.Config.MODE_TV); phone(); assertFalse(config.isPhoneMode());
    }

    @Test @Config(sdk={24,28}) public void phoneWelcomeNeverRendersQrAndUsesRegistrationAction() throws Exception {
        ActivationActivity activity = welcome(); assertFalse(views(activity.getWindow().getDecorView()).stream().anyMatch(v->v instanceof ImageView));
        assertTrue(text(activity).contains("Добре дошли в Nyama+")); assertTrue(text(activity).contains("Очакваме регистрация"));
    }

    @Test public void tvWelcomeButtonSupportsFocusableRemoteAndClick() {
        tv(); ActivationActivity activity = welcome(); Button registered = button(activity, "I am registered already");
        assertTrue(registered.isFocusable()); assertTrue(registered.hasOnClickListeners()); assertTrue(registered.performClick());
        assertTrue(text(activity).contains("Вашият имейл адрес"));
        assertFalse(text(activity).contains("M3U")); assertFalse(text(activity).contains("EPG"));
    }

    @Test public void phoneRegistrationButtonOpensExactWebsite() {
        ActivationActivity activity = welcome(); button(activity, "Регистрация").performClick();
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, intent.getAction()); assertEquals(PairingControllerTest.QR, intent.getDataString());
    }
    @Test public void mobileRegistrationWaitsForBackendUrlWithoutDisplayingQr() throws Exception {
        PairingUiTest.Harness h=new PairingUiTest.Harness();
        assertFalse(button(h.activity,"Регистрация").isEnabled());
        assertFalse(views(h.activity.getWindow().getDecorView()).stream().anyMatch(v->v instanceof ImageView));
        h.respond(PairingControllerTest.start());assertTrue(button(h.activity,"Регистрация").isEnabled());
        assertNotNull(h.activity.findViewById(android.R.id.content).findViewWithTag("activation-path-separator"));
        button(h.activity,"Регистрация").performClick();
        assertEquals(PairingControllerTest.QR,Shadows.shadowOf(h.activity).getNextStartedActivity().getDataString());
    }
    @Test @Config(qualifiers="w1280dp-h720dp-land-mdpi") public void tvWelcomeUsesLargerScannableQrAndSeparateExistingUserPath() throws Exception {
        tv();ActivationActivity activity=welcome();
        View frame=activity.findViewById(android.R.id.content).findViewWithTag("welcome-registration-qr");
        assertNotNull(frame);assertTrue(frame.getLayoutParams().width>320);
        assertEquals(frame.getLayoutParams().width,frame.getLayoutParams().height);
        assertEquals(PairingControllerTest.QR,decode(bitmap(activity)));
        assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("activation-path-separator"));
        assertTrue(button(activity,"I am registered already").isFocusable());
    }

    @Test public void emailScreenNeverAsksWebsitePasswordAndDoesNotSaveInputState() {
        ActivationActivity activity = welcome(); button(activity, "I am registered already").performClick();
        int count = 0;
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof EditText) { count++; assertFalse(view.isSaveEnabled()); }
        assertEquals(1, count); assertFalse(text(activity).contains("password"));
    }

    @Test public void englishEmailScreenUsesConfiguredLanguage() {
        new fun.nyama.tv.Config(context).setLanguage("en"); ActivationActivity activity = welcome();
        button(activity, "I am registered already").performClick(); assertTrue(text(activity).contains("Your email address"));
    }

    @Test public void tvRenewalQrUsesExactPersonalizedUrl() throws Exception {
        tv(); ActivationActivity activity = welcome(); expired(activity);
        assertEquals(PAYMENT, decode(bitmap(activity))); assertTrue(button(activity, "Проверка за плащане").isFocusable());
        assertFalse(text(activity).contains("synthetic-account-token"));
    }

    @Test public void phoneRenewalOpensExactUrlAndDoesNotRequireQr() throws Exception {
        ActivationActivity activity = welcome(); expired(activity); button(activity, "Поднови абонамента").performClick();
        assertEquals(PAYMENT, Shadows.shadowOf(activity).getNextStartedActivity().getDataString());
        assertFalse(text(activity).contains("synthetic-account-token"));
        for (View view : views(activity.getWindow().getDecorView())) assertFalse(view instanceof ImageView);
    }

    @Test public void renewalBackCannotRevealPlayback() throws Exception {
        ActivationActivity activity = welcome(); expired(activity); activity.onBackPressed(); assertTrue(activity.isFinishing());
    }

    @Test public void configuredLegacyInstallationBypassesEnrollmentWithoutChangingSettings() {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context);
        config.saveUrls("https://legacy.example/playlist", "https://legacy.example/epg"); config.setPin("123456");
        config.setPukankiBaseUrl("https://catalog.example"); config.setLastChannelKey("legacy-channel"); config.toggleFavorite("favorite");
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        assertTrue(ActivationAccess.require(activity)); assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertFalse(ActivationStore.managed(context)); assertEquals("https://legacy.example/playlist", config.playlistUrl());
        assertEquals("https://legacy.example/epg", config.epgUrl()); assertEquals("https://catalog.example", config.pukankiBaseUrl());
        assertTrue(config.verifyPin("123456")); assertTrue(config.isFavorite("favorite")); assertEquals("legacy-channel", config.lastChannelKey());
    }

    @Test public void managedCachedPlaylistAndPinCannotBypassFreshServerAuthorization() {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context); config.saveUrls("https://cached.example/playlist", ""); config.setPin("123456");
        context.getSharedPreferences("nyama_activation_mode", 0).edit().putBoolean("managed", true).commit();
        Activity activity = Robolectric.buildActivity(Activity.class).create().get(); assertFalse(ActivationAccess.require(activity));
        assertEquals(ActivationActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        assertTrue(config.verifyPin("123456")); assertFalse(ActivationAccess.playbackAllowed(context));
    }

    @Test public void activatedPlaybackCanBeReadyWithoutUniversalAdminPin() throws Exception {
        context.getSharedPreferences("nyama_activation_mode", 0).edit().putBoolean("managed", true).commit();
        ActivationAccess.accept(context, new ActivationResponse(new JSONObject().put("ok", true).put("access_status", "active")
                .put("config", new JSONObject().put("playlist_url", "https://nyama.fun/playlist.m3u"))));
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context);
        assertTrue(config.isConfigured()); assertFalse(config.hasPin()); assertTrue(ActivationAccess.playbackAllowed(context));
    }

    private void authorized() throws Exception {
        context.getSharedPreferences("nyama_activation_mode", 0).edit().putBoolean("managed", true).commit();
        ActivationAccess.accept(context, new ActivationResponse(new JSONObject().put("ok", true).put("access_status", "active")
                .put("config", new JSONObject().put("playlist_url", "https://nyama.fun/playlist.m3u"))));
    }

    @Test public void newAdministratorPinRequiresMatchingSixDigitsThenSeparateVerification() throws Exception {
        authorized(); SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).create().start().resume().visible().get();
        Method method = SettingsActivity.class.getDeclaredMethod("askAdminPin"); method.setAccessible(true); method.invoke(activity);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        List<EditText> fields = new ArrayList<>();
        for (View view : views(dialog.getWindow().getDecorView())) if (view instanceof EditText) fields.add((EditText) view);
        assertEquals(2, fields.size()); fields.get(0).setText("123"); fields.get(1).setText("123");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); assertFalse(new fun.nyama.tv.Config(context).hasPin());
        assertTrue(dialog.isShowing());
        fields.get(0).setText("987654"); fields.get(1).setText("987654");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(new fun.nyama.tv.Config(context).verifyPin("987654"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity()); // Still waiting for the protected PIN prompt.
    }

    @Test public void existingAdministratorPinIsNeverOverwrittenByActivation() throws Exception {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context); config.setPin("111111"); authorized();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).create().start().resume().visible().get();
        Method method = SettingsActivity.class.getDeclaredMethod("askAdminPin"); method.setAccessible(true); method.invoke(activity);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        List<EditText> fields = new ArrayList<>();
        for (View view : views(dialog.getWindow().getDecorView())) if (view instanceof EditText) fields.add((EditText) view);
        assertEquals(1, fields.size()); fields.get(0).setText("222222"); dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(config.verifyPin("111111")); assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void administratorFormCannotOpenOnManagedInstallationWithoutPin() throws Exception {
        authorized(); AdminSettingsActivity activity = Robolectric.buildActivity(AdminSettingsActivity.class).create().get();
        assertTrue(activity.isFinishing()); assertFalse(new fun.nyama.tv.Config(context).hasPin());
    }

    @Test public void leavingForegroundInvalidatesEntitlementWithoutRemovingPreferences() throws Exception {
        authorized(); fun.nyama.tv.Config config = new fun.nyama.tv.Config(context); config.toggleFavorite("saved");
        ActivationAccess access = new ActivationAccess((Application) context);
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        access.onActivityStarted(activity); access.onActivityStopped(activity);
        assertFalse(ActivationAccess.playbackAllowed(context)); assertTrue(config.isFavorite("saved"));
        assertTrue(ActivationStore.managed(context));
    }

    @Test public void dpadCenterOpensEmailScreenInSimulatedTvUi() {
        tv(); ActivationActivity activity = welcome(); Button registered = button(activity, "I am registered already"); registered.requestFocus();
        registered.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER));
        registered.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER));
        assertTrue(text(activity).contains("Вашият имейл адрес"));
    }

    @Test @Config(sdk = 24, application = Application.class)
    public void enrollmentUiWorksAtMinimumSdk24() throws Exception {
        tv();ActivationActivity activity = welcome(); assertEquals(PairingControllerTest.QR, decode(bitmap(activity)));
        button(activity, "I am registered already").performClick(); assertTrue(text(activity).contains("Вашият имейл адрес"));
    }
}
