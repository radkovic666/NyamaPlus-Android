package fun.nyama.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PlayerWebViewStartupTest {
    private PlayerWebViewStartup.Starter original;
    private AtomicBoolean requested() throws Exception {
        Field field = PlayerWebViewStartup.class.getDeclaredField("requested"); field.setAccessible(true);
        return (AtomicBoolean) field.get(null);
    }
    @Before public void before() throws Exception { original = PlayerWebViewStartup.starter; requested().set(false); }
    @After public void after() throws Exception { PlayerWebViewStartup.starter = original; requested().set(false); }
    @Test public void preparationIsOncePerProcessUsesApplicationAndNeverWaitsForReady() {
        AtomicInteger calls = new AtomicInteger();
        Context[] retained = new Context[1]; Runnable[] ready = new Runnable[1];
        PlayerWebViewStartup.starter = (context, executor, callback) -> {
            calls.incrementAndGet(); retained[0] = context; ready[0] = callback;
        };
        Activity first = Robolectric.buildActivity(Activity.class).get();
        PlayerWebViewStartup.prepare(first);
        assertEquals(1, calls.get()); // Callback deliberately never invoked: startup is still free to proceed.
        PlayerWebViewStartup.prepare(Robolectric.buildActivity(Activity.class).get());
        assertEquals(1, calls.get());
        assertSame(RuntimeEnvironment.getApplication(), retained[0]); assertNotSame(first, retained[0]);
        assertNotNull(ready[0]);
    }
    @Test public void unavailableWebViewDoesNotInterruptBrowsingOrCreateRetryLoop() {
        AtomicInteger calls = new AtomicInteger();
        PlayerWebViewStartup.starter = (context, executor, callback) -> { calls.incrementAndGet(); throw new IllegalStateException("unavailable"); };
        PlayerWebViewStartup.prepare(RuntimeEnvironment.getApplication());
        PlayerWebViewStartup.prepare(RuntimeEnvironment.getApplication());
        assertEquals(1, calls.get());
    }
}
