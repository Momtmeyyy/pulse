package com.example.miband.Activities;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.HttpCookie;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.example.miband.Bluetooth.HeartRateGattCallback;
import com.example.miband.DataStructures.HeartRate;
import com.example.miband.Device.MiBandDevice;
import com.example.miband.MainActivity;
import com.example.miband.R;
import com.example.miband.Utils.AndroidUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.ViewPortHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;

import retrofit2.http.Body;
import retrofit2.http.POST;
public class DeviceControlActivity extends AppCompatActivity {
    public static String TAG = "MiBand: DeviceControlActivity";

    HeartRateGattCallback heartrateGattCallback;
    ScheduledExecutorService service;

    Button clickBtn;
    Button offBtn;
    MiBandDevice mDevice;

    private LineChart mChart;

    private static final float TOTAL_MEMORY = 190.0f;
    private static final float LIMIT_MAX_MEMORY = 180.0f;

    private String serverUrl;
    private String simulationId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_device_control);

        Intent intent = getIntent();
        Bundle bundle = intent.getBundleExtra("bundle");
        mDevice = bundle.getParcelable(MiBandDevice.EXTRA_DEVICE);

        clickBtn = findViewById(R.id.onBtn);
        clickBtn.setOnClickListener(new View.OnClickListener() {
            @RequiresApi(api = Build.VERSION_CODES.M)
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(DeviceControlActivity.this);

                LinearLayout layout = new LinearLayout(DeviceControlActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);

                final EditText simulationIdField = new EditText(DeviceControlActivity.this);
                simulationIdField.setHint("Identyfikator symulacji");
                simulationIdField.setInputType(InputType.TYPE_CLASS_NUMBER);
                layout.addView(simulationIdField);

                final EditText serverIpField = new EditText(DeviceControlActivity.this);
                serverIpField.setHint("IP Serwera");
                layout.addView(serverIpField);

                builder.setView(layout);

                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setServerURL("http://" + serverIpField.getText().toString() + "/mibandpulse/sendPulse.php");
                        setSimulationId(simulationIdField.getText().toString());

                        startHeartRateMeasurement();
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });

                builder.show();


            }
        });

        offBtn = findViewById(R.id.offBtn);
        offBtn.setOnClickListener(new View.OnClickListener() {
            @RequiresApi(api = Build.VERSION_CODES.M)
            @Override
            public void onClick(View v) {
                AndroidUtils.toast(DeviceControlActivity.this, "Wyłączono czytnik", Toast.LENGTH_SHORT);

                service.shutdownNow();
                heartrateGattCallback.enableRealtimeHeartRateMeasurement(false);

                LineData data = mChart.getData();

                data.clearValues();
                data.notifyDataChanged();
                mChart.notifyDataSetChanged();
            }
        });


        mChart = findViewById(R.id.chart);

        setupChart();
        setupAxes();
        setupData();
        setLegend();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void startHeartRateMeasurement(){
        AndroidUtils.toast(DeviceControlActivity.this, "Odczyt pulsu rozpoczęty", Toast.LENGTH_SHORT);

        heartrateGattCallback = new HeartRateGattCallback(MainActivity.getMiBandSupport(), DeviceControlActivity.this) {
            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);

                int heartRateValue = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1);

                // Отправить значение пульса на сервер Django
                getCsrfTokenAndSendHeartRateToServer(heartRateValue);
            }
        };

        service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                FragmentActivity activity = DeviceControlActivity.this;
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            heartrateGattCallback.enableRealtimeHeartRateMeasurement(true);
                        }
                    });
                }
            }
        }, 0, 4000, TimeUnit.MILLISECONDS);
    }
    private void getCsrfTokenAndSendHeartRateToServer(int heartRateValue) {
        try {
            URL url = new URL("http://192.168.0.2:8000/api/get_csrf_token/");  // Замените на URL, возвращающий CSRF-токен
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            // Проверка кода ответа
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Чтение ответа, чтобы получить CSRF-токен
                InputStream inputStream = connection.getInputStream();
                Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
                String csrfToken = scanner.hasNext() ? scanner.next() : "";
                scanner.close();

                // Отправить значение пульса на сервер Django с CSRF-токеном
                sendHeartRateToServer(heartRateValue, csrfToken);
            } else {
                // Обработка ошибки
            }

            // Разрыв соединения
            connection.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendHeartRateToServer(int heartRateValue, String csrfToken) {
        try {
            URL url = new URL("http://192.168.0.2:8000/api/heart_rate/");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            // Добавить CSRF-токен в заголовок запроса
            if (csrfToken != null) {
                connection.setRequestProperty("X-CSRFToken", csrfToken);
            }

            // Создайте JSON-объект с значением пульса
            JSONObject requestBody = new JSONObject();
            requestBody.put("heart_rate", heartRateValue);

            // Отправьте JSON-данные в качестве тела запроса
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.close();

            // Проверка кода ответа
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Запрос успешно выполнен
                InputStream inputStream = connection.getInputStream();
                // Чтение и обработка ответа при необходимости
                inputStream.close();
            } else {
                // Запрос не удался
                InputStream errorStream = connection.getErrorStream();
                // Чтение и обработка ошибочного ответа при необходимости
                errorStream.close();
            }

            // Разрыв соединения
            connection.disconnect();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }





    public void setServerURL(String url){
        serverUrl = url;
    }

    public String getServerUrl(){
        return serverUrl;
    }

    public void setSimulationId(String id){
        simulationId = id;
    }

    public String getSimulationId(){
        return simulationId;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void setupChart() {
        // disable description text
        mChart.getDescription().setEnabled(false);
        // enable touch gestures
        mChart.setTouchEnabled(true);
        // if disabled, scaling can be done on x- and y-axis separately
        mChart.setPinchZoom(true);
        // enable scaling
        mChart.setScaleEnabled(true);
        mChart.setDrawGridBackground(false);
        // set an alternative background color
        //mChart.setBackgroundColor(Color.DKGRAY);
        mChart.setBackgroundColor(getResources().getColor(R.color.colorBackground));
    }

    private void setupAxes() {
        XAxis xl = mChart.getXAxis();
        xl.setTextColor(Color.WHITE);
        xl.setDrawGridLines(false);
        xl.setAvoidFirstLastClipping(true);
        xl.setPosition(XAxis.XAxisPosition.BOTTOM);
        xl.setEnabled(true);

        YAxis leftAxis = mChart.getAxisLeft();
        leftAxis.setTextColor(Color.WHITE);
        leftAxis.setAxisMaximum(TOTAL_MEMORY);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setDrawGridLines(true);

        YAxis rightAxis = mChart.getAxisRight();
        rightAxis.setEnabled(false);

        // Add a limit line
        LimitLine ll = new LimitLine(LIMIT_MAX_MEMORY, "Limit");
        ll.setLineWidth(3f);
        ll.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
        ll.setTextSize(12f);
        ll.setTextColor(Color.WHITE);
        ll.setLineColor(Color.BLACK);
        // reset all limit lines to avoid overlapping lines
        leftAxis.removeAllLimitLines();
        leftAxis.addLimitLine(ll);
        // limit lines are drawn behind data (and not on top)
        leftAxis.setDrawLimitLinesBehindData(true);
    }

    private void setupData() {
        LineData data = new LineData();
        data.setValueTextColor(Color.WHITE);

        // add empty data
        mChart.setData(data);
    }

    private void setLegend() {
    }

    private LineDataSet createSet() {
        LineDataSet set = new LineDataSet(null, "Puls");
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setCircleColor(Color.RED);
        set.setLineWidth(2f);
        set.setCircleRadius(4f);
        set.setValueTextColor(Color.WHITE);
        set.setValueTextSize(15f);
        set.setValueFormatter(new IValueFormatter() {
            @Override
            public String getFormattedValue(float value, Entry entry, int dataSetIndex, ViewPortHandler viewPortHandler) {
                return new DecimalFormat("#").format(value);
            }
        });
        // To show values of each point
        set.setDrawValues(true);

        return set;
    }

    public void addEntry(HeartRate heartRate) {
        LineData data = mChart.getData();

        if (data != null) {
            ILineDataSet set = data.getDataSetByIndex(0);

            if (set == null) {
                set = createSet();
                data.addDataSet(set);
            }

            data.addEntry(new Entry(set.getEntryCount(), heartRate.getValue()), 0);

            // let the chart know it's data has changed
            data.notifyDataChanged();
            mChart.notifyDataSetChanged();

            // limit the number of visible entries
            mChart.setVisibleXRangeMaximum(10);

            // move to the latest entry
            mChart.moveViewToX(data.getEntryCount());
        }


    }

}
