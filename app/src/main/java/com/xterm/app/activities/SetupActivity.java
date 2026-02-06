package com.xterm.app.activities;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.xterm.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.xterm.app.TermuxInstaller;

public class SetupActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST_CODE = 1001;

    private Spinner osTypeSpinner;
    private EditText rootfsUrlEdit;
    private TextView selectedFileText;
    private Uri selectedFileUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        osTypeSpinner = findViewById(R.id.os_type_spinner);
        rootfsUrlEdit = findViewById(R.id.rootfs_url_edit);
        selectedFileText = findViewById(R.id.selected_file_text);

        Button pickFileButton = findViewById(R.id.pick_rootfs_button);
        pickFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_FILE_REQUEST_CODE);
        });

        Button initializeButton = findViewById(R.id.initialize_button);
        initializeButton.setOnClickListener(v -> {
            String osType = osTypeSpinner.getSelectedItem().toString().toLowerCase();
            String url = rootfsUrlEdit.getText().toString();

            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
            if (preferences == null) return;

            preferences.setRootfsOsType(osType);

            if (selectedFileUri != null) {
                preferences.setRootfsBundleLocalPath(selectedFileUri.toString());
                preferences.setRootfsBundleUrl(null);
            } else if (!url.isEmpty()) {
                preferences.setRootfsBundleUrl(url);
                preferences.setRootfsBundleLocalPath(null);
            } else {
                Toast.makeText(this, "Please select a file or enter a URL", Toast.LENGTH_SHORT).show();
                return;
            }

            // Start initialization in TermuxInstaller
            TermuxInstaller.installRootfs(this, () -> {
                preferences.setRootfsInstalled(true);
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            selectedFileUri = data.getData();
            if (selectedFileUri != null) {
                selectedFileText.setText("Selected: " + selectedFileUri.getPath());
                rootfsUrlEdit.setText(""); // Clear URL if file is picked
            }
        }
    }
}
