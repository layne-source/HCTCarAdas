package com.hct.adas;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Initial application shell. Camera and ADAS modules are integrated in later phases. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setText(R.string.app_bootstrap_status);
        status.setTextSize(20f);
        status.setPadding(32, 32, 32, 32);
        setContentView(status);
    }
}
