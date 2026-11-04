package org.sigil.app;

import android.app.Activity;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Android 15+ draws edge-to-edge, so adjustResize no longer lifts content above the keyboard.
 * This pads the root view by the status bar, nav bar and keyboard height instead.
 */
public final class EdgeToEdge {
    private EdgeToEdge() {}

    public static void apply(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        View content = activity.findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
    }
}
