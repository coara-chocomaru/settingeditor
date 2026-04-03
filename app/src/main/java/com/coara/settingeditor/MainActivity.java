package com.coara.settingeditor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private List<String> systemKeys;
    private List<String> globalKeys;
    private List<String> secureKeys;
    private String currentCategory = "";
    private String selectedKey = "";
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private TextView tvSelected;
    private EditText etValue;
    private Button btnExecute;
    private Button btnExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnSystem = findViewById(R.id.btn_system);
        Button btnGlobal = findViewById(R.id.btn_global);
        Button btnSecure = findViewById(R.id.btn_secure);
        listView = findViewById(R.id.list_view);
        tvSelected = findViewById(R.id.tv_selected);
        etValue = findViewById(R.id.et_value);
        btnExecute = findViewById(R.id.btn_execute);
        btnExport = findViewById(R.id.btn_export);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        listView.setAdapter(adapter);

        fetchAndSaveAll();
        systemKeys = loadList("system");
        globalKeys = loadList("global");
        secureKeys = loadList("secure");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage("設定を変更するにはWRITE_SETTINGS権限が必要です。\n許可しますか？");
                builder.setPositiveButton("許可する", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                });
                builder.setNegativeButton("キャンセル", null);
                builder.show();
            }
        }

        btnSystem.setOnClickListener(v -> { currentCategory = "system"; populateList(systemKeys); resetSelection(); });
        btnGlobal.setOnClickListener(v -> { currentCategory = "global"; populateList(globalKeys); resetSelection(); });
        btnSecure.setOnClickListener(v -> { currentCategory = "secure"; populateList(secureKeys); resetSelection(); });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            selectedKey = (String) parent.getItemAtPosition(position);
            tvSelected.setText("選択されたキー: " + selectedKey);
            etValue.setText(getCurrentValue());
        });

        btnExecute.setOnClickListener(v -> executeSettingChange());

        btnExport.setOnClickListener(v -> exportToZip());
    }

    private void resetSelection() {
        tvSelected.setText("選択されたキー: ");
        selectedKey = "";
        etValue.setText("");
    }

    private void executeSettingChange() {
        if (selectedKey.isEmpty() || currentCategory.isEmpty()) {
            Toast.makeText(this, "キーを選択してください", Toast.LENGTH_SHORT).show();
            return;
        }
        String newValue = etValue.getText().toString().trim();
        boolean changed = false;
        try {
            ContentResolver cr = getContentResolver();
            if ("system".equals(currentCategory)) Settings.System.putString(cr, selectedKey, newValue);
            else if ("global".equals(currentCategory)) Settings.Global.putString(cr, selectedKey, newValue);
            else if ("secure".equals(currentCategory)) Settings.Secure.putString(cr, selectedKey, newValue);
            changed = true;
        } catch (Exception ignored) {}
        Toast.makeText(this, changed ? "値を設定しました" : "変更に失敗しました。権限が不足しています", Toast.LENGTH_SHORT).show();
    }

    private void exportToZip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
                return;
            }
        }
        doExport();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doExport();
            } else {
                Toast.makeText(this, "ストレージ権限が拒否されました。エクスポートできません。", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doExport() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(new Date());
        String fileName = "settings_export_" + timestamp + ".zip";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("MediaStoreにURIを作成できませんでした");

                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    writeZipToStream(os);
                }
            } else {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                    throw new Exception("Downloadフォルダを作成できませんでした");
                }
                File zipFile = new File(downloadDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(zipFile)) {
                    writeZipToStream(fos);
                }
            }

            Toast.makeText(this, "エクスポート成功！\n/sdcard/Download/" + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "エクスポート失敗\n" + e.getMessage() + "\n権限・空き容量を確認してください", Toast.LENGTH_LONG).show();
        }
    }

    private void writeZipToStream(OutputStream outputStream) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            addValuesToZip(zos, "system", systemKeys);
            addValuesToZip(zos, "global", globalKeys);
            addValuesToZip(zos, "secure", secureKeys);
        }
    }

    private void addValuesToZip(ZipOutputStream zos, String category, List<String> keys) throws Exception {
        if (keys == null || keys.isEmpty()) return;
        String fileName = category + "_values.txt";
        zos.putNextEntry(new ZipEntry(fileName));
        for (String key : keys) {
            String value = getValueForKey(category, key);
            String line = key + "=" + (value != null ? value : "") + "\n";
            zos.write(line.getBytes("UTF-8"));
        }
        zos.closeEntry();
    }

    private String getValueForKey(String category, String key) {
        try {
            ContentResolver cr = getContentResolver();
            if ("system".equals(category)) return Settings.System.getString(cr, key);
            if ("global".equals(category)) return Settings.Global.getString(cr, key);
            if ("secure".equals(category)) return Settings.Secure.getString(cr, key);
        } catch (Exception ignored) {}
        return null;
    }

    private void fetchAndSaveAll() {
        saveKeys("system", fetchKeys(Settings.System.CONTENT_URI));
        saveKeys("global", fetchKeys(Settings.Global.CONTENT_URI));
        saveKeys("secure", fetchKeys(Settings.Secure.CONTENT_URI));
    }

    private List<String> fetchKeys(Uri uri) {
        List<String> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{"name"}, null, null, "name");
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex("name");
                do {
                    String name = cursor.getString(idx);
                    if (name != null) list.add(name);
                } while (cursor.moveToNext());
            }
        } catch (Exception ignored) {} finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    private void saveKeys(String category, List<String> keys) {
        try (FileOutputStream fos = openFileOutput(category + "_keys.txt", MODE_PRIVATE)) {
            for (String key : keys) fos.write((key + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    private List<String> loadList(String category) {
        List<String> keys = new ArrayList<>();
        try (FileInputStream fis = openFileInput(category + "_keys.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(fis, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) keys.add(line);
            }
        } catch (Exception ignored) {}
        return keys;
    }

    private void populateList(List<String> keys) {
        adapter.clear();
        if (keys != null) adapter.addAll(keys);
        adapter.notifyDataSetChanged();
    }

    private String getCurrentValue() {
        if (currentCategory.isEmpty() || selectedKey.isEmpty()) return "";
        return getValueForKey(currentCategory, selectedKey);
    }
}
