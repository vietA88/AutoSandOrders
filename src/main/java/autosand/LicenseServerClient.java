package autosand;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class LicenseServerClient {
    
    public static String loadKey(File dir) {
        // Viết logic đọc key ở đây
        return null;
    }

    public static CompletableFuture<Boolean> validate(File configDir, String key) {
        // Viết logic xác thực key ở đây
        return CompletableFuture.completedFuture(true);
    }

    public static CompletableFuture<Boolean> activate(File configDir, String key) {
        // Viết logic kích hoạt key ở đây
        return CompletableFuture.completedFuture(true);
    }
}
