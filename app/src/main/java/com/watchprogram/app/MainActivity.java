package com.watchprogram.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WatchAppMain";
    private BluetoothManager bluetoothManager;
    private TextView tvStatus, tvInitStatus;
    private ProgressBar pbInit;
    private Button btnSelectApp, btnSelectPhoto;

    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализация UI
        tvStatus = findViewById(R.id.tvStatus);
        tvInitStatus = findViewById(R.id.tvInitStatus);
        pbInit = findViewById(R.id.pbInit);
        btnSelectApp = findViewById(R.id.btnSelectApp);
        btnSelectPhoto = findViewById(R.id.btnSelectPhoto);

        bluetoothManager = new BluetoothManager(this);

        // Поиск и подключение к часам при запуске
        setupBluetoothConnection();

        // Обработка выбора приложения (.rpk, .bin)
        btnSelectApp.setOnClickListener(v -> openFilePicker());

        // Обработка выбора фото для обоев
        btnSelectPhoto.setOnClickListener(v -> openGalleryPicker());
    }

    private void setupBluetoothConnection() {
        bluetoothManager.startScanning(result -> {
            Log.d(TAG, "Обнаружено устройство, пробуем подключиться...");
        });

        bluetoothManager.connectToDevice(null, newState -> {
            runOnUiThread(() -> {
                if (newState == 0) { // STATE_CONNECTED
                    isConnected = true;
                    tvStatus.setText(R.string.status_connected);
                    tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));

                    // Показываем возможность инициализации WatchApp после подключения
                    showWatchAppInit();
                } else {
                    isConnected = false;
                    tvStatus.setText(R.string.status_disconnected);
                    tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                }
            });
        });
    }

    private void showWatchAppInit() {
        // В реальном приложении здесь была бы отдельная кнопка "Начать инициализацию"
        // Для беты: запускаем процесс WatchApp автоматически при подключении или по клику
        pbInit.setVisibility(View.VISIBLE);
        tvInitStatus.setVisibility(View.VISIBLE);
        startWatchAppInitialization();
    }

    private void startWatchAppInitialization() {
        new Thread(() -> {
            for (int i = 0; i <= 100; i += 5) {
                final int progress = i;
                runOnUiThread(() -> {
                    pbInit.setProgress(progress);
                    tvInitStatus.setText(getString(R.string.init_process, progress));
                });
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            }

            // Отправка "Магического пакета" инициализации WatchApp на часы
            byte[] initPacket = {0x41, 0x57, 0x41, 0x54, 0x43, 0x48, 0x41, 0x50, 0x5F, 0x42, 0x45, 0x54, 0x41}; // "WATCHAPP_BETA"
            bluetoothManager.sendData(initPacket);

            runOnUiThread(() -> {
                tvInitStatus.setText("WatchApp успешно установлен на часы!");
                Toast.makeText(this, "Теперь вы можете устанавливать любые .rpk/.bin файлы!", Toast.LENGTH_LONG).show();
            });
        }).start();
    }


    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream"});
        startActivityForResult(intent, 101);
    }

    private void openGalleryPicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, 102);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (requestCode == 101) {
                handleAppFile(uri);
            } else if (requestCode == 102) {
                handlePhotoFile(uri);
            }
        }
    }

    private void handleAppFile(Uri uri) {
        if (!isConnected) {
            Toast.makeText(this, "Сначала подключите часы!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] fileData = readUriBytes(uri);
            Log.d(TAG, "Файл приложения готов к отправке: " + fileData.length + " байт");
            sendDataInChunks(fileData);
        } catch (IOException e) {
            Log.e(TAG, "Ошибка чтения файла: " + e.getMessage());
        }
    }

    private void handlePhotoFile(Uri uri) {
        if (!isConnected) {
            Toast.makeText(this, "Сначала подключите часы!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] imageBytes = readUriBytes(uri);
            Log.d(TAG, "Фото готово к отправке: " + imageBytes.length + " байт");
            sendDataInChunks(imageBytes);
        } catch (IOException e) {
            Log.e(TAG, "Ошибка чтения фото: " + e.getMessage());
        }
    }

    private byte[] readUriBytes(Uri uri) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private void sendDataInChunks(byte[] data) {
        // Часы не могут принять огромный файл за раз.
        // Разбиваем данные на пакеты по 20 байт (стандарт BLE MTU).
        int chunkSize = 20;
        int totalChunks = (int) Math.ceil((double) data.length / chunkSize);

        new Thread(() -> {
            for (int i = 0; i < data.length; i += chunkSize) {
                int end = Math.min(i + chunkSize, data.length);
                byte[] chunk = new byte[end - i];
                System.arraycopy(data, i, chunk, 0, chunk.length);

                bluetoothManager.sendData(chunk);

                // Небольшая пауза, чтобы не перегрузить буфер часов
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
            runOnUiThread(() -> Toast.makeText(this, "Передача завершена!", Toast.LENGTH_SHORT).show());
        }).start();
    }
}
