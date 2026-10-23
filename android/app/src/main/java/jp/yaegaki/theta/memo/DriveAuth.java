package jp.yaegaki.theta.memo;

import android.content.Context;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

// Background token for sync. Works only after the user granted Drive access once in the
// app; if Google needs the user again it fails and the app asks on the next manual sync.
public final class DriveAuth {
  public static final String SCOPE = "https://www.googleapis.com/auth/drive";

  public static AuthorizationRequest request() {
    return AuthorizationRequest.builder()
        .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
        .build();
  }

  public static String silentToken(Context c) throws Exception {
    AuthorizationResult r =
        Tasks.await(Identity.getAuthorizationClient(c).authorize(request()), 60, TimeUnit.SECONDS);
    if (r.hasResolution() || r.getAccessToken() == null)
      throw new IOException("Google Driveの再許可が必要です。メニューの「今すぐ同期」を押してください");
    return r.getAccessToken();
  }
}
